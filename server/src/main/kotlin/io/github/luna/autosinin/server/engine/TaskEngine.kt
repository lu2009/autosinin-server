package io.github.luna.autosinin.server.engine

import io.github.luna.autosinin.protocol.AddFriendTask
import io.github.luna.autosinin.protocol.DailySignCards
import io.github.luna.autosinin.protocol.DramaSso
import io.github.luna.autosinin.protocol.FarmGateway
import io.github.luna.autosinin.protocol.FeedReadTask
import io.github.luna.autosinin.protocol.MiniAppCode
import io.github.luna.autosinin.protocol.MiniGameAdFetcher
import io.github.luna.autosinin.protocol.MiniGameArena
import io.github.luna.autosinin.protocol.MiniGameProtocolSession
import io.github.luna.autosinin.protocol.MiniGameStatus
import io.github.luna.autosinin.protocol.NovelSession
import io.github.luna.autosinin.protocol.PublishTask
import io.github.luna.autosinin.protocol.TaskPageAction
import io.github.luna.autosinin.protocol.VideoAdTask
import io.github.luna.autosinin.protocol.VipSign
import io.github.luna.autosinin.protocol.VisitReport
import io.github.luna.autosinin.protocol.WelfareClaim
import io.github.luna.autosinin.server.core.AccountLedger
import io.github.luna.autosinin.server.core.ProtoTransport
import io.github.luna.autosinin.server.core.ServerLog
import io.github.luna.autosinin.server.core.TaskRunRecord
import io.github.luna.autosinin.server.core.TaskState
import io.github.luna.autosinin.server.core.TicketProvider
import kotlinx.serialization.Serializable

@Serializable
data class TaskExecutionResult(
    val uin: String,
    val taskKey: String,
    val ok: Boolean,
    val skipped: Boolean = false,
    val pending: Boolean = false,
    val detail: String,
    val durationMs: Long = 0,
)

class TaskEngine(
    private val ledger: AccountLedger,
    private val transport: ProtoTransport,
    private val tickets: TicketProvider,
    friendOperator: AddFriendTask.FriendOperator = AddFriendTask.FriendOperator.NOOP,
    private val beforeRun: ((String) -> Unit)? = null,
) {
    val miniGameStatus = MiniGameStatus(transport)
    val dramaSso = DramaSso(transport)
    val vipSign = VipSign(transport)
    val miniGameArena = MiniGameArena(transport, miniGameStatus)
    val visitReport = VisitReport(transport)
    val feedReadTask = FeedReadTask(transport, visitReport, miniGameStatus)
    val dailySignCards = DailySignCards(transport)
    val welfareClaim = WelfareClaim(tickets)
    val miniGameSession = MiniGameProtocolSession(transport)
    val miniGameAdFetcher = MiniGameAdFetcher(transport)
    val publishTask = PublishTask(transport)
    val novelSession = NovelSession(transport)
    val miniAppCode = MiniAppCode(transport)
    val farmGateway = FarmGateway()
    val videoAdTask = VideoAdTask(transport)
    val addFriendTask = AddFriendTask(transport, friendOperator)

    fun runAllEnabled(tasksFilter: Set<String>? = null, force: Boolean = false): List<TaskExecutionResult> {
        val accounts = ledger.listAccounts(onlyEnabled = true)
        return accounts.flatMap { runAccount(it.uin, tasksFilter, force) }
    }

    /**
     * 单点 0x9172 异步核验：拉取一次全量任务状态，将当日处于 PENDING 的任务对账转正为 DONE。
     */
    fun reconcilePending(uin: String): List<TaskExecutionResult> {
        val day = AccountLedger.todayKey()
        val entries = ledger.listDayEntries(uin, day).filter { it.state == TaskState.PENDING }
        if (entries.isEmpty()) return emptyList()

        val remoteTasks = miniGameStatus.fetchAllTasks(uin) ?: return emptyList()
        val promoted = ArrayList<TaskExecutionResult>()
        for (entry in entries) {
            val taskId = TASK_ID_MAP[entry.taskKey] ?: continue
            val remote = miniGameStatus.findTask(remoteTasks, taskId)
            if (remote?.done == true) {
                val detail = "0x9172 异步核验通过（task=$taskId）"
                ledger.recordTaskState(uin, day, entry.taskKey, TaskState.DONE, detail, incrementAttempt = false)
                ServerLog.i(TAG, "uin=$uin reconciled ${entry.taskKey} -> DONE")
                promoted += TaskExecutionResult(uin, entry.taskKey, ok = true, detail = detail)
            }
        }
        return promoted
    }

    fun runAccount(
        uin: String,
        tasksFilter: Set<String>? = null,
        force: Boolean = false,
    ): List<TaskExecutionResult> {
        beforeRun?.invoke(uin)
        val account = ledger.getAccount(uin) ?: return listOf(
            TaskExecutionResult(uin, "account", ok = false, detail = "账号不存在"),
        )
        val day = AccountLedger.todayKey()
        val selected = if (tasksFilter.isNullOrEmpty()) DEFAULT_TASKS else DEFAULT_TASKS.filter { it in tasksFilter }
        val remoteSnapshot = if (!force) miniGameStatus.fetchAllTasks(uin) else null
        val results = ArrayList<TaskExecutionResult>(selected.size)

        for (taskKey in selected) {
            val existing = ledger.getTaskEntry(uin, day, taskKey)
            if (!force && existing?.state == TaskState.DONE) {
                results += TaskExecutionResult(
                    uin = uin,
                    taskKey = taskKey,
                    ok = true,
                    skipped = true,
                    detail = "今日已完成（${existing.detail}）",
                )
                continue
            }

            val remoteId = TASK_ID_MAP[taskKey]
            val remoteDone = if (remoteSnapshot != null && remoteId != null) {
                miniGameStatus.findTask(remoteSnapshot, remoteId)?.done == true
            } else {
                false
            }
            if (!force && remoteDone) {
                val detail = "0x9172 已完成（task=$remoteId）"
                ledger.recordTaskState(uin, day, taskKey, TaskState.DONE, detail, incrementAttempt = false)
                results += TaskExecutionResult(
                    uin = uin,
                    taskKey = taskKey,
                    ok = true,
                    skipped = true,
                    detail = detail,
                )
                continue
            }

            val startMs = System.currentTimeMillis()
            val (ok, pending, detail) = runCatching {
                executeSingle(uin, account.qqVersion, taskKey)
            }.getOrElse {
                Triple(false, false, "异常: ${it.javaClass.simpleName}: ${it.message}")
            }
            val durationMs = System.currentTimeMillis() - startMs

            val nextState = when {
                ok -> TaskState.DONE
                pending -> TaskState.PENDING
                else -> TaskState.FAILED
            }
            ledger.recordTaskState(uin, day, taskKey, nextState, detail)
            ledger.appendRun(
                TaskRunRecord(
                    uin = uin,
                    day = day,
                    taskKey = taskKey,
                    ok = ok,
                    pending = pending,
                    detail = detail,
                    durationMs = durationMs,
                ),
            )
            ServerLog.i(TAG, "uin=$uin task=$taskKey state=$nextState duration=${durationMs}ms detail=$detail")
            results += TaskExecutionResult(
                uin = uin,
                taskKey = taskKey,
                ok = ok,
                pending = pending,
                detail = detail,
                durationMs = durationMs,
            )
        }
        return results
    }

    private fun executeSingle(uin: String, qqVersion: String, taskKey: String): Triple<Boolean, Boolean, String> {
        return when (taskKey) {
            "vip_sign" -> {
                val q = vipSign.query(uin, qqVersion)
                if (q.ok && q.state?.status == 1) {
                    Triple(true, false, "今日已签到 day=${q.state.currentDay}")
                } else {
                    val s = vipSign.sign(uin, qqVersion)
                    val ok = s.ok && (s.result == 0 || s.result == 1)
                    Triple(ok, false, "result=${s.result} day=${s.currentDay} ${s.detail}")
                }
            }
            "daily_cards" -> {
                val r = dailySignCards.run(uin)
                Triple(r.ok, false, r.detail)
            }
            "welfare_claim" -> {
                val r = welfareClaim.claim(uin)
                val desc = listOfNotNull(r.detail, r.benefitName, r.billNo?.takeIf { it.isNotBlank() }).joinToString(" | ")
                Triple(r.ok, false, desc)
            }
            "drama" -> {
                // 0x9d3a 回包的 f1 是配置常量（实测恒为 40250），不是观看时长，
                // 不能拿它判断完成；完成状态以 0x9172 任务列表为准。
                val dramaTaskId = TASK_ID_MAP["drama"] ?: 1043
                val before = miniGameStatus.fetchAllTasks(uin)
                if (before != null && miniGameStatus.findTask(before, dramaTaskId)?.done == true) {
                    Triple(true, false, "0x9172 漫剧任务已完成（task=$dramaTaskId）")
                } else {
                    val r = dramaSso.finish(uin)
                    val after = miniGameStatus.fetchAllTasks(uin)
                    val done = after != null && miniGameStatus.findTask(after, dramaTaskId)?.done == true
                    Triple(
                        done,
                        false,
                        if (done) "0x9172 漫剧任务已标记完成（task=$dramaTaskId）"
                        else "上报已发送但 0x9172 仍未完成：${r.detail}",
                    )
                }
            }
            "publish_mood" -> {
                val r = publishTask.publishMood(uin)
                Triple(r.ok, false, r.detail)
            }
            "publish_blind_box" -> {
                val r = publishTask.publishBlindBox(uin)
                Triple(r.ok, false, r.detail)
            }
            "farm_login" -> {
                val code = miniAppCode.fetch(uin, MiniAppCode.DEFAULT_FARM_APPID)
                if (code.isNullOrBlank()) {
                    Triple(false, false, "获取农场小程序 code 失败")
                } else {
                    val r = farmGateway.login(code)
                    Triple(r.ok, false, r.detail)
                }
            }
            "video_ad" -> {
                val r = videoAdTask.run(uin)
                Triple(r.ok, r.pending, r.detail)
            }
            "add_friend" -> {
                val r = addFriendTask.run(uin)
                Triple(r.ok, r.pending, r.detail)
            }
            "feed_read" -> {
                val r = feedReadTask.run(uin)
                Triple(r.ok, r.pending, r.detail)
            }
            "novel_read" -> {
                val r = novelSession.run(uin)
                Triple(r.ok, false, r.detail)
            }
            "minigame_arena" -> {
                val r = miniGameArena.run(uin)
                Triple(r.ok, false, r.detail)
            }
            "minigame_trial" -> {
                val appId = runCatching { miniGameAdFetcher.fetchGameAppId(uin) }.getOrNull() ?: MiniGameArena.DEFAULT_APPID
                val r = miniGameSession.run(uin, appId)
                Triple(r.anyAccepted, false, "appId=$appId launch=${r.launchReported} start=${r.judgeStartOk} end=${r.judgeEndOk}")
            }
            else -> Triple(false, false, "未知任务: $taskKey")
        }
    }

    companion object {
        private const val TAG = "ENGINE"
        val DEFAULT_TASKS = listOf(
            "vip_sign",
            "daily_cards",
            "welfare_claim",
            "drama",
            "publish_mood",
            "publish_blind_box",
            "feed_read",
            "novel_read",
            "minigame_arena",
            "minigame_trial",
            "farm_login",
            "video_ad",
            "add_friend",
        )
        val TASK_ID_MAP = mapOf(
            "welfare_claim" to 1024,
            "vip_sign" to 1048,
            "drama" to 1043,
            "publish_mood" to 1013,
            "feed_read" to 1019,
            "novel_read" to 1029,
            "minigame_arena" to 1045,
            "minigame_trial" to 1036,
            "video_ad" to 1021,
        )
    }
}
