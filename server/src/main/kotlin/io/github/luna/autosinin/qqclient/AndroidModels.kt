package io.github.luna.autosinin.qqclient

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.random.Random

data class WtLoginSdkInfo(
    val sdkBuildTime: Long,
    val sdkVersion: String,
    val miscBitMap: Long,
    val subSigMap: Long,
    val mainSigMap: Long,
)

data class AndroidAppInfo(
    val os: String,
    val kernel: String,
    val vendorOs: String,
    val qua: String,
    val currentVersion: String,
    val ptVersion: String,
    val ssoVersion: Int,
    val packageName: String,
    val apkSignatureMd5: ByteArray,
    val sdkInfo: WtLoginSdkInfo,
    val appId: Int,
    val subAppId: Int,
    val appClientVersion: Int,
) {
    companion object {
        private val APK_SIG_MD5 = byteArrayOf(
            0xa6.toByte(), 0xb7.toByte(), 0x45.toByte(), 0xbf.toByte(),
            0x24.toByte(), 0xa2.toByte(), 0xc2.toByte(), 0x77.toByte(),
            0x52.toByte(), 0x77.toByte(), 0x16.toByte(), 0xf6.toByte(),
            0xf3.toByte(), 0x6e.toByte(), 0xb6.toByte(), 0x8d.toByte(),
        )

        val ANDROID_PAD_9_3_85 = AndroidAppInfo(
            os = "Android",
            kernel = "Linux",
            vendorOs = "android",
            qua = "V1_AND_SQ_9.3.85_16920_YYB_D",
            currentVersion = "9.3.85.fe91e501",
            ptVersion = "9.3.85",
            ssoVersion = 22,
            packageName = "com.tencent.mobileqq",
            apkSignatureMd5 = APK_SIG_MD5,
            sdkInfo = WtLoginSdkInfo(
                sdkBuildTime = 1757058014L,
                sdkVersion = "6.0.0.2591",
                miscBitMap = 150470524L,
                subSigMap = 66560L,
                mainSigMap = 16724722L,
            ),
            appId = 16,
            subAppId = 537402012,
            appClientVersion = 16920,
        )

        val ANDROID_PHONE_9_3_85 = ANDROID_PAD_9_3_85.copy(
            subAppId = 537402551,
        )
    }
}

@Serializable
data class AndroidSessionStore(
    var uin: Long = 0L,
    var password: String = "",
    var uid: String = "",
    var state: State = State(),
    var wloginSigs: WLoginSigs = WLoginSigs(),
    var guid: ByteArray = ByteArray(16),
    var androidId: String = "",
    var qimei: String = "",
    var deviceName: String = "",
) {
    @Serializable
    data class State(
        var tlv104: ByteArray? = null,
        var tlv174: ByteArray? = null,
        var tlv547: ByteArray? = null,
    )

    @Serializable
    data class WLoginSigs(
        var a1: ByteArray = ByteArray(0),
        var a1Key: ByteArray = Random.nextBytes(16),
        var a2: ByteArray = ByteArray(0),
        var a2Key: ByteArray = ByteArray(0),
        var d2: ByteArray = ByteArray(0),
        var d2Key: ByteArray = ByteArray(0),
        var st: ByteArray = ByteArray(0),
        var stKey: ByteArray = ByteArray(0),
        var stWeb: ByteArray = ByteArray(0),
        var sKey: ByteArray = ByteArray(0),
        var superKey: ByteArray = ByteArray(0),
        var ksid: ByteArray = ByteArray(0),
        var noPicSig: ByteArray = ByteArray(0),
        var tgtgtKey: ByteArray = Random.nextBytes(16),
        var wtSessionTicket: ByteArray = ByteArray(0),
        var wtSessionTicketKey: ByteArray = ByteArray(0),
        var psKey: MutableMap<String, String> = mutableMapOf(),
    )

    fun clear() {
        state = State()
        wloginSigs = WLoginSigs()
    }

    fun toJson(): String = JSON.encodeToString(serializer(), this)

    companion object {
        private val JSON = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            prettyPrint = true
        }

        fun fromJson(raw: String): AndroidSessionStore = JSON.decodeFromString(serializer(), raw)

        fun empty(uin: Long, password: String, androidId: String, deviceName: String): AndroidSessionStore =
            AndroidSessionStore(
                uin = uin,
                password = password,
                guid = Random.nextBytes(16),
                androidId = androidId,
                qimei = Random.nextBytes(18).joinToString("") { "%02x".format(it) },
                deviceName = deviceName,
            )
    }
}

data class SignValue(
    val sign: ByteArray,
    val token: ByteArray,
    val extra: ByteArray,
)

class QsignClient(
    private val baseUrl: String,
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()
    private val jsonType = "application/json; charset=utf-8".toMediaType()
    private val normalizedUrl = baseUrl.trimEnd('/')

    fun sign(
        uin: Long,
        cmd: String,
        seq: Int,
        buffer: ByteArray,
        guid: String,
        version: String,
        qua: String,
    ): SignValue {
        val body = JSONObject()
            .put("uin", uin)
            .put("cmd", cmd)
            .put("seq", seq)
            .put("buffer", buffer.toHex())
            .put("guid", guid)
            .put("version", version)
            .put("qua", qua)
            .toString()
        val data = postJson("/sign", body).getJSONObject("data")
        return SignValue(
            sign = data.optString("sign", "").hexToBytes(),
            token = data.optString("token", "").hexToBytes(),
            extra = data.optString("extra", "").hexToBytes(),
        )
    }

    fun energy(
        uin: Long,
        data: String,
        guid: String,
        ver: String,
        version: String,
        qua: String,
    ): ByteArray {
        val body = JSONObject()
            .put("uin", uin)
            .put("data", data)
            .put("guid", guid)
            .put("ver", ver)
            .put("version", version)
            .put("qua", qua)
            .toString()
        return postJson("/energy", body).optString("data", "").hexToBytes()
    }

    fun getDebugXwid(
        uin: Long,
        data: String,
        guid: String,
        version: String,
        qua: String,
    ): ByteArray {
        val body = JSONObject()
            .put("uin", uin)
            .put("data", data)
            .put("guid", guid)
            .put("version", version)
            .put("qua", qua)
            .toString()
        return postJson("/get_tlv553", body).optString("data", "").hexToBytes()
    }

    private fun postJson(path: String, jsonBody: String): JSONObject {
        val req = Request.Builder()
            .url(normalizedUrl + path)
            .post(jsonBody.toRequestBody(jsonType))
            .build()
        client.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            check(resp.isSuccessful) { "qsign $path HTTP ${resp.code}: $text" }
            val obj = JSONObject(text)
            check(obj.optInt("code", -1) == 0) { "qsign $path error: $text" }
            return obj
        }
    }
}

internal fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

internal fun String.hexToBytes(): ByteArray {
    val clean = trim()
    if (clean.isEmpty()) return ByteArray(0)
    require(clean.length % 2 == 0) { "Invalid hex length: ${clean.length}" }
    return ByteArray(clean.length / 2) { i ->
        clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
    }
}
