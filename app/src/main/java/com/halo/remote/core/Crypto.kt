package com.halo.remote.core

import java.io.ByteArrayOutputStream
import java.io.File
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPublicKey
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.net.ssl.KeyManager
import javax.net.ssl.SSLEngine
import javax.net.ssl.X509ExtendedKeyManager
import java.net.Socket
import java.security.Principal

/**
 * The phone's own identity: an RSA key pair plus a self-signed certificate.
 * Android TVs remember this certificate after pairing; Fire TV (ADB) remembers the key.
 */
class ClientIdentity(val privateKey: PrivateKey, val certificate: X509Certificate) {

    val publicKey: RSAPublicKey get() = certificate.publicKey as RSAPublicKey

    /**
     * Always offers our certificate, even if the TV lists specific certificate authorities.
     * (A stock key manager would refuse, because our certificate is self-signed.)
     */
    fun keyManagers(): Array<KeyManager> = arrayOf(object : X509ExtendedKeyManager() {
        override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?) = arrayOf("client")
        override fun chooseClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?) = "client"
        override fun chooseEngineClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, engine: SSLEngine?) = "client"
        override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? = null
        override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?): String? = null
        override fun getCertificateChain(alias: String?): Array<X509Certificate> = arrayOf(certificate)
        override fun getPrivateKey(alias: String?): PrivateKey = privateKey
    })

    fun save(file: File) {
        val ks = KeyStore.getInstance("PKCS12")
        ks.load(null, null)
        ks.setKeyEntry("client", privateKey, PASSWORD, arrayOf(certificate))
        file.outputStream().use { ks.store(it, PASSWORD) }
    }

    companion object {
        private val PASSWORD = "halo-remote".toCharArray()

        fun loadOrCreate(file: File, commonName: String = "OneBeam Remote"): ClientIdentity {
            if (file.exists()) {
                try {
                    val ks = KeyStore.getInstance("PKCS12")
                    file.inputStream().use { ks.load(it, PASSWORD) }
                    val key = ks.getKey("client", PASSWORD) as PrivateKey
                    val cert = ks.getCertificate("client") as X509Certificate
                    return ClientIdentity(key, cert)
                } catch (e: Exception) {
                    // corrupted: fall through and regenerate
                }
            }
            val id = generate(commonName)
            file.parentFile?.mkdirs()
            id.save(file)
            return id
        }

        fun generate(commonName: String): ClientIdentity {
            val gen = KeyPairGenerator.getInstance("RSA")
            gen.initialize(2048)
            val kp = gen.generateKeyPair()
            return ClientIdentity(kp.private, CertBuilder.selfSigned(kp, commonName))
        }
    }
}

/** Builds a self-signed X.509 v3 certificate using DER encoding by hand (no extra libraries). */
object CertBuilder {
    private val SHA256_RSA = byteArrayOf(0x2A, 0x86.toByte(), 0x48, 0x86.toByte(), 0xF7.toByte(), 0x0D, 0x01, 0x01, 0x0B)
    private val CN = byteArrayOf(0x55, 0x04, 0x03)

    fun selfSigned(kp: KeyPair, commonName: String): X509Certificate {
        val algId = seq(tlv(0x06, SHA256_RSA), byteArrayOf(0x05, 0x00))
        val name = seq(tlv(0x31, seq(tlv(0x06, CN), tlv(0x0C, commonName.toByteArray(Charsets.UTF_8)))))
        val now = System.currentTimeMillis()
        val validity = seq(
            time(Date(now - 24L * 3600 * 1000)),
            time(Date(now + 20L * 365 * 24 * 3600 * 1000))
        )
        val serial = BigInteger(63, java.security.SecureRandom()).add(BigInteger.ONE)
        val tbs = seq(
            tlv(0xA0, int(BigInteger.valueOf(2))), // v3
            int(serial),
            algId,
            name,
            validity,
            name,
            kp.public.encoded // SubjectPublicKeyInfo, already DER
        )
        val sig = Signature.getInstance("SHA256withRSA")
        sig.initSign(kp.private)
        sig.update(tbs)
        val signature = sig.sign()
        val der = seq(tbs, algId, tlv(0x03, byteArrayOf(0) + signature))
        return CertificateFactory.getInstance("X.509").generateCertificate(der.inputStream()) as X509Certificate
    }

    private fun time(d: Date): ByteArray {
        val f = SimpleDateFormat("yyMMddHHmmss'Z'", Locale.US)
        f.timeZone = TimeZone.getTimeZone("UTC")
        return tlv(0x17, f.format(d).toByteArray(Charsets.US_ASCII))
    }

    private fun int(v: BigInteger) = tlv(0x02, v.toByteArray())
    private fun seq(vararg parts: ByteArray): ByteArray {
        val b = ByteArrayOutputStream()
        parts.forEach { b.write(it) }
        return tlv(0x30, b.toByteArray())
    }

    fun tlv(tag: Int, value: ByteArray): ByteArray {
        val b = ByteArrayOutputStream()
        b.write(tag)
        val len = value.size
        when {
            len < 0x80 -> b.write(len)
            len < 0x100 -> { b.write(0x81); b.write(len) }
            len < 0x10000 -> { b.write(0x82); b.write(len ushr 8); b.write(len and 0xFF) }
            else -> { b.write(0x83); b.write(len ushr 16); b.write((len ushr 8) and 0xFF); b.write(len and 0xFF) }
        }
        b.write(value)
        return b.toByteArray()
    }
}

/** Crypto needed for ADB (Fire TV) authentication. */
object AdbCrypto {
    private val SHA1_DIGEST_INFO = byteArrayOf(
        0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14
    )

    /** ADB signs the 20-byte token as if it were a SHA-1 digest (PKCS#1 v1.5). */
    fun signToken(key: PrivateKey, token: ByteArray): ByteArray {
        val sig = Signature.getInstance("NONEwithRSA")
        sig.initSign(key)
        sig.update(SHA1_DIGEST_INFO + token)
        return sig.sign()
    }

    /** Android's custom RSA public key format, base64 + " name". */
    fun androidPublicKey(pub: RSAPublicKey, name: String): ByteArray {
        val n = pub.modulus
        val words = 64
        val r32 = BigInteger.ONE.shiftLeft(32)
        val n0inv = r32.subtract(n.mod(r32).modInverse(r32))
        val rr = BigInteger.ONE.shiftLeft(2048).pow(2).mod(n)
        val buf = java.nio.ByteBuffer.allocate(4 + 4 + 256 + 256 + 4).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        buf.putInt(words)
        buf.putInt(n0inv.toInt())
        buf.put(littleEndian(n, 256))
        buf.put(littleEndian(rr, 256))
        buf.putInt(pub.publicExponent.toInt())
        return (Base64Compat.encode(buf.array()) + " " + name).toByteArray(Charsets.UTF_8)
    }

    private fun littleEndian(v: BigInteger, size: Int): ByteArray {
        val be = unsigned(v)
        val out = ByteArray(size)
        for (i in be.indices) out[i] = be[be.size - 1 - i]
        return out
    }

    /** Big-endian bytes without the sign byte. */
    fun unsigned(v: BigInteger): ByteArray {
        val b = v.toByteArray()
        return if (b.size > 1 && b[0] == 0.toByte()) b.copyOfRange(1, b.size) else b
    }
}
