package io.github.luna.autosinin.qsign

import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class QsignRoutesTest {

    @Test
    fun healthAndSignRoutesReturnValidJson() = testApplication {
        val fekit = File("../data/qsign/libfekit.so").let { if (it.exists()) it else File("data/qsign/libfekit.so") }
        val qsec = File("../data/qsign/libQSec.so").let { if (it.exists()) it else File("data/qsign/libQSec.so") }
        val emu = FekitEmulator(fekit, qsec)
        try {
            application {
                qsignModule(emu, fekit.exists())
            }

            val healthResp = client.get("/health")
            assertEquals(HttpStatusCode.OK, healthResp.status)
            assertTrue(healthResp.bodyAsText().contains("\"ok\":true"))

            val signResp = client.post("/sign") {
                contentType(ContentType.Application.Json)
                setBody("""{"uin":123456789,"cmd":"wtlogin.login","buffer":"01020304","seq":100,"version":"9.3.85","qua":"V1_AND_SQ_9.3.85_14154_YYB_D"}""")
            }
            assertEquals(HttpStatusCode.OK, signResp.status)
            val signJson = Json.parseToJsonElement(signResp.bodyAsText()).jsonObject
            assertEquals("0", signJson["code"]?.jsonPrimitive?.content)
            val signData = signJson["data"]?.jsonObject
            assertTrue((signData?.get("sign")?.jsonPrimitive?.content?.length ?: 0) > 0)
            assertTrue((signData?.get("extra")?.jsonPrimitive?.content?.length ?: 0) > 0)

            val energyResp = client.post("/energy") {
                contentType(ContentType.Application.Json)
                setBody("""{"uin":123456789,"data":"010203040506","ver":"810_9","version":"9.3.85","qua":"V1_AND_SQ_9.3.85_14154_YYB_D"}""")
            }
            assertEquals(HttpStatusCode.OK, energyResp.status)
            val energyJson = Json.parseToJsonElement(energyResp.bodyAsText()).jsonObject
            assertEquals("0", energyJson["code"]?.jsonPrimitive?.content)
            assertTrue((energyJson["data"]?.jsonPrimitive?.content?.length ?: 0) > 0)

            val xwidResp = client.post("/get_tlv553") {
                contentType(ContentType.Application.Json)
                setBody("""{"uin":123456789,"data":"810_9","version":"9.3.85","qua":"V1_AND_SQ_9.3.85_14154_YYB_D"}""")
            }
            assertEquals(HttpStatusCode.OK, xwidResp.status)
            val xwidJson = Json.parseToJsonElement(xwidResp.bodyAsText()).jsonObject
            assertEquals("0", xwidJson["code"]?.jsonPrimitive?.content)
            assertTrue((xwidJson["data"]?.jsonPrimitive?.content?.length ?: 0) > 0)
        } finally {
            emu.close()
        }
    }
}
