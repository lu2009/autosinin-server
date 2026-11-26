package io.github.luna.autosinin.protocol

import io.github.luna.autosinin.server.core.ServerLog
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** 等级任务页 report/action 通用单条上报（如 task=1036）。 */
class TaskPageAction(
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build(),
) {

    fun send(
        uin: String,
        taskId: Int,
        taskName: String,
        actionId: Int,
        value: Long,
        attr: Int,
        club: Int = 3,
    ): Boolean {
        val now = System.currentTimeMillis()
        val busi = JSONObject().put("club_status", club).put("vip_level", 0)
            .put("task_name", taskName).put("task_id", taskId)
            .put("page_state", "1").put("page_type", "kuikly")
            .put("kuikly_res_version", 2140).put("vas_pg", "vas_qqvip_account_info_host").toString()
        val item = JSONObject().put("module_id", "level_task").put("item_id", "level_task").put("item_type", "4")
            .put("busi_info", busi)
            .put("action_infos", JSONArray().put(JSONObject()
                .put("action_id", actionId).put("action_value", value)
                .put("action_attr", attr).put("oper_time", now)))
            .put("sub_item_id", JSONArray()).put("sub_module_id", "")
            .put("rule_info", JSONObject()).put("position_id", "")
        val info = JSONObject().put("page_info", JSONObject().put("appid", "qqviptrace21")
            .put("page_id", "account_info").put("item_infos", JSONArray().put(item)))
            .put("trace_info", JSONObject().put("trace_id", randomHex(32))
                .put("trace_detail", "").put("trace_index", "5003452dc7b8fa1c20e6c270a50ca221").put("trace_num", 1))
            .put("to_uin", 0)
        val body = JSONObject().put("type", 1)
            .put("user_comm_report", JSONObject()
                .put("app_version", "9.3.85").put("client_type", "qq")
                .put("platform", "android").put("mobile_type", "android")
                .put("os_version", "14").put("network_type", "WIFI")
                .put("qimei", "be9741c6cffd0a340d99c75610001751a904")
                .put("qua", "V1_AND_SQ_9.3.85_16920_YYB_D")
                .put("uin", uin).put("to_uin", 0).put("user_tag", JSONObject()))
            .put("report_infos", JSONArray().put(info))
        return runCatching {
            val req = Request.Builder().url(URL).header("Content-Type", "application/json")
                .post(body.toString().toRequestBody(jsonType)).build()
            httpClient.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                ServerLog.i(TAG, "uin=$uin task=$taskId action=$actionId value=$value http=${resp.code} ${text.take(100)}")
                resp.isSuccessful
            }
        }.getOrElse {
            ServerLog.w(TAG, "uin=$uin failed: ${it.message}")
            false
        }
    }

    private fun randomHex(n: Int) = (1..n).map { "0123456789abcdef".random() }.joinToString("")

    companion object {
        private const val TAG = "TASKPAGE"
        private const val URL = "https://h5.qzone.qq.com/report/action?contenttype=json"
        private val jsonType = "application/json; charset=utf-8".toMediaType()
    }
}
