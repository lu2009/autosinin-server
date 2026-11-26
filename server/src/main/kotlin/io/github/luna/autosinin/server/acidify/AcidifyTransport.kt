package io.github.luna.autosinin.server.acidify

import io.github.luna.autosinin.server.core.ProtoTransport
import io.github.luna.autosinin.server.core.StubProtoTransport
import kotlinx.coroutines.runBlocking
import org.ntqqrev.acidify.common.UnsafeAcidifyApi

/**
 * 基于 acidify-core Bot.sendPacket 的服务端 ProtoTransport 实现。
 * 若对应 uin 未通过 acidify-core 登录，则回退到 fallback（如 HttpGatewayProtoTransport 或 StubProtoTransport）。
 */
class AcidifyTransport(
    private val sessionManager: AcidifySessionManager,
    private val fallback: ProtoTransport = StubProtoTransport(),
    private val timeoutMs: Long = 15_000L,
) : ProtoTransport {

    @OptIn(UnsafeAcidifyApi::class)
    override fun sendPbRaw(uin: String, cmd: String, body: ByteArray, trace: String): ProtoTransport.RawResponse {
        val bot = sessionManager.getBot(uin)
        if (bot == null || !bot.isLoggedIn) {
            return fallback.sendPbRaw(uin, cmd, body, trace)
        }
        return runCatching {
            runBlocking {
                val rsp = bot.sendPacket(cmd, body, timeoutMs)
                ProtoTransport.RawResponse(
                    code = rsp.retCode,
                    msg = rsp.extra.orEmpty(),
                    bytes = rsp.response,
                )
            }
        }.getOrElse {
            ProtoTransport.RawResponse(
                code = -1,
                msg = "acidify sendPacket error: ${it.javaClass.simpleName}: ${it.message}",
                bytes = null,
            )
        }
    }
}
