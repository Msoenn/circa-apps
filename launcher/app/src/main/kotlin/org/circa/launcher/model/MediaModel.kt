package org.circa.launcher.model

/**
 * One `/music` row from WatchLink's phone-data provider, reduced to what the media tile needs
 * (watchlink/DATA-CONTRACT.md). `state` is `play` / `pause` / `stop` (`""`/absent when
 * unknown); `positionAtMs` is when `positionS` was reported.
 */
data class MusicState(
    val updatedMs: Long,
    val state: String,
    val artist: String?,
    val album: String?,
    val track: String?,
    val durationS: Int,
    val positionS: Int,
    val positionAtMs: Long,
)

/** What the media tile draws: connected, nothing playing, or a track with its transport state. */
sealed interface MediaView {
    /** `/status` says the phone is not connected (or the provider could not be reached at all). */
    data object NotConnected : MediaView

    /** Connected but nothing is playing (no row, or the row is stale). */
    data object NothingPlaying : MediaView

    /** A live track: 1-line title and artist, and whether the play/pause button should show Pause. */
    data class NowPlaying(val title: String, val artist: String, val playing: Boolean) : MediaView
}

/**
 * Selection and formatting rules for the media tile. Pure and JVM-testable: no Android types.
 * Same staleness rule as Circa Companion's Media screen, so the tile and the app agree.
 */
object MediaModel {
    /** The contract: a row is stale when `updated_ms` is older than this and the state is not `play`. */
    const val STALE_MS = 10 * 60 * 1000L

    /** Shown when the row carries no track name. */
    const val UNKNOWN_TRACK = "Unknown track"

    /** A missing / zero timestamp is stale; otherwise only a non-playing row can go stale. */
    fun isStale(updatedMs: Long?, state: String?, nowMs: Long): Boolean {
        if (updatedMs == null || updatedMs <= 0) return true
        return state != "play" && nowMs - updatedMs > STALE_MS
    }

    /**
     * The tile's state. Not connected always wins (from `/status`; `null` = provider unreachable, so
     * also "not connected"). Otherwise a missing or stale row is "Nothing playing", else the track.
     * Title falls back to [UNKNOWN_TRACK] and artist to the album (both blank stay blank).
     */
    fun view(music: MusicState?, connected: Boolean?, nowMs: Long): MediaView {
        if (connected != true) return MediaView.NotConnected
        if (music == null || isStale(music.updatedMs, music.state, nowMs)) return MediaView.NothingPlaying
        val title = music.track?.trim().takeUnless { it.isNullOrEmpty() } ?: UNKNOWN_TRACK
        val artist = music.artist?.trim().takeUnless { it.isNullOrEmpty() }
            ?: music.album?.trim().orEmpty()
        return MediaView.NowPlaying(title, artist, playing = music.state == "play")
    }
}
