package io.github.luna.autosinin.server

import io.github.luna.autosinin.protocol.DailySignCards
import io.github.luna.autosinin.protocol.DramaSso
import io.github.luna.autosinin.protocol.MiniGameAdFetcher
import io.github.luna.autosinin.protocol.MiniGameArena
import io.github.luna.autosinin.protocol.MiniGameProtocolSession
import io.github.luna.autosinin.protocol.MiniGameStatus
import io.github.luna.autosinin.protocol.NovelSession
import io.github.luna.autosinin.protocol.PbEdit
import io.github.luna.autosinin.protocol.PublishTask
import io.github.luna.autosinin.protocol.VipSign
import io.github.luna.autosinin.protocol.WelfareClaim
import io.github.luna.autosinin.server.core.AccountInfo
import io.github.luna.autosinin.server.core.AccountLedger
import io.github.luna.autosinin.server.core.ProtoTransport
import io.github.luna.autosinin.server.core.SqliteAccountLedger
import io.github.luna.autosinin.server.core.TaskState
import io.github.luna.autosinin.server.engine.TaskEngine
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtocolMigrationTest {

    private class RecordingTransport(
        private val handler: (uin: String, cmd: String, body: ByteArray) -> ProtoTransport.RawResponse,
    ) : ProtoTransport {
        val calls = mutableListOf<Pair<String, String>>()
        override fun sendPbRaw(uin: String, cmd: String, body: ByteArray, trace: String): ProtoTransport.RawResponse {
            calls += uin to cmd
            return handler(uin, cmd, body)
        }
    }

    @Test
    fun miniGameStatusParsesTaskItems() {
        val item = PbEdit.bytes(1, "每日签到".toByteArray()) +
            PbEdit.varintField(6, 1) +
            PbEdit.varintField(19, 1) +
            PbEdit.varintField(20, 1048) +
            PbEdit.varintField(21, 1)
        val wrapper = PbEdit.bytes(5, item)
        val root = PbEdit.bytes(4, wrapper)
        val padded = root + ByteArray(64)

        val transport = RecordingTransport { _, _, _ ->
            ProtoTransport.RawResponse(0, "ok", padded)
        }
        val status = MiniGameStatus(transport)
        val tasks = status.fetchAllTasks("123456")
        assertNotNull(tasks)
        assertEquals(1, tasks!!.size)
        assertEquals(1048, tasks[0].id)
        assertTrue(tasks[0].done)
        assertTrue(tasks[0].isVipTask)
    }

    @Test
    fun miniGameArenaBuildsAndParsesRoom() {
        val body = MiniGameArena.buildBody(MiniGameArena.DEFAULT_APPID)
        assertTrue(body.isNotEmpty())
        val room = MiniGameArena.parseRoom("""{"room_id":"sluqou0y87xz","valid_duration_minutes":180}""")
        assertNotNull(room)
        assertEquals("sluqou0y87xz", room?.id)
        assertEquals(180, room?.validMinutes)
    }

    @Test
    fun dailySignCardsParsesAndSigns() {
        val transport = RecordingTransport { _, cmd, _ ->
            if (cmd.contains("0x97d0")) {
                val json = """{"list":[{"card_type":2,"signed_in":false,"task_info":{"task_id":"t1","inner_task_id":99}}]}"""
                ProtoTransport.RawResponse(0, "ok", json.toByteArray())
            } else {
                ProtoTransport.RawResponse(0, "ok", """{"ret":0}""".toByteArray())
            }
        }
        val cards = DailySignCards(transport)
        val res = cards.run("123456")
        assertTrue(res.ok)
        assertEquals(2, transport.calls.size)
    }

    @Test
    fun welfareClaimPicksAndPrioritizesBenefits() {
        val zone = JSONObject(
            """
            {
              "zoneInfo": {
                "merchantBenefits": [
                  {
                    "benefits": [
                      {"base": {"type": 2, "benefitsId": 100, "merchantId": 1, "displayInfo": {"name": "普通礼包"}}},
                      {"base": {"type": 2, "benefitsId": 200, "merchantId": 9, "displayInfo": {"name": "外卖满减券"}}}
                    ]
                  }
                ]
              }
            }
            """.trimIndent(),
        )
        val picked = WelfareClaim.pickBenefits(zone)
        assertEquals(2, picked.size)
        assertEquals(200L, picked[0].id)

        val prioritized = WelfareClaim.prioritize(picked, 100L)
        assertEquals(100L, prioritized[0].id)
    }

    @Test
    fun dramaSsoAndMiniGameSessionSendExpectedCommands() {
        val transport = RecordingTransport { _, _, _ ->
            ProtoTransport.RawResponse(0, "ok", ByteArray(4))
        }
        val drama = DramaSso(transport)
        val dOut = drama.finish("123456", sleepFn = {})
        assertTrue(dOut.ok)
        assertEquals(3, dOut.sent)

        val session = MiniGameProtocolSession(transport)
        val mOut = session.run("123456", MiniGameArena.DEFAULT_APPID, dwellMs = 15_000L, sleepFn = {})
        assertTrue(mOut.anyAccepted)
        assertTrue(mOut.launchReported)
        assertTrue(mOut.judgeStartOk)
        assertTrue(mOut.judgeEndOk)
    }

    @Test
    fun vipSignQueryHandlesGatewayErrorGracefully() {
        val transport = RecordingTransport { _, _, _ ->
            ProtoTransport.RawResponse(-503, "unconfigured", null)
        }
        val vip = VipSign(transport)
        val q = vip.query("123456")
        assertEquals(-503, q.code)
    }

    @Test
    fun publishTaskAndNovelSessionWorkWithMockTransport() {
        val transport = RecordingTransport { _, cmd, _ ->
            if (cmd.contains("0x98cc")) {
                val json = """{"book_list":[{"base_info":{"id":"1161697019","first_chapter_id":"61188665929419058"}}]}"""
                ProtoTransport.RawResponse(0, "ok", json.toByteArray())
            } else {
                ProtoTransport.RawResponse(0, "ok", ByteArray(4))
            }
        }
        val pub = PublishTask(transport)
        val pubRes = pub.publishMood("123456")
        assertTrue(pubRes.ok)

        val novel = NovelSession(transport)
        val novelRes = novel.run("123456", sleepFn = {})
        assertTrue(novelRes.ok)
    }

    @Test
    fun miniGameAdFetcherParsesGameAppId() {
        val transport = RecordingTransport { _, _, _ ->
            val json = """
                {
                  "session": "s1",
                  "modules": [
                    {
                      "module_type": 3,
                      "pos_id": "pos1",
                      "host_scene": "12345678",
                      "ad_card": {
                        "ad_cards": [
                          {
                            "id": "wx2ea52a972ea18e5e",
                            "trace_info": {
                              "product_id": "wx2ea52a972ea18e5e",
                              "aid": "a1",
                              "traceid": "t1",
                              "view_id": "v1"
                            }
                          }
                        ]
                      }
                    }
                  ]
                }
            """.trimIndent()
            ProtoTransport.RawResponse(0, "ok", json.toByteArray())
        }
        val fetcher = MiniGameAdFetcher(transport)
        assertEquals("wx2ea52a972ea18e5e", fetcher.fetchGameAppId("123456"))
    }

    @Test
    fun taskEngineReconcilesPendingVia0x9172() {
        val item = PbEdit.bytes(1, "浏览十条好友动态".toByteArray()) +
            PbEdit.varintField(6, 1) +
            PbEdit.varintField(19, 1) +
            PbEdit.varintField(20, 1019)
        val root = PbEdit.bytes(4, PbEdit.bytes(5, item)) + ByteArray(64)
        val transport = RecordingTransport { _, _, _ ->
            ProtoTransport.RawResponse(0, "ok", root)
        }
        val ledger = SqliteAccountLedger(":memory:")
        ledger.upsertAccount(AccountInfo("123456"))
        val day = AccountLedger.todayKey()
        ledger.recordTaskState("123456", day, "feed_read", TaskState.PENDING, "waiting 0x9172")

        val engine = TaskEngine(ledger, transport, ledger, gapRangeMs = 0L..0L)
        val promoted = engine.reconcilePending("123456")
        assertEquals(1, promoted.size)
        assertEquals(TaskState.DONE, ledger.getTaskEntry("123456", day, "feed_read")?.state)
    }
}
