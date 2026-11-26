package io.github.luna.autosinin

import io.github.luna.autosinin.protocol.FeedRefreshReplay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FeedRefreshReplayTest {

    @Test
    fun pageHeadKeepsSizeAndRotatesSession() {
        val b = FeedRefreshReplay.buildPageHead(
            "3937903488", 1795279999000L, 53, "3937903488_1795279999000_3193",
        )
        assertEquals(FeedRefreshReplay.pageHeadTemplate().size, b.size)
        assertTrue(String(b, Charsets.ISO_8859_1).contains("3937903488_1795279999000"))
    }

    @Test
    fun feedsKeepsSizeAndRotatesSession() {
        val b = FeedRefreshReplay.buildFeeds(
            "3937903488", 1795279999000L, 52, "3937903488_1795279999000_3204",
        )
        assertEquals(FeedRefreshReplay.feedsTemplate().size, b.size)
        assertTrue(String(b, Charsets.ISO_8859_1).contains("3937903488_1795279999000"))
    }

    @Test
    fun jsonLengthStaysConstant() {
        val a = FeedRefreshReplay.jsonFor("3937903488", 1795279999000L)
        val b = FeedRefreshReplay.jsonFor("3937903488", 1795279678344L)
        assertEquals(a.toByteArray().size, b.toByteArray().size)
    }
}
