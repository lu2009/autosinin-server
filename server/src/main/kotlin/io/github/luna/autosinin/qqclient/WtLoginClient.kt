package io.github.luna.autosinin.qqclient

import io.github.luna.autosinin.protocol.PbEdit
import io.github.luna.autosinin.server.core.ServerLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.random.Random

/**
 * Self-hosted Android WtLogin + SsoInfoSync client (replaces acidify-core WtLogin/LoginOps).
 */
class WtLoginClient(
    private val sso: SsoClient,
) {
    private val appInfo = sso.appInfo
    private val store = sso.sessionStore
    private val signClient = sso.signClient

    suspend fun login(
        onRequireCaptchaTicket: suspend (captchaUrl: String) -> String,
        onRequireSmsCode: suspend (countryCode: String, phone: String, smsUrl: String) -> String,
    ) {
        if (store.wloginSigs.a2.isEmpty()) {
            if (store.wloginSigs.a1.isNotEmpty() && store.wloginSigs.noPicSig.isNotEmpty()) {
                try {
                    ServerLog.i(TAG, "uin=${store.uin} a2 empty, trying A1 session renewal")
                    renewWithA1()
                    return
                } catch (e: Exception) {
                    ServerLog.w(TAG, "uin=${store.uin} A1 renewal failed (${e.message}), falling back to password login")
                }
            }
            ServerLog.i(TAG, "uin=${store.uin} session empty, starting password login")
            passwordLogin(onRequireCaptchaTicket, onRequireSmsCode)
        } else {
            try {
                online()
            } catch (e: Exception) {
                ServerLog.w(TAG, "uin=${store.uin} existing session login failed (${e.message}), trying A1 renewal")
                if (store.wloginSigs.a1.isNotEmpty() && store.wloginSigs.noPicSig.isNotEmpty()) {
                    try {
                        renewWithA1()
                        return
                    } catch (renewErr: Exception) {
                        ServerLog.w(TAG, "uin=${store.uin} A1 renewal failed (${renewErr.message}), falling back to password login")
                    }
                }
                store.clear()
                passwordLogin(onRequireCaptchaTicket, onRequireSmsCode)
            }
        }
    }

    private suspend fun renewWithA1() {
        val result = sendWtLogin(0x09) {
            buildQrExchangeTlv(
                energy = fetchEnergy(0x09),
                debugXwid = fetchDebugXwid(0x09),
            )
        }
        check(result.state == 0) { "A1 renewal failed with state=${result.state}" }
        applyLoginSuccessTlvs(result.tlvPack)
        online()
    }

    suspend fun online() {
        val req = buildSsoInfoSyncReq()
        val rsp = sso.sendPacket(
            command = "trpc.msg.register_proxy.RegisterProxy.SsoInfoSync",
            payload = req,
            requestType = RequestType.D2Auth,
            encryptType = EncryptType.WithD2Key,
        )
        check(rsp.retCode == 0) { "SsoInfoSync failed retCode=${rsp.retCode}: ${rsp.extra}" }
        val regInfoResp = readPbBytes(rsp.response, 7)
        val msg = regInfoResp?.let { readPbBytes(it, 2)?.toString(Charsets.UTF_8) }.orEmpty()
        if (regInfoResp != null && msg != "register success") {
            ServerLog.w(TAG, "SsoInfoSync respLen=${rsp.response.size} hex=${rsp.response.take(48).toByteArray().toHex()}")
            error("SsoInfoSync unexpected message: '$msg'")
        }
        sso.isOnline = true
        sso.startHeartbeat()
        ServerLog.i(TAG, "uin=${store.uin} online succeeded (self-hosted SSO, msg='${msg.ifEmpty { "ok" }}')")
    }

    private suspend fun passwordLogin(
        onRequireCaptchaTicket: suspend (captchaUrl: String) -> String,
        onRequireSmsCode: suspend (countryCode: String, phone: String, smsUrl: String) -> String,
    ) {
        var result = sendWtLogin(0x09) {
            buildTgtgtTlv(
                energy = fetchEnergy(0x09),
                debugXwid = fetchDebugXwid(0x09),
            )
        }

        suspend fun handleSms(countryCode: String, phone: String, smsUrl: String): LoginResp {
            val smsCode = onRequireSmsCode(countryCode, phone, smsUrl)
            return if (smsCode.isNotEmpty()) {
                sendWtLogin(0x07) {
                    buildSubmitSmsTlv(
                        energy = fetchEnergy(0x07),
                        debugXwid = fetchDebugXwid(0x07),
                        smsCode = smsCode,
                    )
                }
            } else {
                sendWtLogin(0x09) {
                    buildTgtgtTlv(
                        energy = fetchEnergy(0x09),
                        debugXwid = fetchDebugXwid(0x09),
                    )
                }
            }
        }

        if (result.state == 2) {
            result.tlvPack[0x104]?.let { store.state.tlv104 = it }
            result.tlvPack[0x546]?.let { store.state.tlv547 = generateTlv547(it) }
            val captchaUrl = result.tlvPack[0x192]?.toString(Charsets.UTF_8)
                ?: error("Missing captcha url (tlv 0x192)")
            val ticket = onRequireCaptchaTicket(captchaUrl)
            result = sendWtLogin(0x02) {
                buildSubmitCaptchaTlv(
                    energy = fetchEnergy(0x02),
                    debugXwid = fetchDebugXwid(0x02),
                    ticket = ticket,
                )
            }
            if (result.state == 160) {
                result.tlvPack[0x104]?.let { store.state.tlv104 = it }
                val (countryCode, phone, smsUrl) = readSmsInfo(result.tlvPack)
                result = handleSms(countryCode, phone, smsUrl)
            }
        }

        if (result.state == 239) {
            result.tlvPack[0x104]?.let { store.state.tlv104 = it }
            result.tlvPack[0x174]?.let { store.state.tlv174 = it }
            val (countryCode, phone, smsUrl) = readSmsInfo(result.tlvPack)
            result = sendWtLogin(0x08) {
                buildFetchSmsTlv(debugXwid = fetchDebugXwid(0x08))
            }
            if (result.state == 160) {
                result.tlvPack[0x104]?.let { store.state.tlv104 = it }
                result = handleSms(countryCode, phone, smsUrl)
            }
        }

        check(result.state == 0) { "WtLogin failed with state=${result.state}" }
        applyLoginSuccessTlvs(result.tlvPack)
        online()
    }

    private suspend fun fetchEnergy(internalCmd: Int): ByteArray = withContext(Dispatchers.IO) {
        signClient.energy(
            uin = store.uin,
            data = "810_${internalCmd.toString(16)}",
            guid = store.guid.toHex(),
            ver = appInfo.sdkInfo.sdkVersion,
            version = appInfo.ptVersion,
            qua = appInfo.qua,
        )
    }

    private suspend fun fetchDebugXwid(internalCmd: Int): ByteArray = withContext(Dispatchers.IO) {
        signClient.getDebugXwid(
            uin = store.uin,
            data = "810_${internalCmd.toString(16)}",
            guid = store.guid.toHex(),
            version = appInfo.ptVersion,
            qua = appInfo.qua,
        )
    }

    data class QrCodeFetchResult(
        val qrSig: ByteArray,
        val qrUrl: String,
        val qrPng: ByteArray,
    )

    sealed class QrCodePollResult {
        data object WaitingForScan : QrCodePollResult()
        data object WaitingForConfirm : QrCodePollResult()
        data object Expired : QrCodePollResult()
        data object Cancelled : QrCodePollResult()
        data class Confirmed(
            val uin: Long,
            val tgtgtKey: ByteArray,
            val a1: ByteArray,
            val noPicSig: ByteArray,
        ) : QrCodePollResult()
        data class Unknown(val state: Int) : QrCodePollResult()
    }

    suspend fun fetchQrCode(): QrCodeFetchResult {
        val code2d = ByteArrayOutputStream().apply {
            writeShortBE(0)
            writeIntBE(appInfo.appId)
            writeLongBE(0L)
            write(0)
            writeShortBE(0)
            val tlv = TlvPackBuilder().apply {
                writeTlv(0x16) {
                    writeIntBE(0)
                    writeIntBE(appInfo.appId)
                    writeIntBE(appInfo.subAppId)
                    write(store.guid)
                    writeString16(appInfo.packageName)
                    writeString16(appInfo.ptVersion)
                    writeString16(appInfo.packageName)
                }
                writeTlv(0x1b) {
                    writeIntBE(0); writeIntBE(0); writeIntBE(3); writeIntBE(4)
                    writeIntBE(72); writeIntBE(2); writeIntBE(2); writeShortBE(0)
                }
                writeTlv(0x1d) {
                    write(1)
                    writeIntBE(appInfo.sdkInfo.mainSigMap.toInt())
                    writeIntBE(0)
                    write(0)
                }
                writeTlv(0x33, store.guid)
                writeTlv(0x35) { writeIntBE(appInfo.ssoVersion) }
                writeTlv(0x66) { writeIntBE(appInfo.ssoVersion) }
                writeTlv(0xd1) {
                    val sys = PbEdit.bytes(1, appInfo.os.toByteArray(Charsets.UTF_8)) +
                        PbEdit.bytes(2, store.device.model.toByteArray(Charsets.UTF_8))
                    write(PbEdit.bytes(1, sys))
                    write(PbEdit.bytes(4, byteArrayOf(0x30, 0x01)))
                }
            }.build()
            write(tlv)
        }.toByteArray()

        val respCode2d = sendTransEmp(subCmd = 0x31, code2d = code2d)
        val buf = ByteBuffer.wrap(respCode2d).order(ByteOrder.BIG_ENDIAN)
        val st = buf.get().toInt() and 0xff
        if (st != 0) {
            error("FetchQRCode failed status=$st respHex=${respCode2d.toHex()}")
        }
        return runCatching {
            val qrSig = readPrefixed16Bytes(buf)
            val tlvPack = parseTlv(buf)
            val d1Raw = tlvPack[0xd1] ?: error("Missing tlv 0xd1 in FetchQRCode response")
            val qrUrl = readPbBytes(d1Raw, 2)?.toString(Charsets.UTF_8).orEmpty()
            val qrPng = tlvPack[0x17] ?: error("Missing tlv 0x17 (QR PNG) in FetchQRCode response")
            QrCodeFetchResult(qrSig = qrSig, qrUrl = qrUrl, qrPng = qrPng)
        }.getOrElse { err ->
            error("FetchQRCode parse failed (${err.javaClass.simpleName}) status=$st hex=${respCode2d.toHex()}")
        }
    }

    suspend fun queryQrCodeState(qrSig: ByteArray): QrCodePollResult {
        val code2d = ByteArrayOutputStream().apply {
            writeShortBE(0)
            writeIntBE(appInfo.appId)
            writeShortBE(qrSig.size)
            write(qrSig)
            writeLongBE(0L)
            write(0)
            writeShortBE(0)
            writeShortBE(0)
        }.toByteArray()

        val respCode2d = sendTransEmp(subCmd = 0x12, code2d = code2d)
        val buf = ByteBuffer.wrap(respCode2d).order(ByteOrder.BIG_ENDIAN)
        val state = buf.get().toInt() and 0xff
        return when (state) {
            0 -> {
                buf.int // discard 4
                val uin = buf.int.toLong() and 0xffffffffL
                buf.int // discard 4
                val tlvPack = parseTlv(buf)
                val tgtgtKey = tlvPack[0x1e] ?: error("Missing tlv 0x1e in QR confirmed response")
                val a1 = tlvPack[0x18] ?: error("Missing tlv 0x18 in QR confirmed response")
                val noPicSig = tlvPack[0x19] ?: error("Missing tlv 0x19 in QR confirmed response")
                QrCodePollResult.Confirmed(uin = uin, tgtgtKey = tgtgtKey, a1 = a1, noPicSig = noPicSig)
            }
            17 -> QrCodePollResult.Expired
            48 -> QrCodePollResult.WaitingForScan
            53 -> QrCodePollResult.WaitingForConfirm
            54 -> QrCodePollResult.Cancelled
            else -> QrCodePollResult.Unknown(state)
        }
    }

    suspend fun completeQrLogin(confirmed: QrCodePollResult.Confirmed) {
        store.uin = confirmed.uin
        store.wloginSigs.tgtgtKey = confirmed.tgtgtKey
        store.wloginSigs.a1 = confirmed.a1
        store.wloginSigs.noPicSig = confirmed.noPicSig

        val result = sendWtLogin(0x09) {
            buildQrExchangeTlv(
                energy = fetchEnergy(0x09),
                debugXwid = fetchDebugXwid(0x09),
            )
        }
        check(result.state == 0) { "QR WtLogin failed with state=${result.state}" }
        applyLoginSuccessTlvs(result.tlvPack)
        online()
    }

    private fun applyLoginSuccessTlvs(tlvPack: Map<Int, ByteArray>) {
        val tlv119 = tlvPack[0x119] ?: error("Missing tlv 0x119")
        val internalTlv = parseTlv(TeaCipher.decrypt(tlv119, store.wloginSigs.tgtgtKey))

        store.wloginSigs.apply {
            internalTlv[0x103]?.let { stWeb = it }
            internalTlv[0x143]?.let { d2 = it }
            internalTlv[0x108]?.let { ksid = it }
            internalTlv[0x10A]?.let { a2 = it }
            internalTlv[0x10C]?.let { a1Key = it }
            internalTlv[0x10D]?.let { a2Key = it }
            internalTlv[0x10E]?.let { stKey = it }
            internalTlv[0x114]?.let { st = it }
            internalTlv[0x120]?.let { sKey = it }
            internalTlv[0x133]?.let { wtSessionTicket = it }
            internalTlv[0x134]?.let { wtSessionTicketKey = it }
            internalTlv[0x305]?.let { d2Key = it }
            internalTlv[0x106]?.let { a1 = it }
            internalTlv[0x16A]?.let { noPicSig = it }
            internalTlv[0x16D]?.let { superKey = it }
            internalTlv[0x512]?.let { raw512 ->
                psKey = parseTlv512(raw512)
            }
        }
        internalTlv[0x543]?.let { raw543 ->
            val common = readPbBytes(raw543, 9)
            val rspNt = common?.let { readPbBytes(it, 11) }
            val uid = rspNt?.let { readPbBytes(it, 1)?.toString(Charsets.UTF_8) }
            if (!uid.isNullOrEmpty()) store.uid = uid
        }
    }

    private suspend fun sendTransEmp(subCmd: Int, code2d: ByteArray): ByteArray {
        val code2dBuf = ByteArrayOutputStream().apply {
            writeIntBE((System.currentTimeMillis() / 1000L).toInt())
            write(2)
            writeShortBE(43 + code2d.size + 1)
            writeShortBE(subCmd)
            write(ByteArray(21))
            write(3)
            writeShortBE(0)
            writeShortBE(50)
            writeIntBE(0)
            writeLongBE(0L)
            write(code2d)
            write(3)
        }.toByteArray()

        val wtPayload = ByteArrayOutputStream().apply {
            write(0)
            writeShortBE(code2dBuf.size)
            writeIntBE(appInfo.appId)
            writeIntBE(114)
            writeShortBE(0)
            write(0)
            write(code2dBuf)
        }.toByteArray()

        val resp = sendRawWtLoginPacket(wtLoginSubCmd = 2066, wtPayload = wtPayload, command = "wtlogin.trans_emp")
        val dec = TeaCipher.decrypt(resp.raw.copyOfRange(16, resp.raw.size - 1), resp.shareKey)
        require(dec.size > 54) { "Invalid trans_emp response len=${dec.size}" }
        return dec.copyOfRange(54, dec.size)
    }

    private fun buildQrExchangeTlv(energy: ByteArray, debugXwid: ByteArray): ByteArray {
        val tlv = TlvPackBuilder()
        tlv.tlv18()
        tlv.tlv1()
        tlv.writeTlv(0x106, store.wloginSigs.a1)
        tlv.tlv116()
        tlv.tlv100()
        tlv.tlv107()
        tlv.tlv142()
        tlv.tlv144Report()
        tlv.tlv145()
        tlv.tlv147()
        tlv.tlv154()
        tlv.tlv141()
        tlv.tlv8()
        tlv.tlv511()
        tlv.tlv187()
        tlv.tlv188()
        tlv.tlv191(0x82)
        tlv.tlv177()
        tlv.tlv516()
        tlv.tlv521()
        tlv.tlv525()
        tlv.writeTlv(0x16a, store.wloginSigs.noPicSig)
        tlv.writeTlv(0x544, energy)
        tlv.writeTlv(0x545, store.qimei.toByteArray(Charsets.UTF_8))
        tlv.writeTlv(0x548, generateTlv548())
        tlv.writeTlv(0x553, debugXwid)
        return tlv.build()
    }

    private data class LoginResp(val state: Int, val tlvPack: Map<Int, ByteArray>)

    private data class WtRawResp(val raw: ByteArray, val shareKey: ByteArray, val randomKey: ByteArray)

    private suspend fun sendRawWtLoginPacket(wtLoginSubCmd: Int, wtPayload: ByteArray, command: String): WtRawResp {
        val ecdh = Secp192k1Ecdh.generate()
        val shareKey = ecdh.keyExchange(SECP192K1_BOB_PUBKEY, hashMd5 = true)
        val encrypted = TeaCipher.encrypt(wtPayload, shareKey)
        val pubKey = ecdh.packPublic(compressed = true)

        val randomKey = Random.nextBytes(16)
        val inner = ByteArrayOutputStream().apply {
            writeShortBE(8001)
            writeShortBE(wtLoginSubCmd)
            writeShortBE(0) // sequence
            writeIntBE(store.uin.toInt())
            write(3) // extVer
            write(135) // cmdVer
            writeIntBE(0)
            write(19) // pubId
            writeShortBE(0) // insId
            writeShortBE(appInfo.appClientVersion)
            writeIntBE(0) // retryTime
            write(1)
            write(1)
            write(randomKey)
            writeShortBE(0x102)
            writeShortBE(pubKey.size)
            write(pubKey)
            write(encrypted)
            write(3)
        }.toByteArray()

        val totalLen = 1 + 2 + inner.size // start(1) + len(2) + inner
        val packet = ByteBuffer.allocate(totalLen).order(ByteOrder.BIG_ENDIAN)
            .put(0x02.toByte())
            .putShort(totalLen.toShort())
            .put(inner)
            .array()

        val rsp = sso.sendPacket(
            command = command,
            payload = packet,
            requestType = RequestType.D2Auth,
            encryptType = EncryptType.WithEmptyKey,
        )
        check(rsp.retCode == 0) { "$command SSO retCode=${rsp.retCode}: ${rsp.extra}" }

        val raw = rsp.response
        require(raw.size > 17 && raw[0] == 0x02.toByte() && raw.last() == 0x03.toByte()) {
            "Invalid wtlogin response frame (len=${raw.size})"
        }
        return WtRawResp(raw = raw, shareKey = shareKey, randomKey = randomKey)
    }

    private suspend fun sendWtLogin(internalCmd: Int, buildTlv: suspend () -> ByteArray): LoginResp {
        val tlvBytes = buildTlv()
        val wtPayload = ByteBuffer.allocate(2 + tlvBytes.size).order(ByteOrder.BIG_ENDIAN)
            .putShort(internalCmd.toShort())
            .put(tlvBytes)
            .array()
        val resp = sendRawWtLoginPacket(wtLoginSubCmd = 2064, wtPayload = wtPayload, command = "wtlogin.login")
        val raw = resp.raw
        var dec = TeaCipher.decrypt(raw.copyOfRange(16, raw.size - 1), resp.shareKey)
        if (dec.isEmpty()) {
            dec = TeaCipher.decrypt(raw.copyOfRange(16, raw.size - 1), resp.randomKey)
            ServerLog.w(TAG, "wtlogin shareKey decrypt empty; randomKey decLen=${dec.size} decHex=${dec.toHex()} rawHex=${raw.toHex()}")
        }
        return runCatching {
            val buf = ByteBuffer.wrap(dec).order(ByteOrder.BIG_ENDIAN)
            buf.short // command
            val state = buf.get().toInt() and 0xff
            val tlvPack = parseTlv(buf)

            tlvPack[0x146]?.let { raw146 ->
                val r = ByteBuffer.wrap(raw146).order(ByteOrder.BIG_ENDIAN)
                val code = if (r.remaining() >= 4) r.int else -1
                val tag = if (r.remaining() >= 2) readPrefixed16Str(r) else ""
                val msg = if (r.remaining() >= 2) readPrefixed16Str(r) else ""
                if (code != 160) {
                    val url543 = if (code == 237) extract543Url(tlvPack[0x543]) else ""
                    val suffix = if (url543.isNotEmpty()) " (验证链接: $url543)" else ""
                    error("WtLogin error code=$code ($tag): $msg$suffix")
                }
            }
            LoginResp(state, tlvPack)
        }.getOrElse { err ->
            ServerLog.w(TAG, "wtlogin parse failed (${err.javaClass.simpleName}: ${err.message}) decHex=${dec.take(64).toByteArray().toHex()}")
            throw err
        }
    }

    private fun buildSsoInfoSyncReq(): ByteArray {
        val msgCookie = PbEdit.varintField(1, 0L)
        val c2cSync = ByteArrayOutputStream().apply {
            write(PbEdit.bytes(1, msgCookie))
            write(PbEdit.varintField(2, 0L))
            write(PbEdit.bytes(3, msgCookie))
        }.toByteArray()
        val normalCfg = ByteArrayOutputStream().apply {
            write(PbEdit.bytes(1, PbEdit.varintField(1, 46L) + PbEdit.varintField(2, 0L)))
            write(PbEdit.bytes(1, PbEdit.varintField(1, 283L) + PbEdit.varintField(2, 0L)))
        }.toByteArray()
        val devInfo = ByteArrayOutputStream().apply {
            write(PbEdit.bytes(1, store.device.model.toByteArray(Charsets.UTF_8)))
            write(PbEdit.bytes(2, store.device.codename.toByteArray(Charsets.UTF_8)))
            write(PbEdit.bytes(3, store.device.androidVersion.toByteArray(Charsets.UTF_8)))
            write(PbEdit.bytes(4, store.device.manufacturer.toByteArray(Charsets.UTF_8)))
            write(PbEdit.bytes(5, store.device.incremental.toByteArray(Charsets.UTF_8)))
        }.toByteArray()
        val bizInfo = ByteArrayOutputStream().apply {
            write(PbEdit.varintField(1, 1L))
            write(PbEdit.varintField(2, 1L))
        }.toByteArray()
        val regInfo = ByteArrayOutputStream().apply {
            write(PbEdit.bytes(1, store.guid.toHex().toByteArray(Charsets.UTF_8)))
            write(PbEdit.varintField(2, 0L))
            write(PbEdit.bytes(3, appInfo.currentVersion.toByteArray(Charsets.UTF_8)))
            write(PbEdit.varintField(4, 0L))
            write(PbEdit.varintField(5, 2052L))
            write(PbEdit.bytes(6, devInfo))
            write(PbEdit.varintField(7, 0L))
            write(PbEdit.varintField(8, 6L))
            write(PbEdit.varintField(9, 1L))
            write(PbEdit.bytes(10, bizInfo))
            write(PbEdit.varintField(11, 0L))
        }.toByteArray()
        val appState = ByteArrayOutputStream().apply {
            write(PbEdit.varintField(1, 0L))
            write(PbEdit.varintField(2, 1L))
            write(PbEdit.varintField(3, 0L))
        }.toByteArray()

        return ByteArrayOutputStream().apply {
            write(PbEdit.varintField(1, 735L))
            write(PbEdit.varintField(2, Random.nextInt().toLong() and 0xffffffffL))
            write(PbEdit.varintField(4, 2L))
            write(PbEdit.varintField(5, 0L))
            write(PbEdit.bytes(6, c2cSync))
            write(PbEdit.bytes(8, normalCfg))
            write(PbEdit.bytes(9, regInfo))
            write(PbEdit.bytes(10, PbEdit.varintField(1, 0L) + PbEdit.varintField(2, 1L)))
            write(PbEdit.bytes(11, appState))
        }.toByteArray()
    }

    private fun buildTgtgtTlv(energy: ByteArray, debugXwid: ByteArray): ByteArray {
        val tlv = TlvPackBuilder()
        tlv.tlv18()
        tlv.tlv1()
        tlv.tlv106Pwd()
        tlv.tlv116()
        tlv.tlv100()
        tlv.tlv107()
        tlv.tlv142()
        tlv.tlv144Report()
        tlv.tlv145()
        tlv.tlv147()
        tlv.tlv154()
        tlv.tlv141()
        tlv.tlv8()
        tlv.tlv511()
        tlv.tlv187()
        tlv.tlv188()
        tlv.tlv191(0x82)
        tlv.tlv177()
        tlv.tlv516()
        tlv.tlv521()
        tlv.tlv525()
        tlv.writeTlv(0x544, energy)
        tlv.writeTlv(0x545, store.qimei.toByteArray(Charsets.UTF_8))
        tlv.writeTlv(0x548, generateTlv548())
        tlv.writeTlv(0x553, debugXwid)
        return tlv.build()
    }

    private fun buildSubmitCaptchaTlv(energy: ByteArray, debugXwid: ByteArray, ticket: String): ByteArray {
        val tlv = TlvPackBuilder()
        tlv.writeTlv(0x193, ticket.toByteArray(Charsets.UTF_8))
        tlv.tlv8()
        store.state.tlv104?.let { tlv.writeTlv(0x104, it) }
        tlv.tlv116()
        store.state.tlv547?.let { tlv.writeTlv(0x547, it) }
        tlv.writeTlv(0x544, energy)
        tlv.writeTlv(0x553, debugXwid)
        tlv.tlv542()
        return tlv.build()
    }

    private fun buildFetchSmsTlv(debugXwid: ByteArray): ByteArray {
        val tlv = TlvPackBuilder()
        tlv.tlv8()
        store.state.tlv104?.let { tlv.writeTlv(0x104, it) }
        tlv.tlv116()
        store.state.tlv174?.let { tlv.writeTlv(0x174, it) }
        tlv.writeTlv(0x17a) { writeIntBE(9) }
        tlv.writeTlv(0x197) { write(0) }
        tlv.writeTlv(0x553, debugXwid)
        return tlv.build()
    }

    private fun buildSubmitSmsTlv(energy: ByteArray, debugXwid: ByteArray, smsCode: String): ByteArray {
        val tlv = TlvPackBuilder()
        tlv.tlv8()
        store.state.tlv104?.let { tlv.writeTlv(0x104, it) }
        tlv.tlv116()
        store.state.tlv174?.let { tlv.writeTlv(0x174, it) }
        tlv.writeTlv(0x17c) { writeString16(smsCode) }
        tlv.writeTlv(0x401, Random.nextBytes(16))
        tlv.writeTlv(0x198) { write(0) }
        tlv.writeTlv(0x544, energy)
        tlv.writeTlv(0x553, debugXwid)
        return tlv.build()
    }

    private inner class TlvPackBuilder {
        private var count = 0
        private val body = ByteArrayOutputStream()

        fun writeTlv(tag: Int, bytes: ByteArray) {
            count++
            body.writeShortBE(tag)
            body.writeShortBE(bytes.size)
            body.write(bytes)
        }

        inline fun writeTlv(tag: Int, block: ByteArrayOutputStream.() -> Unit) {
            val inner = ByteArrayOutputStream().apply(block).toByteArray()
            writeTlv(tag, inner)
        }

        fun build(): ByteArray {
            val inner = body.toByteArray()
            return ByteBuffer.allocate(2 + inner.size).order(ByteOrder.BIG_ENDIAN)
                .putShort(count.toShort())
                .put(inner)
                .array()
        }

        fun tlv1() = writeTlv(0x1) {
            writeShortBE(1)
            writeIntBE(Random.nextInt())
            writeIntBE(store.uin.toInt())
            writeIntBE((System.currentTimeMillis() / 1000L).toInt())
            writeIntBE(0)
            writeShortBE(0)
        }

        fun tlv8() = writeTlv(0x8) {
            writeShortBE(0)
            writeIntBE(2052)
            writeShortBE(0)
        }

        fun tlv18() = writeTlv(0x18) {
            writeShortBE(1)
            writeIntBE(0x600)
            writeIntBE(appInfo.appId)
            writeIntBE(appInfo.appClientVersion)
            writeIntBE(store.uin.toInt())
            writeShortBE(0)
            writeShortBE(0)
        }

        fun tlv100() = writeTlv(0x100) {
            writeShortBE(1)
            writeIntBE(appInfo.ssoVersion)
            writeIntBE(appInfo.appId)
            writeIntBE(appInfo.subAppId)
            writeIntBE(appInfo.appClientVersion)
            writeIntBE(appInfo.sdkInfo.mainSigMap.toInt())
        }

        fun tlv106Pwd() = writeTlv(0x106) {
            val md5Pwd = md5(store.password.toByteArray(Charsets.UTF_8))
            val key = md5(
                ByteArrayOutputStream().apply {
                    write(md5Pwd)
                    writeIntBE(0)
                    writeIntBE(store.uin.toInt())
                }.toByteArray(),
            )
            val plain = ByteArrayOutputStream().apply {
                writeShortBE(4)
                writeIntBE(Random.nextInt())
                writeIntBE(appInfo.ssoVersion)
                writeIntBE(appInfo.appId)
                writeIntBE(appInfo.appClientVersion)
                writeLongBE(store.uin)
                writeIntBE((System.currentTimeMillis() / 1000L).toInt())
                writeIntBE(0)
                write(1)
                write(md5Pwd)
                write(store.wloginSigs.tgtgtKey)
                writeIntBE(0)
                write(1)
                write(store.guid)
                writeIntBE(appInfo.subAppId)
                writeIntBE(1)
                writeString16(store.uin.toString())
                writeShortBE(0)
            }.toByteArray()
            write(TeaCipher.encrypt(plain, key))
        }

        fun tlv107() = writeTlv(0x107) {
            writeShortBE(0)
            write(0)
            writeShortBE(0)
            write(1)
        }

        fun tlv116() = writeTlv(0x116) {
            write(0)
            writeIntBE(appInfo.sdkInfo.miscBitMap.toInt())
            writeIntBE(appInfo.sdkInfo.subSigMap.toInt())
            write(0)
        }

        fun tlv141() = writeTlv(0x141) {
            writeShortBE(1)
            writeString16("")
            writeString16("")
            writeString16("wifi")
        }

        fun tlv142() = writeTlv(0x142) {
            writeShortBE(0)
            writeString16(appInfo.packageName)
        }

        fun tlv144Report() = writeTlv(0x144) {
            val nested = TlvPackBuilder().apply {
                writeTlv(0x109, md5(store.androidId.toByteArray(Charsets.UTF_8)))
                writeTlv(0x52d, buildDeviceReport())
                writeTlv(0x124) {
                    writeString16("android")
                    writeString16("14")
                    writeShortBE(2)
                    writeString16("")
                    writeIntBE(4)
                    write("wifi".toByteArray(Charsets.UTF_8))
                }
                writeTlv(0x128) {
                    writeShortBE(0)
                    write(0); write(0); write(0)
                    writeIntBE(0)
                    writeString16(appInfo.os)
                    writeShortBE(store.guid.size); write(store.guid)
                    writeString16("Xiaomi")
                }
                writeTlv(0x16e, store.device.model.toByteArray(Charsets.UTF_8))
            }.build()
            write(TeaCipher.encrypt(nested, store.wloginSigs.tgtgtKey))
        }

        fun tlv145() = writeTlv(0x145, store.guid)

        fun tlv147() = writeTlv(0x147) {
            writeIntBE(appInfo.appId)
            writeString16(appInfo.ptVersion)
            writeShortBE(appInfo.apkSignatureMd5.size)
            write(appInfo.apkSignatureMd5)
        }

        fun tlv154() = writeTlv(0x154) { writeIntBE(0) }

        fun tlv177() = writeTlv(0x177) {
            write(1)
            writeIntBE(0)
            writeString16(appInfo.sdkInfo.sdkVersion)
        }

        fun tlv187() = writeTlv(0x187, md5(byteArrayOf(0x02, 0x00, 0x00, 0x00, 0x00, 0x00)))
        fun tlv188() = writeTlv(0x188, md5(store.androidId.toByteArray(Charsets.UTF_8)))
        fun tlv191(k: Int) = writeTlv(0x191) { write(k) }

        fun tlv511() = writeTlv(0x511) {
            val domains = arrayOf(
                "office.qq.com", "qun.qq.com", "gamecenter.qq.com", "docs.qq.com",
                "mail.qq.com", "tim.qq.com", "ti.qq.com", "vip.qq.com",
                "tenpay.com", "qqweb.qq.com", "qzone.qq.com", "mma.qq.com",
                "game.qq.com", "openmobile.qq.com", "connect.qq.com",
            )
            writeShortBE(domains.size)
            for (d in domains) {
                write(1)
                writeString16(d)
            }
        }

        fun tlv516() = writeTlv(0x516) { writeIntBE(0) }

        fun tlv521() = writeTlv(0x521) {
            writeIntBE(0)
            writeString16("")
        }

        fun tlv525() = writeTlv(0x525) {
            writeShortBE(1)
            writeShortBE(0x536)
            val b = byteArrayOf(0x02, 0x01, 0x00)
            writeShortBE(b.size)
            write(b)
        }

        fun tlv542() = writeTlv(0x542) {
            val f9 = PbEdit.varintField(12, 1L) + PbEdit.varintField(15, 1L)
            write(PbEdit.bytes(9, f9))
            write(PbEdit.varintField(17, 1L))
        }
    }

    private fun buildDeviceReport(): ByteArray = ByteArrayOutputStream().apply {
        val dev = store.device
        write(PbEdit.bytes(1, dev.incremental.toByteArray(Charsets.UTF_8)))
        write(PbEdit.bytes(2, dev.kernelVersion.toByteArray(Charsets.UTF_8)))
        write(PbEdit.bytes(3, "REL".toByteArray(Charsets.UTF_8)))
        write(PbEdit.bytes(4, dev.incremental.toByteArray(Charsets.UTF_8)))
        write(PbEdit.bytes(5, dev.fingerprint.toByteArray(Charsets.UTF_8)))
        write(PbEdit.bytes(6, "unknown".toByteArray(Charsets.UTF_8)))
        write(PbEdit.bytes(7, store.androidId.toByteArray(Charsets.UTF_8)))
        write(PbEdit.bytes(8, ByteArray(0)))
        write(PbEdit.bytes(9, dev.incremental.toByteArray(Charsets.UTF_8)))
    }.toByteArray()

    companion object {
        private const val TAG = "WTLOGIN"
        private val SECP192K1_BOB_PUBKEY =
            "04928D8850673088B343264E0C6BACB8496D697799F37211DEB25BB73906CB089FEA9639B4E0260498B51A992D50813DA8".hexToBytes()

        private fun parseTlv(bytes: ByteArray): Map<Int, ByteArray> =
            parseTlv(ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN))

        private fun parseTlv(buf: ByteBuffer): Map<Int, ByteArray> {
            val count = buf.short.toInt() and 0xffff
            val map = LinkedHashMap<Int, ByteArray>(count)
            repeat(count) {
                if (buf.remaining() < 4) return map
                val tag = buf.short.toInt() and 0xffff
                val len = buf.short.toInt() and 0xffff
                val data = ByteArray(minOf(len, buf.remaining()))
                buf.get(data)
                map[tag] = data
            }
            return map
        }

        private fun parseTlv512(raw: ByteArray): MutableMap<String, String> {
            val r = ByteBuffer.wrap(raw).order(ByteOrder.BIG_ENDIAN)
            val count = r.short.toInt() and 0xffff
            val map = LinkedHashMap<String, String>(count)
            repeat(count) {
                val domain = readPrefixed16Str(r)
                val key = readPrefixed16Str(r)
                readPrefixed16Str(r) // pt4Token
                map[domain] = key
            }
            return map
        }

        private fun readSmsInfo(tlvPack: Map<Int, ByteArray>): Triple<String, String, String> {
            val smsUrl = tlvPack[0x204]?.toString(Charsets.UTF_8).orEmpty()
            val raw178 = tlvPack[0x178] ?: return Triple("", "", smsUrl)
            val r = ByteBuffer.wrap(raw178).order(ByteOrder.BIG_ENDIAN)
            val country = readPrefixed16Str(r)
            val phone = readPrefixed16Str(r)
            return Triple(country, phone, smsUrl)
        }

        private fun generateTlv547(tlv546: ByteArray): ByteArray {
            val r = ByteBuffer.wrap(tlv546).order(ByteOrder.BIG_ENDIAN)
            val version = r.get()
            val typ = r.get()
            val hashType = r.get()
            r.get() // ok
            val maxIndex = r.short
            val reserved = ByteArray(2).also { r.get(it) }
            val src = readPrefixed16Bytes(r)
            val tgt = readPrefixed16Bytes(r)
            val cpy = readPrefixed16Bytes(r)

            require(tgt.size == 32) { "Only SHA256 PoW supported" }
            val sha = MessageDigest.getInstance("SHA-256")
            var num = BigInteger(1, src)
            var cnt = 0
            val t0 = System.currentTimeMillis()
            while (true) {
                val cand = toUnsignedBytes(num)
                if (sha.digest(cand).contentEquals(tgt)) {
                    val elapsed = (System.currentTimeMillis() - t0).toInt()
                    return ByteArrayOutputStream().apply {
                        write(version.toInt()); write(typ.toInt()); write(hashType.toInt()); write(1)
                        writeShortBE(maxIndex.toInt())
                        write(reserved)
                        writeShortBE(src.size); write(src)
                        writeShortBE(tgt.size); write(tgt)
                        writeShortBE(cpy.size); write(cpy)
                        writeShortBE(cand.size); write(cand)
                        writeIntBE(elapsed)
                        writeIntBE(cnt)
                    }.toByteArray()
                }
                num = num.add(BigInteger.ONE)
                cnt++
                require(cnt <= 6_000_000) { "PoW exceeded max iterations" }
            }
        }

        private fun generateTlv548(): ByteArray {
            val src = Random.nextBytes(128).also { it[0] = 21 }
            val dst = toUnsignedBytes(BigInteger(1, src).add(BigInteger.valueOf(10_000)))
            val tgt = MessageDigest.getInstance("SHA-256").digest(dst)
            val header = ByteArrayOutputStream().apply {
                write(1); write(2); write(1); write(2)
                writeShortBE(10)
                writeShortBE(0)
                writeShortBE(src.size); write(src)
                writeShortBE(tgt.size); write(tgt)
            }.toByteArray()
            val tlv546 = ByteArrayOutputStream().apply {
                write(header)
                writeShortBE(header.size); write(header)
            }.toByteArray()
            return generateTlv547(tlv546)
        }

        private fun toUnsignedBytes(v: BigInteger): ByteArray {
            val raw = v.toByteArray()
            return if (raw.size > 1 && raw[0] == 0.toByte()) raw.copyOfRange(1, raw.size) else raw
        }

        private fun md5(data: ByteArray): ByteArray = MessageDigest.getInstance("MD5").digest(data)

        private fun extract543Url(raw543: ByteArray?): String {
            if (raw543 == null || raw543.isEmpty()) return ""
            val buttonInfo = readPbBytes(raw543, 1) ?: return ""
            var idx = 0
            while (idx < buttonInfo.size) {
                val (tag, nextIdx) = readVarint(buttonInfo, idx)
                idx = nextIdx
                val field = (tag ushr 3).toInt()
                val wire = (tag and 0x07).toInt()
                when (wire) {
                    0 -> idx = readVarint(buttonInfo, idx).second
                    1 -> idx += 8
                    2 -> {
                        val (len, afterLen) = readVarint(buttonInfo, idx)
                        val end = afterLen + len.toInt()
                        if (field == 4 && end <= buttonInfo.size) {
                            val action = buttonInfo.copyOfRange(afterLen, end)
                            val url = readPbBytes(action, 3)?.toString(Charsets.UTF_8).orEmpty()
                            if (url.isNotEmpty()) return url
                        }
                        idx = end
                    }
                    5 -> idx += 4
                    else -> break
                }
            }
            return ""
        }

        private fun readPbBytes(bytes: ByteArray, targetField: Int): ByteArray? {
            var idx = 0
            while (idx < bytes.size) {
                val (tag, nextIdx) = readVarint(bytes, idx)
                idx = nextIdx
                val field = (tag ushr 3).toInt()
                val wire = (tag and 0x07).toInt()
                when (wire) {
                    0 -> {
                        val (_, after) = readVarint(bytes, idx)
                        idx = after
                    }
                    1 -> idx += 8
                    2 -> {
                        val (len, afterLen) = readVarint(bytes, idx)
                        val end = afterLen + len.toInt()
                        if (field == targetField && end <= bytes.size) {
                            return bytes.copyOfRange(afterLen, end)
                        }
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
}

private fun ByteArrayOutputStream.writeShortBE(v: Int) {
    write((v ushr 8) and 0xff)
    write(v and 0xff)
}

private fun ByteArrayOutputStream.writeIntBE(v: Int) {
    write((v ushr 24) and 0xff)
    write((v ushr 16) and 0xff)
    write((v ushr 8) and 0xff)
    write(v and 0xff)
}

private fun ByteArrayOutputStream.writeLongBE(v: Long) {
    for (shift in 56 downTo 0 step 8) {
        write(((v ushr shift) and 0xffL).toInt())
    }
}

private fun ByteArrayOutputStream.writeString16(s: String) {
    val bytes = s.toByteArray(Charsets.UTF_8)
    writeShortBE(bytes.size)
    write(bytes)
}

private fun readPrefixed16Bytes(r: ByteBuffer): ByteArray {
    val len = r.short.toInt() and 0xffff
    val out = ByteArray(minOf(len, r.remaining()))
    r.get(out)
    return out
}

private fun readPrefixed16Str(r: ByteBuffer): String = readPrefixed16Bytes(r).toString(Charsets.UTF_8)
