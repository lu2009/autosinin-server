package io.github.luna.autosinin.server.acidify

import io.github.luna.autosinin.server.core.TicketProvider

/**
 * 基于自研 AndroidSessionStore (WLoginSigs) 的 TicketProvider，实时获取指定域名的 p_skey / skey；
 * 未在线或缺失时回退到 SQLite 中缓存/手动录入的票据。
 */
class AcidifyTicketProvider(
    private val sessionManager: AcidifySessionManager,
    private val fallback: TicketProvider,
) : TicketProvider {

    override fun pskey(uin: String, domain: String): String? {
        val client = sessionManager.getBot(uin)
        if (client != null && client.isOnline) {
            val live = client.sessionStore.wloginSigs.psKey[domain]?.takeIf { it.isNotBlank() }
            if (live != null) return live
        }
        return fallback.pskey(uin, domain)
    }

    override fun skey(uin: String): String? {
        val client = sessionManager.getBot(uin)
        if (client != null && client.isOnline) {
            val live = client.sessionStore.wloginSigs.sKey
                .toString(Charsets.UTF_8)
                .takeIf { it.isNotBlank() }
            if (live != null) return live
        }
        return fallback.skey(uin)
    }
}
