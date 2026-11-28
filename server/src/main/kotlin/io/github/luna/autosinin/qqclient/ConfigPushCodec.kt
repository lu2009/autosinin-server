package io.github.luna.autosinin.qqclient

object ConfigPushCodec {
    private const val SERVANT = "QQService.ConfigPushSvc.MainServant"

    data class PushReq(val type: Int, val buf: ByteArray, val seq: Long)

    fun decodePushReq(raw: ByteArray): PushReq {
        val body = if (raw.size > 4 && readIntBE(raw) == raw.size) raw.copyOfRange(4, raw.size) else raw
        val reader = JceReader(body)
        var sBuffer: ByteArray? = null
        while (!reader.eof()) {
            val (tag, type) = reader.readHead()
            if (tag == 7 && type == JceType.SIMPLE_LIST) {
                sBuffer = reader.readBytesValue()
            } else {
                reader.skipValue(type)
            }
        }
        val sb = sBuffer ?: error("PushReq missing sBuffer")
        return decodePushReqStruct(extractMapStruct(sb))
    }

    private fun readIntBE(b: ByteArray): Int =
        ((b[0].toInt() and 0xFF) shl 24) or
            ((b[1].toInt() and 0xFF) shl 16) or
            ((b[2].toInt() and 0xFF) shl 8) or
            (b[3].toInt() and 0xFF)

    private fun extractMapStruct(sBuffer: ByteArray): ByteArray {
        val reader = JceReader(sBuffer)
        val (_, mapType) = reader.readHead()
        require(mapType == JceType.MAP) { "sBuffer is not a map" }
        require(reader.readTaggedInt() >= 1) { "sBuffer map is empty" }
        val (_, keyType) = reader.readHead()
        reader.skipValue(keyType)
        val (_, valueType) = reader.readHead()
        return when (valueType) {
            JceType.STRUCT_BEGIN -> reader.readStructFields()
            JceType.SIMPLE_LIST -> reader.readBytesValue()
            else -> error("sBuffer value type=$valueType")
        }
    }

    private fun decodePushReqStruct(fields: ByteArray): PushReq {
        var type = 0
        var buf = ByteArray(0)
        var seq = 0L
        val reader = JceReader(fields)
        while (!reader.eof()) {
            val (tag, fieldType) = reader.readHead()
            when (tag) {
                1 -> type = reader.readNumber(fieldType).toInt()
                2 -> {
                    if (fieldType == JceType.SIMPLE_LIST) {
                        buf = reader.readBytesValue()
                    } else {
                        reader.skipValue(fieldType)
                    }
                }
                3 -> seq = reader.readNumber(fieldType)
                else -> reader.skipValue(fieldType)
            }
        }
        return PushReq(type, buf, seq)
    }

    fun encodePushResp(req: PushReq): ByteArray {
        val respStruct = JceWriter().apply {
            writeInt(1, req.type)
            writeLong(2, req.seq)
            if (req.type == 3 && req.buf.isNotEmpty()) writeBytes(3, req.buf)
        }.toByteArray()

        val sBuffer = JceWriter().apply {
            writeMapBytes(0, mapOf("PushResp" to respStruct))
        }.toByteArray()

        return JceWriter().apply {
            writeByte(1, 2)
            writeZero(2)
            writeZero(3)
            writeZero(4)
            writeString(5, SERVANT)
            writeString(6, "PushResp")
            writeBytes(7, sBuffer)
            writeZero(8)
            writeEmptyMap(9)
            writeEmptyMap(10)
        }.toByteArray()
    }
}
