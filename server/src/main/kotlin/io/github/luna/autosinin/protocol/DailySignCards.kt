package io.github.luna.autosinin.protocol

import io.github.luna.autosinin.server.core.ProtoTransport
import io.github.luna.autosinin.server.core.ServerLog
import org.json.JSONObject

/** 日签卡：0x97d0 取卡片列表，0x97d8 按卡片字段签到。 */
class DailySignCards(private val transport: ProtoTransport) {

    data class Card(val cardType: Int, val taskId: String, val subType: String, val checked: Boolean)
    data class Outcome(val ok: Boolean, val detail: String)

    fun run(uin: String): Outcome {
        val trace = "${uin}_dailycard_${System.currentTimeMillis()}"
        val info = transport.sendPbRaw(uin, CMD_INFO, ByteArray(0), trace)
        ServerLog.i(TAG, "uin=$uin info ${info.detail}")
        val text = info.bytes?.toString(Charsets.UTF_8).orEmpty()
        val cards = parseCards(text)
        if (cards.isEmpty()) return Outcome(false, "0x97d0 未解析到卡片：${info.detail}")
        val card = cards.firstOrNull { !it.checked } ?: return Outcome(true, "所有卡片已签到")
        val json = JSONObject()
            .put("card_type", card.cardType)
            .put("task_id", card.taskId)
            .put("sub_type", card.subType)
            .toString().toByteArray()
        val body = PbEdit.bytes(3, json)
        val trace2 = "${uin}_dailycard_sign_${System.currentTimeMillis()}"
        val r = transport.sendPbRaw(uin, CMD_SIGN, body, trace2)
        ServerLog.i(TAG, "uin=$uin sign cardType=${card.cardType} taskId=${card.taskId} ${r.detail}")
        return if (r.code == 0 || (r.bytes?.isNotEmpty() == true)) Outcome(true, "签到 cardType=${card.cardType} ${r.detail}")
        else Outcome(false, "签到失败 ${r.detail}")
    }

    internal fun parseCards(text: String): List<Card> {
        val start = text.indexOf('{')
        if (start < 0) return emptyList()
        return runCatching {
            val root = JSONObject(text.substring(start))
            val arr = root.optJSONArray("list") ?: return emptyList()
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val taskInfo = o.optJSONObject("task_info")
                    val type = o.optInt("card_type", taskInfo?.optInt("card_type", 1) ?: 1)
                    val task = taskInfo?.optString("task_id", "").orEmpty()
                    val sub = (taskInfo?.optLong("inner_task_id", 0L) ?: 0L).toString()
                    val checked = o.optBoolean("signed_in", false)
                    add(Card(type, task, sub, checked))
                }
            }
        }.getOrDefault(emptyList())
    }

    companion object {
        private const val TAG = "DAILYCARD"
        private const val CMD_INFO = "MQUpdateSvc_com_qq_vip.web.OidbSvcTrpcJsapiTcp.0x97d0_0"
        private const val CMD_SIGN = "MQUpdateSvc_com_qq_vip.web.OidbSvcTrpcJsapiTcp.0x97d8_0"
    }
}
