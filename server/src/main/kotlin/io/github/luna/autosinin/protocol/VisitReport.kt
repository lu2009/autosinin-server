package io.github.luna.autosinin.protocol

import io.github.luna.autosinin.server.core.ProtoTransport
import io.github.luna.autosinin.server.core.ServerLog

/**
 * 好友动态浏览上报：从 GetFriendFeeds 的 feed cell 生成 VisitRecord。
 */
class VisitReport(private val transport: ProtoTransport) {

    data class Result(val ok: Boolean, val detail: String, val count: Int = 0)

    fun sendFromFeedResponse(uin: String, response: ByteArray, maxRecords: Int = 10): Result {
        val (cellCount, records) = extractRecords(response)
        ServerLog.i(TAG, "uin=$uin cells=$cellCount visitRecords=${records.size}")
        if (records.isEmpty()) return Result(false, "回包没有可用 feed cell（cells=$cellCount）", 0)
        return sendBatch(uin, records.take(maxRecords))
    }

    fun sendBatch(uin: String, records: List<ByteArray>): Result {
        if (records.isEmpty()) return Result(false, "没有可上报的 VisitRecord", 0)
        val indexed = records.mapIndexed { index, rec ->
            if (index > 0) rec + PbEdit.varintField(8, index.toLong()) else rec
        }
        val trace = "${uin}_visit_${System.currentTimeMillis()}"
        val body = envelope(1L, indexed, trace)
        val r = transport.sendPbRaw(uin, CMD, body, trace)
        ServerLog.i(TAG, "uin=$uin visitBatch size=${indexed.size} code=${r.code} msg=${r.msg}")
        return if (r.code == 0) Result(true, "已批量发送 ${indexed.size} 条真实 VisitRecord", indexed.size)
        else Result(false, "VisitReport 失败 code=${r.code} msg=${r.msg}", indexed.size)
    }

    companion object {
        private const val TAG = "FEED"
        const val CMD = "QzoneV4Service.trpc.qzone.client_report.VisitReporter.ReportVisit"
        private const val QUA = "V1_AND_SQ_9.3.85_16920_YYB_D"
        private const val CLIENT_INFO =
            "i=be9741c6cffd0a340d99c75610001751a904&imsi=be9741c6cffd0a340d99c75610001751a904&mac=02:00:00:00:00:00&m=22041211AC&o=14&a=34&sd=0&c64=1&sc=1&p=1080*2280&aid=be9741c6cffd0a340d99c75610001751a904&f=Xiaomi&mm=7478&cf=1953&cc=8&qimei=be9741c6cffd0a340d99c75610001751a904&qimei36=be9741c6cffd0a340d99c75610001751a904&sharpP=1&n=wifi&support_xsj_live=true&client_mod=default&qimei=be9741c6cffd0a340d99c75610001751a904&longitude=&latitude=&coordtype=0&timezone=+8,id:Asia/Shanghai&is_teenager_mod=0&is_care_mod=0&feeds_new_style=1&is_feed_pro=1&is_pad_device=2&is_fold_device=2&feed_in_tab=0"

        fun extractRecords(response: ByteArray): Pair<Int, List<ByteArray>> {
            val top = FarmProto.decode(response)
            val payload = top.bytes(4) ?: return 0 to emptyList()
            val root = FarmProto.decode(payload)
            val cells = root.bytesList(2)
            if (cells.isEmpty()) return 0 to emptyList()
            val out = ArrayList<ByteArray>()
            for (cellWrapper in cells) {
                val first = FarmProto.decode(cellWrapper).bytes(1) ?: continue
                val firstFields = FarmProto.decode(first)
                val feed = if (firstFields.bytes(15) != null) {
                    first
                } else {
                    val second = firstFields.bytes(1) ?: continue
                    if (FarmProto.decode(second).bytes(15) == null) continue
                    second
                }
                val feedFields = FarmProto.decode(feed)
                val holderFields = FarmProto.decode(first)
                val feedKey = feedFields.bytes(15) ?: continue
                val ctx = feedFields.bytes(32) ?: holderFields.bytes(32) ?: continue
                val busi = feedFields.bytes(29) ?: holderFields.bytes(29)
                var record = PbEdit.bytes(2, feedKey)
                record += PbEdit.varintField(3, System.currentTimeMillis() / 1000)
                record += PbEdit.bytes(4, ctx)
                record += PbEdit.varintField(5, 3)
                record += PbEdit.varintField(6, 3)
                if (busi != null) {
                    for (entry in FarmProto.decode(busi).bytesList(3)) {
                        val ef = FarmProto.decode(entry)
                        val key = ef.long(3) ?: continue
                        val value = ef.bytes(2) ?: continue
                        val mapEntry = PbEdit.varintField(1, key) + PbEdit.bytes(2, value)
                        record += PbEdit.bytes(9, mapEntry)
                    }
                }
                out += record
            }
            return cells.size to out
        }

        private fun FarmProto.Fields.bytesList(field: Int): List<ByteArray> =
            map[field]?.filterIsInstance<ByteArray>() ?: emptyList()

        private fun envelope(seq: Long, records: List<ByteArray>, trace: String): ByteArray {
            var payload = ByteArray(0)
            for (r in records) payload += PbEdit.bytes(2, r)
            var env = PbEdit.varintField(1, seq)
            env += PbEdit.bytes(2, QUA.toByteArray())
            env += PbEdit.bytes(3, CLIENT_INFO.toByteArray())
            env += PbEdit.bytes(4, payload)
            env += PbEdit.bytes(5, trace.toByteArray())
            val appid = PbEdit.bytes(1, "fc-appid".toByteArray()) + PbEdit.bytes(2, "101".toByteArray())
            env += PbEdit.bytes(10, appid)
            return env
        }
    }
}
