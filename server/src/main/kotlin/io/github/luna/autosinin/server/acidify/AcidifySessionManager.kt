package io.github.luna.autosinin.server.acidify

import io.github.luna.autosinin.server.core.AccountInfo
import io.github.luna.autosinin.server.core.AccountLedger
import io.github.luna.autosinin.server.core.ServerLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import okhttp3.OkHttpClient
import okhttp3.Request
import org.ntqqrev.acidify.Bot
import org.ntqqrev.acidify.common.AppInfo
import org.ntqqrev.acidify.common.LagrangeUrlSignProvider
import org.ntqqrev.acidify.common.SessionStore
import org.ntqqrev.acidify.common.SignProvider
import org.ntqqrev.acidify.common.UrlSignProvider
import org.ntqqrev.acidify.event.BotOfflineEvent
import org.ntqqrev.acidify.event.QRCodeGeneratedEvent
import org.ntqqrev.acidify.event.SessionStoreUpdatedEvent
import org.ntqqrev.acidify.logging.LogHandler
import org.ntqqrev.acidify.logging.LogLevel
import org.ntqqrev.acidify.login
import org.ntqqrev.acidify.qrCodeLogin
import org.ntqqrev.acidify.renewSession
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
 * 基于 org.ntqqrev:acidify-core:1.7.0 的多账号托管与扫码登录管理器。
 */
class AcidifySessionManager(
    private val ledger: AccountLedger,
    private val sessionDir: File = File("data/sessions"),
    private val defaultSignUrl: String = "",
    private val defaultSignToken: String = "",
    private val httpProxy: String = "",
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val bots = ConcurrentHashMap<String, Bot>()
    private val qrSessions = ConcurrentHashMap<String, QrLoginStateDto>()
    private val healthClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    private val logHandler = LogHandler { level, tag, message, throwable ->
        val suffix = throwable?.let { " (${it.javaClass.simpleName}: ${it.message})" }.orEmpty()
        if (level.ordinal >= LogLevel.WARN.ordinal) {
            ServerLog.w("ACIDIFY/$tag", "$message$suffix")
        } else if (level.ordinal >= LogLevel.INFO.ordinal) {
            ServerLog.i("ACIDIFY/$tag", "$message$suffix")
        }
    }

    init {
        sessionDir.mkdirs()
    }

    fun getBot(uin: String): Bot? = bots[uin]

    fun listOnlineUins(): List<String> = bots.entries.filter { it.value.isLoggedIn }.map { it.key }.sorted()

    /** 启动时自动从 sessionDir 加载已保存的 SessionStore 并尝试重连上线。 */
    fun restoreAllSessions() {
        if (defaultSignUrl.isBlank()) {
            ServerLog.w(TAG, "AUTOSININ_SIGN_URL not set; skip restoring acidify sessions")
            return
        }
        val files = sessionDir.listFiles { f -> f.isFile && f.name.endsWith(".session.json") }.orEmpty()
        for (file in files) {
            runCatching {
                val store = SessionStore.fromJson(file.readText(Charsets.UTF_8))
                if (store.uin <= 0L) return@runCatching
                val uin = store.uin.toString()
                scope.launch {
                    restoreSingle(uin, store)
                }
            }.onFailure {
                ServerLog.w(TAG, "failed to load session ${file.name}: ${it.message}")
            }
        }
    }

    private suspend fun restoreSingle(uin: String, store: SessionStore) {
        runCatching {
            val signProvider = buildSignProvider(defaultSignUrl, defaultSignToken, store)
            val appInfo = resolveAppInfo(defaultSignUrl)
            val bot = Bot(
                appInfo = appInfo,
                sessionStore = store,
                signProvider = signProvider,
                scope = scope,
                minLogLevel = LogLevel.INFO,
                logHandler = logHandler,
            )
            attachBotListeners(bot)
            bot.renewSession()
            bot.login()
            bots[uin] = bot
            ledger.upsertAccount(AccountInfo(uin = uin, nickname = uin, enabled = true))
            ServerLog.i(TAG, "uin=$uin session restored and logged in")
        }.onFailure {
            ServerLog.w(TAG, "uin=$uin restore failed: ${it.javaClass.simpleName}: ${it.message}")
        }
    }

    /** 发起一次扫码登录；立即返回 qrId，二维码生成后可通过 getQrState(qrId) 获取。 */
    suspend fun startQrLogin(signUrlOverride: String? = null, signTokenOverride: String? = null): QrLoginStateDto {
        val signUrl = signUrlOverride?.takeIf { it.isNotBlank() } ?: defaultSignUrl
        val signToken = signTokenOverride?.takeIf { it.isNotBlank() } ?: defaultSignToken
        if (signUrl.isBlank()) {
            return QrLoginStateDto(
                qrId = "",
                status = "ERROR",
                error = "未配置 AUTOSININ_SIGN_URL",
            )
        }

        val qrId = UUID.randomUUID().toString()
        val initial = QrLoginStateDto(qrId = qrId, status = "WAITING_QR")
        qrSessions[qrId] = initial

        val store = SessionStore.empty()
        val appInfo = resolveAppInfo(signUrl)
        val signProvider = buildSignProvider(signUrl, signToken, store)
        val bot = Bot(
            appInfo = appInfo,
            sessionStore = store,
            signProvider = signProvider,
            scope = scope,
            minLogLevel = LogLevel.INFO,
            logHandler = logHandler,
        )

        val qrCollector = scope.launch {
            bot.eventFlow.filterIsInstance<QRCodeGeneratedEvent>().collect { ev ->
                val b64 = Base64.getEncoder().encodeToString(ev.png)
                qrSessions[qrId] = QrLoginStateDto(
                    qrId = qrId,
                    status = "PENDING_SCAN",
                    url = ev.url,
                    pngBase64 = b64,
                )
                ServerLog.i(TAG, "qrId=$qrId QR generated url=${ev.url.take(64)}...")
            }
        }

        attachBotListeners(bot)

        scope.launch {
            runCatching {
                bot.qrCodeLogin()
                val uin = bot.uin.toString()
                bots[uin] = bot
                saveSession(uin, bot.sessionStore)
                ledger.upsertAccount(AccountInfo(uin = uin, nickname = uin, enabled = true))
                qrSessions[qrId] = qrSessions[qrId]?.copy(
                    status = "LOGGED_IN",
                    uin = uin,
                    updatedAt = System.currentTimeMillis(),
                ) ?: QrLoginStateDto(qrId = qrId, status = "LOGGED_IN", uin = uin)
                ServerLog.i(TAG, "qrId=$qrId logged in uin=$uin")
            }.onFailure { err ->
                qrSessions[qrId] = qrSessions[qrId]?.copy(
                    status = "ERROR",
                    error = "${err.javaClass.simpleName}: ${err.message}",
                    updatedAt = System.currentTimeMillis(),
                ) ?: QrLoginStateDto(qrId = qrId, status = "ERROR", error = err.message.orEmpty())
                ServerLog.w(TAG, "qrId=$qrId login failed: ${err.message}")
            }
            qrCollector.cancel()
        }

        return initial
    }

    fun getQrState(qrId: String): QrLoginStateDto? = qrSessions[qrId]

    fun checkSignServerHealth(): SignHealthDto {
        if (defaultSignUrl.isBlank()) {
            return SignHealthDto(configured = false, url = "", healthy = false, detail = "AUTOSININ_SIGN_URL not set")
        }
        return runCatching {
            val req = Request.Builder().url(defaultSignUrl).get().build()
            healthClient.newCall(req).execute().use { resp ->
                SignHealthDto(
                    configured = true,
                    url = defaultSignUrl,
                    healthy = resp.code in 200..499,
                    detail = "http=${resp.code}",
                )
            }
        }.getOrElse {
            SignHealthDto(
                configured = true,
                url = defaultSignUrl,
                healthy = false,
                detail = "${it.javaClass.simpleName}: ${it.message}",
            )
        }
    }

    private fun attachBotListeners(bot: Bot) {
        scope.launch {
            bot.eventFlow.filterIsInstance<SessionStoreUpdatedEvent>().collect { ev ->
                val uin = ev.sessionStore.uin
                if (uin > 0L) {
                    saveSession(uin.toString(), ev.sessionStore)
                }
            }
        }
        scope.launch {
            bot.eventFlow.filterIsInstance<BotOfflineEvent>().collect { ev ->
                ServerLog.w(TAG, "uin=${bot.uin} offline: $ev")
            }
        }
    }

    private fun saveSession(uin: String, store: SessionStore) {
        val file = File(sessionDir, "$uin.session.json")
        file.writeText(store.toJson(), Charsets.UTF_8)
    }

    private suspend fun resolveAppInfo(signUrl: String): AppInfo {
        return UrlSignProvider(signUrl, httpProxy).getAppInfo()
            ?: error("Sign server did not return AppInfo: $signUrl")
    }

    private fun buildSignProvider(signUrl: String, signToken: String, store: SessionStore): SignProvider {
        return if (signToken.isNotBlank()) {
            LagrangeUrlSignProvider(
                url = signUrl,
                token = signToken,
                uin = store.uin,
                guid = store.guid.joinToString("") { "%02x".format(it) },
                qua = "",
                httpProxy = httpProxy,
            )
        } else {
            UrlSignProvider(signUrl, httpProxy)
        }
    }

    fun shutdown() {
        scope.cancel()
    }

    companion object {
        private const val TAG = "ACIDIFY"
    }
}
