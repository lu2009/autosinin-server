package io.github.luna.autosinin.protocol

import java.io.ByteArrayOutputStream

/** 农场网关极简 protobuf（NapCat 插件同款线格式；不引入 protobuf 运行时）。 */
object FarmProto {

    const val SERVICE = "gamepb.userpb.UserService"
    const val LOGIN = "Login"

    fun gate(service: String, method: String, body: ByteArray, seq: Long): ByteArray {
        val meta = str(1, service) + str(2, method) + varintField(3, 1) + varintField(4, seq)
        return bytes(1, meta) + bytes(2, body)
    }

    fun varint(v: Long): ByteArray {
        var x = v
        val out = ByteArrayOutputStream()
        do {
            var b = (x and 0x7f).toInt()
            x = x ushr 7
            if (x != 0L) b = b or 0x80
            out.write(b)
        } while (x != 0L)
        return out.toByteArray()
    }

    private fun tag(field: Int, wire: Int): ByteArray = varint(((field shl 3) or wire).toLong())

    fun varintField(field: Int, v: Long): ByteArray = tag(field, 0) + varint(v)

    fun str(field: Int, s: String): ByteArray {
        if (s.isEmpty()) return ByteArray(0)
        val b = s.toByteArray()
        return tag(field, 2) + varint(b.size.toLong()) + b
    }

    fun bytes(field: Int, b: ByteArray): ByteArray {
        if (b.isEmpty()) return ByteArray(0)
        return tag(field, 2) + varint(b.size.toLong()) + b
    }

    data class Fields(val map: Map<Int, List<Any>>) {
        fun long(field: Int): Long? = map[field]?.firstOrNull() as? Long
        fun bytes(field: Int): ByteArray? = map[field]?.firstOrNull() as? ByteArray
        fun text(field: Int): String? = bytes(field)?.toString(Charsets.UTF_8)
    }

    /** 解析 wire 格式；未知 wire type 直接停止，不做猜测。 */
    fun decode(b: ByteArray): Fields {
        val map = LinkedHashMap<Int, MutableList<Any>>()
        var i = 0
        while (i < b.size) {
            val (tagV, ni) = readVarint(b, i); i = ni
            val field = (tagV ushr 3).toInt()
            if (field == 0) break
            when (tagV and 7L) {
                0L -> {
                    val (v, n) = readVarint(b, i); i = n
                    map.getOrPut(field) { mutableListOf() }.add(v)
                }
                2L -> {
                    val (len, n) = readVarint(b, i); i = n
                    if (len < 0 || i + len > b.size) break
                    map.getOrPut(field) { mutableListOf() }.add(b.copyOfRange(i, i + len.toInt()))
                    i += len.toInt()
                }
                else -> break
            }
        }
        return Fields(map)
    }

    private fun readVarint(b: ByteArray, start: Int): Pair<Long, Int> {
        var v = 0L; var shift = 0; var i = start
        while (i < b.size && shift <= 63) {
            val x = b[i].toInt() and 0xff
            i++
            v = v or ((x and 0x7f).toLong() shl shift)
            if (x and 0x80 == 0) return v to i
            shift += 7
        }
        return v to i
    }

    data class Reply(val gid: String?, val name: String?, val level: Long?, val openId: String?)
}
