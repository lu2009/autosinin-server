package io.github.luna.autosinin.protocol

import io.github.luna.autosinin.server.core.ServerLog
import io.github.luna.autosinin.server.core.TicketProvider
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 会员福利社领券：
 *   get-zone -> GetBenefitsDetail -> ExecAct
 */
class WelfareClaim(
    private val tickets: TicketProvider,
    private val httpClient: OkHttpClient = defaultHttpClient(),
) {

    @Volatile
    private var lastSuccessId: Long = 0L

    data class Result(
        val ok: Boolean,
        val detail: String,
        val benefitName: String? = null,
        val billNo: String? = null,
    )

    data class Benefit(val id: Long, val name: String, val merchantId: Long = 0)

    fun claim(uin: String): Result {
        val clubPskey = tickets.pskey(uin, "vip.qq.com")
            ?: return Result(false, "取不到 vip p_skey")
        val actPskey = tickets.pskey(uin, "qzone.qq.com")
            ?: tickets.pskey(uin, "act.qzone.qq.com")
            ?: return Result(false, "取不到 qzone p_skey")

        val zone = post(
            "$CLUB/mono/api/benefits/get-zone?g_tk=${TicketProvider.bkn(clubPskey)}",
            zoneBody(),
            cookie(uin, clubPskey),
        ) ?: return Result(false, "get-zone 无回包")
        val picked = pickBenefits(zone).distinctBy { it.id }
        if (picked.isEmpty()) return Result(false, "没有可选福利券")
        val candidates = prioritize(picked, lastSuccessId)

        ServerLog.i(TAG, "uin=$uin candidates(${candidates.size})=${candidates.take(20).joinToString { "${it.id}:${it.name}" }}")
        for (b in candidates.take(40)) {
            val detail = post(
                "$CLUB/mono/api/trpc/qqva/vipopen_benefits_center_server/benefit_service/Vipopen/GetBenefitsDetail" +
                    "?ADTAG=level&g_tk=${TicketProvider.bkn(clubPskey)}",
                JSONObject().put("data", JSONObject().put("benefits_id", b.id)).toString(),
                cookie(uin, clubPskey),
            ) ?: continue
            val tx = findTianxuan(detail) ?: continue
            val subAct = tx.optString("getSubactId").ifBlank { tx.optString("useSubactId") }
            if (subAct.isBlank()) continue
            val left = tx.optJSONObject("stock")?.optLong("left", 1L) ?: 1L
            if (left <= 0L) continue

            val exec = post(
                "$ACT/v2/vip/tx/trpc/subact/ExecAct?g_tk=${TicketProvider.bkn(actPskey)}",
                execBody(subAct),
                cookie(uin, actPskey),
            ) ?: continue
            val code = exec.optInt("Code", -1)
            if (code == 0) {
                lastSuccessId = b.id
                val bill = exec.optString("bill_no").orEmpty()
                ServerLog.i(TAG, "uin=$uin claimed ${b.name} bill=$bill")
                return Result(true, "领取成功", b.name, bill)
            }
            ServerLog.w(TAG, "uin=$uin ExecAct code=$code for ${b.id}")
        }
        return Result(false, "领取失败（无可领券或接口拒绝）")
    }

    private fun findTianxuan(o: JSONObject?, depth: Int = 0): JSONObject? {
        if (o == null || depth > 4) return null
        val direct = o.optJSONObject("tianxuanAct")
        if (direct != null) return direct
        val asText = o.optString("tianxuanAct")
        if (asText.startsWith("{")) runCatching { return JSONObject(asText) }
        for (k in o.keys()) {
            val v = o.opt(k)
            if (v is JSONObject) findTianxuan(v, depth + 1)?.let { return it }
            else if (v is String && v.trimStart().startsWith("{")) {
                runCatching { findTianxuan(JSONObject(v), depth + 1)?.let { return it } }
            }
        }
        return null
    }

    private fun cookie(uin: String, pskey: String): String {
        val o = "o" + uin.padStart(10, '0')
        return "uin=$o; p_uin=$o; p_skey=$pskey"
    }

    private fun post(url: String, body: String, cookie: String): JSONObject? = runCatching {
        val req = Request.Builder()
            .url(url)
            .header("Cookie", cookie)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 MQQBrowser/6.2 TBS/047941")
            .post(body.toRequestBody(jsonType))
            .build()
        httpClient.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            ServerLog.i(TAG, "http ${resp.code} ${url.substringBefore('?')} len=${text.length}")
            if (!resp.isSuccessful) return null
            JSONObject(text)
        }
    }.onFailure { ServerLog.w(TAG, "post failed: ${it.javaClass.simpleName}: ${it.message}") }.getOrNull()

    companion object {
        private const val TAG = "WELFARE"
        private const val CLUB = "https://club.vip.qq.com"
        private const val ACT = "https://act.qzone.qq.com"
        private val jsonType = "application/json; charset=utf-8".toMediaType()

        fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .build()

        fun zoneBody(): String = JSONObject()
            .put("data", JSONObject().put("zone", JSONObject()
                .put("zone_type", 2)
                .put("tag", JSONObject().put("common_tag", ""))))
            .put("opt", JSONObject().put("extendContext", JSONObject()
                .put("vipopen-env", "debug")
                .put("fromsrc", "benefits_center_page")
                .put("vipopen-version", "1")))
            .toString()

        fun execBody(subActId: String): String = JSONObject()
            .put(
                "ActReqData",
                JSONObject()
                    .put("send_type", "2")
                    .put("ext_recommend_source", 1)
                    .put("appid", "pg_qqvip_benefit")
                    .put("page_id", "pg_new_benefit_homepage")
                    .put("date", "0")
                    .put("sub_id", "")
                    .toString(),
            )
            .put("SubActId", subActId)
            .put("ReportInfo", JSONObject().put("enteranceId", "level").toString())
            .toString()

        internal fun prioritize(picked: List<Benefit>, cachedId: Long): List<Benefit> {
            if (cachedId <= 0L) return picked
            val hit = picked.firstOrNull { it.id == cachedId } ?: return picked
            return listOf(hit) + picked.filterNot { it.id == cachedId }
        }

        fun pickBenefits(zone: JSONObject): List<Benefit> {
            val out = ArrayList<Benefit>()
            val merchants = zone.optJSONObject("zoneInfo")?.optJSONArray("merchantBenefits") ?: JSONArray()
            for (i in 0 until merchants.length()) {
                val arr = merchants.optJSONObject(i)?.optJSONArray("benefits") ?: continue
                for (j in 0 until arr.length()) {
                    val ben = arr.optJSONObject(j) ?: continue
                    val base = ben.optJSONObject("base") ?: continue
                    if (base.optInt("type", 0) != 2) continue
                    val id = base.optLong("benefitsId", 0L)
                    if (id <= 0L) continue
                    val name = base.optJSONObject("displayInfo")?.optString("name").orEmpty()
                    out.add(Benefit(id, name, base.optLong("merchantId", 0L)))
                }
            }
            return out.sortedWith(
                compareBy<Benefit> {
                    when {
                        it.name.contains("外卖") -> 0
                        it.name.contains("券") -> 1
                        else -> 2
                    }
                }.thenBy { if (it.merchantId == 9L) 0 else 1 }.thenByDescending { it.id },
            )
        }
    }
}
