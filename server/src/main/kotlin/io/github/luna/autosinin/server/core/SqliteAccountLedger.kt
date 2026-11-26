package io.github.luna.autosinin.server.core

import java.io.File
import java.sql.Connection
import java.sql.DriverManager

/**
 * 基于 SQLite 的多账号账本与票据存储。
 * 同时实现 AccountLedger 与 TicketProvider。
 */
class SqliteAccountLedger(private val dbPath: String) : AccountLedger, TicketProvider {

    private val lock = Any()
    private val persistentInMemoryConn: Connection? =
        if (dbPath == ":memory:") DriverManager.getConnection("jdbc:sqlite::memory:") else null

    init {
        if (dbPath != ":memory:") {
            File(dbPath).absoluteFile.parentFile?.mkdirs()
        }
        withConn { conn ->
            conn.createStatement().use { st ->
                st.execute("PRAGMA journal_mode=WAL;")
                st.execute(
                    """
                    CREATE TABLE IF NOT EXISTS accounts (
                        uin TEXT PRIMARY KEY,
                        nickname TEXT NOT NULL DEFAULT '',
                        enabled INTEGER NOT NULL DEFAULT 1,
                        qq_version TEXT NOT NULL DEFAULT '9.3.85',
                        updated_at INTEGER NOT NULL
                    );
                    """.trimIndent(),
                )
                st.execute(
                    """
                    CREATE TABLE IF NOT EXISTS tickets (
                        uin TEXT NOT NULL,
                        domain TEXT NOT NULL,
                        pskey TEXT NOT NULL,
                        skey TEXT,
                        updated_at INTEGER NOT NULL,
                        PRIMARY KEY (uin, domain)
                    );
                    """.trimIndent(),
                )
                st.execute(
                    """
                    CREATE TABLE IF NOT EXISTS daily_task_ledger (
                        uin TEXT NOT NULL,
                        day TEXT NOT NULL,
                        task_key TEXT NOT NULL,
                        state TEXT NOT NULL,
                        detail TEXT NOT NULL DEFAULT '',
                        attempts INTEGER NOT NULL DEFAULT 0,
                        updated_at INTEGER NOT NULL,
                        PRIMARY KEY (uin, day, task_key)
                    );
                    """.trimIndent(),
                )
                st.execute(
                    """
                    CREATE TABLE IF NOT EXISTS task_runs (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        uin TEXT NOT NULL,
                        day TEXT NOT NULL,
                        task_key TEXT NOT NULL,
                        ok INTEGER NOT NULL,
                        pending INTEGER NOT NULL,
                        detail TEXT NOT NULL DEFAULT '',
                        duration_ms INTEGER NOT NULL DEFAULT 0,
                        created_at INTEGER NOT NULL
                    );
                    """.trimIndent(),
                )
            }
        }
    }

    private inline fun <T> withConn(block: (Connection) -> T): T = synchronized(lock) {
        val mem = persistentInMemoryConn
        if (mem != null) {
            return block(mem)
        }
        DriverManager.getConnection("jdbc:sqlite:$dbPath").use(block)
    }

    override fun upsertAccount(account: AccountInfo) {
        withConn { conn ->
            conn.prepareStatement(
                """
                INSERT INTO accounts (uin, nickname, enabled, qq_version, updated_at)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT(uin) DO UPDATE SET
                    nickname = excluded.nickname,
                    enabled = excluded.enabled,
                    qq_version = excluded.qq_version,
                    updated_at = excluded.updated_at;
                """.trimIndent(),
            ).use { ps ->
                ps.setString(1, account.uin)
                ps.setString(2, account.nickname)
                ps.setInt(3, if (account.enabled) 1 else 0)
                ps.setString(4, account.qqVersion)
                ps.setLong(5, account.updatedAt)
                ps.executeUpdate()
            }
        }
    }

    override fun getAccount(uin: String): AccountInfo? = withConn { conn ->
        conn.prepareStatement("SELECT uin, nickname, enabled, qq_version, updated_at FROM accounts WHERE uin = ?").use { ps ->
            ps.setString(1, uin)
            ps.executeQuery().use { rs ->
                if (!rs.next()) null
                else AccountInfo(
                    uin = rs.getString("uin"),
                    nickname = rs.getString("nickname"),
                    enabled = rs.getInt("enabled") != 0,
                    qqVersion = rs.getString("qq_version"),
                    updatedAt = rs.getLong("updated_at"),
                )
            }
        }
    }

    override fun listAccounts(onlyEnabled: Boolean): List<AccountInfo> = withConn { conn ->
        val sql = if (onlyEnabled) {
            "SELECT uin, nickname, enabled, qq_version, updated_at FROM accounts WHERE enabled = 1 ORDER BY uin"
        } else {
            "SELECT uin, nickname, enabled, qq_version, updated_at FROM accounts ORDER BY uin"
        }
        conn.prepareStatement(sql).use { ps ->
            ps.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        add(
                            AccountInfo(
                                uin = rs.getString("uin"),
                                nickname = rs.getString("nickname"),
                                enabled = rs.getInt("enabled") != 0,
                                qqVersion = rs.getString("qq_version"),
                                updatedAt = rs.getLong("updated_at"),
                            ),
                        )
                    }
                }
            }
        }
    }

    override fun deleteAccount(uin: String): Boolean = withConn { conn ->
        conn.prepareStatement("DELETE FROM tickets WHERE uin = ?").use { ps ->
            ps.setString(1, uin)
            ps.executeUpdate()
        }
        conn.prepareStatement("DELETE FROM accounts WHERE uin = ?").use { ps ->
            ps.setString(1, uin)
            ps.executeUpdate() > 0
        }
    }

    override fun upsertTicket(ticket: TicketRecord) {
        withConn { conn ->
            conn.prepareStatement(
                """
                INSERT INTO tickets (uin, domain, pskey, skey, updated_at)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT(uin, domain) DO UPDATE SET
                    pskey = excluded.pskey,
                    skey = COALESCE(excluded.skey, tickets.skey),
                    updated_at = excluded.updated_at;
                """.trimIndent(),
            ).use { ps ->
                ps.setString(1, ticket.uin)
                ps.setString(2, ticket.domain)
                ps.setString(3, ticket.pskey)
                ps.setString(4, ticket.skey)
                ps.setLong(5, ticket.updatedAt)
                ps.executeUpdate()
            }
        }
    }

    override fun listTickets(uin: String): List<TicketRecord> = withConn { conn ->
        conn.prepareStatement(
            "SELECT uin, domain, pskey, skey, updated_at FROM tickets WHERE uin = ? ORDER BY domain",
        ).use { ps ->
            ps.setString(1, uin)
            ps.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        add(
                            TicketRecord(
                                uin = rs.getString("uin"),
                                domain = rs.getString("domain"),
                                pskey = rs.getString("pskey"),
                                skey = rs.getString("skey"),
                                updatedAt = rs.getLong("updated_at"),
                            ),
                        )
                    }
                }
            }
        }
    }

    override fun pskey(uin: String, domain: String): String? = withConn { conn ->
        conn.prepareStatement("SELECT pskey FROM tickets WHERE uin = ? AND domain = ?").use { ps ->
            ps.setString(1, uin)
            ps.setString(2, domain)
            ps.executeQuery().use { rs ->
                if (rs.next()) rs.getString("pskey")?.takeIf { it.isNotBlank() } else null
            }
        }
    }

    override fun skey(uin: String): String? = withConn { conn ->
        conn.prepareStatement(
            "SELECT skey FROM tickets WHERE uin = ? AND skey IS NOT NULL AND skey != '' ORDER BY updated_at DESC LIMIT 1",
        ).use { ps ->
            ps.setString(1, uin)
            ps.executeQuery().use { rs ->
                if (rs.next()) rs.getString("skey") else null
            }
        }
    }

    override fun getTaskEntry(uin: String, day: String, taskKey: String): TaskLedgerEntry? = withConn { conn ->
        conn.prepareStatement(
            "SELECT uin, day, task_key, state, detail, attempts, updated_at FROM daily_task_ledger WHERE uin = ? AND day = ? AND task_key = ?",
        ).use { ps ->
            ps.setString(1, uin)
            ps.setString(2, day)
            ps.setString(3, taskKey)
            ps.executeQuery().use { rs ->
                if (!rs.next()) null
                else TaskLedgerEntry(
                    uin = rs.getString("uin"),
                    day = rs.getString("day"),
                    taskKey = rs.getString("task_key"),
                    state = TaskState.valueOf(rs.getString("state")),
                    detail = rs.getString("detail"),
                    attempts = rs.getInt("attempts"),
                    updatedAt = rs.getLong("updated_at"),
                )
            }
        }
    }

    override fun listDayEntries(uin: String, day: String): List<TaskLedgerEntry> = withConn { conn ->
        conn.prepareStatement(
            "SELECT uin, day, task_key, state, detail, attempts, updated_at FROM daily_task_ledger WHERE uin = ? AND day = ? ORDER BY task_key",
        ).use { ps ->
            ps.setString(1, uin)
            ps.setString(2, day)
            ps.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        add(
                            TaskLedgerEntry(
                                uin = rs.getString("uin"),
                                day = rs.getString("day"),
                                taskKey = rs.getString("task_key"),
                                state = TaskState.valueOf(rs.getString("state")),
                                detail = rs.getString("detail"),
                                attempts = rs.getInt("attempts"),
                                updatedAt = rs.getLong("updated_at"),
                            ),
                        )
                    }
                }
            }
        }
    }

    override fun recordTaskState(
        uin: String,
        day: String,
        taskKey: String,
        state: TaskState,
        detail: String,
        incrementAttempt: Boolean,
    ) {
        val now = System.currentTimeMillis()
        val inc = if (incrementAttempt) 1 else 0
        withConn { conn ->
            conn.prepareStatement(
                """
                INSERT INTO daily_task_ledger (uin, day, task_key, state, detail, attempts, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(uin, day, task_key) DO UPDATE SET
                    state = excluded.state,
                    detail = excluded.detail,
                    attempts = daily_task_ledger.attempts + ?,
                    updated_at = excluded.updated_at;
                """.trimIndent(),
            ).use { ps ->
                ps.setString(1, uin)
                ps.setString(2, day)
                ps.setString(3, taskKey)
                ps.setString(4, state.name)
                ps.setString(5, detail)
                ps.setInt(6, inc)
                ps.setLong(7, now)
                ps.setInt(8, inc)
                ps.executeUpdate()
            }
        }
    }

    override fun appendRun(record: TaskRunRecord) {
        withConn { conn ->
            conn.prepareStatement(
                """
                INSERT INTO task_runs (uin, day, task_key, ok, pending, detail, duration_ms, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?);
                """.trimIndent(),
            ).use { ps ->
                ps.setString(1, record.uin)
                ps.setString(2, record.day)
                ps.setString(3, record.taskKey)
                ps.setInt(4, if (record.ok) 1 else 0)
                ps.setInt(5, if (record.pending) 1 else 0)
                ps.setString(6, record.detail)
                ps.setLong(7, record.durationMs)
                ps.setLong(8, record.createdAt)
                ps.executeUpdate()
            }
        }
    }

    override fun listRecentRuns(uin: String?, limit: Int): List<TaskRunRecord> = withConn { conn ->
        val sql = if (uin.isNullOrBlank()) {
            "SELECT id, uin, day, task_key, ok, pending, detail, duration_ms, created_at FROM task_runs ORDER BY id DESC LIMIT ?"
        } else {
            "SELECT id, uin, day, task_key, ok, pending, detail, duration_ms, created_at FROM task_runs WHERE uin = ? ORDER BY id DESC LIMIT ?"
        }
        conn.prepareStatement(sql).use { ps ->
            if (uin.isNullOrBlank()) {
                ps.setInt(1, limit)
            } else {
                ps.setString(1, uin)
                ps.setInt(2, limit)
            }
            ps.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        add(
                            TaskRunRecord(
                                id = rs.getLong("id"),
                                uin = rs.getString("uin"),
                                day = rs.getString("day"),
                                taskKey = rs.getString("task_key"),
                                ok = rs.getInt("ok") != 0,
                                pending = rs.getInt("pending") != 0,
                                detail = rs.getString("detail"),
                                durationMs = rs.getLong("duration_ms"),
                                createdAt = rs.getLong("created_at"),
                            ),
                        )
                    }
                }
            }
        }
    }
}
