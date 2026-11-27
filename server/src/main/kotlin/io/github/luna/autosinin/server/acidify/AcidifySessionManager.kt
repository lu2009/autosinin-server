package io.github.luna.autosinin.server.acidify

import io.github.luna.autosinin.qqclient.AndroidAppInfo
import io.github.luna.autosinin.qqclient.AndroidSessionStore
import io.github.luna.autosinin.qqclient.DeviceProfile
import io.github.luna.autosinin.qqclient.QsignClient
import io.github.luna.autosinin.qqclient.SsoClient
import io.github.luna.autosinin.qqclient.WtLoginClient
import io.github.luna.autosinin.server.core.AccountInfo
import io.github.luna.autosinin.server.core.AccountLedger
import io.github.luna.autosinin.server.core.ServerLog
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

@Serializable
data class QrLoginStateDto(
    val qrId: String,
    val status: String,
    val url: String = "",
    val pngBase64: String = "",
    val uin: String = "",
    val error: String = "",
    val updatedAt: Long = System.currentTimeMillis(),
)

@Serializable
data class SignHealthDto(
    val configured: Boolean,
    val url: String,
    val healthy: Boolean,
    val detail: String,
)

/**
 * 基于自研 Android SSO + WtLogin 客户端的多账号会话管理器（零 acidify-core 依赖）。
 */
class AcidifySessionManager(
    private val ledger: AccountLedger,
    private val sessionDir: File = File("data/sessions"),
    private val defaultSignUrl: String = "",
    @Suppress("unused") private val defaultSignToken: String = "",
    @Suppress("unused") private val httpProxy: String = "",
    private val protocolMode: String = "android",
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val clients = ConcurrentHashMap<String, SsoClient>()
    private val qrSessions = ConcurrentHashMap<String, QrLoginStateDto>()
    private val pendingVerifications = ConcurrentHashMap<String, CompletableDeferred<String>>()
    private val healthClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    init {
        sessionDir.mkdirs()
    }

    fun getBot(uin: String): SsoClient? = clients[uin]

    fun listOnlineUins(): List<String> = clients.entries.filter { it.value.isOnline }.map { it.key }.sorted()

    /** 启动时自动从 sessionDir 加载已保存的 AndroidSessionStore 并尝试重连上线。 */
    fun restoreAllSessions() {
        if (defaultSignUrl.isBlank()) {
            ServerLog.w(TAG, "AUTOSININ_SIGN_URL not set; skip restoring sessions")
            return
        }
        val androidFiles = sessionDir.listFiles { f -> f.isFile && f.name.endsWith(".android.session.json") }.orEmpty()
        for (file in androidFiles) {
            runCatching {
                val store = AndroidSessionStore.fromJson(file.readText(Charsets.UTF_8))
                if (store.uin <= 0L) return@runCatching
                val uin = store.uin.toString()
                scope.launch {
                    restoreSingleAndroid(uin, store)
                }
            }.onFailure {
                ServerLog.w(TAG, "failed to load android session ${file.name}: ${it.message}")
            }
        }
    }

    private suspend fun restoreSingleAndroid(uin: String, store: AndroidSessionStore) {
        runCatching {
            if (store.deviceName.startsWith("Lagrange-") || store.deviceName == "22041211AC") {
                store.deviceName = REAL_DEVICE_NAME
            }
            val sso = buildSsoClient(uin, store, defaultSignUrl)
            val wt = WtLoginClient(sso)
            wt.login(
                onRequireCaptchaTicket = { url -> error("Captcha required: $url") },
                onRequireSmsCode = { _, _, _ -> error("SMS verification required") },
            )
            clients[uin]?.close()
            clients[uin] = sso
            saveAndroidSession(uin, store)
            ledger.upsertAccount(AccountInfo(uin = uin, nickname = uin, enabled = true))
            ServerLog.i(TAG, "uin=$uin android session restored and logged in")
        }.onFailure {
            ServerLog.w(TAG, "uin=$uin android restore failed: ${it.javaClass.simpleName}: ${it.message}")
        }
    }

    fun startQrLogin(
        signUrlOverride: String? = null,
        @Suppress("unused") signTokenOverride: String? = null,
    ): QrLoginStateDto {
        val signUrl = signUrlOverride?.takeIf { it.isNotBlank() } ?: defaultSignUrl
        if (signUrl.isBlank()) {
            return QrLoginStateDto(qrId = "", status = "ERROR", error = "未配置 AUTOSININ_SIGN_URL")
        }
        val qrId = "qr-${UUID.randomUUID().toString().take(8)}"
        val initial = QrLoginStateDto(qrId = qrId, status = "LOGGING_IN")
        qrSessions[qrId] = initial

        val store = AndroidSessionStore.empty(0L, "", DeviceProfile.freshAndroidId(), REAL_DEVICE_NAME)
        val sso = buildSsoClient("0", store, signUrl)
        val wt = WtLoginClient(sso)

        scope.launch {
            runCatching {
                val qr = wt.fetchQrCode()
                val pngB64 = Base64.getEncoder().encodeToString(qr.qrPng)
                qrSessions[qrId] = QrLoginStateDto(
                    qrId = qrId,
                    status = "WAITING_FOR_SCAN",
                    url = qr.qrUrl,
                    pngBase64 = pngB64,
                )
                ServerLog.i(TAG, "qrId=$qrId QR fetched url=${qr.qrUrl}")

                var confirmed: WtLoginClient.QrCodePollResult.Confirmed? = null
                repeat(60) {
                    delay(2000L)
                    when (val poll = wt.queryQrCodeState(qr.qrSig)) {
                        is WtLoginClient.QrCodePollResult.Confirmed -> {
                            confirmed = poll
                            return@repeat
                        }
                        WtLoginClient.QrCodePollResult.WaitingForScan -> {
                            qrSessions[qrId] = QrLoginStateDto(
                                qrId = qrId,
                                status = "WAITING_FOR_SCAN",
                                url = qr.qrUrl,
                                pngBase64 = pngB64,
                            )
                        }
                        WtLoginClient.QrCodePollResult.WaitingForConfirm -> {
                            qrSessions[qrId] = QrLoginStateDto(
                                qrId = qrId,
                                status = "WAITING_FOR_CONFIRMATION",
                                url = qr.qrUrl,
                                pngBase64 = pngB64,
                            )
                        }
                        WtLoginClient.QrCodePollResult.Expired -> {
                            qrSessions[qrId] = QrLoginStateDto(qrId = qrId, status = "EXPIRED", error = "二维码已过期")
                            sso.close()
                            return@launch
                        }
                        WtLoginClient.QrCodePollResult.Cancelled -> {
                            qrSessions[qrId] = QrLoginStateDto(qrId = qrId, status = "CANCELLED", error = "用户取消扫码")
                            sso.close()
                            return@launch
                        }
                        is WtLoginClient.QrCodePollResult.Unknown -> Unit
                    }
                }

                val res = confirmed ?: error("二维码轮询超时")
                wt.completeQrLogin(res)
                val uinStr = res.uin.toString()
                clients[uinStr]?.close()
                clients[uinStr] = sso
                saveAndroidSession(uinStr, store)
                ledger.upsertAccount(AccountInfo(uin = uinStr, nickname = uinStr, enabled = true))
                qrSessions[qrId] = QrLoginStateDto(qrId = qrId, status = "LOGGED_IN", uin = uinStr)
                ServerLog.i(TAG, "uin=$uinStr android QR login succeeded")
            }.onFailure { err ->
                sso.close()
                ServerLog.w(TAG, "qrId=$qrId android QR login failed: ${err.message}")
                qrSessions[qrId] = QrLoginStateDto(
                    qrId = qrId,
                    status = "ERROR",
                    error = "${err.javaClass.simpleName}: ${err.message}",
                )
            }
        }
        return initial
    }

    /** 发起一次 Android 协议账密登录（配合自建 :qsign-server），支持异步滑块/短信验证。 */
    fun startAndroidLogin(uin: Long, password: String, signUrlOverride: String? = null): QrLoginStateDto {
        val signUrl = signUrlOverride?.takeIf { it.isNotBlank() } ?: defaultSignUrl
        if (signUrl.isBlank()) {
            return QrLoginStateDto(qrId = "", status = "ERROR", error = "未配置 AUTOSININ_SIGN_URL")
        }
        val uinStr = uin.toString()
        val loginId = "android-$uinStr-${UUID.randomUUID().toString().take(8)}"
        val initial = QrLoginStateDto(qrId = loginId, status = "LOGGING_IN", uin = uinStr)
        qrSessions[loginId] = initial

        val store = loadOrCreateStoreWithStableDevice(uin, password)
        val sso = buildSsoClient(uinStr, store, signUrl)
        val wt = WtLoginClient(sso)

        scope.launch {
            runCatching {
                wt.login(
                    onRequireCaptchaTicket = { url ->
                        val deferred = CompletableDeferred<String>()
                        pendingVerifications[loginId] = deferred
                        qrSessions[loginId] = QrLoginStateDto(
                            qrId = loginId,
                            status = "NEED_CAPTCHA",
                            url = url,
                            uin = uinStr,
                        )
                        ServerLog.i(TAG, "loginId=$loginId captcha required: $url")
                        deferred.await()
                    },
                    onRequireSmsCode = { countryCode, phone, tips ->
                        val deferred = CompletableDeferred<String>()
                        pendingVerifications[loginId] = deferred
                        qrSessions[loginId] = QrLoginStateDto(
                            qrId = loginId,
                            status = "NEED_SMS",
                            url = "$countryCode $phone $tips".trim(),
                            uin = uinStr,
                        )
                        ServerLog.i(TAG, "loginId=$loginId sms required: $countryCode $phone")
                        deferred.await()
                    },
                )
                clients[uinStr]?.close()
                clients[uinStr] = sso
                saveAndroidSession(uinStr, store)
                ledger.upsertAccount(AccountInfo(uin = uinStr, nickname = uinStr, enabled = true))
                qrSessions[loginId] = QrLoginStateDto(qrId = loginId, status = "LOGGED_IN", uin = uinStr)
                ServerLog.i(TAG, "uin=$uinStr android login succeeded (mode=$protocolMode)")
            }.onFailure { err ->
                sso.close()
                pendingVerifications.remove(loginId)?.cancel()
                ServerLog.w(TAG, "uin=$uinStr android login failed: ${err.message}")
                qrSessions[loginId] = QrLoginStateDto(
                    qrId = loginId,
                    status = "ERROR",
                    uin = uinStr,
                    error = "${err.javaClass.simpleName}: ${err.message}",
                )
            }
        }

        return initial
    }

    fun submitVerification(loginId: String, value: String): Boolean {
        val deferred = pendingVerifications.remove(loginId) ?: return false
        return deferred.complete(value.trim())
    }

    fun getQrState(qrId: String): QrLoginStateDto? = qrSessions[qrId]

    fun checkSignServerHealth(): SignHealthDto {
        if (defaultSignUrl.isBlank()) {
            return SignHealthDto(configured = false, url = "", healthy = false, detail = "未配置 AUTOSININ_SIGN_URL")
        }
        val target = defaultSignUrl.trimEnd('/') + "/health"
        return runCatching {
            val req = Request.Builder().url(target).get().build()
            healthClient.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                SignHealthDto(
                    configured = true,
                    url = defaultSignUrl,
                    healthy = resp.isSuccessful,
                    detail = if (resp.isSuccessful) body.take(120) else "HTTP ${resp.code}",
                )
            }
        }.getOrElse {
            SignHealthDto(configured = true, url = defaultSignUrl, healthy = false, detail = "${it.javaClass.simpleName}: ${it.message}")
        }
    }

    private fun buildSsoClient(uin: String, store: AndroidSessionStore, signUrl: String): SsoClient {
        return SsoClient(
            appInfo = resolveAndroidAppInfo(),
            sessionStore = store,
            signClient = QsignClient(signUrl),
            scope = scope,
            onOffline = { reason ->
                ServerLog.w(TAG, "uin=$uin went offline: $reason")
            },
        )
    }

    private fun saveAndroidSession(uin: String, store: AndroidSessionStore) {
        sessionDir.mkdirs()
        val file = File(sessionDir, "$uin.android.session.json")
        file.writeText(store.toJson(), Charsets.UTF_8)
        saveDeviceProfile(uin, store)
    }

    @Serializable
    private data class StoredDeviceProfile(
        val guidHex: String,
        val androidId: String,
        val qimei: String,
        val deviceName: String,
    )

    private fun deviceDir(): File = File(sessionDir.parentFile ?: File("data"), "devices")

    private fun saveDeviceProfile(uin: String, store: AndroidSessionStore) {
        runCatching {
            val dir = deviceDir().apply { mkdirs() }
            val profile = StoredDeviceProfile(
                guidHex = store.guid.joinToString("") { "%02x".format(it) },
                androidId = store.androidId,
                qimei = store.qimei,
                deviceName = store.deviceName,
            )
            File(dir, "$uin.device.json").writeText(DEVICE_JSON.encodeToString(StoredDeviceProfile.serializer(), profile), Charsets.UTF_8)
        }
    }

    private fun loadOrCreateStoreWithStableDevice(uin: Long, password: String): AndroidSessionStore {
        val uinStr = uin.toString()
        val sessionFile = File(sessionDir, "$uinStr.android.session.json")
        if (sessionFile.isFile) {
            runCatching {
                val existing = AndroidSessionStore.fromJson(sessionFile.readText(Charsets.UTF_8)).apply {
                    this.uin = uin
                    this.password = password
                    if (this.deviceName.startsWith("Lagrange-") || this.deviceName == "22041211AC") {
                        this.deviceName = REAL_DEVICE_NAME
                    }
                    this.state = AndroidSessionStore.State()
                }
                saveDeviceProfile(uinStr, existing)
                return existing
            }
        }
        val devFile = File(deviceDir(), "$uinStr.device.json")
        if (devFile.isFile) {
            runCatching {
                val prof = DEVICE_JSON.decodeFromString(StoredDeviceProfile.serializer(), devFile.readText(Charsets.UTF_8))
                val guidBytes = prof.guidHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
                if (guidBytes.size == 16) {
                    return AndroidSessionStore(
                        uin = uin,
                        password = password,
                        guid = guidBytes,
                        androidId = prof.androidId,
                        qimei = prof.qimei,
                        deviceName = prof.deviceName,
                    )
                }
            }
        }
        val fresh = AndroidSessionStore.empty(uin, password, DeviceProfile.freshAndroidId(), REAL_DEVICE_NAME)
        saveDeviceProfile(uinStr, fresh)
        return fresh
    }

    fun shutdown() {
        clients.values.forEach { it.close() }
        clients.clear()
        scope.cancel()
    }

    private fun resolveAndroidAppInfo(): AndroidAppInfo =
        if (protocolMode == "android_phone") AndroidAppInfo.ANDROID_PHONE_9_3_85 else AndroidAppInfo.ANDROID_PAD_9_3_85

    companion object {
        private const val TAG = "SSO_MGR"
        private const val REAL_DEVICE_NAME = "24091RPADC"
        private val DEVICE_JSON = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }
    }
}
