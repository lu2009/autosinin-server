package io.github.luna.autosinin.server

import io.github.luna.autosinin.server.core.SqliteAccountLedger
import io.github.luna.autosinin.server.core.StubProtoTransport
import io.github.luna.autosinin.server.engine.TaskEngine
import io.github.luna.autosinin.server.engine.TaskScheduler
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerRoutesTest {

    @Test
    fun accountTicketAndRunRoutesWork() = testApplication {
        val ledger = SqliteAccountLedger(":memory:")
        val transport = StubProtoTransport()
        val engine = TaskEngine(ledger, transport, ledger)
        val scheduler = TaskScheduler(ledger, engine, enabled = false)

        application {
            configureAutoSininModule(ledger, transport, engine, scheduler)
        }

        val healthResp = client.get("/health")
        assertEquals(HttpStatusCode.OK, healthResp.status)
        assertTrue(healthResp.bodyAsText().contains("StubProtoTransport"))

        val createAcc = client.post("/api/accounts") {
            contentType(ContentType.Application.Json)
            setBody("""{"uin":"987654321","nickname":"Tester","enabled":true}""")
        }
        assertEquals(HttpStatusCode.OK, createAcc.status)

        val addTicket = client.post("/api/accounts/987654321/tickets") {
            contentType(ContentType.Application.Json)
            setBody("""{"domain":"vip.qq.com","pskey":"secret_pskey_123"}""")
        }
        assertEquals(HttpStatusCode.OK, addTicket.status)

        val ticketsResp = client.get("/api/accounts/987654321/tickets")
        assertEquals(HttpStatusCode.OK, ticketsResp.status)
        assertTrue(ticketsResp.bodyAsText().contains("sec***123"))

        val runResp = client.post("/api/accounts/987654321/run") {
            contentType(ContentType.Application.Json)
            setBody("""{"tasks":["vip_sign"],"force":true}""")
        }
        assertEquals(HttpStatusCode.OK, runResp.status)
        assertTrue(runResp.bodyAsText().contains("vip_sign"))

        val ledgerResp = client.get("/api/accounts/987654321/ledger")
        assertEquals(HttpStatusCode.OK, ledgerResp.status)
        assertTrue(ledgerResp.bodyAsText().contains("FAILED"))

        val signHealth = client.get("/api/sign/health")
        assertEquals(HttpStatusCode.OK, signHealth.status)
        assertTrue(signHealth.bodyAsText().contains("configured"))

        val dashboard = client.get("/")
        assertEquals(HttpStatusCode.OK, dashboard.status)
        assertTrue(dashboard.bodyAsText().contains("autosinin-server"))
    }
}
