package io.github.luna.autosinin.server.core

/**
 * 服务端票据提供器，替代 Android 端 TicketBridge。
 * 为 HTTP 协议模块按 (uin, domain) 提供 p_skey / skey。
 */
interface TicketProvider {
    fun pskey(uin: String, domain: String): String?
    fun skey(uin: String): String? = null

    companion object {
        /** 计算 QQ Web 接口通用的 g_tk / bkn 值。 */
        fun bkn(key: String): Long {
            var h = 5381L
            for (c in key) h += (h shl 5) + c.code
            return h and 0x7FFFFFFFL
        }
    }
}
