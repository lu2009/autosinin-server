package io.github.luna.autosinin.qsign

import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class QsignHealth(val ok: Boolean, val libfekitExists: Boolean)

@Serializable
data class SignRequest(
    val uin: Long,
    val cmd: String,
    val buffer: String,
    val guid: String = "",
    val seq: Int = 0,
    val version: String = "",
    val qua: String = "",
)

@Serializable
data class SignValue(
    val sign: String,
    val token: String,
    val extra: String,
)

@Serializable
data class EnergyRequest(
    val uin: Long,
    val data: String,
    val guid: String = "",
    val ver: String = "",
    val version: String = "",
    val qua: String = "",
)

@Serializable
data class DebugXwidRequest(
    val uin: Long,
    val data: String,
    val guid: String = "",
    val version: String = "",
    val qua: String = "",
)

@Serializable
data class QsignResponse<T>(
    val code: Int,
    val msg: String,
    val data: T? = null,
)

fun Application.qsignModule(emulator: FekitEmulator?, fekitExists: Boolean) {
    install(ContentNegotiation) {
        json(Json { ignoreUnknownKeys = true; encodeDefaults = true })
    }
    routing {
        get("/health") {
            call.respond(HttpStatusCode.OK, QsignHealth(ok = emulator != null, libfekitExists = fekitExists))
        }
        post("/sign") {
            val emu = emulator ?: return@post call.respond(
                HttpStatusCode.ServiceUnavailable,
                QsignResponse<SignValue>(code = -1, msg = "libfekit.so not loaded"),
            )
            val req = call.receive<SignRequest>()
            val t0 = System.currentTimeMillis()
            val out = emu.sign(
                uin = req.uin.toString(),
                cmd = req.cmd,
                buffer = hexToBytes(req.buffer),
                seq = req.seq,
                quaOverride = req.qua.ifBlank { null },
                guidOverride = req.guid.ifBlank { null },
            )
            println("[QSIGN /sign] uin=${req.uin} cmd=${req.cmd} seq=${req.seq} qua=${req.qua} signLen=${out.sign.size} tokenLen=${out.token.size} extraLen=${out.extra.size} ms=${System.currentTimeMillis() - t0}")
            call.respond(
                HttpStatusCode.OK,
                QsignResponse(
                    code = 0,
                    msg = "success",
                    data = SignValue(
                        sign = out.sign.toHex(),
                        token = out.token.toHex(),
                        extra = out.extra.toHex(),
                    ),
                ),
            )
        }
        post("/energy") {
            val emu = emulator ?: return@post call.respond(
                HttpStatusCode.ServiceUnavailable,
                QsignResponse<String>(code = -1, msg = "libfekit.so not loaded"),
            )
            val req = call.receive<EnergyRequest>()
            val t0 = System.currentTimeMillis()
            val payload = decodeHexOrUtf8(req.data)
            val out = emu.energy(
                uin = req.uin.toString(),
                salt = req.ver,
                data = payload,
                guidOverride = req.guid.ifBlank { null },
            )
            println("[QSIGN /energy] uin=${req.uin} ver=${req.ver} dataLen=${payload.size} outLen=${out.size} ms=${System.currentTimeMillis() - t0}")
            call.respond(
                HttpStatusCode.OK,
                QsignResponse(code = 0, msg = "success", data = out.toHex()),
            )
        }
        post("/get_tlv553") {
            val emu = emulator ?: return@post call.respond(
                HttpStatusCode.ServiceUnavailable,
                QsignResponse<String>(code = -1, msg = "libfekit.so not loaded"),
            )
            val req = call.receive<DebugXwidRequest>()
            val t0 = System.currentTimeMillis()
            val out = emu.getDebugXwid(
                uin = req.uin.toString(),
                data = req.data,
                guidOverride = req.guid.ifBlank { null },
            )
            println("[QSIGN /get_tlv553] uin=${req.uin} data=${req.data} outLen=${out.size} ms=${System.currentTimeMillis() - t0}")
            call.respond(
                HttpStatusCode.OK,
                QsignResponse(code = 0, msg = "success", data = out.toHex()),
            )
        }
    }
}

private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

private fun hexToBytes(hex: String): ByteArray {
    val clean = hex.trim()
    if (clean.isEmpty()) return ByteArray(0)
    require(clean.length % 2 == 0) { "Invalid hex length: ${clean.length}" }
    return ByteArray(clean.length / 2) { i ->
        clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
    }
}

private fun decodeHexOrUtf8(value: String): ByteArray {
    val clean = value.trim()
    if (clean.isEmpty()) return ByteArray(0)
    val isHex = clean.length % 2 == 0 && clean.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }
    return if (isHex) hexToBytes(clean) else clean.toByteArray(Charsets.UTF_8)
}

fun main() {
    val port = System.getenv("QSIGN_PORT")?.toIntOrNull() ?: 8081
    val fekitFile = File(System.getenv("QSIGN_FEKIT_PATH") ?: "data/qsign/libfekit.so")
    val qsecFile = File(System.getenv("QSIGN_QSEC_PATH") ?: "data/qsign/libQSec.so")
    val emulator = if (fekitFile.exists()) FekitEmulator(fekitFile, qsecFile) else null

    embeddedServer(Netty, port = port, host = "0.0.0.0") {
        qsignModule(emulator, fekitFile.exists())
    }.start(wait = true)
}
