package io.github.luna.autosinin.protocol

import io.github.luna.autosinin.server.core.ProtoTransport
import io.github.luna.autosinin.server.core.ServerLog
import java.util.UUID

/**
 * 空间说说/盲盒发布任务：
 *   - publish_mood (1013)：默认发公开 + 24h 自删，同时顺带完成 publish_feed (1010)。
 */
class PublishTask(private val transport: ProtoTransport) {

    data class Result(val ok: Boolean, val detail: String)

    fun publishMood(uin: String, seq: Int = 5): Result {
        val nowMs = System.currentTimeMillis()
        val trace = "${uin}_${nowMs}_${(1000..9999).random()}"
        val uuid = UUID.randomUUID().toString()
        val body = PublishReplay.buildPublicMood(
            PublishReplay.Params(
                uin = uin,
                trace = trace,
                uuid = uuid,
                ts = nowMs / 1000L,
                seq = seq,
            ),
        )
        val r = transport.sendPbRaw(uin, PublishReplay.CMD, body, trace)
        ServerLog.i(TAG, "uin=$uin publishMood ${r.detail}")
        val ok = r.code == 0 || (r.bytes?.isNotEmpty() == true)
        return Result(ok, if (ok) "公开说说发布已受理 ${r.detail}" else "说说发布失败 ${r.detail}")
    }

    companion object {
        private const val TAG = "PUBLISH"
    }
}
