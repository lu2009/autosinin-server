package io.github.luna.autosinin.server.acidify

import io.github.luna.autosinin.protocol.AddFriendTask
import io.github.luna.autosinin.protocol.PbEdit
import io.github.luna.autosinin.qqclient.SsoClient
import io.github.luna.autosinin.server.core.ServerLog
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayOutputStream

/**
 * 基于自研 Android SSO 协议的好友查询 (0xfd4_1) 与删除 (0x126b_0) 实现。
 */
class AcidifyFriendOperator(
    private val sessionManager: AcidifySessionManager,
) : AddFriendTask.FriendOperator {

    override fun deleteFriendIfPresent(uin: String, targetUin: Long): Boolean {
        val client = sessionManager.getBot(uin) ?: return false
        if (!client.isOnline) return false
        return runCatching {
            runBlocking {
                val uid = findFriendUid(client, targetUin) ?: return@runBlocking false
                val delBody = buildDeleteFriendBody(uid)
                val oidbReq = buildOidbPkg(0x126b, 0, delBody)
                val rsp = client.sendPacket("OidbSvcTrpcTcp.0x126b_0", oidbReq, 15_000L)
                if (rsp.retCode != 0) {
                    ServerLog.w("ADDFRIEND", "uin=$uin delete friend=$targetUin failed: sso code=${rsp.retCode} ${rsp.extra}")
                    return@runBlocking false
                }
                val oidbCode = readVarintField(rsp.response, 3) ?: -1L
                if (oidbCode != 0L) {
                    ServerLog.w("ADDFRIEND", "uin=$uin delete friend=$targetUin failed: oidb code=$oidbCode")
                    return@runBlocking false
                }
                ServerLog.i("ADDFRIEND", "uin=$uin deleted existing friend=$targetUin")
                true
            }
        }.onFailure {
            ServerLog.w("ADDFRIEND", "uin=$uin delete friend=$targetUin failed: ${it.message}")
        }.getOrDefault(false)
    }

    private suspend fun findFriendUid(client: SsoClient, targetUin: Long): String? {
        var nextUin: Long? = null
        repeat(10) {
            val reqBody = ByteArrayOutputStream().apply {
                write(PbEdit.varintField(2, 300L))
                if (nextUin != null) {
                    write(PbEdit.bytes(5, PbEdit.varintField(1, nextUin!!)))
                }
                write(PbEdit.varintField(6, 1L))
            }.toByteArray()
            val oidbReq = buildOidbPkg(0xfd4, 1, reqBody)
            val rsp = client.sendPacket("OidbSvcTrpcTcp.0xfd4_1", oidbReq, 15_000L)
            if (rsp.retCode != 0) return null
            val oidbCode = readVarintField(rsp.response, 3) ?: -1L
            val oidbBody = readBytesField(rsp.response, 4)
            if (oidbCode != 0L || oidbBody == null) return null

            val found = parseFriendListAndCheck(oidbBody, targetUin)
            if (found.uid != null) return found.uid
            if (found.isEnd || found.nextUin == null) return null
            nextUin = found.nextUin
        }
        return null
    }

    private fun buildOidbPkg(cmd: Int, svc: Int, body: ByteArray): ByteArray =
        PbEdit.varintField(1, cmd.toLong()) +
            PbEdit.varintField(2, svc.toLong()) +
            PbEdit.bytes(4, body) +
            PbEdit.bytes(6, "android 9.3.85".toByteArray(Charsets.UTF_8))

    private fun buildDeleteFriendBody(targetUid: String): ByteArray {
        val f3 = ByteArrayOutputStream().apply {
            write(PbEdit.varintField(1, 8L))
            write(PbEdit.varintField(2, 8L))
            write(PbEdit.varintField(3, 50L))
        }.toByteArray()
        val f2 = ByteArrayOutputStream().apply {
            write(PbEdit.varintField(1, 130L))
            write(PbEdit.varintField(2, 109L))
            write(PbEdit.bytes(3, f3))
        }.toByteArray()
        val body = ByteArrayOutputStream().apply {
            write(PbEdit.bytes(1, targetUid.toByteArray(Charsets.UTF_8)))
            write(PbEdit.bytes(2, f2))
            write(PbEdit.varintField(3, 0L))
        }.toByteArray()
        return PbEdit.bytes(1, body)
    }

    private data class FriendPageScan(val uid: String?, val nextUin: Long?, val isEnd: Boolean)

    private fun parseFriendListAndCheck(bytes: ByteArray, targetUin: Long): FriendPageScan {
        var idx = 0
        var nextUin: Long? = null
        var isEnd = false
        while (idx < bytes.size) {
            val (tag, afterTag) = readVarint(bytes, idx)
            idx = afterTag
            val field = (tag ushr 3).toInt()
            val wire = (tag and 0x07).toInt()
            when (wire) {
                0 -> {
                    val (v, afterV) = readVarint(bytes, idx)
                    if (field == 3) isEnd = v != 0L
                    idx = afterV
                }
                1 -> idx += 8
                2 -> {
                    val (len, afterLen) = readVarint(bytes, idx)
                    val end = minOf(bytes.size, afterLen + len.toInt())
                    val slice = bytes.copyOfRange(afterLen, end)
                    if (field == 2) {
                        nextUin = readVarintField(slice, 1)
                    } else if (field == 101) {
                        val uin = readVarintField(slice, 3)
                        if (uin == targetUin) {
                            val uid = readBytesField(slice, 1)?.toString(Charsets.UTF_8)
                            if (!uid.isNullOrEmpty()) return FriendPageScan(uid, nextUin, isEnd)
                        }
                    }
                    idx = end
                }
                5 -> idx += 4
                else -> break
            }
        }
        return FriendPageScan(null, nextUin, isEnd)
    }

    private fun readVarintField(bytes: ByteArray, targetField: Int): Long? {
        var idx = 0
        while (idx < bytes.size) {
            val (tag, afterTag) = readVarint(bytes, idx)
            idx = afterTag
            val field = (tag ushr 3).toInt()
            val wire = (tag and 0x07).toInt()
            when (wire) {
                0 -> {
                    val (v, afterV) = readVarint(bytes, idx)
                    if (field == targetField) return v
                    idx = afterV
                }
                1 -> idx += 8
                2 -> {
                    val (len, afterLen) = readVarint(bytes, idx)
                    idx = minOf(bytes.size, afterLen + len.toInt())
                }
                5 -> idx += 4
                else -> return null
            }
        }
        return null
    }

    private fun readBytesField(bytes: ByteArray, targetField: Int): ByteArray? {
        var idx = 0
        while (idx < bytes.size) {
            val (tag, afterTag) = readVarint(bytes, idx)
            idx = afterTag
            val field = (tag ushr 3).toInt()
            val wire = (tag and 0x07).toInt()
            when (wire) {
                0 -> {
                    val (_, afterV) = readVarint(bytes, idx)
                    idx = afterV
                }
                1 -> idx += 8
                2 -> {
                    val (len, afterLen) = readVarint(bytes, idx)
                    val end = minOf(bytes.size, afterLen + len.toInt())
                    if (field == targetField) return bytes.copyOfRange(afterLen, end)
                    idx = end
                }
                5 -> idx += 4
                else -> return null
            }
        }
        return null
    }

    private fun readVarint(bytes: ByteArray, start: Int): Pair<Long, Int> {
        var res = 0L
        var shift = 0
        var idx = start
        while (idx < bytes.size) {
            val b = bytes[idx++].toInt() and 0xff
            res = res or ((b and 0x7f).toLong() shl shift)
            if ((b and 0x80) == 0) break
            shift += 7
        }
        return res to idx
    }
}
