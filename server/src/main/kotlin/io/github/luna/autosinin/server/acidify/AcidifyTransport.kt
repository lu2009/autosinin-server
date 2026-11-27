package io.github.luna.autosinin.server.acidify

import io.github.luna.autosinin.server.core.ProtoTransport
import io.github.luna.autosinin.server.core.StubProtoTransport
import kotlinx.coroutines.runBlocking

/**
 * 基于自研 Android SSO 客户端 (SsoClient.sendPacket) 的服务端 ProtoTransport 实现。
 * 若对应 uin 未在线，则回退到 fallback（如 HttpGatewayProtoTransport 或 StubProtoTransport）。
 */
class AcidifyTransport(
    private val sessionManager: AcidifySessionManager,
    private val fallback: ProtoTransport = StubProtoTransport(),
    private val timeoutMs: Long = 15_000L,
) : ProtoTransport {

    override fun sendPbRaw(uin: String, cmd: String, body: ByteArray, trace: String): ProtoTransport.RawResponse {
        val client = sessionManager.getBot(uin)
        if (client == null || !client.isOnline) {
            return fallback.sendPbRaw(uin, cmd, body, trace)
        }
        return runCatching {
            runBlocking {
                val rsp = client.sendPacket(cmd, body, timeoutMs)
                ProtoTransport.RawResponse(
                    code = rsp.retCode,
                    msg = rsp.extra,
                    bytes = rsp.response,
                )
            }
        }.getOrElse {
            ProtoTransport.RawResponse(
                code = -1,
                msg = "sso sendPacket error: ${it.javaClass.simpleName}: ${it.message}",
                bytes = null,
            )
        }
    }
}
