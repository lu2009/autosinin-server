package io.github.luna.autosinin.server

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
    val gatewayUrl = System.getenv("AUTOSININ_GATEWAY_URL")?.trim().orEmpty()
    val gatewayToken = System.getenv("AUTOSININ_GATEWAY_TOKEN")?.trim()
    val scheduleMinutes = System.getenv("AUTOSININ_SCHEDULE_INTERVAL_MIN")?.toLongOrNull() ?: 360L
    val scheduleEnabled = System.getenv("AUTOSININ_SCHEDULE_ENABLED")?.toBooleanStrictOrNull() ?: true

    val ledger = SqliteAccountLedger(dbPath)
    val sessionManager = AcidifySessionManager(
        ledger = ledger,
        sessionDir = sessionDir,
        defaultSignUrl = signUrl,
        defaultSignToken = signToken,
        httpProxy = httpProxy,
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

    val engine = TaskEngine(ledger = ledger, transport = transport, tickets = tickets)
    val scheduler = TaskScheduler(
        ledger = ledger,
        engine = engine,
        intervalMinutes = scheduleMinutes,
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
