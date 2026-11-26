package io.github.luna.autosinin.protocol

import io.github.luna.autosinin.server.core.ProtoTransport
import io.github.luna.autosinin.server.core.ServerLog
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicLong

/**
 * 小游戏任务 1036 纯协议会话：0x94b9_85 + JudgeTiming。
 */
class MiniGameProtocolSession(private val transport: ProtoTransport) {

    private val seq = AtomicLong(1L)

    data class Outcome(
        val launchReported: Boolean,
        val judgeStartOk: Boolean,
        val judgeEndOk: Boolean,
    ) {
        val anyAccepted: Boolean
            get() = launchReported || judgeEndOk
    }

    fun run(
        uin: String,
        appId: String,
        dwellMs: Long = 16_000L,
        sleepFn: (Long) -> Unit = { Thread.sleep(it) },
    ): Outcome {
        val nowMs = System.currentTimeMillis()
        val launchRes = sendLaunchCallback(uin, appId, nowMs)
        val judgeStartRes = sendJudgeTiming(uin, appId, factType = 11, durationSec = 0, totalSec = 0)

        sleepFn(dwellMs)

        val sec = (dwellMs / 1000L).toInt().coerceAtLeast(15)
        val judgeEndRes = sendJudgeTiming(uin, appId, factType = 13, durationSec = sec, totalSec = sec)

        val out = Outcome(
            launchReported = launchRes,
            judgeStartOk = judgeStartRes,
            judgeEndOk = judgeEndRes,
        )
        ServerLog.i(TAG, "uin=$uin protocol session outcome=$out")
        return out
    }

    private fun sendLaunchCallback(uin: String, appId: String, nowMs: Long): Boolean {
        val body = buildLaunchCallbackBody(appId, nowMs)
        val r = transport.sendOidb(uin, OIDB_CMD, SVC_LAUNCH_CALLBACK, body, "${uin}_wxa_launch_$nowMs")
        ServerLog.i(TAG, "uin=$uin 0x94b9_85 launchCallback ${r.detail}")
        return r.code == 0
    }

    private fun sendJudgeTiming(
        uin: String,
        appId: String,
        factType: Int,
        durationSec: Int,
        totalSec: Int,
    ): Boolean {
        val req = buildJudgeTimingQWebReq(appId, factType, durationSec, totalSec, seq.getAndIncrement())
        val r = transport.sendPbRaw(uin, CMD_JUDGE_TIMING, req, "${uin}_wxa_jt_${factType}_${System.currentTimeMillis()}")
        ServerLog.i(TAG, "uin=$uin JudgeTiming fact=$factType ${r.detail}")
        return r.code == 0
    }

    companion object {
        private const val TAG = "GAMETRIAL"
        private const val OIDB_CMD = 0x94b9
        private const val SVC_LAUNCH_CALLBACK = 85
        private const val CMD_JUDGE_TIMING = "miniapp.trpc.wxgame.user_grow_guard.UserGrowGuard.JudgeTiming"
        private const val HOST_SCENE_LEVEL_TASK = 1037L
        private const val QUA = "V1_AND_SQ_9.3.85_16920_YYB_D"

        internal fun buildLaunchCallbackBody(appId: String, timestampMs: Long): ByteArray {
            val ctx = PbEdit.bytes(1, appId.toByteArray(Charsets.UTF_8))
            return concat(
                PbEdit.varintField(1, HOST_SCENE_LEVEL_TASK),
                PbEdit.varintField(2, 0L),
                PbEdit.varintField(6, timestampMs),
                PbEdit.bytes(50, ctx),
            )
        }

        internal fun buildJudgeTimingQWebReq(
            appId: String,
            factType: Int,
            durationSec: Int,
            totalSec: Int,
            reqSeq: Long,
        ): ByteArray {
            val busi = concat(
                PbEdit.bytes(2, appId.toByteArray(Charsets.UTF_8)),
                PbEdit.varintField(3, factType.toLong()),
                PbEdit.varintField(4, durationSec.toLong()),
                PbEdit.varintField(5, totalSec.toLong()),
                PbEdit.bytes(6, HOST_SCENE_LEVEL_TASK.toString().toByteArray(Charsets.UTF_8)),
            )
            return concat(
                PbEdit.varintField(1, reqSeq),
                PbEdit.bytes(2, QUA.toByteArray(Charsets.UTF_8)),
                PbEdit.bytes(4, busi),
            )
        }

        private fun concat(vararg parts: ByteArray): ByteArray {
            val out = ByteArrayOutputStream()
            for (p in parts) out.write(p)
            return out.toByteArray()
        }
    }
}
