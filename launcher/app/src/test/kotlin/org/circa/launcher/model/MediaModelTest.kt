package org.circa.launcher.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaModelTest {
    private val now = 1_700_000_000_000L
    private val min = 60_000L

    private fun music(
        updated: Long = now,
        state: String = "play",
        track: String? = "Digital Love",
        artist: String? = "Daft Punk",
        album: String? = "Discovery",
    ) = MusicState(updated, state, artist, album, track, 301, 12, now)

    @Test fun staleRule() {
        assertTrue(MediaModel.isStale(null, "play", now))
        assertTrue(MediaModel.isStale(0, "play", now))
        assertFalse("a playing row never goes stale", MediaModel.isStale(now - 30 * min, "play", now))
        assertFalse(MediaModel.isStale(now - 9 * min, "pause", now))
        assertTrue(MediaModel.isStale(now - 11 * min, "pause", now))
        assertTrue(MediaModel.isStale(now - 11 * min, "stop", now))
        assertTrue(MediaModel.isStale(now - 11 * min, null, now))
    }

    @Test fun notConnectedWins() {
        assertEquals(MediaView.NotConnected, MediaModel.view(music(), false, now))
        // null = the provider could not be read at all, which is also "not connected".
        assertEquals(MediaView.NotConnected, MediaModel.view(music(), null, now))
    }

    @Test fun nothingPlayingOnEmptyOrStale() {
        assertEquals(MediaView.NothingPlaying, MediaModel.view(null, true, now))
        assertEquals(
            MediaView.NothingPlaying,
            MediaModel.view(music(updated = now - 11 * min, state = "pause"), true, now),
        )
    }

    @Test fun nowPlaying() {
        assertEquals(MediaView.NowPlaying("Digital Love", "Daft Punk", true), MediaModel.view(music(state = "play"), true, now))
        assertEquals(MediaView.NowPlaying("Digital Love", "Daft Punk", false), MediaModel.view(music(state = "pause"), true, now))
    }

    @Test fun titleAndArtistFallbacks() {
        val blank = MediaModel.view(music(track = "  ", artist = " ", album = "Discovery"), true, now) as MediaView.NowPlaying
        assertEquals(MediaModel.UNKNOWN_TRACK, blank.title)
        assertEquals("Discovery", blank.artist)

        val noArtist = MediaModel.view(music(artist = null, album = null), true, now) as MediaView.NowPlaying
        assertEquals("", noArtist.artist)
    }
}
