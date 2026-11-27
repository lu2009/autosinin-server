package io.github.luna.autosinin.protocol

import io.github.luna.autosinin.server.core.ProtoTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MigratedTasksTest {

    @Test
    fun miniAppCodeEncodesAndParsesResponse() {
        val req = MiniAppCode.buildGetCodeReq("1112386029", 7L)
        assertTrue(req.isNotEmpty())

        val innerRsp = PbEdit.bytes(2, "test_farm_code_123".toByteArray(Charsets.UTF_8))
        val qwebRsp = PbEdit.bytes(4, innerRsp)
        val parsed = MiniAppCode.parseGetCodeResp(qwebRsp)
        assertEquals("test_farm_code_123", parsed)
    }

    @Test
    fun publishBlindBoxAcceptsZeroCode() {
        val transport = object : ProtoTransport {
            override fun sendPbRaw(uin: String, cmd: String, body: ByteArray, trace: String): ProtoTransport.RawResponse {
                assertEquals(PublishReplay.CMD, cmd)
                assertTrue(body.isNotEmpty())
                return ProtoTransport.RawResponse(code = 0, msg = "ok", bytes = byteArrayOf(1, 2, 3))
            }
        }
        val res = PublishTask(transport).publishBlindBox("10001")
        assertTrue(res.ok)
    }

    @Test
    fun videoAdTaskRunsFullSequence() {
        val sent = mutableListOf<String>()
        val transport = object : ProtoTransport {
            override fun sendPbRaw(uin: String, cmd: String, body: ByteArray, trace: String): ProtoTransport.RawResponse {
                sent += cmd
                return when (cmd) {
                    VideoAdTask.CMD_ADV_GET -> {
                        val msg = PbEdit.bytes(3, "cookie_1".toByteArray())
                        val rspInfo = PbEdit.varintField(1, 1700000000L) +
                            PbEdit.varintField(2, 999L) +
                            PbEdit.bytes(3, "sig_ok".toByteArray())
                        val rsp = PbEdit.varintField(1, 0L) + PbEdit.bytes(3, msg) + PbEdit.bytes(5, rspInfo)
                        ProtoTransport.RawResponse(0, "ok", rsp)
                    }
                    VideoAdTask.CMD_ADV_REPORT -> {
                        val rsp = PbEdit.bytes(3, """{"coin_info":{"cur_coins":100}}""".toByteArray())
                        ProtoTransport.RawResponse(0, "ok", rsp)
                    }
                    VideoAdTask.CMD_REFRESH, VideoAdTask.CMD_FINISH ->
                        ProtoTransport.RawResponse(0, "ok", byteArrayOf(0x08, 0x00))
                    else -> ProtoTransport.RawResponse(-1, "unexpected $cmd", null)
                }
            }
        }
        val res = VideoAdTask(transport).run("10001", sleepFn = {})
        assertTrue(res.pending)
        assertEquals(
            listOf(
                VideoAdTask.CMD_ADV_GET,
                VideoAdTask.CMD_ADV_REPORT,
                VideoAdTask.CMD_REFRESH,
                VideoAdTask.CMD_FINISH,
            ),
            sent,
        )
    }

    @Test
    fun addFriendTaskDeletesAndSends0x7c2() {
        val deleted = mutableListOf<Long>()
        val cmds = mutableListOf<String>()
        val friendOp = object : AddFriendTask.FriendOperator {
            override fun deleteFriendIfPresent(uin: String, targetUin: Long): Boolean {
                deleted += targetUin
                return true
            }
        }
        val transport = object : ProtoTransport {
            override fun sendPbRaw(uin: String, cmd: String, body: ByteArray, trace: String): ProtoTransport.RawResponse {
                cmds += cmd
                assertTrue(body.isNotEmpty())
                return ProtoTransport.RawResponse(0, "ok", byteArrayOf(0x08, 0x00))
            }
        }
        val res = AddFriendTask(transport, friendOp).run("10001", sleepFn = {})
        assertTrue(res.ok)
        assertEquals(AddFriendTask.DEFAULT_TARGETS, deleted)
        assertEquals(listOf("OidbSvcTrpcTcp.0x7c2_5", "OidbSvcTrpcTcp.0x7c2_5", "OidbSvcTrpcTcp.0x7c2_5"), cmds)
    }
}
