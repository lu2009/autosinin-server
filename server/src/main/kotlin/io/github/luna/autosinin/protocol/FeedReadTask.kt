package io.github.luna.autosinin.protocol

import io.github.luna.autosinin.server.core.ProtoTransport
import io.github.luna.autosinin.server.core.ServerLog

/** 浏览十条好友动态：按需拉取真实 feed cell，累计到 10 条立即停止发送。 */
class FeedReadTask(
    private val transport: ProtoTransport,
    private val visitReport: VisitReport,
    private val status: MiniGameStatus,
) {

    data class Result(val ok: Boolean, val detail: String, val pending: Boolean = false)

    fun run(uin: String, sleep: (Long) -> Unit = { Thread.sleep(it) }): Result {
        val collected = ArrayList<ByteArray>(TARGET)
        var lastDetail = "未取到好友动态"
        for (round in 0 until MAX_ROUNDS) {
            if (collected.size >= TARGET) break
            val ts = System.currentTimeMillis()
            val trace = "${uin}_feed_${ts}_$round"
            val body = FeedRefreshReplay.buildFeeds(uin, ts, 52 + round, trace)
            val r = transport.sendPbRaw(uin, FeedRefreshReplay.FEEDS_CMD, body, trace)
            ServerLog.i(TAG, "uin=$uin round=${round + 1} feeds code=${r.code} len=${r.bytes?.size ?: -1} collected=${collected.size}")
            if (r.code != 0 || r.bytes == null) {
                lastDetail = "GetFriendFeeds code=${r.code} msg=${r.msg}"
                break
            }

            val (cellCount, records) = VisitReport.extractRecords(r.bytes)
            ServerLog.i(TAG, "uin=$uin round=${round + 1} cells=$cellCount extracted=${records.size}")
            if (records.isEmpty()) {
                lastDetail = "回包没有可用 feed cell（cells=$cellCount）"
                break
            }
            val need = TARGET - collected.size
            collected += records.take(need)
        }
        if (collected.size < TARGET) {
            return Result(false, "$lastDetail；实际收集 ${collected.size} 条，未达到 $TARGET 条")
        }

        val vr = visitReport.sendBatch(uin, collected)
        if (!vr.ok) return Result(false, vr.detail)

        sleep(2_000)
        val task = runCatching { status.fetchFeedTask(uin) }.getOrNull()
        if (task?.done == true) {
            return Result(true, "已批量发送 ${collected.size} 条真实 VisitRecord；任务已完成")
        }
        return Result(
            ok = false,
            detail = "已批量发送 ${collected.size} 条真实 VisitRecord，待 0x9172 异步核验（status=${task?.status ?: -1}）",
            pending = true,
        )
    }

    companion object {
        private const val TAG = "FEED"
        private const val TARGET = 10
        private const val MAX_ROUNDS = 6
    }
}
