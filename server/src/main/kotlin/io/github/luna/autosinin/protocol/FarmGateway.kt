package io.github.luna.autosinin.protocol

import io.github.luna.autosinin.server.core.ServerLog
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * QQ 经典农场网关登录（wss://gate-obt.nqf.qq.com/prod/ws）。
 */
class FarmGateway(
    private val httpClient: OkHttpClient = WelfareClaim.defaultHttpClient(),
) {

    data class Outcome(
        val ok: Boolean,
        val detail: String,
        val gid: String? = null,
        val name: String? = null,
        val openId: String? = null,
    )

    fun login(code: String): Outcome {
        if (code.isBlank()) return Outcome(false, "code 为空")
        val url = "$URL?platform=qq&os=iOS&ver=$VER&code=${enc(code)}&openID="
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", UA)
            .header("Origin", "https://gate-obt.nqf.qq.com")
            .build()
        val latch = CountDownLatch(1)
        val result = AtomicReference(Outcome(false, "timeout"))

        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                val msg = FarmProto.gate(FarmProto.SERVICE, FarmProto.LOGIN, ByteArray(0), 1)
                webSocket.send(ByteString.of(*msg))
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                val parsed = parseFrame(bytes.toByteArray())
                if (parsed != null) {
                    result.set(parsed)
                    latch.countDown()
                    webSocket.close(1000, "done")
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                result.set(Outcome(false, "ws failure: ${t.javaClass.simpleName}: ${t.message}"))
                latch.countDown()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                latch.countDown()
            }
        }

        return try {
            val ws = httpClient.newWebSocket(req, listener)
            latch.await(20, TimeUnit.SECONDS)
            ws.cancel()
            val out = result.get()
            ServerLog.i(TAG, "login ok=${out.ok} detail=${out.detail} gid=${out.gid} name=${out.name}")
            out
        } catch (t: Throwable) {
            Outcome(false, "connect failed: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    private fun parseFrame(bytes: ByteArray): Outcome? {
        val gate = FarmProto.decode(bytes)
        val metaBytes = gate.bytes(1) ?: return null
        val meta = FarmProto.decode(metaBytes)
        val method = meta.text(2) ?: return null
        if (method != FarmProto.LOGIN) return null
        val errCode = meta.long(6) ?: 0L
        val errMsg = decode(meta.bytes(7)).orEmpty()
        if (errCode != 0L) return Outcome(false, "errCode=$errCode $errMsg")
        val body = gate.bytes(2) ?: ByteArray(0)
        val reply = FarmProto.decode(body)
        val gid = reply.long(1)?.toString() ?: clean(decode(reply.bytes(1)))
        val name = clean(decode(reply.bytes(2)))
        val openId = clean(decode(reply.bytes(6) ?: reply.bytes(5)))
        return Outcome(true, "login ok", gid, name, openId)
    }

    private fun decode(bytes: ByteArray?): String? {
        if (bytes == null || bytes.isEmpty()) return null
        for (charset in listOf(Charsets.UTF_8, Charset.forName("GB18030"))) {
            try {
                return charset.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString()
            } catch (_: CharacterCodingException) {
            }
        }
        return null
    }

    private fun clean(s: String?): String? {
        if (s == null) return null
        val filtered = s.filter {
            !it.isISOControl() && it != '\uFFFD' &&
                (it.isLetterOrDigit() || it == ' ' || it == '_' || it == '-' || it == '.' || it == '·' ||
                    it.code in 0x4E00..0x9FFF)
        }.trim()
        if (filtered.length < 2) return null
        if (filtered.length * 2 < s.trim().length) return null
        return filtered.take(24)
    }

    private fun enc(s: String): String = java.net.URLEncoder.encode(s, "UTF-8")

    companion object {
        private const val TAG = "FARM"
        private const val URL = "wss://gate-obt.nqf.qq.com/prod/ws"
        private const val VER = "1.13.3.14_20260826"
        private const val UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/132.0.0.0 Safari/537.36 " +
                "MicroMessenger/7.0.20.1781(0x6700143B) NetType/WIFI MiniProgramEnv/Windows " +
                "WindowsWechat/WMPF WindowsWechat(0x63090a13)"
    }
}
