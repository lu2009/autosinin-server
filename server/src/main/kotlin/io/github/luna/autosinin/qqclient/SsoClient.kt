package io.github.luna.autosinin.qqclient

import io.github.luna.autosinin.protocol.PbEdit
import io.github.luna.autosinin.server.core.ServerLog
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.Inflater
import kotlin.random.Random

enum class RequestType { D2Auth, Simple }

enum class EncryptType(val underlying: Byte) {
    None(0),
    WithD2Key(1),
    WithEmptyKey(2),
}

data class SsoResponse(
    val retCode: Int,
    val command: String,
    val response: ByteArray,
    val sequence: Int,
    val extra: String = "",
)

/**
 * Self-hosted lightweight Android QQ SSO TCP client (replaces acidify-core PacketContext).
 */
class SsoClient(
    val appInfo: AndroidAppInfo,
    val sessionStore: AndroidSessionStore,
    val signClient: QsignClient,
    private val scope: CoroutineScope,
    private val onOffline: suspend (String) -> Unit = {},
) {
    private val seqGen = AtomicInteger(Random.nextInt(100_000, 900_000))
    private val pending = ConcurrentHashMap<Int, CompletableDeferred<SsoResponse>>()
    private val writeMutex = Mutex()
    private val connMutex = Mutex()

    @Volatile
    private var socket: Socket? = null
    @Volatile
    private var readJob: Job? = null
    @Volatile
    private var heartbeatJob: Job? = null
    @Volatile
    var isOnline: Boolean = false
        internal set

    fun nextSeq(): Int = seqGen.incrementAndGet()

    suspend fun ensureConnected() {
        val existing = socket
        if (existing != null && existing.isConnected && !existing.isClosed && readJob?.isActive == true) {
            return
        }
        connMutex.withLock {
            val again = socket
            if (again != null && again.isConnected && !again.isClosed && readJob?.isActive == true) {
                return
            }
            closeSocketOnly()
            val sock = Socket()
            sock.keepAlive = true
            sock.tcpNoDelay = true
            withContext(Dispatchers.IO) {
                sock.connect(InetSocketAddress(SSO_HOST, SSO_PORT), 10_000)
            }
            socket = sock
            readJob = scope.launch(Dispatchers.IO) {
                readLoop(sock)
            }
            ServerLog.i(TAG, "uin=${sessionStore.uin} connected to $SSO_HOST:$SSO_PORT")
        }
    }

    private suspend fun readLoop(sock: Socket) {
        try {
            val input = DataInputStream(sock.getInputStream())
            while (scope.isActive && !sock.isClosed) {
                val totalLen = input.readInt()
                if (totalLen < 4 || totalLen > 64 * 1024 * 1024) {
                    throw IOException("Invalid SSO frame length: $totalLen")
                }
                val frame = ByteArray(totalLen - 4)
                input.readFully(frame)
                runCatching {
                    val rsp = decodeSsoFrame(frame, sessionStore.wloginSigs.d2Key)
                    val waiter = pending.remove(rsp.sequence)
                    waiter?.complete(rsp)
                }.onFailure { err ->
                    ServerLog.w(TAG, "uin=${sessionStore.uin} decode SSO frame failed (len=${frame.size}): ${err.javaClass.simpleName}: ${err.message}")
                }
            }
        } catch (e: EOFException) {
            ServerLog.w(TAG, "uin=${sessionStore.uin} SSO connection EOF")
        } catch (e: Exception) {
            if (!sock.isClosed) {
                ServerLog.w(TAG, "uin=${sessionStore.uin} SSO readLoop error: ${e.message}")
            }
        } finally {
            isOnline = false
            val err = IOException("SSO connection closed")
            pending.values.forEach { it.completeExceptionally(err) }
            pending.clear()
            runCatching { sock.close() }
        }
    }

    suspend fun sendPacket(
        command: String,
        payload: ByteArray,
        timeoutMs: Long = 15_000L,
        requestType: RequestType = RequestType.D2Auth,
        encryptType: EncryptType = EncryptType.WithD2Key,
        ssoReservedMsgType: Int = 32,
    ): SsoResponse {
        ensureConnected()
        val seq = nextSeq()
        val secureInfo = if (command in SIGN_REQUIRED_COMMANDS) {
            val sig = withContext(Dispatchers.IO) {
                signClient.sign(
                    uin = sessionStore.uin,
                    cmd = command,
                    seq = seq,
                    buffer = payload,
                    guid = sessionStore.guid.toHex(),
                    version = appInfo.ptVersion,
                    qua = appInfo.qua,
                )
            }
            sig
        } else {
            null
        }

        val frame = encodeSsoFrame(
            command = command,
            payload = payload,
            sequence = seq,
            requestType = requestType,
            encryptType = encryptType,
            msgType = ssoReservedMsgType,
            signValue = secureInfo,
        )

        val deferred = CompletableDeferred<SsoResponse>()
        pending[seq] = deferred
        try {
            writeMutex.withLock {
                val sock = socket ?: throw IOException("SSO socket not connected")
                withContext(Dispatchers.IO) {
                    val out = sock.getOutputStream()
                    out.write(frame)
                    out.flush()
                }
            }
            return withTimeout(timeoutMs) {
                deferred.await()
            }
        } finally {
            withContext(NonCancellable) {
                pending.remove(seq, deferred)
            }
        }
    }

    fun startHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch(Dispatchers.IO) {
            var count = 0
            while (isActive && isOnline) {
                delay(10_000L)
                count++
                try {
                    val aliveRsp = sendPacket(
                        command = "Heartbeat.Alive",
                        payload = byteArrayOf(0, 0, 0, 4),
                        requestType = RequestType.Simple,
                        encryptType = EncryptType.None,
                    )
                    if (aliveRsp.retCode != 0) {
                        isOnline = false
                        onOffline("Heartbeat.Alive retCode=${aliveRsp.retCode}")
                        break
                    }
                    if (count % 27 == 0) {
                        count = 0
                        val hbPayload = PbEdit.varintField(1, 1L)
                        val hbRsp = sendPacket(
                            command = "trpc.qq_new_tech.status_svc.StatusService.SsoHeartBeat",
                            payload = hbPayload,
                        )
                        if (hbRsp.retCode != 0) {
                            isOnline = false
                            onOffline("SsoHeartBeat retCode=${hbRsp.retCode}")
                            break
                        }
                    }
                } catch (e: Exception) {
                    ServerLog.w(TAG, "uin=${sessionStore.uin} heartbeat error: ${e.message}")
                    if (socket?.isClosed != false) {
                        isOnline = false
                        onOffline("heartbeat socket closed: ${e.message}")
                        break
                    }
                }
            }
        }
    }

    fun close() {
        isOnline = false
        heartbeatJob?.cancel()
        closeSocketOnly()
    }

    private fun closeSocketOnly() {
        readJob?.cancel()
        runCatching { socket?.close() }
        socket = null
    }

    private fun buildSsoReserved(msgType: Int, signValue: SignValue?): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(PbEdit.bytes(15, generateTrace().toByteArray(Charsets.UTF_8)))
        if (sessionStore.uid.isNotEmpty()) {
            out.write(PbEdit.bytes(16, sessionStore.uid.toByteArray(Charsets.UTF_8)))
        }
        out.write(PbEdit.varintField(21, msgType.toLong()))
        if (signValue != null) {
            val sec = ByteArrayOutputStream().apply {
                if (signValue.sign.isNotEmpty()) write(PbEdit.bytes(1, signValue.sign))
                if (signValue.token.isNotEmpty()) write(PbEdit.bytes(2, signValue.token))
                if (signValue.extra.isNotEmpty()) write(PbEdit.bytes(3, signValue.extra))
            }.toByteArray()
            out.write(PbEdit.bytes(24, sec))
        }
        return out.toByteArray()
    }

    private fun encodeSsoFrame(
        command: String,
        payload: ByteArray,
        sequence: Int,
        requestType: RequestType,
        encryptType: EncryptType,
        msgType: Int,
        signValue: SignValue?,
    ): ByteArray {
        val sigs = sessionStore.wloginSigs
        val innerBody = ByteArrayOutputStream().apply {
            val header = ByteArrayOutputStream().apply {
                when (requestType) {
                    RequestType.D2Auth -> {
                        writeIntBE(sequence)
                        writeIntBE(appInfo.subAppId)
                        writeIntBE(2052)
                        write(SSO_FIXED_BYTES)
                        writePrefixed32(sigs.a2)
                        writePrefixed32(command.toByteArray(Charsets.UTF_8))
                        writePrefixed32(ByteArray(0))
                        writePrefixed32(sessionStore.guid.toHex().toByteArray(Charsets.UTF_8))
                        writePrefixed32(ByteArray(0))
                        writePrefixed16(appInfo.currentVersion.toByteArray(Charsets.UTF_8))
                        writePrefixed32(buildSsoReserved(msgType, signValue))
                    }
                    RequestType.Simple -> {
                        writePrefixed32(command.toByteArray(Charsets.UTF_8))
                        writeIntBE(4)
                        writePrefixed32(buildSsoReserved(msgType, signValue))
                    }
                }
            }.toByteArray()
            writePrefixed32(header)
            writePrefixed32(payload)
        }.toByteArray()

        val encrypted = when (encryptType) {
            EncryptType.None -> innerBody
            EncryptType.WithD2Key -> TeaCipher.encrypt(innerBody, sigs.d2Key)
            EncryptType.WithEmptyKey -> TeaCipher.encrypt(innerBody, ByteArray(16))
        }

        val outer = ByteArrayOutputStream().apply {
            when (requestType) {
                RequestType.D2Auth -> {
                    writeIntBE(12)
                    write(encryptType.underlying.toInt())
                    writePrefixed32(if (encryptType == EncryptType.WithD2Key) sigs.d2 else ByteArray(0))
                    write(0)
                    writePrefixed32(sessionStore.uin.toString().toByteArray(Charsets.UTF_8))
                    write(encrypted)
                }
                RequestType.Simple -> {
                    writeIntBE(13)
                    write(encryptType.underlying.toInt())
                    writeIntBE(sequence)
                    write(0)
                    writePrefixed32(sessionStore.uin.toString().toByteArray(Charsets.UTF_8))
                    write(encrypted)
                }
            }
        }.toByteArray()

        return ByteBuffer.allocate(4 + outer.size)
            .order(ByteOrder.BIG_ENDIAN)
            .putInt(4 + outer.size)
            .put(outer)
            .array()
    }

    private fun decodeSsoFrame(frame: ByteArray, d2Key: ByteArray): SsoResponse {
        val buf = ByteBuffer.wrap(frame).order(ByteOrder.BIG_ENDIAN)
        val protocol = buf.int
        require(protocol == 12 || protocol == 13) { "Unsupported SSO protocol: $protocol" }
        val authFlag = buf.get()
        buf.get() // dummy
        readPrefixed32(buf) // uin
        val remaining = ByteArray(buf.remaining())
        buf.get(remaining)

        val decrypted = when (authFlag) {
            EncryptType.None.underlying -> remaining
            EncryptType.WithD2Key.underlying -> {
                if (d2Key.size != 16) return SsoResponse(-1, "", ByteArray(0), -1, "no d2Key")
                TeaCipher.decrypt(remaining, d2Key)
            }
            EncryptType.WithEmptyKey.underlying -> TeaCipher.decrypt(remaining, ByteArray(16))
            else -> error("Unknown auth flag: $authFlag")
        }

        val inner = ByteBuffer.wrap(decrypted).order(ByteOrder.BIG_ENDIAN)
        val headLen = inner.int
        val seq = inner.int
        val retCode = inner.int
        val extra = readPrefixed32(inner).toString(Charsets.UTF_8)
        val command = readPrefixed32(inner).toString(Charsets.UTF_8)
        readPrefixed32(inner) // messageCookie
        val compressFlag = inner.int
        val compressed = compressFlag == 1
        if (headLen in 4..decrypted.size - 4) {
            inner.position(headLen)
        } else {
            val reservedLen = inner.int
            if (reservedLen > 0 && reservedLen <= inner.remaining()) {
                inner.position(inner.position() + reservedLen)
            }
        }
        var payload = if (inner.remaining() >= 4) readPrefixed32(inner) else ByteArray(0)
        if (compressed && payload.isNotEmpty()) {
            payload = inflateZlib(payload)
        }
        if (command.contains("SsoInfoSync") || command.contains("wtlogin")) {
            ServerLog.i(TAG, "decode cmd=$command seq=$seq ret=$retCode headLen=$headLen comp=$compressFlag payLen=${payload.size}")
        }
        return SsoResponse(retCode = retCode, command = command, response = payload, sequence = seq, extra = extra)
    }

    private fun inflateZlib(data: ByteArray): ByteArray {
        val inflater = Inflater(false)
        inflater.setInput(data)
        val out = ByteArrayOutputStream()
        val tmp = ByteArray(4096)
        try {
            while (!inflater.finished()) {
                val n = inflater.inflate(tmp)
                if (n == 0) {
                    if (inflater.needsInput() || inflater.needsDictionary()) break
                } else {
                    out.write(tmp, 0, n)
                }
            }
        } finally {
            inflater.end()
        }
        return out.toByteArray()
    }

    private fun generateTrace(): String {
        val hex = "0123456789abcdef"
        val sb = StringBuilder(55)
        sb.append("00-")
        repeat(32) { sb.append(hex[Random.nextInt(16)]) }
        sb.append('-')
        repeat(16) { sb.append(hex[Random.nextInt(16)]) }
        sb.append("-01")
        return sb.toString()
    }

    companion object {
        private const val TAG = "SSO"
        private const val SSO_HOST = "msfwifi.3g.qq.com"
        private const val SSO_PORT = 8080

        private val SSO_FIXED_BYTES = byteArrayOf(
            0x02, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00,
        )

        val SIGN_REQUIRED_COMMANDS = setOf(
            "trpc.msg.register_proxy.RegisterProxy.SsoInfoSync",
            "trpc.qq_new_tech.status_svc.StatusService.SsoHeartBeat",
            "OidbSvcTrpcTcp.0x7c2_5",
            "OidbSvcTrpcTcp.0x126b_0",
            "OidbSvcTrpcTcp.0xfd4_1",
            "OidbSvcTrpcTcp.0x9a2_12",
            "OidbSvcTrpcTcp.0x911a_19",
            "OidbSvcTrpcTcp.0x911a_22",
            "OidbSvcTrpcTcp.0x9124_0",
            "OidbSvcTrpcTcp.0x92d5_0",
            "OidbSvcTrpcTcp.0x9379_0",
            "MQUpdateSvc_com_qq_ti.web.OidbSvcTrpcJsapiTcp.0x9172_0",
            "MQUpdateSvc_com_qq_qzone_act.web.OidbSvcTrpcJsapiTcp.0x9377_0",
            "SQQzoneSvc.publishmood",
            "SQQzoneSvc.addComment",
            "SQQzoneSvc.addReply",
            "SQQzoneSvc.like",
            "SQQzoneSvc.forward",
            "SQQzoneSvc.getProfileFeeds",
            "SQQzoneSvc.getMainPage",
            "LightAppSvc.mini_program_auth.GetCode",
            "LightAppSvc.mini_app_cloudstorage.GetUserCloudStorage",
            "LightAppSvc.mini_app_privacy.GetPrivacyInfo",
            "HttpConn.0x6ff_501",
            "trpc.commercial.dataworks.UserActionReport_sso.SsoReport",
            "trpc.lplan.feed_svr.StatusWrite.SsoPostStatus",
            "wtlogin.login",
            "wtlogin.trans_emp",
            "wtlogin.exchange_emp",
            "wtlogin.device_lock",
        )
    }
}

private fun ByteArrayOutputStream.writeIntBE(v: Int) {
    write((v ushr 24) and 0xff)
    write((v ushr 16) and 0xff)
    write((v ushr 8) and 0xff)
    write(v and 0xff)
}

private fun ByteArrayOutputStream.writePrefixed32(bytes: ByteArray) {
    writeIntBE(4 + bytes.size)
    write(bytes)
}

private fun ByteArrayOutputStream.writePrefixed16(bytes: ByteArray) {
    val len = 2 + bytes.size
    write((len ushr 8) and 0xff)
    write(len and 0xff)
    write(bytes)
}

private fun readPrefixed32(buf: ByteBuffer): ByteArray {
    val total = buf.int
    val len = total - 4
    if (len <= 0) return ByteArray(0)
    val out = ByteArray(len)
    buf.get(out)
    return out
}
