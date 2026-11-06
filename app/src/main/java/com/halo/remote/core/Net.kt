package com.halo.remote.core

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.KeyManager
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/** TLS helpers. TVs use self-signed certificates on the local network, so we accept them. */
object Tls {
    private val trustAll = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
    }

    fun context(keyManagers: Array<KeyManager>? = null): SSLContext {
        val ctx = SSLContext.getInstance("TLS")
        ctx.init(keyManagers, arrayOf<TrustManager>(trustAll), SecureRandom())
        return ctx
    }

    fun connect(host: String, port: Int, timeoutMs: Int, keyManagers: Array<KeyManager>? = null): SSLSocket {
        val raw = Socket()
        raw.connect(InetSocketAddress(host, port), timeoutMs)
        raw.tcpNoDelay = true
        val s = context(keyManagers).socketFactory.createSocket(raw, host, port, true) as SSLSocket
        s.soTimeout = timeoutMs
        s.startHandshake()
        return s
    }
}

/** Small RFC 6455 WebSocket client (text messages only), works over ws:// and wss://. */
class WebSocketClient(private val url: String, private val connectTimeoutMs: Int = 5000) {

    private var socket: Socket? = null
    private lateinit var input: InputStream
    private lateinit var output: OutputStream
    private val writeLock = Any()
    private val random = SecureRandom()

    val isOpen: Boolean get() = socket?.isClosed == false

    fun connect() {
        val uri = URI(url)
        val secure = uri.scheme == "wss"
        val host = uri.host
        val port = if (uri.port > 0) uri.port else if (secure) 443 else 80
        val s: Socket = if (secure) {
            Tls.connect(host, port, connectTimeoutMs)
        } else {
            Socket().also {
                it.connect(InetSocketAddress(host, port), connectTimeoutMs)
                it.tcpNoDelay = true
            }
        }
        s.soTimeout = connectTimeoutMs
        socket = s
        input = BufferedInputStream(s.getInputStream())
        output = s.getOutputStream()

        val keyBytes = ByteArray(16).also { random.nextBytes(it) }
        val key = Base64Compat.encode(keyBytes)
        val path = (uri.rawPath?.ifEmpty { "/" } ?: "/") + (uri.rawQuery?.let { "?$it" } ?: "")
        val hostHeader = if (uri.port > 0) "$host:$port" else host
        val req = "GET $path HTTP/1.1\r\n" +
            "Host: $hostHeader\r\n" +
            "Upgrade: websocket\r\n" +
            "Connection: Upgrade\r\n" +
            "Sec-WebSocket-Key: $key\r\n" +
            "Sec-WebSocket-Version: 13\r\n\r\n"
        output.write(req.toByteArray(Charsets.ISO_8859_1))
        output.flush()

        val header = readHttpHeader()
        val status = header.lineSequence().firstOrNull() ?: ""
        if (!status.contains(" 101")) {
            close()
            throw IOException("WebSocket upgrade refused: $status")
        }
    }

    private fun readHttpHeader(): String {
        val buf = ByteArrayOutputStream()
        var matched = 0
        val end = byteArrayOf(13, 10, 13, 10)
        while (matched < 4) {
            val b = input.read()
            if (b < 0) throw EOFException("Connection closed during handshake")
            buf.write(b)
            matched = if (b.toByte() == end[matched]) matched + 1 else if (b == 13) 1 else 0
            if (buf.size() > 16384) throw IOException("Handshake too large")
        }
        return buf.toString("ISO-8859-1")
    }

    /** Sets how long [readText] waits before throwing SocketTimeoutException. 0 = forever. */
    fun setReadTimeout(ms: Int) {
        socket?.soTimeout = ms
    }

    fun sendText(text: String) {
        sendFrame(0x1, text.toByteArray(Charsets.UTF_8))
    }

    private fun sendFrame(opcode: Int, payload: ByteArray) {
        val frame = ByteArrayOutputStream()
        frame.write(0x80 or opcode)
        val len = payload.size
        when {
            len < 126 -> frame.write(0x80 or len)
            len < 65536 -> {
                frame.write(0x80 or 126)
                frame.write(len ushr 8); frame.write(len and 0xFF)
            }
            else -> {
                frame.write(0x80 or 127)
                for (shift in 56 downTo 0 step 8) frame.write(((len.toLong() ushr shift) and 0xFF).toInt())
            }
        }
        val mask = ByteArray(4).also { random.nextBytes(it) }
        frame.write(mask)
        for (i in payload.indices) frame.write(payload[i].toInt() xor mask[i % 4].toInt())
        synchronized(writeLock) {
            output.write(frame.toByteArray())
            output.flush()
        }
    }

    /** Blocks until the next complete text message. Answers pings automatically. */
    fun readText(): String {
        val message = ByteArrayOutputStream()
        while (true) {
            val b0 = input.read()
            if (b0 < 0) throw EOFException("Connection closed")
            val b1 = readByte()
            val fin = b0 and 0x80 != 0
            val opcode = b0 and 0x0F
            var len = (b1 and 0x7F).toLong()
            if (len == 126L) len = ((readByte() shl 8) or readByte()).toLong()
            else if (len == 127L) {
                len = 0
                repeat(8) { len = (len shl 8) or readByte().toLong() }
            }
            val masked = b1 and 0x80 != 0
            val mask = if (masked) ByteArray(4) { readByte().toByte() } else null
            if (len > 8_000_000) throw IOException("Frame too large")
            val payload = ByteArray(len.toInt())
            var off = 0
            while (off < payload.size) {
                val n = input.read(payload, off, payload.size - off)
                if (n < 0) throw EOFException("Connection closed")
                off += n
            }
            if (mask != null) for (i in payload.indices) payload[i] = (payload[i].toInt() xor mask[i % 4].toInt()).toByte()
            when (opcode) {
                0x0, 0x1, 0x2 -> {
                    message.write(payload)
                    if (fin) return message.toString("UTF-8")
                }
                0x8 -> {
                    try { sendFrame(0x8, ByteArray(0)) } catch (_: IOException) {}
                    close()
                    throw EOFException("Closed by TV")
                }
                0x9 -> sendFrame(0xA, payload)
                else -> {}
            }
        }
    }

    private fun readByte(): Int {
        val b = input.read()
        if (b < 0) throw EOFException("Connection closed")
        return b
    }

    fun close() {
        try { socket?.close() } catch (_: IOException) {}
    }
}

/** java.util.Base64 is API 26+, same as our minSdk; wrapped for clarity. */
object Base64Compat {
    fun encode(b: ByteArray): String = java.util.Base64.getEncoder().encodeToString(b)
    fun decode(s: String): ByteArray = java.util.Base64.getDecoder().decode(s)
}
