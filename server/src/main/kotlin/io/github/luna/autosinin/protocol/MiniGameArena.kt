package io.github.luna.autosinin.protocol

import io.github.luna.autosinin.server.core.ProtoTransport
import io.github.luna.autosinin.server.core.ServerLog
import org.json.JSONArray
import org.json.JSONObject

/**
 * 等级任务 1045「创建小游戏擂台并取得成绩」。
 */
class MiniGameArena(
    private val transport: ProtoTransport,
    private val status: MiniGameStatus,
) {

    data class Room(
        val id: String,
        val validMinutes: Int,
        val raw: String,
        val alreadyActive: Boolean = false,
        val expireAt: Long = 0,
    )

    data class Outcome(val ok: Boolean, val detail: String, val room: Room? = null)

    private fun post(uin: String, cmd: String, data: JSONObject): ProtoTransport.RawResponse {
        val body = PbEdit.bytes(3, data.toString().toByteArray(Charsets.UTF_8))
        val r = transport.sendPbRaw(uin, cmd, body, "${uin}_arena_init_${System.currentTimeMillis()}")
        ServerLog.i(TAG, "uin=$uin ${cmd.substringAfterLast("0x94b9_")} -> code=${r.code} msg=${r.msg}")
        return r
    }

    fun warmup(uin: String) {
        runCatching {
            post(
                uin, CMD_LIST,
                JSONObject().put("session", "").put("role_filter", 1)
                    .put("status_filter", 1).put("from", "qqlevel_task").put("top_room_id", ""),
            )
            post(uin, CMD_HOME, JSONObject().put("session", "").put("invite_id", ""))
            post(uin, CMD_OVERVIEW, JSONObject().put("session", ""))
            post(
                uin, CMD_RECOMMEND,
                JSONObject().put("top_appids", JSONArray()).put("from", 2).put("session", ""),
            )
            post(uin, CMD_APPINFO, JSONObject().put("appids", JSONArray().put(DEFAULT_APPID)))
        }.onFailure { ServerLog.w(TAG, "uin=$uin warmup failed: ${it.javaClass.simpleName}: ${it.message}") }
    }

    fun fetchArenaAppIds(uin: String): List<String> {
        val json = JSONObject().put("num", 0).put("session", "").toString()
        val body = PbEdit.bytes(3, json.toByteArray(Charsets.UTF_8))
        val r = transport.sendPbRaw(uin, CMD_GAMES, body, "${uin}_arena_games_${System.currentTimeMillis()}")
        ServerLog.i(TAG, "uin=$uin 0x94b9_120 -> ${r.detail}")
        val text = r.bytes?.toString(Charsets.UTF_8).orEmpty()
        val ids = LinkedHashSet<String>()
        Regex("\"id\"\\s*:\\s*\"(wx[0-9a-f]{16})\"").findAll(text).forEach { ids += it.groupValues[1] }
        return ids.toList()
    }

    fun createFirstRoom(uin: String): Room? {
        warmup(uin)
        tryCreate(uin, DEFAULT_APPID)?.let { return it }
        for (appId in fetchArenaAppIds(uin).take(8)) {
            if (appId == DEFAULT_APPID) continue
            tryCreate(uin, appId)?.let { return it }
        }
        ServerLog.w(TAG, "uin=$uin no arena-eligible appid")
        return null
    }

    private fun tryCreate(uin: String, appId: String): Room? {
        val room = createRoom(uin, appId) ?: return null
        if (room.alreadyActive && room.expireAt == 0L) {
            activeRoom(uin)?.let { return it }
        }
        return room
    }

    fun createRoom(uin: String, appId: String): Room? {
        val body = buildBody(appId)
        val trace = "${uin}_arena_create_${System.currentTimeMillis()}"
        val r = transport.sendPbRaw(uin, CMD, body, trace)
        ServerLog.i(TAG, "uin=$uin 0x94b9_96 appId=$appId -> ${r.detail}")
        if (r.code == 300768) {
            return Room("", 0, r.msg, alreadyActive = true)
        }
        if (r.code != 0 || r.bytes == null) {
            ServerLog.w(TAG, "uin=$uin createRoom rejected code=${r.code} msg=${r.msg}")
            return null
        }
        val text = r.bytes.toString(Charsets.UTF_8)
        ServerLog.i(TAG, "uin=$uin createRoom appId=$appId rsp(${text.length})=$text")
        val room = parseRoom(text) ?: return null
        return room.copy(expireAt = System.currentTimeMillis() + room.validMinutes * 60_000L)
    }

    fun listRooms(uin: String, roleFilter: Int, statusFilter: Int): List<Room> {
        warmup(uin)
        val r = post(
            uin, CMD_LIST,
            JSONObject().put("session", "").put("role_filter", roleFilter)
                .put("status_filter", statusFilter).put("from", "qqlevel_task").put("top_room_id", ""),
        )
        val text = r.bytes?.toString(Charsets.UTF_8).orEmpty()
        val start = text.indexOf('{')
        if (start < 0) return emptyList()
        return runCatching {
            val root = JSONObject(text.substring(start))
            val records = root.optJSONArray("records") ?: return emptyList()
            val out = ArrayList<Room>()
            for (i in 0 until records.length()) {
                val rec = records.optJSONObject(i) ?: continue
                val room = rec.optJSONObject("room") ?: rec
                val id = room.optString("room_id").takeIf { it.isNotBlank() } ?: continue
                val appid = room.optString("appid")
                val expire = room.optString("expire_at").toLongOrNull()?.times(1000L) ?: 0L
                out.add(Room(id, 0, "appid=$appid", alreadyActive = room.optInt("status") == 1, expireAt = expire))
            }
            out
        }.getOrElse { emptyList() }
    }

    fun joinRoom(uin: String, roomId: String): String {
        val r = post(uin, CMD_HOME, JSONObject().put("session", "").put("invite_id", roomId))
        val text = r.bytes?.toString(Charsets.UTF_8).orEmpty()
        return "code=${r.code} msg=${r.msg} raw=${text.take(500)}"
    }

    fun activeRoom(uin: String): Room? {
        warmup(uin)
        val r = post(
            uin, CMD_LIST,
            JSONObject().put("session", "").put("role_filter", 1)
                .put("status_filter", 1).put("from", "qqlevel_task").put("top_room_id", ""),
        )
        val text = r.bytes?.toString(Charsets.UTF_8).orEmpty()
        val start = text.indexOf('{')
        if (start < 0) return null
        return runCatching {
            val root = JSONObject(text.substring(start))
            val records = root.optJSONArray("records") ?: return null
            for (i in 0 until records.length()) {
                val rec = records.optJSONObject(i) ?: continue
                val room = rec.optJSONObject("room") ?: rec
                if (room.optInt("status", -1) != 1) continue
                val id = room.optString("room_id").takeIf { it.isNotBlank() } ?: continue
                val expire = room.optString("expire_at").toLongOrNull()?.times(1000L) ?: 0L
                return Room(id, 0, "", alreadyActive = true, expireAt = expire)
            }
            null
        }.getOrNull()
    }

    fun task(uin: String): MiniGameStatus.TaskInfo? =
        runCatching { status.fetchTask(uin, TASK_ID, "擂台") }.getOrNull()

    fun run(uin: String): Outcome {
        val room = createFirstRoom(uin) ?: return Outcome(false, "0x94b9_96 未返回 room_id（无可用擂台游戏/被拒）")
        return Outcome(true, "已创建擂台 room=${room.id} valid=${room.validMinutes}min", room)
    }

    companion object {
        private const val TAG = "ARENA"
        const val TASK_ID = 1045
        const val DEFAULT_APPID = "wx141bfb9b73c970a9"
        private const val CMD = "MQUpdateSvc_com_qq_gamecenter.web.OidbSvcTrpcJsapiTcp.0x94b9_96"
        private const val CMD_GAMES = "MQUpdateSvc_com_qq_gamecenter.web.OidbSvcTrpcJsapiTcp.0x94b9_120"
        private const val CMD_LIST = "MQUpdateSvc_com_qq_gamecenter.web.OidbSvcTrpcJsapiTcp.0x94b9_101"
        private const val CMD_HOME = "MQUpdateSvc_com_qq_gamecenter.web.OidbSvcTrpcJsapiTcp.0x94b9_100"
        private const val CMD_OVERVIEW = "MQUpdateSvc_com_qq_gamecenter.web.OidbSvcTrpcJsapiTcp.0x94b9_128"
        private const val CMD_RECOMMEND = "MQUpdateSvc_com_qq_gamecenter.web.OidbSvcTrpcJsapiTcp.0x94b9_111"
        private const val CMD_APPINFO = "MQUpdateSvc_com_qq_gamecenter.web.OidbSvcTrpcJsapiTcp.0x94b9_104"

        internal fun buildBody(appId: String): ByteArray {
            val json = JSONObject()
                .put("appid", appId)
                .put("source", 1)
                .put("from", "qqlevel_task")
                .toString()
            return PbEdit.bytes(3, json.toByteArray(Charsets.UTF_8))
        }

        internal fun parseRoom(text: String): Room? {
            val id = Regex("\"room_id\"\\s*:\\s*\"([^\"]+)\"").find(text)?.groupValues?.getOrNull(1)
                ?.takeIf { it.isNotBlank() } ?: return null
            val minutes = Regex("\"valid_duration_minutes\"\\s*:\\s*(\\d+)").find(text)
                ?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
            return Room(id, minutes, text)
        }
    }
}
