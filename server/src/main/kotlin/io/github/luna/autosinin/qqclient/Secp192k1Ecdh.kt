package io.github.luna.autosinin.qqclient

import java.math.BigInteger
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Pure Kotlin/JVM BigInteger ECDH over secp192k1 for QQ WtLogin.
 */
internal class Secp192k1Ecdh private constructor(
    private val secret: BigInteger,
    private val publicPoint: Point,
) {
    data class Point(val x: BigInteger, val y: BigInteger) {
        val isInfinity: Boolean get() = x == BigInteger.ZERO && y == BigInteger.ZERO
    }

    fun packPublic(compressed: Boolean = true): ByteArray {
        val xBytes = toFixedBytes(publicPoint.x, 24)
        return if (compressed) {
            val prefix = if (publicPoint.y.testBit(0)) 0x03.toByte() else 0x02.toByte()
            byteArrayOf(prefix) + xBytes
        } else {
            val yBytes = toFixedBytes(publicPoint.y, 24)
            byteArrayOf(0x04) + xBytes + yBytes
        }
    }

    fun keyExchange(bobPublicKey: ByteArray, hashMd5: Boolean = true): ByteArray {
        val bobPoint = unpackPublic(bobPublicKey)
        val shared = scalarMul(secret, bobPoint)
        val xBytes = toFixedBytes(shared.x, 24)
        return if (hashMd5) {
            MessageDigest.getInstance("MD5").digest(xBytes)
        } else {
            xBytes
        }
    }

    companion object {
        private val P = BigInteger("FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEFFFFEE37", 16)
        private val A = BigInteger.ZERO
        private val B = BigInteger.valueOf(3)
        private val N = BigInteger("FFFFFFFFFFFFFFFFFFFFFFFE26F2FC170F69466A74DEFD8D", 16)
        private val GX = BigInteger("DB4FF10EC057E9AE26B07D0280B7F4341DA5D1B1EAE06C7D", 16)
        private val GY = BigInteger("9B2F2F6D9C5628A7844163D015BE86344082AA88D95E2F9D", 16)
        private val G = Point(GX, GY)
        private val INFINITY = Point(BigInteger.ZERO, BigInteger.ZERO)
        private val THREE = BigInteger.valueOf(3)
        private val TWO = BigInteger.valueOf(2)
        private val RNG = SecureRandom()

        fun generate(): Secp192k1Ecdh {
            var k: BigInteger
            do {
                k = BigInteger(192, RNG)
            } while (k <= BigInteger.ONE || k >= N)
            val pub = scalarMul(k, G)
            return Secp192k1Ecdh(k, pub)
        }

        internal fun fromSecret(k: BigInteger): Secp192k1Ecdh = Secp192k1Ecdh(k, scalarMul(k, G))

        private fun unpackPublic(bytes: ByteArray): Point {
            require(bytes.isNotEmpty()) { "Empty public key" }
            return when (bytes[0].toInt() and 0xff) {
                0x04 -> {
                    require(bytes.size == 49) { "Invalid uncompressed secp192k1 key size: ${bytes.size}" }
                    val x = BigInteger(1, bytes.copyOfRange(1, 25))
                    val y = BigInteger(1, bytes.copyOfRange(25, 49))
                    Point(x, y)
                }
                0x02, 0x03 -> {
                    require(bytes.size == 25) { "Invalid compressed secp192k1 key size: ${bytes.size}" }
                    val x = BigInteger(1, bytes.copyOfRange(1, 25))
                    val rhs = x.modPow(THREE, P).add(A.multiply(x)).add(B).mod(P)
                    // P % 4 == 3 for secp192k1, so sqrt = rhs^((P+1)/4) mod P
                    val yCand = rhs.modPow(P.add(BigInteger.ONE).shiftRight(2), P)
                    val wantOdd = (bytes[0].toInt() and 0xff) == 0x03
                    val y = if (yCand.testBit(0) == wantOdd) yCand else P.subtract(yCand)
                    Point(x, y)
                }
                else -> error("Unsupported public key prefix: ${bytes[0]}")
            }
        }

        private fun pointAdd(p: Point, q: Point): Point {
            if (p.isInfinity) return q
            if (q.isInfinity) return p
            if (p.x == q.x) {
                if (p.y != q.y || p.y == BigInteger.ZERO) return INFINITY
                val lambda = THREE.multiply(p.x.multiply(p.x)).add(A)
                    .multiply(TWO.multiply(p.y).modInverse(P)).mod(P)
                val rx = lambda.multiply(lambda).subtract(TWO.multiply(p.x)).mod(P)
                val ry = lambda.multiply(p.x.subtract(rx)).subtract(p.y).mod(P)
                return Point(rx, ry)
            }
            val lambda = q.y.subtract(p.y).multiply(q.x.subtract(p.x).modInverse(P)).mod(P)
            val rx = lambda.multiply(lambda).subtract(p.x).subtract(q.x).mod(P)
            val ry = lambda.multiply(p.x.subtract(rx)).subtract(p.y).mod(P)
            return Point(rx, ry)
        }

        private fun scalarMul(k: BigInteger, p: Point): Point {
            var res = INFINITY
            var addend = p
            var rem = k
            while (rem > BigInteger.ZERO) {
                if (rem.testBit(0)) res = pointAdd(res, addend)
                addend = pointAdd(addend, addend)
                rem = rem.shiftRight(1)
            }
            return res
        }

        private fun toFixedBytes(v: BigInteger, size: Int): ByteArray {
            val raw = v.toByteArray()
            return when {
                raw.size == size -> raw
                raw.size > size -> raw.copyOfRange(raw.size - size, raw.size)
                else -> ByteArray(size - raw.size) + raw
            }
        }
    }
}
