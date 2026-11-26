package io.github.luna.autosinin.protocol

import io.github.luna.autosinin.server.core.ProtoTransport
import io.github.luna.autosinin.server.core.ServerLog

/**
 * 会员连续签到（等级任务 1048「会员官网连续签到奖励」）。
 */
class VipSign(private val transport: ProtoTransport) {

    data class QueryOutcome(
        val ok: Boolean,
        val code: Int,
        val msg: String,
        val retcode: Int,
        val state: WelfareSignProto.SignState?,
        val detail: String,
    )

    data class SignOutcome(
        val ok: Boolean,
        val code: Int,
        val msg: String,
        val retcode: Int,
        val result: Int,
        val currentDay: Int,
        val rewards: List<WelfareSignProto.Reward>,
        val cardNum: Int,
        val cardGranted: Boolean,
        val missedDays: Int,
        val canGuideAutoPay: Boolean,
        val detail: String,
    )

    fun query(uin: String, qqVersion: String = "9.3.85"): QueryOutcome {
        val raw = send(uin, WelfareSignProto.CMD_QUERY, CMD_QUERY, WelfareSignProto.queryBody(), qqVersion)
        if (raw.code != 0) {
            return QueryOutcome(false, raw.code, raw.msg, -1, null, raw.detail)
        }
        val pkg = raw.bytes?.let { WelfareSignProto.parseOidb(it) }
            ?: return QueryOutcome(false, raw.code, raw.msg, -1, null, "回包不是 OIDB 包：${raw.detail}")
        if (pkg.command != WelfareSignProto.CMD_QUERY) {
            return QueryOutcome(false, raw.code, raw.msg, pkg.retcode, null, "命令号不符 cmd=${pkg.command}")
        }
        if (pkg.retcode != 0) {
            return QueryOutcome(false, raw.code, raw.msg, pkg.retcode, null, "retcode=${pkg.retcode} ${raw.detail}")
        }
        if (pkg.body.isEmpty()) {
            return QueryOutcome(false, raw.code, raw.msg, pkg.retcode, null, "业务体为空 ${raw.detail}")
        }
        val state = WelfareSignProto.parseState(pkg.body)
            ?: return QueryOutcome(false, raw.code, raw.msg, pkg.retcode, null, "状态解析失败 len=${pkg.body.size}")
        ServerLog.i(
            TAG,
            "uin=$uin query ok status=${state.status} day=${state.currentDay} vip=${state.isVip} card=${state.cardNum} missed=${state.missedDays}",
        )
        return QueryOutcome(true, raw.code, raw.msg, pkg.retcode, state, raw.detail)
    }

    fun sign(uin: String, qqVersion: String = "9.3.85"): SignOutcome {
        val raw = send(uin, WelfareSignProto.CMD_SIGN, CMD_SIGN, WelfareSignProto.signBody(), qqVersion)
        if (raw.code != 0) {
            return failure(raw.code, raw.msg, -1, raw.detail)
        }
        val pkg = raw.bytes?.let { WelfareSignProto.parseOidb(it) }
            ?: return failure(raw.code, raw.msg, -1, "回包不是 OIDB 包：${raw.detail}")
        if (pkg.command != WelfareSignProto.CMD_SIGN) {
            return failure(raw.code, raw.msg, pkg.retcode, "命令号不符 cmd=${pkg.command}")
        }
        if (pkg.retcode != 0) {
            return failure(raw.code, raw.msg, pkg.retcode, "retcode=${pkg.retcode} ${raw.detail}")
        }
        if (pkg.body.isEmpty()) {
            return failure(raw.code, raw.msg, pkg.retcode, "业务体为空 ${raw.detail}")
        }
        val sr = WelfareSignProto.parseSignResult(pkg.body)
            ?: return failure(raw.code, raw.msg, pkg.retcode, "签到回包解析失败 len=${pkg.body.size}")
        ServerLog.i(
            TAG,
            "uin=$uin sign ok result=${sr.result} day=${sr.currentDay} card=${sr.cardNum} granted=${sr.cardGranted} missed=${sr.missedDays}",
        )
        return SignOutcome(
            ok = true,
            code = raw.code,
            msg = raw.msg,
            retcode = pkg.retcode,
            result = sr.result,
            currentDay = sr.currentDay,
            rewards = sr.rewards,
            cardNum = sr.cardNum,
            cardGranted = sr.cardGranted,
            missedDays = sr.missedDays,
            canGuideAutoPay = sr.canGuideAutoPay,
            detail = raw.detail,
        )
    }

    private fun failure(code: Int, msg: String, retcode: Int, detail: String): SignOutcome {
        ServerLog.w(TAG, "sign failed code=$code retcode=$retcode msg=${msg.take(120)} $detail")
        return SignOutcome(
            ok = false,
            code = code,
            msg = msg,
            retcode = retcode,
            result = -1,
            currentDay = 0,
            rewards = emptyList(),
            cardNum = 0,
            cardGranted = false,
            missedDays = 0,
            canGuideAutoPay = false,
            detail = detail,
        )
    }

    private fun send(
        uin: String,
        command: Int,
        cmd: String,
        business: ByteArray,
        qqVersion: String,
    ): ProtoTransport.RawResponse {
        val platform = if (qqVersion.isBlank()) "android 9.3.85" else "android $qqVersion"
        val pkg = WelfareSignProto.oidbRequest(command, business, platform)
        val trace = "${uin}_vipsign_${command.toString(16)}_${System.currentTimeMillis()}"
        return transport.sendPbRaw(uin, cmd, pkg, trace)
    }

    companion object {
        private const val TAG = "VIPSIGN"
        const val CMD_QUERY = "OidbSvcTrpcTcp.0x9e99_0"
        const val CMD_SIGN = "OidbSvcTrpcTcp.0x9e9a_0"
    }
}
