package io.github.luna.autosinin.server.core

import java.time.LocalDate
import java.time.ZoneId

enum class TaskState {
    PENDING,
    DONE,
    FAILED,
    SKIPPED,
}

data class AccountInfo(
    val uin: String,
    val nickname: String = "",
    val enabled: Boolean = true,
    val qqVersion: String = "9.3.85",
    val updatedAt: Long = System.currentTimeMillis(),
)

data class TicketRecord(
    val uin: String,
    val domain: String,
    val pskey: String,
    val skey: String? = null,
    val updatedAt: Long = System.currentTimeMillis(),
)

data class TaskLedgerEntry(
    val uin: String,
    val day: String,
    val taskKey: String,
    val state: TaskState,
    val detail: String = "",
    val attempts: Int = 0,
    val updatedAt: Long = System.currentTimeMillis(),
)

data class TaskRunRecord(
    val id: Long = 0,
    val uin: String,
    val day: String,
    val taskKey: String,
    val ok: Boolean,
    val pending: Boolean,
    val detail: String,
    val durationMs: Long,
    val createdAt: Long = System.currentTimeMillis(),
)

/**
 * 多账号每日任务账本与状态存储抽象，替代 Android SharedPreferences。
 */
interface AccountLedger {
    fun upsertAccount(account: AccountInfo)
    fun getAccount(uin: String): AccountInfo?
    fun listAccounts(onlyEnabled: Boolean = false): List<AccountInfo>
    fun deleteAccount(uin: String): Boolean

    fun upsertTicket(ticket: TicketRecord)
    fun listTickets(uin: String): List<TicketRecord>

    fun getTaskEntry(uin: String, day: String, taskKey: String): TaskLedgerEntry?
    fun listDayEntries(uin: String, day: String): List<TaskLedgerEntry>
    fun recordTaskState(
        uin: String,
        day: String,
        taskKey: String,
        state: TaskState,
        detail: String,
        incrementAttempt: Boolean = true,
    )

    fun appendRun(record: TaskRunRecord)
    fun listRecentRuns(uin: String? = null, limit: Int = 50): List<TaskRunRecord>

    companion object {
        val SHANGHAI_ZONE: ZoneId = ZoneId.of("Asia/Shanghai")
        fun todayKey(): String = LocalDate.now(SHANGHAI_ZONE).toString()
    }
}
