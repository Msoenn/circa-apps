package org.circa.launcher.data

import android.content.ContentResolver
import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import org.circa.launcher.model.CalendarEvent
import org.circa.launcher.model.MusicState
import org.circa.launcher.model.NavState
import org.circa.launcher.model.WeatherReading

/**
 * WatchLink's phone-data provider (watchlink/DATA-CONTRACT.md). Readable only by a system app named
 * `org.circa.launcher` (the launcher is a priv-app in /product on Circa); anyone else gets a SecurityException.
 */
object PhoneDataContract {
    const val AUTHORITY = "org.circa.watchlink.data"
    val BASE: Uri = Uri.parse("content://$AUTHORITY")
    val WEATHER: Uri = Uri.withAppendedPath(BASE, "weather")
    val CALENDAR: Uri = Uri.withAppendedPath(BASE, "calendar")
    val MUSIC: Uri = Uri.withAppendedPath(BASE, "music")
    val STATUS: Uri = Uri.withAppendedPath(BASE, "status")
    val NAV: Uri = Uri.withAppendedPath(BASE, "nav")
}

/**
 * Feeds the face's weather and next-event complications, its navigation view, and the media/agenda tiles. Event
 * driven: one [ContentObserver] on the provider (WatchLink calls `notifyChange` on every write, coalesced to
 * 300 ms), one read at [start]. No timer. Staleness and "is this event over yet" are decided at draw time from the
 * clock, not here; a route WatchLink has gone quiet on is dropped by the provider itself.
 *
 * Never throws: WatchLink missing, a SecurityException or a malformed cursor leave the state empty, so the
 * complications hide, the face draws normally and the tiles fall back to their empty states.
 */
class PhoneDataRepository(private val context: Context) {
    private val resolver: ContentResolver = context.contentResolver
    private val handler = Handler(Looper.getMainLooper())
    private var started = false

    private val _weather: MutableState<WeatherReading?> = mutableStateOf(null)
    private val _events: MutableState<List<CalendarEvent>> = mutableStateOf(emptyList())
    private val _music: MutableState<MusicState?> = mutableStateOf(null)
    private val _connected: MutableState<Boolean?> = mutableStateOf(null)
    private val _nav: MutableState<NavState?> = mutableStateOf(null)

    val weather: State<WeatherReading?> get() = _weather
    val events: State<List<CalendarEvent>> get() = _events
    val music: State<MusicState?> get() = _music

    /** `/status` `connected`: true/false from WatchLink, null while the provider cannot be read. */
    val connected: State<Boolean?> get() = _connected

    /** The active route, null when there is none. */
    val nav: State<NavState?> get() = _nav

    private val observer = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean, uri: Uri?) {
            if (!started) return
            when (uri?.lastPathSegment) {
                "weather" -> readWeather()
                "calendar" -> readCalendar()
                "music" -> readMusic()
                "status" -> readStatus()
                "nav" -> readNav()
                else -> refresh() // unknown: cheap enough to re-read everything
            }
        }
    }

    fun start() {
        if (started) return
        started = true
        runCatching { resolver.registerContentObserver(PhoneDataContract.BASE, true, observer) }
        refresh()
    }

    fun stop() {
        if (!started) return
        started = false
        runCatching { resolver.unregisterContentObserver(observer) }
    }

    fun refresh() {
        readWeather()
        readCalendar()
        readMusic()
        readStatus()
        readNav()
    }

    /**
     * Send a media transport command (`playpause` / `next` / `previous`) through WatchLink's
     * `call("music", …)`. Off the main thread: `call` is a blocking Binder call, and a failure
     * (`not_connected`) only matters as a log line - the tile redraws from the next `/music` write.
     */
    fun sendMusicCommand(command: String) {
        Thread {
            runCatching { resolver.call(PhoneDataContract.MUSIC, "music", command, null) }
                .onFailure { Log.d(TAG, "phone data: music $command failed: $it") }
        }.apply { name = "phone-data-call"; isDaemon = true }.start()
    }

    private fun readWeather() {
        _weather.value = try {
            resolver.query(
                PhoneDataContract.WEATHER,
                arrayOf("updated_ms", "temp_c", "code"),
                null, null, null,
            )?.use { c ->
                if (c.moveToFirst() && !c.isNull(0) && !c.isNull(1)) {
                    WeatherReading(c.getLong(0), c.getInt(1), if (c.isNull(2)) 0 else c.getInt(2))
                } else {
                    null
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "phone data: /weather not readable: $e")
            null
        }
    }

    private fun readCalendar() {
        _events.value = try {
            resolver.query(
                PhoneDataContract.CALENDAR,
                arrayOf("title", "start_ms", "end_ms", "all_day", "color"),
                null, null, null,
            )?.use { c ->
                buildList {
                    while (c.moveToNext()) {
                        if (c.isNull(1)) continue
                        val start = c.getLong(1)
                        add(
                            CalendarEvent(
                                title = c.getString(0).orEmpty(),
                                startMs = start,
                                endMs = if (c.isNull(2)) start else c.getLong(2),
                                allDay = !c.isNull(3) && c.getInt(3) != 0,
                                color = if (c.isNull(4)) 0 else c.getInt(4),
                            ),
                        )
                    }
                }
            }.orEmpty()
        } catch (e: Exception) {
            Log.d(TAG, "phone data: /calendar not readable: $e")
            emptyList()
        }
    }

    private fun readMusic() {
        _music.value = try {
            resolver.query(
                PhoneDataContract.MUSIC,
                arrayOf("updated_ms", "state", "artist", "album", "track", "duration_s", "position_s", "position_at_ms"),
                null, null, null,
            )?.use { c ->
                if (c.moveToFirst()) {
                    MusicState(
                        updatedMs = if (c.isNull(0)) 0L else c.getLong(0),
                        state = c.getString(1).orEmpty(),
                        artist = c.getString(2),
                        album = c.getString(3),
                        track = c.getString(4),
                        durationS = if (c.isNull(5)) 0 else c.getInt(5),
                        positionS = if (c.isNull(6)) 0 else c.getInt(6),
                        positionAtMs = if (c.isNull(7)) 0L else c.getLong(7),
                    )
                } else {
                    null
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "phone data: /music not readable: $e")
            null
        }
    }

    private fun readStatus() {
        _connected.value = try {
            resolver.query(
                PhoneDataContract.STATUS,
                arrayOf("connected"),
                null, null, null,
            )?.use { c ->
                if (c.moveToFirst() && !c.isNull(0)) c.getInt(0) != 0 else null
            }
        } catch (e: Exception) {
            Log.d(TAG, "phone data: /status not readable: $e")
            null
        }
    }

    /** One row while a route is active, none when there is (WatchLink decides that, stale routes included). */
    private fun readNav() {
        _nav.value = try {
            resolver.query(
                PhoneDataContract.NAV,
                arrayOf("updated_ms", "instruction", "distance", "distance_m", "action", "eta"),
                null, null, null,
            )?.use { c ->
                if (c.moveToFirst()) {
                    NavState(
                        updatedMs = if (c.isNull(0)) 0L else c.getLong(0),
                        instruction = c.getString(1),
                        distance = c.getString(2).orEmpty(),
                        distanceM = if (c.isNull(3)) -1L else c.getLong(3),
                        action = c.getString(4),
                        eta = c.getString(5),
                    )
                } else {
                    null
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "phone data: /nav not readable: $e")
            null
        }
    }

    private companion object {
        const val TAG = "AuroraLauncher"
    }
}
