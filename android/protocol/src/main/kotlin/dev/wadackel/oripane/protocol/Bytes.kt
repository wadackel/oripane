package dev.wadackel.oripane.protocol

import java.io.ByteArrayOutputStream

class WireException(message: String) : Exception(message)

class ByteWriter(capacity: Int = 64) {
    private val out = ByteArrayOutputStream(capacity)

    fun u8(v: Int) = apply { out.write(v and 0xFF) }
    fun u16(v: Int) = apply { u8(v ushr 8); u8(v) }
    fun u32(v: Long) = apply { for (s in 24 downTo 0 step 8) u8((v ushr s).toInt()) }
    fun u64(v: Long) = apply { for (s in 56 downTo 0 step 8) u8((v ushr s).toInt()) }
    fun f32(v: Float) = apply { u32(java.lang.Float.floatToIntBits(v).toLong() and 0xFFFFFFFFL) }
    fun bytes(b: ByteArray) = apply { out.write(b) }

    fun toByteArray(): ByteArray = out.toByteArray()
}

/** Big-endian reader. Unsigned 32-bit values come back as Long so they compare correctly. */
class ByteReader(private val data: ByteArray, private var offset: Int = 0, private val end: Int = data.size) {
    val remaining get() = end - offset

    private fun need(n: Int) {
        if (remaining < n) throw WireException("truncated")
    }

    fun u8(): Int {
        need(1)
        return data[offset++].toInt() and 0xFF
    }

    fun u16(): Int = (u8() shl 8) or u8()

    fun u32(): Long {
        need(4)
        var v = 0L
        repeat(4) { v = (v shl 8) or (data[offset++].toLong() and 0xFF) }
        return v
    }

    fun u64(): Long {
        need(8)
        var v = 0L
        repeat(8) { v = (v shl 8) or (data[offset++].toLong() and 0xFF) }
        return v
    }

    fun f32(): Float = java.lang.Float.intBitsToFloat(u32().toInt())

    fun bytes(n: Int): ByteArray {
        need(n)
        return data.copyOfRange(offset, offset + n).also { offset += n }
    }

    fun rest(): ByteArray = data.copyOfRange(offset, end).also { offset = end }
}

fun ByteArray.hex(): String = joinToString("") { "%02x".format(it.toInt() and 0xFF) }

fun String.unhex(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
