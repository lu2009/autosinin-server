package io.github.luna.autosinin.server.engine

import io.github.luna.autosinin.server.core.AccountLedger
import io.github.luna.autosinin.server.core.ServerLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import java.util.concurrent.atomic.AtomicBoolean

@Serializable
data class SchedulerStatus(
    val enabled: Boolean,
    val running: Boolean,
    val intervalMinutes: Long,
    val lastRunAt: Long,
    val nextRunAt: Long,
    val lastSummary: String,
)

class TaskScheduler(
    private val ledger: AccountLedger,
    private val engine: TaskEngine,
    private val intervalMinutes: Long = 360L,
    private val enabled: Boolean = true,
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val running = AtomicBoolean(false)
    private var loopJob: Job? = null
    private var reconcileJob: Job? = null

    @Volatile
    private var lastRunAt: Long = 0L

    @Volatile
    private var nextRunAt: Long = 0L

    @Volatile
    private var lastSummary: String = "idle"

    fun start() {
        if (!enabled || loopJob != null) return
        val intervalMs = intervalMinutes.coerceAtLeast(1L) * 60_000L
        loopJob = scope.launch {
            nextRunAt = System.currentTimeMillis() + intervalMs
            while (isActive) {
                delay(intervalMs)
                triggerNow()
                nextRunAt = System.currentTimeMillis() + intervalMs
            }
        }
        reconcileJob = scope.launch {
            while (isActive) {
                delay(60_000L)
                runCatching {
                    for (acc in ledger.listAccounts(onlyEnabled = true)) {
                        engine.reconcilePending(acc.uin)
                    }
                }
            }
        }
        ServerLog.i(TAG, "scheduler started interval=${intervalMinutes}m (with 60s 0x9172 reconciliation)")
    }

    fun stop() {
        loopJob?.cancel()
        loopJob = null
        reconcileJob?.cancel()
        reconcileJob = null
    }

    fun triggerNow(force: Boolean = false): List<TaskExecutionResult> {
        if (!running.compareAndSet(false, true)) {
            return emptyList()
        }
        return try {
            val results = engine.runAllEnabled(force = force)
            lastRunAt = System.currentTimeMillis()
            val okCount = results.count { it.ok }
            lastSummary = "total=${results.size} ok=$okCount fail=${results.size - okCount}"
            ServerLog.i(TAG, "scheduled sweep $lastSummary")
            results
        } finally {
            running.set(false)
        }
    }

    fun status(): SchedulerStatus = SchedulerStatus(
        enabled = enabled,
        running = running.get(),
        intervalMinutes = intervalMinutes,
        lastRunAt = lastRunAt,
        nextRunAt = nextRunAt,
        lastSummary = lastSummary,
    )

    companion object {
        private const val TAG = "SCHEDULER"
    }
}
