package io.github.luna.autosinin.qqclient

import kotlinx.serialization.Serializable
import kotlin.random.Random

/**
 * 账号绑定的设备档案。
 *
 * 登录 TLV（0xd1 / 0x16e / 设备报告 0x52d）与 qsign 签名器（FekitEmulator 的 SYSTEM_PROPS）
 * 必须使用同一组取值，否则同一次登录会向腾讯声明两台不同的设备。
 *
 * 默认值取自实际抓包的真机：Redmi K50（rubens）。
 */
@Serializable
data class DeviceProfile(
    val model: String = "22041211AC",
    val brand: String = "Redmi",
    val manufacturer: String = "Xiaomi",
    val codename: String = "rubens",
    val androidVersion: String = "14",
    val incremental: String = "OS2.0.5.0.ULNCNXM",
    val kernelVersion: String = "Linux version 5.10.198-android14",
    val fingerprint: String = "Redmi/rubens/rubens:14/UP1A.231005.007/OS2.0.5.0.ULNCNXM:user/release-keys",
    val carrier: String = "中国电信",
    val network: String = "wifi",
) {
    companion object {
        /** 每个账号独立的 androidId（16 位 hex），不再共用同一个常量。 */
        fun freshAndroidId(): String = Random.nextBytes(8).joinToString("") { "%02x".format(it) }
    }
}
