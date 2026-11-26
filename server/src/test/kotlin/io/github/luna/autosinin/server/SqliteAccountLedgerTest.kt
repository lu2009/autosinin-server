package io.github.luna.autosinin.server

import io.github.luna.autosinin.server.core.AccountInfo
import io.github.luna.autosinin.server.core.SqliteAccountLedger
import io.github.luna.autosinin.server.core.TaskRunRecord
import io.github.luna.autosinin.server.core.TaskState
import io.github.luna.autosinin.server.core.TicketProvider
import io.github.luna.autosinin.server.core.TicketRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SqliteAccountLedgerTest {

    @Test
    fun accountAndTicketLifecycle() {
        val ledger = SqliteAccountLedger(":memory:")
        ledger.upsertAccount(AccountInfo("10001", "Alice", enabled = true))
        ledger.upsertAccount(AccountInfo("10002", "Bob", enabled = false))

        assertEquals(2, ledger.listAccounts().size)
        assertEquals(1, ledger.listAccounts(onlyEnabled = true).size)
        assertEquals("Alice", ledger.getAccount("10001")?.nickname)

        ledger.upsertTicket(TicketRecord("10001", "vip.qq.com", "psk_abc", "skey_1"))
        assertEquals("psk_abc", ledger.pskey("10001", "vip.qq.com"))
        assertEquals("skey_1", ledger.skey("10001"))
        assertNull(ledger.pskey("10001", "qzone.qq.com"))

        assertTrue(ledger.deleteAccount("10002"))
        assertFalse(ledger.deleteAccount("10002"))
    }

    @Test
    fun dailyLedgerAndRuns() {
        val ledger = SqliteAccountLedger(":memory:")
        ledger.upsertAccount(AccountInfo("20001"))
        val day = "2026-11-26"

        ledger.recordTaskState("20001", day, "vip_sign", TaskState.FAILED, "timeout")
        ledger.recordTaskState("20001", day, "vip_sign", TaskState.DONE, "signed day=3")

        val entry = ledger.getTaskEntry("20001", day, "vip_sign")
        assertNotNull(entry)
        assertEquals(TaskState.DONE, entry?.state)
        assertEquals(2, entry?.attempts)
        assertEquals("signed day=3", entry?.detail)

        ledger.appendRun(TaskRunRecord(uin = "20001", day = day, taskKey = "vip_sign", ok = true, pending = false, detail = "ok", durationMs = 42))
        val runs = ledger.listRecentRuns("20001", 10)
        assertEquals(1, runs.size)
        assertEquals("vip_sign", runs.first().taskKey)
    }

    @Test
    fun bknMatchesQqAlgorithm() {
        assertTrue(TicketProvider.bkn("test_pskey") > 0L)
    }
}
