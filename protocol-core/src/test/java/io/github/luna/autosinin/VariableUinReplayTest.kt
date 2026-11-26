package io.github.luna.autosinin

import io.github.luna.autosinin.protocol.NovelReport
import io.github.luna.autosinin.protocol.PublishReplay
import org.junit.Assert.assertTrue
import org.junit.Test

class VariableUinReplayTest {

    @Test
    fun publishSupportsNineDigitUin() {
        val b = PublishReplay.build(
            PublishReplay.Params(
                uin = "604131924",
                trace = "604131924_1795323000000_1234",
                uuid = "035be639-cd8a-45ac-8375-fce425a76cb5",
                ts = 1795323000L,
                seq = 5,
            ),
        )
        assertTrue(b.isNotEmpty())
    }

    @Test
    fun novelSupportsNineDigitUin() {
        val b = NovelReport.build(
            "604131924",
            "604131924_1795323000000_1234",
            1795323000000L,
            180_000,
        )
        assertTrue(b.isNotEmpty())
    }
}
