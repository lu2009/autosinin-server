package io.github.luna.autosinin.protocol

/**
 * 会员连续签到（福利签到）的线上 protobuf 编解码。
 *
 * 证据：`docs/WELFARE_SIGN_PROTOCOL.md`、`docs/evidence/welfare-sign/`。
 *
 * 两条命令都走同一层 OIDB 请求包（官方 `i9/b`）：
 *   field1 = command（0x9e99/0x9e9a）
 *   field2 = serviceType（0）
 *   field3 = retcode（请求恒 0）
 *   field4 = 业务 protobuf
 *   field6 = platform（"android 9.3.85"）
 *
 * 业务消息：
 *   0x9e99 请求 `k9/e {1: scene}`
 *   0x9e9a 请求 `k9/c {1: scene, 2: action}`
 *   0x9e99 回包 `k9/f`（CycleSignState）
 *   0x9e9a 回包 `k9/d`（CycleSignResponse）
 */
object WelfareSignProto {

    const val CMD_QUERY: Int = 0x9e99
    const val CMD_SIGN: Int = 0x9e9a

    /** 本页（account_info）固定 scene。 */
    const val SCENE_ACCOUNT_INFO: Int = 2

    /** action：0=今日签到，1=使用补签卡，2=重开周期。 */
    const val ACTION_SIGN: Int = 0
    const val ACTION_MAKEUP: Int = 1
    const val ACTION_RESTART: Int = 2

    // ---- 线上枚举（wire 值，0 基；scene 是 1 基） ----

    /** k9/f field5 */
    object CycleStatus {
        const val CAN_SIGN = 0
        const val SIGNED = 1
        const val BROKEN = 2
    }

    /** k9/a field3 */
    object CycleDayStatus {
        const val LOCKED = 0
        const val SIGNED = 1
        const val RECHECKED = 2
        const val MISSED = 3
        const val TODAY_UNSIGNED = 4
    }

    /**
     * k9/d field1。
     *
     * 官方客户端只显式映射 0/1/2；3 及未知值走默认 FAILED。
     */
    object CycleSignResult {
        const val SUCCESS = 0
        const val ALREADY_SIGNED = 1
        const val BROKEN = 2
    }

    /** k9/b field1 */
    object CycleRewardType {
        const val NONE = 0
        const val GROWTH = 1
        const val Q_COIN = 2
        const val LEVEL_SPEEDUP = 3
        const val VIP_DAYS = 4
    }

    // ---- 数据模型 ----

    data class OidbPkg(
        val command: Int,
        val serviceType: Int,
        val retcode: Int,
        val body: ByteArray,
    )

    data class Reward(
        val type: Int,
        val amount: Int,
        val text: String,
        val icon: String,
    )

    data class DayInfo(
        val day: Int,
        val date: Int,
        val status: Int,
        val reward: Reward?,
    )

    data class SignState(
        val isVip: Boolean,
        val today: Int,
        val cycleStart: Int,
        val currentDay: Int,
        val status: Int,
        val days: List<DayInfo>,
        val cardNum: Int,
        val missedDays: Int,
    )

    data class SignResult(
        val result: Int,
        val currentDay: Int,
        val rewards: List<Reward>,
        val cardNum: Int,
        val cardGranted: Boolean,
        val missedDays: Int,
        val canGuideAutoPay: Boolean,
    )

    // ---- 编码 ----

    fun oidbRequest(command: Int, business: ByteArray, platform: String): ByteArray =
        PbEdit.varintField(1, command.toLong()) +
            PbEdit.varintField(2, 0L) +
            PbEdit.varintField(3, 0L) +
            PbEdit.bytes(4, business) +
            PbEdit.bytes(6, platform.toByteArray(Charsets.UTF_8))

    fun queryBody(scene: Int = SCENE_ACCOUNT_INFO): ByteArray = byteArrayOf(0x08, scene.toByte())

    /** action=0 是 proto3 默认值，官方省略；因此与查询 body 同形。 */
    fun signBody(scene: Int = SCENE_ACCOUNT_INFO): ByteArray = queryBody(scene)

    fun makeupBody(scene: Int = SCENE_ACCOUNT_INFO): ByteArray =
        byteArrayOf(0x08, scene.toByte(), 0x10, ACTION_MAKEUP.toByte())

    fun restartBody(scene: Int = SCENE_ACCOUNT_INFO): ByteArray =
        byteArrayOf(0x08, scene.toByte(), 0x10, ACTION_RESTART.toByte())

    // ---- 解码 ----

    /** 解析 OIDB 包；command 缺失或结构损坏返回 null。 */
    fun parseOidb(bytes: ByteArray): OidbPkg? = runCatching {
        val r = Wire(bytes)
        var command = 0
        var serviceType = 0
        var retcode = 0
        var body: ByteArray? = null
        var sawCommand = false
        while (r.hasMore()) {
            val tag = r.tag()
            val field = tag ushr 3
            val wire = tag and 7
            when {
                field == 1 && wire == 0 -> {
                    command = r.varint().toInt()
                    sawCommand = true
                }
                field == 2 && wire == 0 -> serviceType = r.varint().toInt()
                field == 3 && wire == 0 -> retcode = r.varint().toInt()
                field == 4 && wire == 2 -> body = r.bytes()
                else -> r.skip(wire)
            }
        }
        if (!sawCommand) return@runCatching null
        OidbPkg(command, serviceType, retcode, body ?: ByteArray(0))
    }.getOrNull()

    /** 解析 0x9e99 回包业务体 `k9/f`。 */
    fun parseState(bytes: ByteArray): SignState? = runCatching {
        val r = Wire(bytes)
        var isVip = false
        var today = 0
        var cycleStart = 0
        var currentDay = 0
        var status = 0
        var cardNum = 0
        var missedDays = 0
        var saw = false
        val days = ArrayList<DayInfo>()
        while (r.hasMore()) {
            val tag = r.tag()
            val field = tag ushr 3
            val wire = tag and 7
            when {
                field == 1 && wire == 0 -> { isVip = r.varint() != 0L; saw = true }
                field == 2 && wire == 0 -> { today = r.varint().toInt(); saw = true }
                field == 3 && wire == 0 -> { cycleStart = r.varint().toInt(); saw = true }
                field == 4 && wire == 0 -> { currentDay = r.varint().toInt(); saw = true }
                field == 5 && wire == 0 -> { status = r.varint().toInt(); saw = true }
                field == 6 && wire == 2 -> { parseDay(r.bytes())?.let { days.add(it); saw = true } }
                field == 7 && wire == 0 -> { cardNum = r.varint().toInt(); saw = true }
                field == 8 && wire == 0 -> { missedDays = r.varint().toInt(); saw = true }
                else -> r.skip(wire)
            }
        }
        if (!saw) return@runCatching null
        SignState(isVip, today, cycleStart, currentDay, status, days, cardNum, missedDays)
    }.getOrNull()

    /** 解析 0x9e9a 回包业务体 `k9/d`。 */
    fun parseSignResult(bytes: ByteArray): SignResult? = runCatching {
        val r = Wire(bytes)
        var result = CycleSignResult.SUCCESS
        var currentDay = 0
        var cardNum = 0
        var cardGranted = false
        var missedDays = 0
        var canGuideAutoPay = false
        var saw = false
        val rewards = ArrayList<Reward>()
        while (r.hasMore()) {
            val tag = r.tag()
            val field = tag ushr 3
            val wire = tag and 7
            when {
                field == 1 && wire == 0 -> { result = r.varint().toInt(); saw = true }
                field == 2 && wire == 0 -> { currentDay = r.varint().toInt(); saw = true }
                field == 3 && wire == 2 -> { parseReward(r.bytes())?.let { rewards.add(it); saw = true } }
                field == 4 && wire == 0 -> { cardNum = r.varint().toInt(); saw = true }
                field == 5 && wire == 0 -> { cardGranted = r.varint() != 0L; saw = true }
                field == 6 && wire == 0 -> { missedDays = r.varint().toInt(); saw = true }
                field == 7 && wire == 0 -> { canGuideAutoPay = r.varint() != 0L; saw = true }
                else -> r.skip(wire)
            }
        }
        if (!saw) return@runCatching null
        SignResult(result, currentDay, rewards, cardNum, cardGranted, missedDays, canGuideAutoPay)
    }.getOrNull()

    private fun parseDay(bytes: ByteArray): DayInfo? = runCatching {
        val r = Wire(bytes)
        var day = 0
        var date = 0
        var status = CycleDayStatus.LOCKED
        var reward: Reward? = null
        var saw = false
        while (r.hasMore()) {
            val tag = r.tag()
            val field = tag ushr 3
            val wire = tag and 7
            when {
                field == 1 && wire == 0 -> { day = r.varint().toInt(); saw = true }
                field == 2 && wire == 0 -> { date = r.varint().toInt(); saw = true }
                field == 3 && wire == 0 -> { status = r.varint().toInt(); saw = true }
                field == 4 && wire == 2 -> { reward = parseReward(r.bytes()); saw = true }
                else -> r.skip(wire)
            }
        }
        if (!saw) return@runCatching null
        DayInfo(day, date, status, reward)
    }.getOrNull()

    private fun parseReward(bytes: ByteArray): Reward? {
        if (bytes.isEmpty()) return null
        return runCatching {
            val r = Wire(bytes)
            var type = CycleRewardType.NONE
            var amount = 0
            var text = ""
            var icon = ""
            var saw = false
            while (r.hasMore()) {
                val tag = r.tag()
                val field = tag ushr 3
                val wire = tag and 7
                when {
                    field == 1 && wire == 0 -> { type = r.varint().toInt(); saw = true }
                    field == 2 && wire == 0 -> { amount = r.varint().toInt(); saw = true }
                    field == 3 && wire == 2 -> { text = r.string(); saw = true }
                    field == 4 && wire == 2 -> { icon = r.string(); saw = true }
                    else -> r.skip(wire)
                }
            }
            if (!saw) return@runCatching null
            Reward(type, amount, text, icon)
        }.getOrNull()
    }

    /**
     * 严格 protobuf wire reader。
     *
     * 只依赖标准 wire 规则；未知字段按 wire type 跳过。
     */
    private class Wire(private val buf: ByteArray) {
        private var pos = 0

        fun hasMore(): Boolean = pos < buf.size

        fun tag(): Int {
            val v = varint()
            if (v < 0 || v > Int.MAX_VALUE) throw IllegalArgumentException("bad tag")
            return v.toInt()
        }

        fun varint(): Long {
            var value = 0L
            var shift = 0
            while (shift < 64) {
                if (pos >= buf.size) throw IllegalArgumentException("truncated varint")
                val b = buf[pos++].toInt() and 0xFF
                value = value or ((b and 0x7F).toLong() shl shift)
                if (b and 0x80 == 0) return value
                shift += 7
            }
            throw IllegalArgumentException("varint too long")
        }

        fun bytes(): ByteArray {
            val raw = varint()
            if (raw < 0 || raw > Int.MAX_VALUE) throw IllegalArgumentException("bad length")
            val len = raw.toInt()
            if (len > buf.size - pos) throw IllegalArgumentException("truncated bytes")
            val out = buf.copyOfRange(pos, pos + len)
            pos += len
            return out
        }

        fun string(): String = String(bytes(), Charsets.UTF_8)

        fun skip(wire: Int) {
            when (wire) {
                0 -> varint()
                1 -> advance(8)
                2 -> {
                    val raw = varint()
                    if (raw < 0 || raw > Int.MAX_VALUE) throw IllegalArgumentException("bad length")
                    advance(raw.toInt())
                }
                5 -> advance(4)
                else -> throw IllegalArgumentException("unsupported wire type $wire")
            }
        }

        private fun advance(n: Int) {
            if (n < 0 || n > buf.size - pos) throw IllegalArgumentException("truncated field")
            pos += n
        }
    }
}
