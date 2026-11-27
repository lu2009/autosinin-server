package io.github.luna.autosinin.qqclient

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class QqClientTest {

    @Test
    fun teaEncryptDecryptRoundTrip() {
        val key = Random.nextBytes(16)
        val plain = "hello-qq-sso-protocol-2026".toByteArray(Charsets.UTF_8)
        val cipher = TeaCipher.encrypt(plain, key)
        val decoded = TeaCipher.decrypt(cipher, key)
        assertArrayEquals(plain, decoded)
    }

    @Test
    fun secp192k1EcdhGeneratesCompressedKeyAndSharedSecret() {
        val bobPub =
            "04928D8850673088B343264E0C6BACB8496D697799F37211DEB25BB73906CB089FEA9639B4E0260498B51A992D50813DA8".hexToBytes()
        val alice = Secp192k1Ecdh.generate()
        val pub = alice.packPublic(compressed = true)
        assertEquals(25, pub.size)
        assertTrue(pub[0] == 0x02.toByte() || pub[0] == 0x03.toByte())
        val shared = alice.keyExchange(bobPub, hashMd5 = true)
        assertEquals(16, shared.size)
    }

    @Test
    fun androidSessionStoreParsesExistingFormat() {
        val store = AndroidSessionStore.empty(3885997084L, "pass", "82925c0e28a82a89", "24091RPADC")
        store.wloginSigs.a2 = byteArrayOf(1, 2, 3, 4)
        store.wloginSigs.d2Key = Random.nextBytes(16)
        val json = store.toJson()
        val parsed = AndroidSessionStore.fromJson(json)
        assertEquals(3885997084L, parsed.uin)
        assertEquals("24091RPADC", parsed.deviceName)
        assertArrayEquals(store.wloginSigs.a2, parsed.wloginSigs.a2)
        assertArrayEquals(store.wloginSigs.d2Key, parsed.wloginSigs.d2Key)
    }
}
