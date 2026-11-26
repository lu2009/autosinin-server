package io.github.luna.autosinin.server.acidify

import io.github.luna.autosinin.server.core.TicketProvider
import kotlinx.coroutines.runBlocking
import org.ntqqrev.acidify.getPSKey
import org.ntqqrev.acidify.getSKey

/**
 * 基于 acidify-core 的 TicketProvider，实时获取指定域名的 p_skey / skey；
 * 未在线时回退到 SQLite 中缓存/手动录入的票据。
 */
class AcidifyTicketProvider(
    private val sessionManager: AcidifySessionManager,
    private val fallback: TicketProvider,
) : TicketProvider {

    override fun pskey(uin: String, domain: String): String? {
        val bot = sessionManager.getBot(uin)
        if (bot != null && bot.isLoggedIn) {
            val live = runCatching {
                runBlocking { bot.getPSKey(domain) }
            }.getOrNull()?.takeIf { it.isNotBlank() }
            if (live != null) return live
        }
        return fallback.pskey(uin, domain)
    }

    override fun skey(uin: String): String? {
        val bot = sessionManager.getBot(uin)
        if (bot != null && bot.isLoggedIn) {
            val live = runCatching {
                runBlocking { bot.getSKey() }
            }.getOrNull()?.takeIf { it.isNotBlank() }
            if (live != null) return live
        }
        return fallback.skey(uin)
    }
}
