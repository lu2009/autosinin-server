package io.github.luna.autosinin.protocol

/**
 * 通用 protobuf 模板编辑器：解析 wire 格式，递归替换字节串/varint，再重新编码长度。
 *
 * 只用于长度会变的回放字段（uin/session/trace/时间戳）；未知 wire type 会退回整段字节替换。
 */
object PbEdit {

    private data class Field(val number: Int, val wire: Int, val raw: ByteArray)

    fun replace(template: ByteArray, pairs: List<Pair<ByteArray, ByteArray>>): ByteArray {
        val parsed = parse(template)
        if (parsed == null) return replaceDirect(template, pairs)
        return encode(parsed.map { editField(it, pairs) })
    }

    private fun editField(f: Field, pairs: List<Pair<ByteArray, ByteArray>>): Field {
        if (f.wire == 2) {
            var payload = f.raw
            val nested = parse(payload)
            if (nested != null) {
                val edited = encode(nested.map { editField(it, pairs) })
                if (!edited.contentEquals(payload)) payload = edited
            }
            if (payload.contentEquals(f.raw)) payload = replaceDirect(payload, pairs)
            return f.copy(raw = payload)
        }
        for ((from, to) in pairs) {
            if (f.raw.contentEquals(from)) return f.copy(raw = to)
        }
        return f
    }

    private fun parse(b: ByteArray): List<Field>? {
        val out = ArrayList<Field>()
        var i = 0
        while (i < b.size) {
            val tag = readVarint(b, i) ?: return null
            i = tag.second
            val number = (tag.first ushr 3).toInt()
            val wire = (tag.first and 7L).toInt()
            if (number <= 0) return null
            val raw: ByteArray
            when (wire) {
                0 -> {
                    val start = i
                    val v = readVarint(b, i) ?: return null
                    i = v.second
                    raw = b.copyOfRange(start, i)
                }
                1 -> {
                    if (i + 8 > b.size) return null
                    raw = b.copyOfRange(i, i + 8); i += 8
                }
                2 -> {
                    val lenV = readVarint(b, i) ?: return null
                    i = lenV.second
                    val len = lenV.first
                    if (len < 0 || i + len > b.size) return null
                    raw = b.copyOfRange(i, i + len.toInt()); i += len.toInt()
                }
                5 -> {
                    if (i + 4 > b.size) return null
                    raw = b.copyOfRange(i, i + 4); i += 4
                }
                else -> return null
            }
            out.add(Field(number, wire, raw))
        }
        return out
    }

    private fun encode(fields: List<Field>): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        for (f in fields) {
            out.write(varint(((f.number.toLong() shl 3) or f.wire.toLong())))
            if (f.wire == 2) out.write(varint(f.raw.size.toLong()))
            out.write(f.raw)
        }
        return out.toByteArray()
    }

    private fun readVarint(b: ByteArray, start: Int): Pair<Long, Int>? {
        var v = 0L; var shift = 0; var i = start
        while (i < b.size && shift <= 63) {
            val x = b[i].toInt() and 0xff; i++
            v = v or ((x and 0x7f).toLong() shl shift)
            if (x and 0x80 == 0) return v to i
            shift += 7
        }
        return null
    }

    private fun replaceDirect(b: ByteArray, pairs: List<Pair<ByteArray, ByteArray>>): ByteArray {
        var out = b
        for ((from, to) in pairs) {
            if (from.isEmpty()) continue
            val buf = java.io.ByteArrayOutputStream()
            var i = 0
            while (i <= out.size - from.size) {
                var hit = true
                for (j in from.indices) if (out[i + j] != from[j]) { hit = false; break }
                if (hit) { buf.write(to); i += from.size } else { buf.write(out[i].toInt()); i++ }
            }
            while (i < out.size) { buf.write(out[i].toInt()); i++ }
            out = buf.toByteArray()
        }
        return out
    }

    fun varintField(field: Int, v: Long): ByteArray = varint(((field.toLong() shl 3) or 0)) + varint(v)

    fun bytes(field: Int, b: ByteArray): ByteArray =
        varint(((field.toLong() shl 3) or 2)) + varint(b.size.toLong()) + b

    private fun varint(v: Long): ByteArray {
        var x = v
        val out = java.io.ByteArrayOutputStream()
        while (true) {
            val b = (x and 0x7f).toInt()
            x = x ushr 7
            if (x == 0L) { out.write(b); break }
            out.write(b or 0x80)
        }
        return out.toByteArray()
    }
}
