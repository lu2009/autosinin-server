package io.github.luna.autosinin.protocol

import io.github.luna.autosinin.server.core.ProtoTransport
import io.github.luna.autosinin.server.core.ServerLog
import java.io.ByteArrayOutputStream

/**
 * 等级任务 1021（看视频获得加速时长）纯协议实现：
 *   1. MobileAdv.AdvGet (VacAdvReq) -> 提取 recomm_cookie 与 reward_rsp_info{time, nonce, signature}
 *   2. MobileAdv.AdvReport (MobileAdvReportReq: action_type=4)
 *   3. OidbSvcTrpcTcp.0x92d5_0 刷新 -> OidbSvcTrpcTcp.0x9379_0 {external_biz_type=1} 完成
 */
class VideoAdTask(private val transport: ProtoTransport) {

    data class Result(
        val ok: Boolean,
        val pending: Boolean,
        val detail: String,
    )

    data class AdvGetParsed(
        val errCode: Long,
        val errMsg: String,
        val recommCookie: ByteArray,
        val time: Long,
        val nonce: Long,
        val signature: ByteArray,
    )

    data class AdvReportParsed(
        val retCode: Long,
        val errorMsg: String,
    )

    fun run(uin: String, sleepFn: (Long) -> Unit = { Thread.sleep(it) }): Result {
        val now = System.currentTimeMillis()
        val sessionId = "${uin}_$now"
        val rewardItem = """{"reward_id":$DEFAULT_REWARD_ID,"session_id":"$sessionId"}""".toByteArray(Charsets.UTF_8)

        val getReq = buildAdvGetReq(rewardType = DEFAULT_REWARD_TYPE, rewardItem = rewardItem)
        val getResp = transport.sendPbRaw(uin, CMD_ADV_GET, getReq, "${uin}_adv_get_$now")
        val getBytes = getResp.bytes
        if (getResp.code != 0 || getBytes == null || getBytes.isEmpty()) {
            return Result(ok = false, pending = false, detail = "MobileAdv.AdvGet 失败 ${getResp.detail}")
        }
        val parsedGet = parseAdvGetRsp(getBytes)
            ?: return Result(ok = false, pending = false, detail = "MobileAdv.AdvGet 回包解析失败 len=${getBytes.size}")
        if (parsedGet.errCode != 0L) {
            return Result(
                ok = false,
                pending = false,
                detail = "MobileAdv.AdvGet errCode=${parsedGet.errCode} ${parsedGet.errMsg}".trim(),
            )
        }
        if (parsedGet.signature.isEmpty()) {
            return Result(ok = false, pending = false, detail = "MobileAdv.AdvGet 缺少 reward_rsp_info.signature")
        }

        val reportReq = buildAdvReportReq(
            rewardType = DEFAULT_REWARD_TYPE,
            rewardItem = rewardItem,
            parsed = parsedGet,
            actionValue = DEFAULT_ELAPSED_SEC,
        )
        val reportResp = transport.sendPbRaw(uin, CMD_ADV_REPORT, reportReq, "${uin}_adv_report_$now")
        val reportBytes = reportResp.bytes
        if (reportResp.code != 0 || reportBytes == null || reportBytes.isEmpty()) {
            return Result(ok = false, pending = false, detail = "MobileAdv.AdvReport 失败 ${reportResp.detail}")
        }
        val parsedReport = parseAdvReportRsp(reportBytes)
            ?: return Result(ok = false, pending = false, detail = "MobileAdv.AdvReport 回包解析失败 len=${reportBytes.size}")
        if (parsedReport.retCode != 0L) {
            return Result(
                ok = false,
                pending = false,
                detail = "MobileAdv.AdvReport retCode=${parsedReport.retCode} ${parsedReport.errorMsg}".trim(),
            )
        }

        val refreshResp = transport.sendPbRaw(uin, CMD_REFRESH, REFRESH_BODY, "${uin}_video_refresh_$now")
        ServerLog.i(TAG, "uin=$uin 0x92d5_0 ${refreshResp.detail}")

        sleepFn(300L)

        val finishResp = transport.sendPbRaw(uin, CMD_FINISH, FINISH_BODY, "${uin}_video_finish_$now")
        ServerLog.i(TAG, "uin=$uin 0x9379_0 ${finishResp.detail}")
        if (finishResp.code != 0) {
            return Result(ok = false, pending = false, detail = "0x9379_0 失败 ${finishResp.detail}")
        }

        return Result(
            ok = false,
            pending = true,
            detail = "AdvGet+AdvReport+0x92d5+0x9379 已提交，等待 0x9172 异步对账",
        )
    }

    companion object {
        private const val TAG = "VIDEOAD"
        const val CMD_ADV_GET = "MobileAdv.AdvGet"
        const val CMD_ADV_REPORT = "MobileAdv.AdvReport"
        const val CMD_REFRESH = "OidbSvcTrpcTcp.0x92d5_0"
        const val CMD_FINISH = "OidbSvcTrpcTcp.0x9379_0"
        private const val DEFAULT_ADV_POS = 7L
        private const val DEFAULT_REWARD_TYPE = 33L
        private const val DEFAULT_REWARD_ID = 1
        private const val DEFAULT_ACTION_TYPE = 4L
        private const val DEFAULT_ELAPSED_SEC = 15L

        private val REFRESH_BODY = hex("08d5a502100018002200320e616e64726f696420392e332e3835")
        private val FINISH_BODY = hex("08f9a6021000180022020801320e616e64726f696420392e332e3835")

        private fun minimalDeviceInfo(): ByteArray {
            val out = ByteArrayOutputStream()
            out.write(PbEdit.varintField(5, 2L))
            out.write(PbEdit.bytes(9, "14".toByteArray(Charsets.UTF_8)))
            return out.toByteArray()
        }

        internal fun buildAdvGetReq(rewardType: Long, rewardItem: ByteArray): ByteArray {
            val rewardReqInfo = ByteArrayOutputStream().apply {
                write(PbEdit.varintField(1, rewardType))
                write(PbEdit.bytes(2, rewardItem))
            }.toByteArray()
            return ByteArrayOutputStream().apply {
                write(PbEdit.bytes(2, minimalDeviceInfo()))
                write(PbEdit.varintField(3, DEFAULT_ADV_POS))
                write(PbEdit.bytes(7, rewardReqInfo))
                write(PbEdit.bytes(10, "android".toByteArray(Charsets.UTF_8)))
            }.toByteArray()
        }

        internal fun buildAdvReportReq(
            rewardType: Long,
            rewardItem: ByteArray,
            parsed: AdvGetParsed,
            actionValue: Long,
        ): ByteArray {
            val rewardReportInfo = ByteArrayOutputStream().apply {
                write(PbEdit.varintField(1, rewardType))
                write(PbEdit.bytes(2, rewardItem))
                write(PbEdit.varintField(3, parsed.time))
                write(PbEdit.varintField(4, parsed.nonce))
                write(PbEdit.bytes(5, parsed.signature))
            }.toByteArray()
            return ByteArrayOutputStream().apply {
                write(PbEdit.bytes(2, minimalDeviceInfo()))
                write(PbEdit.varintField(3, DEFAULT_ADV_POS))
                if (parsed.recommCookie.isNotEmpty()) write(PbEdit.bytes(4, parsed.recommCookie))
                write(PbEdit.varintField(5, DEFAULT_ACTION_TYPE))
                write(PbEdit.varintField(6, actionValue))
                write(PbEdit.bytes(8, rewardReportInfo))
            }.toByteArray()
        }

        internal fun parseAdvGetRsp(raw: ByteArray): AdvGetParsed? {
            val buf = stripOptionalLengthPrefix(raw)
            val errCode = readVarintField(buf, 1) ?: 0L
            val errMsg = readBytesField(buf, 2)?.toString(Charsets.UTF_8).orEmpty()
            val firstMsg = readBytesField(buf, 3)
            val recommCookie = firstMsg?.let { readBytesField(it, 3) } ?: ByteArray(0)
            val rspInfo = readBytesField(buf, 5)
            val time = rspInfo?.let { readVarintField(it, 1) } ?: 0L
            val nonce = rspInfo?.let { readVarintField(it, 2) } ?: 0L
            val sig = rspInfo?.let { readBytesField(it, 3) } ?: ByteArray(0)
            if (rspInfo == null && errCode == 0L && firstMsg == null) return null
            return AdvGetParsed(errCode, errMsg, recommCookie, time, nonce, sig)
        }

        internal fun parseAdvReportRsp(raw: ByteArray): AdvReportParsed? {
            val buf = stripOptionalLengthPrefix(raw)
            if (buf.isEmpty()) return null
            val retCode = readVarintField(buf, 1) ?: 0L
            val errorMsg = readBytesField(buf, 2)?.toString(Charsets.UTF_8).orEmpty()
            return AdvReportParsed(retCode, errorMsg)
        }

        private fun stripOptionalLengthPrefix(raw: ByteArray): ByteArray {
            if (raw.size >= 4) {
                val len = ((raw[0].toInt() and 0xFF) shl 24) or
                    ((raw[1].toInt() and 0xFF) shl 16) or
                    ((raw[2].toInt() and 0xFF) shl 8) or
                    (raw[3].toInt() and 0xFF)
                if (len == raw.size) return raw.copyOfRange(4, raw.size)
            }
            return raw
        }

        private fun readBytesField(buf: ByteArray, targetField: Int): ByteArray? {
            var i = 0
            while (i < buf.size) {
                val (tag, n1) = readVarint(buf, i) ?: return null
                i = n1
                val fieldNo = (tag ushr 3).toInt()
                when ((tag and 0x7L).toInt()) {
                    0 -> i = (readVarint(buf, i) ?: return null).second
                    1 -> i += 8
                    2 -> {
                        val (len, n2) = readVarint(buf, i) ?: return null
                        val end = n2 + len.toInt()
                        if (end > buf.size || len < 0) return null
                        if (fieldNo == targetField) return buf.copyOfRange(n2, end)
                        i = end
                    }
                    5 -> i += 4
                    else -> return null
                }
            }
            return null
        }

        private fun readVarintField(buf: ByteArray, targetField: Int): Long? {
            var i = 0
            while (i < buf.size) {
                val (tag, n1) = readVarint(buf, i) ?: return null
                i = n1
                val fieldNo = (tag ushr 3).toInt()
                when ((tag and 0x7L).toInt()) {
                    0 -> {
                        val (v, n2) = readVarint(buf, i) ?: return null
                        if (fieldNo == targetField) return v
                        i = n2
                    }
                    1 -> i += 8
                    2 -> {
                        val (len, n2) = readVarint(buf, i) ?: return null
                        val end = n2 + len.toInt()
                        if (end > buf.size || len < 0) return null
                        i = end
                    }
                    5 -> i += 4
                    else -> return null
                }
            }
            return null
        }

        private fun readVarint(buf: ByteArray, start: Int): Pair<Long, Int>? {
            var res = 0L
            var shift = 0
            var i = start
            while (i < buf.size && shift < 64) {
                val b = buf[i++].toInt() and 0xFF
                res = res or ((b and 0x7F).toLong() shl shift)
                if ((b and 0x80) == 0) return res to i
                shift += 7
            }
            return null
        }

        private fun hex(s: String): ByteArray = ByteArray(s.length / 2) { i ->
            s.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }
}
