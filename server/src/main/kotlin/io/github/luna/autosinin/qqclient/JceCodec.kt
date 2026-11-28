package io.github.luna.autosinin.qqclient

import java.io.ByteArrayOutputStream

object JceType {
    const val BYTE = 0
    const val SHORT = 1
    const val INT = 2
    const val LONG = 3
    const val STRING1 = 6
    const val STRING4 = 7
    const val MAP = 8
    const val LIST = 9
    const val STRUCT_BEGIN = 10
    const val STRUCT_END = 11
    const val ZERO = 12
    const val SIMPLE_LIST = 13
}

class JceWriter {
    private val out = ByteArrayOutputStream()

    fun toByteArray(): ByteArray = out.toByteArray()

    private fun head(tag: Int, type: Int) {
        if (tag <= 14) {
            out.write((tag shl 4) or type)
        } else {
            out.write(0xF0 or type)
            out.write(tag)
        }
    }

    private fun intBytes(v: Int) = byteArrayOf(
        (v ushr 24).toByte(),
        (v ushr 16).toByte(),
        (v ushr 8).toByte(),
        v.toByte(),
    )

    private fun longBytes(v: Long) = ByteArray(8) { i -> (v ushr (56 - 8 * i)).toByte() }

    fun writeByte(tag: Int, v: Int) {
        head(tag, JceType.BYTE)
        out.write(v)
    }

    fun writeShort(tag: Int, v: Int) {
        head(tag, JceType.SHORT)
        out.write(v ushr 8)
        out.write(v)
    }

    fun writeInt(tag: Int, v: Int) {
        head(tag, JceType.INT)
        out.write(intBytes(v))
    }

    fun writeLong(tag: Int, v: Long) {
        head(tag, JceType.LONG)
        out.write(longBytes(v))
    }

    fun writeZero(tag: Int) {
        head(tag, JceType.ZERO)
    }

    fun writeString(tag: Int, s: String) {
        val b = s.toByteArray(Charsets.UTF_8)
        if (b.size <= 255) {
            head(tag, JceType.STRING1)
            out.write(b.size)
        } else {
            head(tag, JceType.STRING4)
            out.write(intBytes(b.size))
        }
        out.write(b)
    }

    private fun writeSize(n: Int) {
        if (n == 0) {
            head(0, JceType.ZERO)
        } else {
            head(0, JceType.INT)
            out.write(intBytes(n))
        }
    }

    fun writeBytes(tag: Int, b: ByteArray) {
        head(tag, JceType.SIMPLE_LIST)
        out.write(0)
        writeSize(b.size)
        out.write(b)
    }

    fun writeMapBytes(tag: Int, entries: Map<String, ByteArray>) {
        head(tag, JceType.MAP)
        writeSize(entries.size)
        for ((key, value) in entries) {
            writeString(0, key)
            head(0, JceType.STRUCT_BEGIN)
            out.write(value)
            head(0, JceType.STRUCT_END)
        }
    }

    fun writeMapOfBytes(tag: Int, entries: Map<String, ByteArray>) {
        head(tag, JceType.MAP)
        writeSize(entries.size)
        for ((key, value) in entries) {
            writeString(0, key)
            writeBytes(0, value)
        }
    }

    fun writeMapOfMaps(tag: Int, entries: Map<String, Map<String, ByteArray>>) {
        head(tag, JceType.MAP)
        writeSize(entries.size)
        for ((key, inner) in entries) {
            writeString(0, key)
            writeMapOfBytes(0, inner)
        }
    }

    fun writeEmptyMap(tag: Int) {
        head(tag, JceType.MAP)
        writeSize(0)
    }
}

class JceReader(private val buf: ByteArray) {
    private var pos = 0

    fun eof(): Boolean = pos >= buf.size

    private fun u8(): Int = buf[pos++].toInt() and 0xFF

    fun readHead(): Pair<Int, Int> {
        val b = u8()
        var tag = b ushr 4
        val type = b and 0x0F
        if (tag == 15) tag = u8()
        return tag to type
    }

    fun readIntRaw(): Int {
        val v = ((buf[pos].toInt() and 0xFF) shl 24) or
            ((buf[pos + 1].toInt() and 0xFF) shl 16) or
            ((buf[pos + 2].toInt() and 0xFF) shl 8) or
            (buf[pos + 3].toInt() and 0xFF)
        pos += 4
        return v
    }

    private fun readLongRaw(): Long {
        var v = 0L
        repeat(8) { v = (v shl 8) or (buf[pos++].toLong() and 0xFF) }
        return v
    }

    fun readNumber(type: Int): Long = when (type) {
        JceType.BYTE -> u8().toByte().toLong()
        JceType.SHORT -> (((u8() shl 8) or u8()).toShort()).toLong()
        JceType.INT -> readIntRaw().toLong()
        JceType.LONG -> readLongRaw()
        JceType.ZERO -> 0L
        else -> error("not a numeric jce type: $type")
    }

    fun readStringValue(type: Int): String {
        val n = if (type == JceType.STRING1) u8() else readIntRaw()
        val s = String(buf, pos, n, Charsets.UTF_8)
        pos += n
        return s
    }

    fun readTaggedInt(): Int {
        val (_, type) = readHead()
        return readNumber(type).toInt()
    }

    fun readBytesValue(): ByteArray {
        u8()
        val n = readTaggedInt()
        val out = buf.copyOfRange(pos, pos + n)
        pos += n
        return out
    }

    fun skipValue(type: Int) {
        when (type) {
            JceType.BYTE -> pos += 1
            JceType.SHORT -> pos += 2
            JceType.INT -> pos += 4
            JceType.LONG -> pos += 8
            JceType.ZERO -> Unit
            JceType.STRING1 -> {
                val n = u8()
                pos += n
            }
            JceType.STRING4 -> {
                val n = readIntRaw()
                pos += n
            }
            JceType.SIMPLE_LIST -> {
                u8()
                val n = readTaggedInt()
                pos += n
            }
            JceType.MAP -> {
                val n = readTaggedInt()
                repeat(n) {
                    val (_, keyType) = readHead()
                    skipValue(keyType)
                    val (_, valueType) = readHead()
                    skipValue(valueType)
                }
            }
            JceType.LIST -> {
                val n = readTaggedInt()
                repeat(n) {
                    val (_, elementType) = readHead()
                    skipValue(elementType)
                }
            }
            JceType.STRUCT_BEGIN -> {
                while (true) {
                    val (_, t) = readHead()
                    if (t == JceType.STRUCT_END) break
                    skipValue(t)
                }
            }
            else -> error("unsupported jce type: $type")
        }
    }

    fun readStructFields(): ByteArray {
        val start = pos
        while (true) {
            val headStart = pos
            val (_, type) = readHead()
            if (type == JceType.STRUCT_END) return buf.copyOfRange(start, headStart)
            skipValue(type)
        }
    }
}
