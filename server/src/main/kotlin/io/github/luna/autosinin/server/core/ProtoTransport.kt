package io.github.luna.autosinin.server.core

import io.github.luna.autosinin.protocol.PbEdit
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.Base64

/**
 * 服务端 Protobuf / OIDB 传输抽象，替代 Android 端 KuiklySender。
 * 可对接无头 QQ MSF / NTQQ 网关或 Unidbg + feikit 签名服务。
 */
interface ProtoTransport {

    data class RawResponse(
        val code: Int,
        val msg: String,
        val bytes: ByteArray? = null,
    ) {
        val detail: String get() = "code=$code msg=$msg len=${bytes?.size ?: -1}"
    }

    fun sendPbRaw(uin: String, cmd: String, body: ByteArray, trace: String): RawResponse

    fun sendOidb(
        uin: String,
        cmd: Int,
        serviceType: Int,
        body: ByteArray,
        trace: String,
        platform: String = "android 9.3.85",
    ): RawResponse {
        val pkg = PbEdit.varintField(1, cmd.toLong()) +
            PbEdit.varintField(2, serviceType.toLong()) +
            PbEdit.bytes(4, body) +
            PbEdit.bytes(6, platform.toByteArray(Charsets.UTF_8))
        val cmdName = "OidbSvcTrpcTcp.0x${cmd.toString(16)}_$serviceType"
        return sendPbRaw(uin, cmdName, pkg, trace)
    }
}

/** 未配置外部签名/MSF 网关时的占位实现。 */
class StubProtoTransport : ProtoTransport {
    override fun sendPbRaw(uin: String, cmd: String, body: ByteArray, trace: String): ProtoTransport.RawResponse {
        return ProtoTransport.RawResponse(
            code = -503,
            msg = "ProtoTransport gateway not configured (cmd=$cmd, uin=$uin)",
            bytes = null,
        )
    }
}

/**
 * 通过 HTTP JSON 协议对接外部无头 QQ MSF / NTQQ / Unidbg 签名网关。
 * 约定请求：POST {baseUrl}/pb/send
 *   {"uin":"...","cmd":"...","trace":"...","bodyBase64":"..."}
 * 约定响应：
 *   {"code":0,"msg":"ok","bodyBase64":"..."}
 */
class HttpGatewayProtoTransport(
    private val baseUrl: String,
    private val authToken: String? = null,
    private val httpClient: OkHttpClient = OkHttpClient(),
) : ProtoTransport {

    private val jsonType = "application/json; charset=utf-8".toMediaType()
    private val endpoint = baseUrl.trimEnd('/') + "/pb/send"

    override fun sendPbRaw(uin: String, cmd: String, body: ByteArray, trace: String): ProtoTransport.RawResponse {
        val payload = JSONObject()
            .put("uin", uin)
            .put("cmd", cmd)
            .put("trace", trace)
            .put("bodyBase64", Base64.getEncoder().encodeToString(body))
            .toString()

        val reqBuilder = Request.Builder()
            .url(endpoint)
            .post(payload.toRequestBody(jsonType))
        if (!authToken.isNullOrBlank()) {
            reqBuilder.header("Authorization", "Bearer $authToken")
        }

        return runCatching {
            httpClient.newCall(reqBuilder.build()).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    return ProtoTransport.RawResponse(-resp.code, "gateway http ${resp.code}: ${text.take(200)}")
                }
                val json = JSONObject(text)
                val code = json.optInt("code", -1)
                val msg = json.optString("msg", "")
                val b64 = json.optString("bodyBase64", "")
                val bytes = if (b64.isNotBlank()) Base64.getDecoder().decode(b64) else null
                ProtoTransport.RawResponse(code, msg, bytes)
            }
        }.getOrElse {
            ProtoTransport.RawResponse(-1, "gateway error: ${it.javaClass.simpleName}: ${it.message}")
        }
    }
}
