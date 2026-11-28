package io.github.luna.autosinin.qqclient

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigPushCodecTest {

    private fun buildPushReqPacket(type: Int, buf: ByteArray, seq: Long): ByteArray {
        val reqStruct = JceWriter().apply {
            writeInt(1, type)
            writeBytes(2, buf)
            writeLong(3, seq)
        }.toByteArray()
        val sBuffer = JceWriter().apply {
            writeMapBytes(0, mapOf("PushReq" to reqStruct))
        }.toByteArray()
        return JceWriter().apply {
            writeByte(1, 2)
            writeZero(2)
            writeZero(3)
            writeZero(4)
            writeString(5, "QQService.ConfigPushSvc.MainServant")
            writeString(6, "PushReq")
            writeBytes(7, sBuffer)
            writeZero(8)
            writeEmptyMap(9)
            writeEmptyMap(10)
        }.toByteArray()
    }

    @Test
    fun decodesPushReqWithTypeBufferAndSeq() {
        val payload = byteArrayOf(0x01, 0x02, 0x03, 0x04)
        val decoded = ConfigPushCodec.decodePushReq(buildPushReqPacket(2, payload, 987654321L))
        assertEquals(2, decoded.type)
        assertArrayEquals(payload, decoded.buf)
        assertEquals(987654321L, decoded.seq)
    }

    @Test
    fun decodesPushReqWithoutBuffer() {
        val decoded = ConfigPushCodec.decodePushReq(buildPushReqPacket(1, ByteArray(0), 5L))
        assertEquals(1, decoded.type)
        assertEquals(0, decoded.buf.size)
        assertEquals(5L, decoded.seq)
    }

    @Test
    fun encodedPushRespCarriesTypeAndSeq() {
        val encoded = ConfigPushCodec.encodePushResp(
            ConfigPushCodec.PushReq(type = 2, buf = ByteArray(0), seq = 11223344L),
        )
        assertEquals(0x10, encoded[0].toInt() and 0xFF)
        assertEquals(2, encoded[1].toInt() and 0xFF)

        val outer = JceReader(encoded)
        var sBuffer: ByteArray? = null
        while (!outer.eof()) {
            val (tag, type) = outer.readHead()
            if (tag == 7 && type == JceType.SIMPLE_LIST) sBuffer = outer.readBytesValue() else outer.skipValue(type)
        }
        assertTrue(sBuffer != null && sBuffer!!.isNotEmpty())

        val map = JceReader(sBuffer!!)
        assertEquals(JceType.MAP, map.readHead().second)
        assertEquals(1, map.readTaggedInt())
        val (_, keyType) = map.readHead()
        assertEquals("PushResp", map.readStringValue(keyType))
        assertEquals(JceType.STRUCT_BEGIN, map.readHead().second)

        var type = 0
        var seq = 0L
        while (true) {
            val (tag, fieldType) = map.readHead()
            if (fieldType == JceType.STRUCT_END) break
            when (tag) {
                1 -> type = map.readNumber(fieldType).toInt()
                2 -> seq = map.readNumber(fieldType)
                else -> map.skipValue(fieldType)
            }
        }
        assertEquals(2, type)
        assertEquals(11223344L, seq)
    }

}
