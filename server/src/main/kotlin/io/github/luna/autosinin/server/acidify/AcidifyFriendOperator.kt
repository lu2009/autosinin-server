package io.github.luna.autosinin.server.acidify

import io.github.luna.autosinin.protocol.AddFriendTask
import io.github.luna.autosinin.server.core.ServerLog
import kotlinx.coroutines.runBlocking
import org.ntqqrev.acidify.deleteFriend
import org.ntqqrev.acidify.getFriends

/**
 * 基于 acidify-core 的好友查询与删除实现。
 */
class AcidifyFriendOperator(
    private val sessionManager: AcidifySessionManager,
) : AddFriendTask.FriendOperator {

    override fun deleteFriendIfPresent(uin: String, targetUin: Long): Boolean {
        val bot = sessionManager.getBot(uin) ?: return false
        if (!bot.isLoggedIn) return false
        return runCatching {
            runBlocking {
                val friends = bot.getFriends(forceUpdate = true)
                val exists = friends.any { it.uin == targetUin }
                if (!exists) return@runBlocking false
                bot.deleteFriend(targetUin, block = false)
                ServerLog.i("ADDFRIEND", "uin=$uin deleted existing friend=$targetUin")
                true
            }
        }.onFailure {
            ServerLog.w("ADDFRIEND", "uin=$uin delete friend=$targetUin failed: ${it.message}")
        }.getOrDefault(false)
    }
}
