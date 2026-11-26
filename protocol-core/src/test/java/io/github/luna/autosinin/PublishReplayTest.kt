package io.github.luna.autosinin

import io.github.luna.autosinin.protocol.PublishReplay
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PublishReplayTest {

    @Test
    fun capturedValuesRoundTrip() {
        val built = PublishReplay.build(
            PublishReplay.Params(
                uin = "3937903488",
                trace = "3937903488_1795278346573_9397",
                uuid = "035be639-cd8a-45ac-8375-fce425a76cb5",
                ts = 1795278346L,
                seq = 5,
            ),
        )
        assertArrayEquals(PublishReplay.template(), built)
    }

    @Test
    fun rotatedValuesKeepLength() {
        val built = PublishReplay.build(
            PublishReplay.Params(
                uin = "3937903488",
                trace = "3937903488_1795279999999_9999",
                uuid = "11111111-2222-3333-4444-555555555555",
                ts = 1795279999L,
                seq = 3,
            ),
        )
        assertEquals(PublishReplay.template().size, built.size)
    }

    @Test
    fun publicBlindBoxIsPublicWithAutoDeleteAndEventTags() {
        val built = PublishReplay.buildPublicBlindBox(
            PublishReplay.Params(
                uin = "2475318409",
                trace = "2475318409_1795279999999_9999",
                uuid = "11111111-2222-3333-4444-555555555555",
                ts = 1795279999L,
                seq = 5,
            ),
        )
        val text = built.toString(Charsets.UTF_8)
        assertTrue(text.contains("event_tags"))
        assertTrue(text.contains("self_delete_time"))
        assertTrue(text.contains("86400"))
        assertFalse(text.contains("仅自己可见"))
    }
}
