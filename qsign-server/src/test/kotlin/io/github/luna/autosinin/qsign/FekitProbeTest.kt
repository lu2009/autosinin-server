package io.github.luna.autosinin.qsign

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class FekitProbeTest {

    @Test
    fun loadFekitAndSignSamplePacket() {
        val fekit = File("../data/qsign/libfekit.so").let { if (it.exists()) it else File("data/qsign/libfekit.so") }
        val qsec = File("../data/qsign/libQSec.so").let { if (it.exists()) it else File("data/qsign/libQSec.so") }
        assertTrue("libfekit.so missing at ${fekit.absolutePath}", fekit.exists())
        val emu = FekitEmulator(fekit, qsec)
        try {
            val res = emu.sign("123456789", "wtlogin.login", byteArrayOf(1, 2, 3, 4), 100)
            println("SIGN len=${res.sign.size} token=${res.token.size} extra=${res.extra.size}")
            assertTrue("sign output should not be empty", res.sign.isNotEmpty())
            assertTrue("extra output should not be empty", res.extra.isNotEmpty())

            val energy = emu.energy("123456789", "810_9", byteArrayOf(1, 2, 3, 4, 5, 6))
            println("ENERGY len=${energy.size}")
            assertTrue("energy output should not be empty", energy.isNotEmpty())

            val xwid = emu.getDebugXwid("123456789", "810_9")
            println("XWID len=${xwid.size}")
            assertTrue("xwid output should not be empty", xwid.isNotEmpty())
        } finally {
            emu.close()
        }
    }
}
