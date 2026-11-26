package io.github.luna.autosinin.protocol

import io.github.luna.autosinin.server.core.ProtoTransport
import io.github.luna.autosinin.server.core.ServerLog
import org.json.JSONArray
import org.json.JSONObject
import java.util.IdentityHashMap

/** 从小游戏推荐页 0x94b9_3 取当前推荐小游戏 appid 与广告实例。 */
class MiniGameAdFetcher(private val transport: ProtoTransport) {

    data class AppExposure(
        val appid: String,
        val moduleType: Int,
        val rankType: Int,
        val pos: Int,
    )

    data class Ad(
        val posId: String,
        val appid: String,
        val aid: String,
        val traceId: String,
        val viewId: String,
        val hostScene: String,
        val originalExposureUrl: String,
        val videoReportUrl: String,
        val effectUrl: String,
        val sessionId: String,
        val slot: String,
        val source: String,
        val items: List<Ad> = emptyList(),
        val apps: List<AppExposure> = emptyList(),
    )

    fun fetchGameAppId(uin: String): String? {
        val ad = fetch(uin) ?: return null
        return ad.appid.takeIf { it.startsWith("wx") }
            ?: ad.apps.firstOrNull { it.appid.startsWith("wx") }?.appid
    }

    fun fetch(uin: String): Ad? {
        val body = PbEdit.bytes(3, TEMPLATE.toByteArray())
        val r = transport.sendPbRaw(uin, CMD, body, "${uin}_minigame_ad_${System.currentTimeMillis()}")
        ServerLog.i(TAG, "uin=$uin ad rsp ${r.detail}")
        if (r.code != 0 || r.bytes == null) {
            return null
        }
        val text = r.bytes.toString(Charsets.UTF_8)
        return parse(text)
    }

    internal fun parse(text: String): Ad? {
        val root = extractJson(text) ?: return null
        val sessionId = root.optString("session").ifBlank { find(root, "session").orEmpty() }
        val parents = IdentityHashMap<JSONObject, JSONObject?>()
        val all = ArrayList<JSONObject>()
        collect(root, null, parents, all)
        val ads = ArrayList<Ad>()
        var best: Ad? = null
        var bestScore = -1
        for (node in all) {
            val trace = node.optJSONObject("trace_info") ?: continue
            val app = trace.optString("product_id").ifBlank { trace.optString("appid") }
            val aid = trace.optString("aid")
            val tid = trace.optString("traceid")
            val vid = trace.optString("view_id")
            if (aid.isBlank() || tid.isBlank() || vid.isBlank() || app.isBlank()) continue
            var cur: JSONObject? = node
            var pos: String? = null
            var host: String? = null
            var exp: String? = null
            var vr: String? = null
            var effect: String? = null
            var slot: String? = null
            var source: String? = null
            var inAdCard = false
            while (cur != null) {
                if (pos == null) pos = cur.optString("pos_id").takeIf { it.isNotBlank() } ?: find(cur, "pos_id")
                if (host == null) host = cur.optString("host_scene").takeIf { it.isNotBlank() } ?: find(cur, "host_scene")
                if (exp == null) exp = find(cur, "original_exposure_url")
                if (vr == null) vr = find(cur, "video_report_url")
                if (effect == null) effect = find(cur, "effect_url")
                if (slot == null) slot = cur.optString("slot").takeIf { it.isNotBlank() } ?: find(cur, "slot")
                if (source == null) source = cur.optString("app_source").takeIf { it.isNotBlank() } ?: find(cur, "app_source")
                if (cur.has("ad_card") || cur.optInt("module_type", -1) == 3) inAdCard = true
                cur = parents[cur]
            }
            if (pos == null) continue
            val slotValue = slot ?: "1"
            val sourceValue = source ?: "1"
            val rawHost = host.orEmpty()
            val composedHost = if (rawHost.length == 8 && slotValue.toIntOrNull() != null) {
                rawHost + slotValue.padStart(2, '0')
            } else rawHost
            val ad = Ad(
                pos, app, aid, tid, vid, composedHost,
                exp.orEmpty(), vr.orEmpty(), effect.orEmpty(),
                sessionId, slotValue, sourceValue,
            )
            var score = 0
            if (app == PREFERRED) score += 100
            if (inAdCard) score += 4
            if (vr.orEmpty().isNotBlank()) score += 2
            if (exp.orEmpty().isNotBlank()) score += 1
            ads.add(ad)
            if (score > bestScore) {
                bestScore = score
                best = ad
            }
        }
        return best?.copy(items = ads, apps = collectApps(root))
    }

    private fun collectApps(root: JSONObject): List<AppExposure> {
        val out = ArrayList<AppExposure>()
        val modules = root.optJSONArray("modules") ?: return out
        for (i in 0 until modules.length()) {
            val m = modules.optJSONObject(i) ?: continue
            val mt = m.optInt("module_type", 0)
            val rank = m.optInt("rank_type", 0)
            val cards = m.optJSONObject("ad_card")?.optJSONArray("ad_cards")
            if (cards != null) for (j in 0 until cards.length()) {
                val card = cards.optJSONObject(j) ?: continue
                val app = find(card, "id") ?: continue
                out.add(AppExposure(app, mt, rank, j + 1))
            }
            val infos = m.optJSONObject("app_list")?.optJSONArray("app_infos")
            if (infos != null) for (j in 0 until infos.length()) {
                val info = infos.optJSONObject(j) ?: continue
                val app = find(info, "id") ?: continue
                out.add(AppExposure(app, mt, rank, j + 1))
            }
        }
        return out.distinctBy { "${it.appid}_${it.moduleType}_${it.rankType}_${it.pos}" }
    }

    private fun extractJson(text: String): JSONObject? {
        val start = listOf("{\"ext\"", "{\"data\"", "{")
            .map { text.indexOf(it) }
            .firstOrNull { it >= 0 } ?: return null
        var depth = 0
        var inStr = false
        var esc = false
        var end = -1
        for (i in start until text.length) {
            val c = text[i]
            if (inStr) {
                if (esc) esc = false
                else if (c == '\\') esc = true
                else if (c == '"') inStr = false
            } else {
                when (c) {
                    '"' -> inStr = true
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) { end = i + 1; break }
                    }
                }
            }
        }
        if (end < 0) return null
        return runCatching { JSONObject(text.substring(start, end)) }.getOrNull()
    }

    private fun collect(
        o: JSONObject,
        parent: JSONObject?,
        parents: IdentityHashMap<JSONObject, JSONObject?>,
        out: MutableList<JSONObject>,
    ) {
        if (parents.containsKey(o)) return
        parents[o] = parent
        out.add(o)
        for (k in o.keys()) {
            when (val v = o.opt(k)) {
                is JSONObject -> collect(v, o, parents, out)
                is JSONArray -> for (i in 0 until v.length()) {
                    v.optJSONObject(i)?.let { collect(it, o, parents, out) }
                }
                is String -> {
                    val t = v.trim()
                    if (t.startsWith("{")) {
                        runCatching { JSONObject(t) }.getOrNull()?.let { collect(it, o, parents, out) }
                    }
                }
            }
        }
    }

    private fun find(o: JSONObject, key: String): String? {
        o.optString(key).takeIf { it.isNotBlank() }?.let { return it }
        for (k in o.keys()) {
            when (val v = o.opt(k)) {
                is JSONObject -> find(v, key)?.let { return it }
                is JSONArray -> for (i in 0 until v.length()) {
                    v.optJSONObject(i)?.let { find(it, key)?.let { x -> return x } }
                }
            }
        }
        return null
    }

    companion object {
        private const val TAG = "MINIGAME"
        private const val CMD = "MQUpdateSvc_com_qq_gamecenter.web.OidbSvcTrpcJsapiTcp.0x94b9_3"
        private const val PREFERRED = "wx2ea52a972ea18e5e"
        private const val TEMPLATE = """{"ext": {}, "session": "", "source_info": {"source_name": "qqlevel_task", "source_ext": "module_type=0&rank_type=0", "red_dot_data": "", "kuikly_build_version": 2540, "oaid": "01BE896D3E5B3369A37787B9AA80CAC25F601D4CB006DA4E66"}, "ams_device_info": "", "ams_device_info_json": "{\"aid_ticket\": \"01BE896D3E5B3369A37787B9AA80CAC25F601D4CB006DA4E66\",\"app_version_id\": 537402556,\"brand\": \"Redmi\",\"carrier_code\": 46001,\"conn\": 1,\"device_brand_and_model\": \"22041211AC\",\"device_ext\": \"{\\\"attri_info\\\":{\\\"app_bundle_id\\\":\\\"com.tencent.mobileqq\\\",\\\"ua\\\":\\\"Dalvik\\\\\\/2.1.0 (Linux; U; Android 14; 22041211AC Build\\\\\\/UP1A.231005.007)\\\",\\\"ua_i\\\":{\\\"c_i\\\":\\\"151.0.7922.199\\\",\\\"s_i\\\":{\\\"b_i\\\":\\\"UP1A.231005.007\\\",\\\"b_m\\\":\\\"22041211AC\\\",\\\"b_mf\\\":\\\"Xiaomi\\\",\\\"b_r_o_c\\\":\\\"14\\\",\\\"b_v_c\\\":\\\"REL\\\",\\\"b_v_i\\\":\\\"OS2.0.5.0.ULNCNXM\\\",\\\"b_v_r\\\":\\\"14\\\",\\\"jvm_v\\\":\\\"2.1.0\\\",\\\"sw_s\\\":\\\"1\\\"}}},\\\"device_model_type\\\":1,\\\"font_size\\\":1,\\\"harmony_sys_info\\\":{\\\"harmony_pure_mode\\\":-1,\\\"is_harmony_os\\\":false},\\\"jump_ability\\\":[9999],\\\"m_os\\\":\\\"OS2.0.5.0.ULNCNXM\\\",\\\"module_name\\\":\\\"pcad-reward\\\",\\\"mqq_config_status\\\":1,\\\"qi36\\\":\\\"be9741c6cffd0a340d99c75610001751a904\\\",\\\"qqb_external_exp_info\\\":{\\\"traffic_type\\\":26},\\\"targeting_ability\\\":{\\\"support_quick_app_link\\\":false,\\\"web_wx_mgame\\\":true},\\\"wechat_authorization_status\\\":1,\\\"wechat_installed_info\\\":{\\\"api_ver\\\":\\\"671108912\\\"},\\\"client_mode\\\":\\\"default\\\"}\",\"is_googleplay_version\": false,\"location\": {\"coordinates_type\": 0,\"latitude\": 38231429,\"longitude\": 114849220},\"manufacturer\": \"Xiaomi\",\"os_type\": 2,\"os_ver\": \"14\",\"qq_ver\": \"9.3.85\",\"taid_ticket\": \"0101869FADD28DD19AE0C08BD92EA24A00A84707B0B668380B3D25A2DD5C1E3824D71D937100A13BC95E73A4\"}", "ams_info_encoding": 0, "jump_from": "qqlevel_task"}"""
    }
}
