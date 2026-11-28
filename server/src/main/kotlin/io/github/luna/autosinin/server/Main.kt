package io.github.luna.autosinin.server

import io.github.luna.autosinin.server.acidify.AcidifyFriendOperator
import io.github.luna.autosinin.server.acidify.AcidifySessionManager
import io.github.luna.autosinin.server.acidify.AcidifyTicketProvider
import io.github.luna.autosinin.server.acidify.AcidifyTransport
import io.github.luna.autosinin.server.core.HttpGatewayProtoTransport
import io.github.luna.autosinin.server.core.ServerLog
import io.github.luna.autosinin.server.core.SqliteAccountLedger
import io.github.luna.autosinin.server.core.StubProtoTransport
import io.github.luna.autosinin.server.engine.TaskEngine
import io.github.luna.autosinin.server.engine.TaskScheduler
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import java.io.File

fun main() {
    val port = System.getenv("AUTOSININ_PORT")?.toIntOrNull() ?: 8080
    val host = System.getenv("AUTOSININ_HOST") ?: "0.0.0.0"
    val dbPath = System.getenv("AUTOSININ_DB_PATH") ?: "data/autosinin.db"
    val sessionDir = File(System.getenv("AUTOSININ_SESSION_DIR") ?: "data/sessions")
    val signUrl = System.getenv("AUTOSININ_SIGN_URL")?.trim().orEmpty()
    val signToken = System.getenv("AUTOSININ_SIGN_TOKEN")?.trim().orEmpty()
    val httpProxy = System.getenv("AUTOSININ_HTTP_PROXY")?.trim().orEmpty()
    val protocolMode = System.getenv("AUTOSININ_PROTOCOL_MODE")?.trim()?.lowercase() ?: "android"
    val gatewayUrl = System.getenv("AUTOSININ_GATEWAY_URL")?.trim().orEmpty()
    val gatewayToken = System.getenv("AUTOSININ_GATEWAY_TOKEN")?.trim()
    val scheduleStart = System.getenv("AUTOSININ_SCHEDULE_START") ?: "09:00"
    val scheduleEnd = System.getenv("AUTOSININ_SCHEDULE_END") ?: "11:00"
    val scheduleEnabled = System.getenv("AUTOSININ_SCHEDULE_ENABLED")?.toBooleanStrictOrNull() ?: true

    val ledger = SqliteAccountLedger(dbPath)
    val sessionManager = AcidifySessionManager(
        ledger = ledger,
        sessionDir = sessionDir,
        defaultSignUrl = signUrl,
        defaultSignToken = signToken,
        httpProxy = httpProxy,
        protocolMode = protocolMode,
    )
    sessionManager.restoreAllSessions()

    val fallbackTransport = if (gatewayUrl.isNotBlank()) {
        ServerLog.i("MAIN", "Fallback HttpGatewayProtoTransport -> $gatewayUrl")
        HttpGatewayProtoTransport(gatewayUrl, gatewayToken)
    } else {
        StubProtoTransport()
    }
    val transport = AcidifyTransport(sessionManager = sessionManager, fallback = fallbackTransport)
    val tickets = AcidifyTicketProvider(sessionManager = sessionManager, fallback = ledger)
    val friendOperator = AcidifyFriendOperator(sessionManager)

    val engine = TaskEngine(
        ledger = ledger,
        transport = transport,
        tickets = tickets,
        friendOperator = friendOperator,
        beforeRun = { uin -> sessionManager.refreshIfStale(uin) },
    )
    val scheduler = TaskScheduler(
        ledger = ledger,
        engine = engine,
        windowStart = scheduleStart,
        windowEnd = scheduleEnd,
        enabled = scheduleEnabled,
    )
    scheduler.start()

    Runtime.getRuntime().addShutdownHook(
        Thread {
            scheduler.stop()
            sessionManager.shutdown()
        },
    )

    ServerLog.i("MAIN", "Starting autosinin-server on http://$host:$port (db=$dbPath, signUrl=${signUrl.ifBlank { "none" }})")
    embeddedServer(Netty, port = port, host = host) {
        configureAutoSininModule(
            ledger = ledger,
            transport = transport,
            engine = engine,
            scheduler = scheduler,
            sessionManager = sessionManager,
        )
    }.start(wait = true)
}
