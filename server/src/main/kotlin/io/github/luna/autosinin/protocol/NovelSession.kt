package io.github.luna.autosinin.protocol

import io.github.luna.autosinin.server.core.ProtoTransport
import io.github.luna.autosinin.server.core.ServerLog
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * 看小说任务（1029）：0x98cc 取任务书 + 0x999d(进度) / 0x999e(会话) / 0x9de7(完成)。
 * 使用按 uin 隔离的书目缓存，保证多账号并发安全。
 */
class NovelSession(private val transport: ProtoTransport) {

    data class BookInfo(val bookId: String, val chapterId: String)
    data class Result(val ok: Boolean, val detail: String)

    private val bookCache = ConcurrentHashMap<String, BookInfo>()

    fun fetchTaskBook(uin: String): BookInfo? {
        val json = JSONObject()
            .put("module_id", 22)
            .put("pagination", JSONObject().put("page_no", 1).put("page_size", 1))
            .toString()
        val body = PbEdit.bytes(3, json.toByteArray())
        val trace = "${uin}_levelbook_${System.currentTimeMillis()}"
        val r = transport.sendPbRaw(uin, CMD_BOOK, body, trace)
        val text = r.bytes?.toString(Charsets.UTF_8).orEmpty()
        ServerLog.i(TAG, "uin=$uin book rsp ${r.detail}")
        val info = parseBookInfo(text) ?: return bookCache[uin]
        bookCache[uin] = info
        return info
    }

    fun run(
        uin: String,
        nowMs: Long = System.currentTimeMillis(),
        sleepFn: (Long) -> Unit = { Thread.sleep(it) },
    ): Result {
        val info = runCatching { fetchTaskBook(uin) }.getOrNull() ?: bookCache[uin]
            ?: return Result(false, "0x98cc 未取到任务 bookId/first_chapter_id")
        ServerLog.i(TAG, "uin=$uin book=${info.bookId} chapter=${info.chapterId}")
        val sessionId = "${uin}_${info.bookId}_$nowMs"
        var okCount = 0

        for (i in 0..8) {
            if (Thread.currentThread().isInterrupted) throw InterruptedException("novel cancelled")
            val progress = sendStep(
                uin,
                CMD_PROGRESS,
                progressBody(info.bookId, info.chapterId, chapterNo = 1, percentage = i * 10, isTurn = true, paragraphId = i.toString(), heartbeat = true),
            )
            if (Thread.currentThread().isInterrupted) throw InterruptedException("novel cancelled")
            val session = sendStep(
                uin,
                CMD_SESSION,
                sessionBody(info.bookId, sessionId, effective = false),
            )
            if (progress) okCount++
            if (session) okCount++
            if (i < 8) sleepFn(20_000L)
        }

        if (Thread.currentThread().isInterrupted) throw InterruptedException("novel cancelled")
        val finalProgress = sendStep(
            uin,
            CMD_PROGRESS,
            progressBody(info.bookId, info.chapterId, chapterNo = 1, percentage = 100, isTurn = true, paragraphId = "99", heartbeat = true),
        )
        val finalSession = sendStep(
            uin,
            CMD_SESSION,
            sessionBody(info.bookId, sessionId, effective = true),
        )
        if (finalProgress) okCount++
        if (finalSession) okCount++
        val complete = transport.sendPbRaw(uin, CMD_COMPLETE, ByteArray(0), "${uin}_novel_complete_${System.currentTimeMillis()}")
        ServerLog.i(TAG, "uin=$uin novel okCount=$okCount complete=${complete.detail}")
        return Result(okCount > 0, "$okCount/20 accepted; complete=${complete.detail}")
    }

    private fun sendStep(uin: String, cmd: String, body: ByteArray): Boolean {
        val trace = "${uin}_novel_${System.currentTimeMillis()}"
        val r = transport.sendPbRaw(uin, cmd, body, trace)
        return r.code == 0 || (r.bytes?.isNotEmpty() == true)
    }

    companion object {
        private const val TAG = "NOVELSESSION"
        private const val CMD_BOOK = "MQUpdateSvc_com_qq_vip.web.OidbSvcTrpcJsapiTcp.0x98cc_0"
        private const val CMD_PROGRESS = "MQUpdateSvc_com_qq_vip.web.OidbSvcTrpcJsapiTcp.0x999d_0"
        private const val CMD_SESSION = "MQUpdateSvc_com_qq_vip.web.OidbSvcTrpcJsapiTcp.0x999e_0"
        private const val CMD_COMPLETE = "MQUpdateSvc_com_qq_vip.web.OidbSvcTrpcJsapiTcp.0x9de7_0"

        internal fun parseBookInfo(text: String): BookInfo? {
            val start = text.indexOf('{')
            if (start < 0) return null
            return runCatching {
                val root = JSONObject(text.substring(start))
                val base = root.optJSONArray("book_list")?.optJSONObject(0)?.optJSONObject("base_info")
                    ?: return null
                val bookId = base.optString("id", "").takeIf { it.isNotBlank() } ?: return null
                val chapterId = base.optString("first_chapter_id", "").takeIf { it.isNotBlank() } ?: return null
                BookInfo(bookId, chapterId)
            }.getOrNull()
        }

        fun progressBody(
            bookId: String,
            chapterId: String,
            chapterNo: Int,
            percentage: Int,
            isTurn: Boolean,
            paragraphId: String = "0",
            heartbeat: Boolean = true,
        ): ByteArray {
            val progress = JSONObject()
                .put("chapter_id", chapterId)
                .put("content_index", 0)
                .put("paragraph_id", paragraphId)
                .put("chapter_no", chapterNo)
                .put("percentage", percentage / 100000.0)
                .put("source_type", 1)
            val json = JSONObject()
                .put("book_id", bookId)
                .put("progress", progress)
                .put("is_turn", isTurn)
            if (heartbeat) json.put("heartbeat_type", 1)
            return PbEdit.bytes(3, json.toString().toByteArray())
        }

        fun sessionBody(bookId: String, sessionId: String, effective: Boolean): ByteArray {
            val json = JSONObject()
                .put("book_id", bookId)
                .put("session_id", sessionId)
                .put("source_type", 1)
                .put("is_effective", effective)
                .put("with_effective_param", false)
            return PbEdit.bytes(3, json.toString().toByteArray())
        }
    }
}
