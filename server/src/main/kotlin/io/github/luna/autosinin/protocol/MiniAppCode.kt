package io.github.luna.autosinin.protocol

import io.github.luna.autosinin.server.core.ProtoTransport
import io.github.luna.autosinin.server.core.ServerLog
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicLong

/**
 * 纯协议获取小程序授权 code：
 *   LightAppSvc.mini_program_auth.GetCode (QWebReq -> INTERFACE$StGetCodeReq -> StGetCodeRsp.code)
 */
class MiniAppCode(private val transport: ProtoTransport) {

    private val seq = AtomicLong(1L)

    fun fetch(uin: String, appId: String = DEFAULT_FARM_APPID): String? {
        val body = buildGetCodeReq(appId, seq.getAndIncrement())
        val r = transport.sendPbRaw(uin, CMD, body, "${uin}_mini_code_${System.currentTimeMillis()}")
        val raw = r.bytes
        if (r.code != 0 || raw == null || raw.isEmpty()) {
            ServerLog.w(TAG, "uin=$uin GetCode failed ${r.detail}")
            return null
        }
        val code = parseGetCodeResp(raw)
        ServerLog.i(TAG, "uin=$uin GetCode appId=$appId ok=${!code.isNullOrBlank()} len=${raw.size}")
        return code
    }

    companion object {
        private const val TAG = "MINIAPP"
        const val CMD = "LightAppSvc.mini_program_auth.GetCode"
        const val DEFAULT_FARM_APPID = "1112386029"
        private const val QUA = "V1_AND_SQ_9.3.85_16920_YYB_D"

        internal fun buildGetCodeReq(appId: String, reqSeq: Long = 1L): ByteArray {
            val busi = PbEdit.bytes(1, appId.toByteArray(Charsets.UTF_8))
            val out = ByteArrayOutputStream()
            out.write(PbEdit.varintField(1, reqSeq))
            out.write(PbEdit.bytes(2, QUA.toByteArray(Charsets.UTF_8)))
            out.write(PbEdit.bytes(4, busi))
            return out.toByteArray()
        }

        internal fun parseGetCodeResp(raw: ByteArray): String? {
            val payload = stripOptionalLengthPrefix(raw)
            // QWebRsp field 4 = busiBuf; if already unwrapped, fallback to payload itself
            val busi = readBytesField(payload, 4) ?: payload
            return readBytesField(busi, 2)?.toString(Charsets.UTF_8)?.takeIf { it.isNotBlank() }
        }

        private fun stripOptionalLengthPrefix(raw: ByteArray): ByteArray {
            if (raw.size >= 4) {
                val len = ((raw[0].toInt() and 0xFF) shl 24) or
                    ((raw[1].toInt() and 0xFF) shl 16) or
                    ((raw[2].toInt() and 0xFF) shl 8) or
                    (raw[3].toInt() and 0xFF)
                if (len == raw.size) return raw.copyOfRange(4, raw.size)
            }
            return raw
        }

        private fun readBytesField(buf: ByteArray, targetField: Int): ByteArray? {
            var i = 0
            while (i < buf.size) {
                val (tag, next1) = readVarint(buf, i) ?: return null
                i = next1
                val fieldNo = (tag ushr 3).toInt()
                val wireType = (tag and 0x7L).toInt()
                when (wireType) {
                    0 -> {
                        val (_, next2) = readVarint(buf, i) ?: return null
                        i = next2
                    }
                    1 -> i += 8
                    2 -> {
                        val (len, next2) = readVarint(buf, i) ?: return null
                        val end = next2 + len.toInt()
                        if (end > buf.size || len < 0) return null
                        if (fieldNo == targetField) return buf.copyOfRange(next2, end)
                        i = end
                    }
                    5 -> i += 4
                    else -> return null
                }
            }
            return null
        }

        private fun readVarint(buf: ByteArray, start: Int): Pair<Long, Int>? {
            var res = 0L
            var shift = 0
            var i = start
            while (i < buf.size && shift < 64) {
                val b = buf[i++].toInt() and 0xFF
                res = res or ((b and 0x7F).toLong() shl shift)
                if ((b and 0x80) == 0) return res to i
                shift += 7
            }
            return null
        }
    }
}
