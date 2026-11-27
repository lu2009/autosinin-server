package io.github.luna.autosinin.qqclient

import kotlin.random.Random

/**
 * Pure Kotlin implementation of QQ TEA (16-round Feistel CBC with random padding).
 */
internal object TeaCipher {
    fun encrypt(data: ByteArray, key: ByteArray): ByteArray = TeaSession().encrypt(data, key)
    fun decrypt(data: ByteArray, key: ByteArray): ByteArray = TeaSession().decrypt(data, key)
}

private class TeaSession {
    private var contextStart = 0
    private var crypt = 0
    private var key: ByteArray = ByteArray(0)
    private var out: ByteArray = ByteArray(0)
    private var padding = 0
    private var plain: ByteArray = ByteArray(0)
    private var pos = 0
    private var preCrypt = 0
    private var prePlain: ByteArray = ByteArray(0)
    private var header = true

    private fun decipher(bArr: ByteArray?, offset: Int = 0): ByteArray {
        var rounds = 16
        var v0 = getUnsignedInt(bArr, offset, 4)
        var v1 = getUnsignedInt(bArr, offset + 4, 4)
        val k0 = getUnsignedInt(key, 0, 4)
        val k1 = getUnsignedInt(key, 4, 4)
        val k2 = getUnsignedInt(key, 8, 4)
        val k3 = getUnsignedInt(key, 12, 4)
        var sum = SUM_INIT
        while (rounds-- > 0) {
            v1 = (v1 - ((((v0 shl 4) + k2) xor (v0 + sum)) xor ((v0 ushr 5) + k3))) and MASK_32
            v0 = (v0 - ((((v1 shl 4) + k0) xor (v1 + sum)) xor ((v1 ushr 5) + k1))) and MASK_32
            sum = (sum - DELTA) and MASK_32
        }
        return encodeIntBE(v0.toInt()) + encodeIntBE(v1.toInt())
    }

    private fun encipher(bArr: ByteArray): ByteArray {
        var rounds = 16
        var v0 = getUnsignedInt(bArr, 0, 4)
        var v1 = getUnsignedInt(bArr, 4, 4)
        val k0 = getUnsignedInt(key, 0, 4)
        val k1 = getUnsignedInt(key, 4, 4)
        val k2 = getUnsignedInt(key, 8, 4)
        val k3 = getUnsignedInt(key, 12, 4)
        var sum = 0L
        while (rounds-- > 0) {
            sum = (sum + DELTA) and MASK_32
            v0 = (v0 + ((((v1 shl 4) + k0) xor (v1 + sum)) xor ((v1 ushr 5) + k1))) and MASK_32
            v1 = (v1 + ((((v0 shl 4) + k2) xor (v0 + sum)) xor ((v0 ushr 5) + k3))) and MASK_32
        }
        return encodeIntBE(v0.toInt()) + encodeIntBE(v1.toInt())
    }

    private fun decrypt8Bytes(bArr: ByteArray, len: Int): Boolean {
        pos = 0
        while (pos < 8) {
            if (contextStart + pos >= len) return true
            prePlain[pos] = (prePlain[pos].toInt() xor bArr[crypt + pos].toInt()).toByte()
            pos++
        }
        prePlain = decipher(prePlain)
        contextStart += 8
        crypt += 8
        pos = 0
        return true
    }

    private fun encrypt8Bytes() {
        pos = 0
        while (pos < 8) {
            plain[pos] = if (header) {
                (plain[pos].toInt() xor prePlain[pos].toInt()).toByte()
            } else {
                (plain[pos].toInt() xor out[preCrypt + pos].toInt()).toByte()
            }
            pos++
        }
        encipher(plain).copyInto(out, crypt)
        pos = 0
        while (pos < 8) {
            val idx = crypt + pos
            out[idx] = (out[idx].toInt() xor prePlain[pos].toInt()).toByte()
            pos++
        }
        plain.copyInto(prePlain)
        preCrypt = crypt
        crypt += 8
        pos = 0
        header = false
    }

    fun encrypt(data: ByteArray, key: ByteArray): ByteArray {
        var idx = 0
        var rem = data.size
        plain = ByteArray(8)
        prePlain = ByteArray(8)
        padding = 0
        preCrypt = 0
        crypt = 0
        this.key = key
        header = true

        pos = (rem + 10) % 8
        if (pos != 0) pos = 8 - pos
        out = ByteArray(pos + rem + 10)
        plain[0] = ((rand() and 248) or pos).toByte()
        for (i in 1..pos) {
            plain[i] = (rand() and 255).toByte()
        }
        pos++
        prePlain.fill(0)
        padding = 1
        while (padding <= 2) {
            if (pos < 8) {
                plain[pos++] = (rand() and 255).toByte()
                padding++
            }
            if (pos == 8) encrypt8Bytes()
        }
        while (rem > 0) {
            if (pos < 8) {
                plain[pos++] = data[idx++]
                rem--
            }
            if (pos == 8) encrypt8Bytes()
        }
        padding = 1
        while (padding <= 7) {
            if (pos < 8) {
                plain[pos++] = 0
                padding++
            }
            if (pos == 8) encrypt8Bytes()
        }
        return out
    }

    fun decrypt(data: ByteArray, key: ByteArray): ByteArray {
        val len = data.size
        require(len % 8 == 0 && len >= 16) { "Invalid TEA cipher length: $len" }
        this.key = key
        crypt = 0
        preCrypt = 0
        prePlain = decipher(data, 0)
        pos = prePlain[0].toInt() and 7
        var outLen = len - pos - 10
        require(outLen >= 0) { "Invalid TEA decrypted length: $outLen" }
        out = ByteArray(outLen)
        preCrypt = 0
        crypt = 8
        contextStart = 8
        pos++
        padding = 1
        var mArr = ByteArray(len)
        while (padding <= 2) {
            if (pos < 8) {
                pos++
                padding++
            }
            if (pos == 8) {
                mArr = data
                if (!decrypt8Bytes(data, len)) return ByteArray(0)
            }
        }
        var outIdx = 0
        while (outLen != 0) {
            if (pos < 8) {
                out[outIdx++] = (mArr[preCrypt + pos].toInt() xor prePlain[pos].toInt()).toByte()
                pos++
                outLen--
            }
            if (pos == 8) {
                mArr = data
                preCrypt = crypt - 8
                if (!decrypt8Bytes(data, len)) return ByteArray(0)
            }
        }
        padding = 1
        while (padding < 8) {
            if (pos < 8) {
                if ((mArr[preCrypt + pos].toInt() xor prePlain[pos].toInt()) != 0) {
                    return ByteArray(0)
                }
                pos++
            }
            if (pos == 8) {
                mArr = data
                preCrypt = crypt
                if (!decrypt8Bytes(data, len)) return ByteArray(0)
            }
            padding++
        }
        return out
    }

    private fun rand(): Int = Random.nextInt()

    private fun getUnsignedInt(bArr: ByteArray?, offset: Int, len: Int): Long {
        if (bArr == null) return 0L
        var res = 0L
        val end = minOf(offset + len, bArr.size)
        for (i in offset until end) {
            res = (res shl 8) or (bArr[i].toInt() and 0xff).toLong()
        }
        return res and MASK_32
    }

    private fun encodeIntBE(value: Int): ByteArray = byteArrayOf(
        ((value ushr 24) and 0xff).toByte(),
        ((value ushr 16) and 0xff).toByte(),
        ((value ushr 8) and 0xff).toByte(),
        (value and 0xff).toByte(),
    )

    companion object {
        private const val DELTA = 0x9e3779b9L
        private const val SUM_INIT = 0xe3779b90L
        private const val MASK_32 = 0xffffffffL
    }
}
