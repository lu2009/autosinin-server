package io.github.luna.autosinin.protocol

import io.github.luna.autosinin.server.core.ProtoTransport
import io.github.luna.autosinin.server.core.ServerLog

/**
 * 看漫剧任务 SSO 回放：0x9d3b -> 0x9c46 -> 0x9d3c / 0x9d3a。
 */
class DramaSso(private val transport: ProtoTransport) {

    data class Outcome(val sent: Int, val failed: Int, val detail: String) {
        val ok: Boolean get() = sent > 0 && failed == 0
    }

    fun replay(uin: String, only: Set<Int> = emptySet()): Outcome {
        val ts = System.currentTimeMillis()
        val token = "${ts}_$uin"
        return send(uin, ts, baseSteps(ts, token, SCHEME), only)
    }

    fun replayWatch(
        uin: String,
        videoA: Long = 703819178397929472L,
        videoB: Long = 703819178414571520L,
        title: String = "厨神归来，我让你三天倒闭",
        episode: String = "第1集",
        watchedMs: Int = 12_000,
    ): Outcome {
        val ts = System.currentTimeMillis()
        val token = "${ts}_$uin"
        val steps = baseSteps(ts, token, SCHEME) + watchSteps(ts, token, videoA, videoB, title, episode, watchedMs)
        return send(uin, ts, steps, emptySet())
    }

    fun finish(
        uin: String,
        taskSec: Int = 10,
        durationSec: Int = 10,
        videoId: Long = 703819178414571520L,
        sleepFn: (Long) -> Unit = { Thread.sleep(it) },
    ): Outcome {
        var sent = 0
        var failed = 0
        val start = send(uin, System.currentTimeMillis(), listOf("9d3b_0" to PbEdit.varintField(1, taskSec.toLong())), emptySet(), sleepFn)
        sent += start.sent; failed += start.failed
        sleepFn(1_500)
        val hb = heartbeat(uin, durationSec, videoId, sleepFn)
        sent += hb.sent; failed += hb.failed
        sleepFn(1_500)
        val fin = send(uin, System.currentTimeMillis(), listOf("9d3c_0" to ByteArray(0)), emptySet(), sleepFn)
        sent += fin.sent; failed += fin.failed
        return Outcome(sent, failed, "start=${start.detail}; hb=${hb.detail}; finish=${fin.detail}")
    }

    fun heartbeat(
        uin: String,
        durationSec: Int = 10,
        videoId: Long = 703819178414571520L,
        sleepFn: (Long) -> Unit = { Thread.sleep(it) },
    ): Outcome {
        val ts = System.currentTimeMillis()
        val body = PbEdit.varintField(1, videoId) + PbEdit.varintField(2, ts) +
            PbEdit.varintField(3, 1) + PbEdit.varintField(4, durationSec.toLong())
        return send(uin, ts, listOf("9c46_0" to body), emptySet(), sleepFn)
    }

    private fun baseSteps(ts: Long, token: String, scheme: String): List<Pair<String, ByteArray>> = listOf(
        "9d3b_0" to PbEdit.varintField(1, 10),
        "9983_0" to (PbEdit.bytes(1, token.toByteArray()) + PbEdit.bytes(6, "dengji_task".toByteArray())),
        "9d13_0" to (PbEdit.varintField(1, ts) + PbEdit.bytes(2, scheme.toByteArray())),
        "9d3a_0" to ByteArray(0),
        "9d54_0" to ByteArray(0),
        "9d55_0" to PbEdit.varintField(1, 1),
        "9b3d_0" to PbEdit.bytes(1, token.toByteArray()),
        "9982_1" to PbEdit.bytes(2, token.toByteArray()),
        "9b3e_0" to (PbEdit.bytes(1, token.toByteArray()) + PbEdit.bytes(2, "全屏".toByteArray())),
        "9ba1_0" to eventBody(ts, token, "entry_video_start_cost", listOf("k1" to "1169", "k2" to "1", "k3" to "dengji_task", "k4" to "0", "k5" to "0")),
        "9bbf_0" to adBody(token),
    )

    private fun watchSteps(
        ts: Long,
        token: String,
        videoA: Long,
        videoB: Long,
        title: String,
        episode: String,
        watchedMs: Int,
    ): List<Pair<String, ByteArray>> {
        val info = PbEdit.varintField(1, videoA) +
            PbEdit.varintField(2, videoB) +
            PbEdit.varintField(3, 2) +
            PbEdit.varintField(5, ts) +
            PbEdit.bytes(6, title.toByteArray()) +
            PbEdit.bytes(7, episode.toByteArray())
        val quality = eventBody(
            ts, token, "video_play_quality",
            listOf(
                "k1" to watchedMs.toString(),
                "k2" to "0",
                "k3" to "0",
                "k4" to "0",
                "k5" to "5",
                "k6" to videoA.toString(),
                "k7" to videoB.toString(),
                "k8" to "1",
                "k9" to "dengji_task",
            ),
        )
        return listOf(
            "9c46_0" to (PbEdit.varintField(1, videoB) + PbEdit.varintField(2, ts) + PbEdit.varintField(3, 1) + PbEdit.varintField(4, 1)),
            "9a5c_1" to PbEdit.bytes(1, info),
            "9ba1_0" to quality,
            "9a6c_0" to (PbEdit.varintField(1, videoA) + PbEdit.varintField(2, videoB) + PbEdit.varintField(4, 1)),
        )
    }

    private fun send(
        uin: String,
        ts: Long,
        steps: List<Pair<String, ByteArray>>,
        only: Set<Int>,
        sleepFn: (Long) -> Unit = { Thread.sleep(it) },
    ): Outcome {
        var sent = 0
        var failed = 0
        val selected = if (only.isEmpty()) steps else steps.filterIndexed { i, _ -> (i + 1) in only }
        if (selected.isEmpty()) return Outcome(0, 0, "no steps selected")
        for ((cmdSuffix, body) in selected) {
            val parts = cmdSuffix.split("_")
            val cmd = parts[0].toInt(16)
            val service = parts.getOrNull(1)?.toIntOrNull() ?: 0
            val trace = "${uin}_drama_${ts}_${cmdSuffix}"
            val r = transport.sendOidb(uin, cmd, service, body, trace)
            if (r.code == 0 || r.bytes != null) sent++ else failed++
            ServerLog.i(TAG, "uin=$uin 0x${parts[0]} service=$service -> ${r.detail}")
            sleepFn(200)
        }
        return Outcome(sent, failed, "sent=$sent failed=$failed")
    }

    private fun adBody(token: String): ByteArray {
        val old = "1794726218978_3885997084".toByteArray()
        val bytes = hexToBytes(HEX_9BBF)
        val idx = bytes.indexOfSlice(old)
        if (idx < 0 || token.toByteArray().size != old.size) return bytes
        return bytes.copyOfRange(0, idx) + token.toByteArray() + bytes.copyOfRange(idx + old.size, bytes.size)
    }

    private fun ByteArray.indexOfSlice(needle: ByteArray): Int {
        if (needle.isEmpty() || needle.size > size) return -1
        outer@ for (i in 0..(size - needle.size)) {
            for (j in needle.indices) if (this[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }

    private fun hexToBytes(hex: String): ByteArray {
        val out = ByteArray(hex.length / 2)
        for (i in out.indices) out[i] = ((Character.digit(hex[i * 2], 16) shl 4) or
            Character.digit(hex[i * 2 + 1], 16)).toByte()
        return out
    }

    private fun eventBody(ts: Long, token: String, event: String, kvs: List<Pair<String, String>>): ByteArray {
        fun kv(k: String, v: String): ByteArray =
            PbEdit.bytes(1, k.toByteArray()) + PbEdit.bytes(2, v.toByteArray())
        var out = PbEdit.bytes(1, event.toByteArray()) +
            PbEdit.varintField(2, ts) +
            PbEdit.bytes(3, token.toByteArray()) +
            PbEdit.bytes(4, "android 14".toByteArray()) +
            PbEdit.bytes(5, "Redmi".toByteArray()) +
            PbEdit.bytes(6, "22041211AC".toByteArray())
        for ((k, v) in kvs) out += PbEdit.bytes(7, kv(k, v))
        return out
    }

    companion object {
        private const val TAG = "DRAMASSO"
        private const val SCHEME = "mqqapi://comicvideo/opentab?tabtype=recommend&task=watch&watchTime=10&from=dengji_task"
        private const val HEX_9BBF = "0a18313739343732363231383937385f3338383539393730383412123734393531393136313734323435383838301a200880c090d4cdf6b4b30a10808088e0cdf6b4b30a18012080a0a0d8fb8cfbc50932d808086512ca0812180a103230383535383639363033333334393110021a0210011a9a08180020b1e7022a0231343206392e332e3735380262065869616f6d696a0a32323034313231314143789594a08002a00100c2015830313031383639463546354433463746454544454342423535423836393944453936334138384634433139393141453034343335343331353742343941394534364145353736433645433438413343323446303341413937e2013230314245383936443345354233333639413337373837423941413830434143323546363031443443423030364441344536368a02055265646d699202cd067b2261747472695f696e666f223a7b226170705f62756e646c655f6964223a22636f6d2e74656e63656e742e6d6f62696c657171222c227561223a2244616c76696b5c2f322e312e3020284c696e75783b20553b20416e64726f69642031343b2032323034313231314143204275696c645c2f555031412e3233313030352e30303729222c2275615f69223a7b22635f69223a223135312e302e373932322e313939222c22735f69223a7b22625f69223a22555031412e3233313030352e303037222c22625f6d223a2232323034313231314143222c22625f6d66223a225869616f6d69222c22625f725f6f5f63223a223134222c22625f765f63223a2252454c222c22625f765f69223a224f53322e302e352e302e554c4e434e584d222c22625f765f72223a223134222c226a766d5f76223a22322e312e30222c2273775f73223a2231227d7d7d2c226465766963655f6d6f64656c5f74797065223a312c22666f6e745f73697a65223a312c226861726d6f6e795f7379735f696e666f223a7b226861726d6f6e795f707572655f6d6f6465223a2d312c2269735f6861726d6f6e795f6f73223a66616c73657d2c226a7362756e646c655f696e666f223a227b5c22706361642d7265776172645c223a5c22302e38312e305c227d222c226a756d705f6162696c697479223a5b393939395d2c226d5f6f73223a224f53322e302e352e302e554c4e434e584d222c226d6f64756c655f6e616d65223a22706361642d726577617264222c226d71715f636f6e6669675f737461747573223a312c2271693336223a22626539373431633663666664306133343064393963373536313030303137353161393034222c227171625f65787465726e616c5f6578705f696e666f223a7b22747261666669635f74797065223a32367d2c22746172676574696e675f6162696c697479223a7b22737570706f72745f717569636b5f6170705f6c696e6b223a66616c73652c227765625f77785f6d67616d65223a747275657d2c227765636861745f617574686f72697a6174696f6e5f737461747573223a312c227765636861745f696e7374616c6c65645f696e666f223a7b226170695f766572223a22363731313038393132227d2c22636c69656e745f6d6f6465223a2264656661756c74227d220f82010c950100000000d00100d8010038011a0764656661756c743801"
    }
}
