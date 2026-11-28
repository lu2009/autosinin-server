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
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random

@Serializable
data class SchedulerStatus(
    val enabled: Boolean,
    val running: Boolean,
    val windowStart: String,
    val windowEnd: String,
    val lastRunAt: Long,
    val nextRunAt: Long,
    val lastSummary: String,
)

class TaskScheduler(
    private val ledger: AccountLedger,
    private val engine: TaskEngine,
    windowStart: String = DEFAULT_START,
    windowEnd: String = DEFAULT_END,
    private val enabled: Boolean = true,
    private val zone: ZoneId = ZoneId.of("Asia/Shanghai"),
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val running = AtomicBoolean(false)
    private var loopJob: Job? = null

    @Volatile
    private var startText: String = windowStart

    @Volatile
    private var endText: String = windowEnd

    @Volatile
    private var startTime: LocalTime = parseTime(windowStart, DEFAULT_START)

    @Volatile
    private var endTime: LocalTime = parseTime(windowEnd, DEFAULT_END)

    @Volatile
    private var lastRunAt: Long = 0L

    @Volatile
    private var nextRunAt: Long = 0L

    @Volatile
    private var lastSummary: String = "idle"

    fun start() {
        if (!enabled) return
        scheduleLoop()
        ServerLog.i(TAG, "scheduler started window=$startText-$endText (daily random, manual reconcile)")
    }

    fun stop() {
        loopJob?.cancel()
        loopJob = null
    }

    fun setSchedule(windowStart: String?, windowEnd: String?): SchedulerStatus {
        val newStart = windowStart?.takeIf { it.isNotBlank() }
        val newEnd = windowEnd?.takeIf { it.isNotBlank() }
        if (newStart != null) {
            startTime = parseTime(newStart, startText)
            startText = newStart
        }
        if (newEnd != null) {
            endTime = parseTime(newEnd, endText)
            endText = newEnd
        }
        if (enabled) scheduleLoop()
        ServerLog.i(TAG, "scheduler reconfigured window=$startText-$endText")
        return status()
    }

    private fun scheduleLoop() {
        loopJob?.cancel()
        loopJob = scope.launch {
            while (isActive) {
                nextRunAt = nextRunMillis()
                val waitMs = nextRunAt - System.currentTimeMillis()
                if (waitMs > 0) delay(waitMs)
                triggerNow()
            }
        }
    }

    private fun nextRunMillis(): Long {
        val now = ZonedDateTime.now(zone)
        val today = randomRunAt(now.toLocalDate())
        return if (today > now.toInstant().toEpochMilli()) today else randomRunAt(now.toLocalDate().plusDays(1))
    }

    private fun randomRunAt(day: LocalDate): Long {
        val start = day.atTime(startTime).atZone(zone).toInstant().toEpochMilli()
        val end = day.atTime(endTime).atZone(zone).toInstant().toEpochMilli()
        return if (end <= start) start else start + Random.nextLong(end - start)
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
        windowStart = startText,
        windowEnd = endText,
        lastRunAt = lastRunAt,
        nextRunAt = nextRunAt,
        lastSummary = lastSummary,
    )

    companion object {
        private const val TAG = "SCHEDULER"
        const val DEFAULT_START = "09:00"
        const val DEFAULT_END = "11:00"

        private fun parseTime(text: String, fallback: String): LocalTime =
            runCatching { LocalTime.parse(text.trim()) }
                .recoverCatching { LocalTime.parse(fallback) }
                .getOrElse { LocalTime.of(9, 0) }
    }
}
