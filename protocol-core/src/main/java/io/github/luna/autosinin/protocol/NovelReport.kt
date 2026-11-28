package io.github.luna.autosinin.protocol

import java.text.SimpleDateFormat
import java.util.TimeZone
import java.util.Date
import java.util.Locale

/**
 * 看小说任务上报（2026-11-22 抓包）：
 *   OidbSvcTrpcTcp.0x93ba_0，页面事件里带 from_src=dengji_task_new、
 *   bundle_name=vas_qqvip_novel_reader、duration_time/delta_time。
 *
 * 回放时只改 uin/session/时间/时长，其余字段保持抓包原样；一次上报。
 */
object NovelReport {

    const val CMD = "OidbSvcTrpcTcp.0x93ba_0"

    private const val OLD_UIN = "3937903488"
    private const val OLD_TIME = "2026-11-22 01:23:35.493"
    private const val OLD_SESSION = "3937903488_1795281658599_1567"
    private const val OLD_DURATION = "156650"
    private const val OLD_DELTA = "141414"
    private const val OLD_EVENT = "pg_out"
    private const val NEW_EVENT = "pg_ext"

    private const val TEMPLATE_HEX: String =
        "08baa7021000180022f3030a116e656761746976655f666565646261636b12dd030a0e0a07627573695f696412037669700a110a0375696e120a3339" +
        "33373930333438380a140a0a6576656e745f636f6465120670675f6f75740a250a0a6576656e745f74696d651217323032362d31312d32322030313a" +
        "32333a33352e3439330a2b0a0a73657373696f6e5f6964121d333933373930333438385f313739353238313635383539395f313536370a140a0b7364" +
        "6b5f76657273696f6e1205312e302e310a250a0b62756e646c655f6e616d6512167661735f71717669705f6e6f76656c5f7265616465720a230a0970" +
        "6167655f6e616d6512167661735f71717669705f6e6f76656c5f7265616465720a080a06646f6d61696e0a130a08706c6174666f726d1207616e6472" +
        "6f69640a150a0b6170705f76657273696f6e1206392e332e38350a140a0b7765625f76657273696f6e120531323334300a070a0570675f69640a0b0a" +
        "096d6f64756c655f69640a1b0a0866726f6d5f737263120f64656e676a695f7461736b5f6e65770a0a0a086164706f735f69640a070a0561645f6964" +
        "0a090a076974656d5f69640a080a066163745f69640a170a0d6475726174696f6e5f74696d6512063135363635300a140a0a64656c74615f74696d65" +
        "12063134313431340a140a0a7265706f72745f73726312066b75696b6c79320e616e64726f696420392e332e3835"

    fun template(): ByteArray = hexToBytes(TEMPLATE_HEX)

    private val timeFmt = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue() = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.CHINA).apply {
            // 报文里声明的是 timezone=+8,id:Asia/Shanghai，格式化时间必须一致，
            // 不能依赖容器时区（容器默认 UTC，会比真机早 8 小时）
            timeZone = TimeZone.getTimeZone("Asia/Shanghai")
        }
    }

    fun build(uin: String, session: String, nowMs: Long, durationMs: Int = 180_000): ByteArray {
        val dur = durationMs.toString()
        val time = timeFmt.get()!!.format(Date(nowMs))
        return PbEdit.replace(
            template(),
            listOf(
                OLD_SESSION.toByteArray() to session.toByteArray(),
                OLD_UIN.toByteArray() to uin.toByteArray(),
                OLD_TIME.toByteArray() to time.toByteArray(),
                OLD_DURATION.toByteArray() to dur.toByteArray(),
                OLD_DELTA.toByteArray() to dur.toByteArray(),
                OLD_EVENT.toByteArray() to NEW_EVENT.toByteArray(),
            ),
        )
    }

    private fun replaceAll(buf: ByteArray, from: ByteArray, to: ByteArray) {
        require(from.size == to.size) { "替换必须等长" }
        var i = 0
        while (true) {
            val at = indexOf(buf, from, i)
            if (at < 0) return
            System.arraycopy(to, 0, buf, at, to.size)
            i = at + to.size
        }
    }

    private fun indexOf(buf: ByteArray, pat: ByteArray, start: Int): Int {
        outer@ for (i in start..buf.size - pat.size) {
            for (j in pat.indices) if (buf[i + j] != pat[j]) continue@outer
            return i
        }
        return -1
    }

    private fun hexToBytes(s: String): ByteArray {
        val clean = s.filter { it in '0'..'9' || it in 'a'..'f' }
        require(clean.length % 2 == 0)
        return ByteArray(clean.length / 2) { clean.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }
}
