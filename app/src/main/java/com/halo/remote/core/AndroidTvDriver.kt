package com.halo.remote.core

import java.io.BufferedInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.security.interfaces.RSAPublicKey
import javax.net.ssl.SSLSocket

/**
 * Google TV / Android TV "Remote v2" protocol, the one used by Google's own TV remote.
 * Works on Sony, Xiaomi, TCL, OnePlus, Hisense, Philips and every other Android TV brand.
 *
 * Pairing (port 6467): TLS with our self-signed certificate, a short protobuf exchange,
 * then the TV shows a 6-character code. We hash both certificates with the code to prove
 * we saw it. After that the TV trusts our certificate on the remote port (6466).
 */
class AndroidTvDriver(device: TvDevice, store: CredentialStore) : TvDriver(device, store) {

    companion object {
        const val REMOTE_PORT = 6466
        const val PAIRING_PORT = 6467
        /** PING | KEY | POWER | VOLUME | APP_LINK */
        const val FEATURES = 1 or 2 or 32 or 64 or 512
        private const val STATUS_OK = 200

        fun appLink(app: StreamingApp): String = when (app) {
            StreamingApp.NETFLIX -> "https://www.netflix.com/title"
            StreamingApp.YOUTUBE -> "https://www.youtube.com"
            StreamingApp.PRIME -> "https://app.primevideo.com"
            StreamingApp.HOTSTAR -> "https://www.hotstar.com"
        }

        /** The secret proving we saw the code: SHA-256 over both public keys and the code. */
        fun pairingSecret(client: RSAPublicKey, server: RSAPublicKey, code: String): ByteArray {
            val md = MessageDigest.getInstance("SHA-256")
            md.update(AdbCrypto.unsigned(client.modulus))
            md.update(AdbCrypto.unsigned(client.publicExponent))
            md.update(AdbCrypto.unsigned(server.modulus))
            md.update(AdbCrypto.unsigned(server.publicExponent))
            md.update(hex(code.substring(2)))
            return md.digest()
        }

        fun hex(s: String): ByteArray = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

        // ---- message builders (field numbers from polo.proto / remotemessage.proto) ----
        private fun outer() = ProtoWriter().varint(1, 2).varint(2, STATUS_OK)
        private fun encoding() = ProtoWriter().varint(1, 3).varint(2, 6) // HEXADECIMAL, 6 symbols

        fun pairingRequest(clientName: String) =
            outer().message(10, ProtoWriter().string(1, "atvremote").string(2, clientName)).toByteArray()
        fun pairingOptions() =
            outer().message(20, ProtoWriter().message(1, encoding()).varint(3, 1)).toByteArray()
        fun pairingConfiguration() =
            outer().message(30, ProtoWriter().message(1, encoding()).varint(2, 1)).toByteArray()
        fun pairingSecretMessage(secret: ByteArray) =
            outer().message(40, ProtoWriter().bytes(1, secret)).toByteArray()

        fun remoteConfigure(features: Int, model: String, vendor: String) = ProtoWriter().message(
            1, ProtoWriter().varint(1, features).message(
                2, ProtoWriter().string(1, model).string(2, vendor).varint(3, 1).string(4, "1")
                    .string(5, "com.halo.remote").string(6, "1.0.0")
            )
        ).toByteArray()
        fun remoteSetActive(features: Int) = ProtoWriter().message(2, ProtoWriter().varint(1, features)).toByteArray()
        fun remotePingResponse(v: Int) = ProtoWriter().message(9, ProtoWriter().varint(1, v)).toByteArray()
        fun remoteKey(code: Int, direction: Int = 3) =
            ProtoWriter().message(10, ProtoWriter().varint(1, code).varint(2, direction)).toByteArray()
        fun remoteAppLink(link: String) = ProtoWriter().message(90, ProtoWriter().string(1, link)).toByteArray()
    }

    override val apps = StreamingApp.values().toSet()

    private val identity by lazy { ClientIdentity.loadOrCreate(store.identityFile) }

    private var remote: SSLSocket? = null
    private var remoteOut: OutputStream? = null
    private var pairing: SSLSocket? = null
    private var pairingIn: InputStream? = null
    private var reader: Thread? = null
    @Volatile private var features = FEATURES

    override fun doConnect() {
        try {
            openRemote()
            emit(ConnState.Connected)
        } catch (e: NeedsPairingException) {
            startPairing()
            emit(ConnState.NeedsCode())
        }
    }

    private fun openRemote() {
        closeRemote()
        val s = try {
            Tls.connect(device.host, REMOTE_PORT, 6000, identity.keyManagers())
        } catch (e: javax.net.ssl.SSLException) {
            throw NeedsPairingException() // TV rejected our certificate
        }
        remote = s
        val input = BufferedInputStream(s.getInputStream())
        val out = s.getOutputStream()
        remoteOut = out
        // An unpaired phone gets disconnected straight away; a paired one receives remote_configure.
        s.soTimeout = 6000
        var configured = false
        try {
            while (!configured) {
                val msg = ProtoMessage.parse(ProtoWriter.readDelimited(input))
                configured = handleRemote(msg, out)
            }
        } catch (e: SocketTimeoutException) {
            closeRemote()
            throw NeedsPairingException()
        } catch (e: java.io.IOException) {
            closeRemote()
            throw NeedsPairingException()
        }
        s.soTimeout = 0
        reader = Thread({ readLoop(s, input, out) }, "atv-reader").apply { isDaemon = true; start() }
    }

    /** Returns true when the message was remote_configure. */
    private fun handleRemote(msg: ProtoMessage, out: OutputStream): Boolean {
        when {
            msg.has(1) -> {
                val cfg = msg.message(1)
                features = FEATURES and (cfg?.int(1) ?: FEATURES)
                write(out, remoteConfigure(features, "OneBeam Remote", "OneBeam"))
                return true
            }
            msg.has(2) -> write(out, remoteSetActive(features))
            msg.has(8) -> write(out, remotePingResponse(msg.message(8)?.int(1) ?: 0))
        }
        return false
    }

    private fun readLoop(s: SSLSocket, input: InputStream, out: OutputStream) {
        try {
            while (!closed && !s.isClosed) {
                handleRemote(ProtoMessage.parse(ProtoWriter.readDelimited(input)), out)
            }
        } catch (e: Exception) {
            if (remote === s) connectionLost()
        }
    }

    private fun write(out: OutputStream, msg: ByteArray) {
        synchronized(out) { ProtoWriter.writeDelimited(out, msg) }
    }

    // ---------------- pairing ----------------

    private fun startPairing() {
        closePairing()
        val s = Tls.connect(device.host, PAIRING_PORT, 10000, identity.keyManagers())
        s.soTimeout = 15000
        pairing = s
        val input = BufferedInputStream(s.getInputStream())
        pairingIn = input
        val out = s.getOutputStream()
        ProtoWriter.writeDelimited(out, pairingRequest("OneBeam Remote"))
        expect(input, 11, "pairing request")
        ProtoWriter.writeDelimited(out, pairingOptions())
        expect(input, 20, "options")
        ProtoWriter.writeDelimited(out, pairingConfiguration())
        expect(input, 31, "configuration")
        // The TV now shows the code. Keep the socket open until the user types it.
        s.soTimeout = 0
    }

    private fun expect(input: InputStream, field: Int, step: String): ProtoMessage {
        val msg = ProtoMessage.parse(ProtoWriter.readDelimited(input))
        val status = msg.int(2)
        if (status != STATUS_OK) {
            throw UserFacingException(
                if (status == 402) "That code didn't match. Try again with the new code on the TV."
                else "The TV stopped pairing at the $step step (status $status)."
            )
        }
        if (!msg.has(field)) throw UserFacingException("Unexpected reply from the TV while pairing.")
        return msg
    }

    override fun doSubmitCode(code: String) {
        val s = pairing
        if (s == null || s.isClosed) {
            startPairing()
            emit(ConnState.NeedsCode("The pairing session expired. A new code is on your TV."))
            return
        }
        if (!code.matches(Regex("[0-9A-F]{6}"))) {
            emit(ConnState.NeedsCode("Enter the 6 letters and numbers shown on the TV."))
            return
        }
        val server = s.session.peerCertificates.first().publicKey as RSAPublicKey
        val secret = pairingSecret(identity.publicKey, server, code)
        if ((secret[0].toInt() and 0xFF) != code.substring(0, 2).toInt(16)) {
            emit(ConnState.NeedsCode("That code doesn't match the TV. Check it and try again."))
            return
        }
        s.soTimeout = 15000
        ProtoWriter.writeDelimited(s.getOutputStream(), pairingSecretMessage(secret))
        expect(pairingIn ?: BufferedInputStream(s.getInputStream()), 41, "code")
        closePairing()
        emit(ConnState.Connecting)
        openRemote()
        emit(ConnState.Connected)
    }

    // ---------------- commands ----------------

    override fun doSend(key: Key) {
        val out = remoteOut ?: throw UserFacingException("Not connected")
        write(out, remoteKey(key.androidCode))
    }

    override val gameButtons = PadButton.values().toSet()
    override val nativeGamepad = true

    /** Hold support: START_LONG on the way down, END_LONG on the way up. */
    override fun doGame(button: PadButton, down: Boolean) {
        val out = remoteOut ?: throw UserFacingException("Not connected")
        write(out, remoteKey(button.androidCode, if (down) 1 else 2))
    }

    override fun doLaunch(app: StreamingApp) {
        val out = remoteOut ?: throw UserFacingException("Not connected")
        write(out, remoteAppLink(appLink(app)))
    }

    private fun closeRemote() {
        val s = remote
        remote = null
        remoteOut = null
        try { s?.close() } catch (_: Exception) {}
    }

    private fun closePairing() {
        try { pairing?.close() } catch (_: Exception) {}
        pairing = null
        pairingIn = null
    }

    override fun doClose() {
        closeRemote()
        closePairing()
    }
}
