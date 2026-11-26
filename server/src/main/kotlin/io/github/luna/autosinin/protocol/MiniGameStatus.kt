package io.github.luna.autosinin.protocol

import io.github.luna.autosinin.server.core.ProtoTransport
import io.github.luna.autosinin.server.core.ServerLog

/**
 * 等级任务页状态探针：OidbSvcTrpcTcp.0x9172_0。
 * 所有异步任务的唯一核验真源。
 */
class MiniGameStatus(private val transport: ProtoTransport) {

    data class TaskInfo(
        val id: Int,
        val title: String,
        val status: Int,
        val isDone: Boolean,
        val isVipTask: Boolean,
        val raw10: Int,
        val raw11: Int,
        val jumpUrl: String = "",
    ) {
        val done: Boolean get() = isDone || status == 1
    }

    fun fetchSignature(uin: String): String? {
        val r = transport.sendPbRaw(uin, CMD, body(), "${uin}_minigame_sig_${System.currentTimeMillis()}")
        val text = r.bytes?.toString(Charsets.UTF_8).orEmpty()
        val sig = Regex("_sig=(\\d+)").find(text)?.groupValues?.getOrNull(1)
        ServerLog.i(TAG, "uin=$uin signature=${sig ?: "none"} code=${r.code}")
        return sig
    }

    fun fetchVideoTask(uin: String): TaskInfo? {
        val bytes = fetchRaw(uin, "video_task") ?: return null
        val found = parseTasks(bytes)
        val hit = found.firstOrNull { it.title.contains("视频") && !it.title.contains("漫剧") }
            ?: scanVideoTask(bytes)
        ServerLog.i(TAG, "uin=$uin videoTask=${hit?.let { "${it.id}/${it.title}/status=${it.status}/done=${it.done}" } ?: "none"} parsed=${found.size}")
        return hit
    }

    fun fetchGameTask(uin: String): TaskInfo? = fetchTask(uin, 1036, "小游戏")

    fun fetchSongTask(uin: String): TaskInfo? = fetchTask(uin, 1020, "听歌")

    fun fetchFeedTask(uin: String): TaskInfo? = fetchTask(uin, 1019, "浏览十条")

    fun fetchAllTasks(uin: String): List<TaskInfo>? {
        val bytes = fetchRaw(uin, "all_tasks") ?: return null
        return parseTasks(bytes)
    }

    fun findTask(tasks: List<TaskInfo>, taskId: Int, titlePart: String = ""): TaskInfo? =
        tasks.firstOrNull { it.id == taskId }
            ?: tasks.firstOrNull { titlePart.isNotBlank() && it.title.contains(titlePart) }

    fun fetchTask(uin: String, taskId: Int, titlePart: String): TaskInfo? {
        val bytes = fetchRaw(uin, "task_$taskId") ?: return null
        val found = parseTasks(bytes)
        val hit = findTask(found, taskId, titlePart)
        ServerLog.i(
            TAG,
            "uin=$uin task=$taskId ${hit?.let { "title=${it.title} status=${it.status} done=${it.isDone} vip=${it.isVipTask}" } ?: "none"} parsed=${found.size}",
        )
        return hit
    }

    private fun fetchRaw(uin: String, tag: String): ByteArray? {
        repeat(2) { attempt ->
            val r = transport.sendPbRaw(uin, CMD, body(), "${uin}_${tag}_${System.currentTimeMillis()}")
            val bytes = r.bytes
            ServerLog.i(TAG, "uin=$uin status raw code=${r.code} len=${bytes?.size ?: -1} attempt=${attempt + 1}")
            if (bytes != null && bytes.size > 64) return bytes
            if (attempt == 0) Thread.sleep(400)
        }
        return null
    }

    private fun body(): ByteArray = hex("08f2a20210001800220408001801320e616e64726f696420392e332e3835")

    private fun scanVideoTask(b: ByteArray): TaskInfo? {
        for (title in listOf("完成视频任务获得加速时长", "看视频", "视频任务")) {
            val tb = title.toByteArray()
            var from = 0
            while (true) {
                val at = indexOf(b, tb, from)
                if (at < 0) break
                val end = minOf(b.size, at + tb.size + 500)
                var id = 0
                var vip = false
                var i = at + tb.size
                while (i + 1 < end) {
                    if (b[i] == 0xa0.toByte() && b[i + 1] == 0x01.toByte()) {
                        var v = 0L; var sh = 0; var j = i + 2
                        while (j < end) {
                            val x = b[j].toInt() and 0xff; j++
                            v = v or ((x and 0x7f).toLong() shl sh)
                            if (x and 0x80 == 0) break
                            sh += 7
                        }
                        if (v in 1..1_000_000) id = v.toInt()
                    }
                    if (b[i] == 0xa8.toByte() && b[i + 1] == 0x01.toByte()) {
                        var v = 0L; var sh = 0; var j = i + 2
                        while (j < end) {
                            val x = b[j].toInt() and 0xff; j++
                            v = v or ((x and 0x7f).toLong() shl sh)
                            if (x and 0x80 == 0) break
                            sh += 7
                        }
                        vip = v != 0L
                    }
                    i++
                }
                if (id > 0) return TaskInfo(id, title, -1, false, vip, 0, 0)
                from = at + 1
            }
        }
        return null
    }

    private fun indexOf(hay: ByteArray, needle: ByteArray, start: Int): Int {
        outer@ for (i in start..hay.size - needle.size) {
            for (j in needle.indices) if (hay[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }

    internal fun parseTasks(b: ByteArray): List<TaskInfo> {
        val out = ArrayList<TaskInfo>()
        walk(b, 0, out)
        return out
    }

    private data class Field(val no: Int, val wire: Int, val value: Any)

    private fun walk(b: ByteArray, depth: Int, out: MutableList<TaskInfo>) {
        if (depth > 8 || b.isEmpty()) return
        val fields = parseAll(b) ?: return
        var title: String? = null
        var id = 0
        var status = -1
        var isDone = false
        var isVip = false
        var raw10 = 0
        var raw11 = 0
        var jumpUrl = ""
        for (f in fields) {
            val v = f.value
            when {
                f.no == 1 && f.wire == 2 && v is ByteArray -> {
                    val t = String(v, Charsets.UTF_8)
                    if (t.length in 2..80 && t.any { it.code > 127 }) title = t
                }
                f.no == 5 && f.wire == 2 && v is ByteArray -> {
                    val u = String(v, Charsets.UTF_8)
                    if (u.startsWith("http")) jumpUrl = u
                }
                f.no == 20 && f.wire == 0 && v is Long && v in 1..1_000_000 -> id = v.toInt()
                f.no == 19 && f.wire == 0 && v is Long -> status = v.toInt()
                f.no == 6 && f.wire == 0 && v is Long -> isDone = v != 0L
                f.no == 21 && f.wire == 0 && v is Long -> isVip = v != 0L
                f.no == 10 && f.wire == 0 && v is Long -> raw10 = v.toInt()
                f.no == 11 && f.wire == 0 && v is Long -> raw11 = v.toInt()
            }
        }
        if (id > 0) out.add(TaskInfo(id, title.orEmpty(), status, isDone, isVip, raw10, raw11, jumpUrl))
        for (f in fields) {
            val v = f.value
            if (f.wire == 2 && v is ByteArray && v.size >= 2) walk(v, depth + 1, out)
        }
    }

    private fun parseAll(b: ByteArray): List<Field>? {
        val out = ArrayList<Field>()
        var i = 0
        while (i < b.size) {
            val key = readVarint(b, i) ?: return out
            i = key.second
            val no = (key.first ushr 3).toInt()
            val wire = (key.first and 7).toInt()
            when (wire) {
                0 -> {
                    val v = readVarint(b, i) ?: return out
                    i = v.second
                    out.add(Field(no, 0, v.first))
                }
                2 -> {
                    val n = readVarint(b, i) ?: return out
                    val len = n.first.toInt()
                    i = n.second
                    if (len < 0 || i + len > b.size) return out
                    out.add(Field(no, 2, b.copyOfRange(i, i + len)))
                    i += len
                }
                1 -> {
                    if (i + 8 > b.size) return out
                    i += 8
                }
                5 -> {
                    if (i + 4 > b.size) return out
                    i += 4
                }
                else -> return out
            }
        }
        return out
    }

    private fun readVarint(b: ByteArray, start: Int): Pair<Long, Int>? {
        var v = 0L
        var shift = 0
        var i = start
        while (i < b.size && shift < 64) {
            val x = b[i].toInt() and 0xff
            i++
            v = v or ((x and 0x7f).toLong() shl shift)
            if (x and 0x80 == 0) return v to i
            shift += 7
        }
        return null
    }

    private fun hex(s: String): ByteArray {
        val out = ByteArray(s.length / 2)
        for (i in out.indices) out[i] = ((Character.digit(s[i * 2], 16) shl 4) or
            Character.digit(s[i * 2 + 1], 16)).toByte()
        return out
    }

    companion object {
        private const val TAG = "MINIGAME"
        const val CMD = "OidbSvcTrpcTcp.0x9172_0"
    }
}
