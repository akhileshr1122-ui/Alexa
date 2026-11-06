package com.halo.remote.core

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream

/** Tiny protobuf encoder/decoder: enough for the Android TV remote protocol. */
class ProtoWriter {
    private val out = ByteArrayOutputStream()

    fun varint(field: Int, value: Long): ProtoWriter {
        writeRawVarint(out, (field.toLong() shl 3) or 0L)
        writeRawVarint(out, value)
        return this
    }

    fun varint(field: Int, value: Int): ProtoWriter = varint(field, value.toLong())

    fun bytes(field: Int, value: ByteArray): ProtoWriter {
        writeRawVarint(out, (field.toLong() shl 3) or 2L)
        writeRawVarint(out, value.size.toLong())
        out.write(value)
        return this
    }

    fun string(field: Int, value: String): ProtoWriter = bytes(field, value.toByteArray(Charsets.UTF_8))

    fun message(field: Int, value: ProtoWriter): ProtoWriter = bytes(field, value.toByteArray())

    fun toByteArray(): ByteArray = out.toByteArray()

    companion object {
        fun writeRawVarint(out: OutputStream, v: Long) {
            var value = v
            while (true) {
                if (value and 0x7FL.inv() == 0L) {
                    out.write(value.toInt())
                    return
                }
                out.write(((value and 0x7F) or 0x80).toInt())
                value = value ushr 7
            }
        }

        /** Writes a length-prefixed message (protobuf "delimited" framing). */
        fun writeDelimited(out: OutputStream, message: ByteArray) {
            val buf = ByteArrayOutputStream()
            writeRawVarint(buf, message.size.toLong())
            buf.write(message)
            out.write(buf.toByteArray())
            out.flush()
        }

        fun readRawVarint(input: InputStream): Long {
            var result = 0L
            var shift = 0
            while (shift < 64) {
                val b = input.read()
                if (b < 0) throw EOFException("Connection closed")
                result = result or ((b and 0x7F).toLong() shl shift)
                if (b and 0x80 == 0) return result
                shift += 7
            }
            throw IllegalStateException("Bad varint")
        }

        fun readDelimited(input: InputStream): ByteArray {
            val len = readRawVarint(input).toInt()
            if (len < 0 || len > 1_000_000) throw IllegalStateException("Bad message length $len")
            val data = ByteArray(len)
            var off = 0
            while (off < len) {
                val n = input.read(data, off, len - off)
                if (n < 0) throw EOFException("Connection closed")
                off += n
            }
            return data
        }
    }
}

/** Parsed protobuf message: field number -> values (Long for varints, ByteArray for length-delimited). */
class ProtoMessage(private val fields: Map<Int, List<Any>>) {

    fun has(field: Int): Boolean = fields.containsKey(field)
    fun long(field: Int): Long? = fields[field]?.firstOrNull() as? Long
    fun int(field: Int): Int? = long(field)?.toInt()
    fun bytes(field: Int): ByteArray? = fields[field]?.firstOrNull() as? ByteArray
    fun string(field: Int): String? = bytes(field)?.toString(Charsets.UTF_8)
    fun message(field: Int): ProtoMessage? = bytes(field)?.let { parse(it) }

    companion object {
        fun parse(data: ByteArray): ProtoMessage {
            val map = HashMap<Int, MutableList<Any>>()
            var i = 0
            fun readVarint(): Long {
                var result = 0L
                var shift = 0
                while (true) {
                    if (i >= data.size) throw IllegalStateException("Truncated varint")
                    val b = data[i++].toInt() and 0xFF
                    result = result or ((b and 0x7F).toLong() shl shift)
                    if (b and 0x80 == 0) return result
                    shift += 7
                    if (shift >= 64) throw IllegalStateException("Bad varint")
                }
            }
            while (i < data.size) {
                val key = readVarint()
                val field = (key ushr 3).toInt()
                when ((key and 7).toInt()) {
                    0 -> map.getOrPut(field) { ArrayList() }.add(readVarint())
                    1 -> { i += 8 }
                    2 -> {
                        val len = readVarint().toInt()
                        if (len < 0 || i + len > data.size) throw IllegalStateException("Truncated field")
                        map.getOrPut(field) { ArrayList() }.add(data.copyOfRange(i, i + len))
                        i += len
                    }
                    5 -> { i += 4 }
                    else -> throw IllegalStateException("Unsupported wire type")
                }
            }
            return ProtoMessage(map)
        }
    }
}
