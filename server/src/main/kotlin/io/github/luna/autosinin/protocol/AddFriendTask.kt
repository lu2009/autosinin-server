package io.github.luna.autosinin.protocol

import io.github.luna.autosinin.server.core.ProtoTransport
import io.github.luna.autosinin.server.core.ServerLog
import java.io.ByteArrayOutputStream
import kotlin.random.Random

/**
 * 加好友任务（纯协议）：
 *   1. 若目标已在好友列表则先删除；
 *   2. 随机间隔后发送 OidbSvcTrpcTcp.0x7c2_5（反编译自 libkernel.so AddBuddyWorkerV2::EncodeRequest）。
 */
class AddFriendTask(
    private val transport: ProtoTransport,
    private val friendOperator: FriendOperator = FriendOperator.NOOP,
) {

    interface FriendOperator {
        fun deleteFriendIfPresent(uin: String, targetUin: Long): Boolean

        companion object {
            val NOOP = object : FriendOperator {
                override fun deleteFriendIfPresent(uin: String, targetUin: Long): Boolean = false
            }
        }
    }

    data class Result(
        val ok: Boolean,
        val pending: Boolean,
        val detail: String,
    )

    fun run(
        uin: String,
        targets: List<Long> = DEFAULT_TARGETS,
        sleepFn: (Long) -> Unit = { Thread.sleep(it) },
    ): Result {
        var addedCount = 0
        val details = mutableListOf<String>()

        for ((idx, target) in targets.withIndex()) {
            val deleted = runCatching { friendOperator.deleteFriendIfPresent(uin, target) }.getOrDefault(false)
            if (deleted) {
                sleepFn(Random.nextLong(900L, 1_800L))
            }

            val req = buildAddBuddyReq(targetUin = target)
            val resp = transport.sendOidb(uin, OIDB_CMD, OIDB_SVC, req, "${uin}_add_friend_${target}_${System.currentTimeMillis()}")
            ServerLog.i(TAG, "uin=$uin target=$target deleted=$deleted ${resp.detail}")
            if (resp.code == 0) addedCount++
            details += "$target:del=$deleted,code=${resp.code}"

            if (idx != targets.lastIndex) {
                sleepFn(Random.nextLong(2_500L, 5_500L))
            }
        }

        return if (addedCount > 0) {
            Result(ok = true, pending = false, detail = "已发送 $addedCount/${targets.size} 个好友申请 (${details.joinToString("; ")})")
        } else {
            Result(ok = false, pending = false, detail = "好友申请发送失败 (${details.joinToString("; ")})")
        }
    }

    companion object {
        private const val TAG = "ADDFRIEND"
        const val OIDB_CMD = 0x7c2
        const val OIDB_SVC = 5
        private const val DEFAULT_SOURCE_ID = 3999L
        val DEFAULT_TARGETS = listOf(2854204259L, 3889004500L, 2854213893L)

        internal fun buildAddBuddyReq(
            targetUin: Long,
            sourceId: Long = DEFAULT_SOURCE_ID,
            subSourceId: Long = 0L,
            guidHex: String = "be9741c6cffd0a340d99c75610001751",
            deviceName: String = "22041211AC",
        ): ByteArray {
            val verifySub = ByteArrayOutputStream().apply {
                write(PbEdit.bytes(1, guidHex.toByteArray(Charsets.UTF_8)))
                write(PbEdit.bytes(2, deviceName.toByteArray(Charsets.UTF_8)))
                write(PbEdit.bytes(3, "android".toByteArray(Charsets.UTF_8)))
            }.toByteArray()

            return ByteArrayOutputStream().apply {
                write(PbEdit.varintField(2, targetUin))
                write(PbEdit.varintField(3, 0L))
                write(PbEdit.varintField(4, 0L))
                write(PbEdit.varintField(5, 0L))
                write(PbEdit.bytes(7, ByteArray(0)))
                write(PbEdit.varintField(8, 0L))
                write(PbEdit.varintField(9, 1L))
                write(PbEdit.varintField(11, sourceId))
                write(PbEdit.varintField(12, subSourceId))
                write(PbEdit.varintField(20, 0L))
                write(PbEdit.bytes(26, verifySub))
                write(PbEdit.varintField(28, 0L))
            }.toByteArray()
        }
    }
}
