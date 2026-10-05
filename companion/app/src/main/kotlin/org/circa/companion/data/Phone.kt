package org.circa.companion.data

import android.content.ContentResolver
import android.database.ContentObserver
import android.database.Cursor
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withContext
import org.circa.companion.model.CalEvent

/** One reading of a provider table: a [value], or an [error] ("denied", "unavailable"). */
class Snap<T>(val value: T?, val error: String? = null)

data class PhoneStatus(val connected: Boolean, val lastSeenMs: Long)

data class WeatherNow(
    val updatedMs: Long, val location: String?, val tempC: Int?, val hiC: Int?, val loC: Int?, val code: Int?,
    val text: String?, val humidity: Int?, val windKmh: Float?, val rainPct: Int?, val forecastJson: String?,
)

data class MusicNow(
    val updatedMs: Long, val state: String, val artist: String?, val album: String?, val track: String?,
    val durationS: Int, val positionS: Int, val positionAtMs: Long,
)

data class CallResult(val ok: Boolean, val error: String?) {
    /** Text for the user when a command failed. */
    val message: String get() = when (error) {
        null -> ""
        "not_connected" -> "Phone not connected"
        "denied" -> "Not allowed"
        else -> "Phone unavailable"
    }
}

/**
 * Client of WatchLink's phone-data provider (watchlink/DATA-CONTRACT.md). The provider only answers
 * system `org.circa.*` packages; anything else gets a SecurityException, reported here as the error "denied".
 */
object Phone {
    const val AUTHORITY = "org.circa.watchlink.data"
    fun uri(path: String): Uri = Uri.parse("content://$AUTHORITY/$path")

    private fun Cursor.str(n: String): String? = getColumnIndex(n).let { if (it < 0 || isNull(it)) null else getString(it) }
    private fun Cursor.int(n: String): Int? = getColumnIndex(n).let { if (it < 0 || isNull(it)) null else getInt(it) }
    private fun Cursor.lng(n: String): Long? = getColumnIndex(n).let { if (it < 0 || isNull(it)) null else getLong(it) }
    private fun Cursor.flt(n: String): Float? = getColumnIndex(n).let { if (it < 0 || isNull(it)) null else getFloat(it) }

    fun readStatus(cr: ContentResolver): PhoneStatus =
        cr.query(uri("status"), null, null, null, null).use { c ->
            if (c != null && c.moveToFirst()) PhoneStatus((c.int("connected") ?: 0) == 1, c.lng("last_seen_ms") ?: 0L)
            else PhoneStatus(false, 0L)
        }

    fun readWeather(cr: ContentResolver): WeatherNow? =
        cr.query(uri("weather"), null, null, null, null).use { c ->
            if (c == null || !c.moveToFirst()) null else WeatherNow(
                c.lng("updated_ms") ?: 0L, c.str("location"), c.int("temp_c"), c.int("hi_c"), c.int("lo_c"), c.int("code"),
                c.str("text"), c.int("humidity"), c.flt("wind_kmh"), c.int("rain_pct"), c.str("forecast_json"),
            )
        }

    fun readMusic(cr: ContentResolver): MusicNow? =
        cr.query(uri("music"), null, null, null, null).use { c ->
            if (c == null || !c.moveToFirst()) null else MusicNow(
                c.lng("updated_ms") ?: 0L, c.str("state") ?: "stop", c.str("artist"), c.str("album"), c.str("track"),
                c.int("duration_s") ?: 0, c.int("position_s") ?: 0, c.lng("position_at_ms") ?: 0L,
            )
        }

    fun readCalendar(cr: ContentResolver): List<CalEvent> =
        cr.query(uri("calendar"), null, null, null, null).use { c ->
            val out = ArrayList<CalEvent>()
            if (c != null) while (c.moveToNext()) {
                val start = c.lng("start_ms") ?: continue
                out += CalEvent(
                    c.lng("id") ?: out.size.toLong(), c.str("title")?.ifBlank { null } ?: "(No title)", start,
                    c.lng("end_ms") ?: start, (c.int("all_day") ?: 0) == 1, c.str("location")?.ifBlank { null },
                    c.str("calendar")?.ifBlank { null }, c.int("color") ?: 0,
                )
            }
            out
        }

    /** `ContentResolver.call` for a command (`music` / `findPhone`). Never throws. */
    fun call(cr: ContentResolver, method: String, arg: String): CallResult = try {
        val b = cr.call(uri(""), method, arg, null)
        if (b == null) CallResult(false, "unavailable")
        else if (b.getBoolean("ok", false)) CallResult(true, null)
        else CallResult(false, b.getString("error") ?: "unavailable")
    } catch (_: SecurityException) {
        CallResult(false, "denied")
    } catch (_: Exception) {
        CallResult(false, "unavailable")
    }

    fun <T> safely(read: (ContentResolver) -> T, cr: ContentResolver): Snap<T> = try {
        Snap(read(cr))
    } catch (_: SecurityException) {
        Snap(null, "denied")
    } catch (_: Exception) {
        Snap(null, "unavailable")
    }
}

/**
 * Live reading of one provider table: read once, then again after every `notifyChange` on that path
 * (a ContentObserver, conflated so a burst of notifications is one read). Null until the first reading.
 */
@Composable
fun <T> rememberPhone(path: String, read: (ContentResolver) -> T): State<Snap<T>?> {
    val cr = LocalContext.current.contentResolver
    return produceState<Snap<T>?>(null, path) {
        val ticks = Channel<Unit>(Channel.CONFLATED)
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { ticks.trySend(Unit) }
        }
        try {
            cr.registerContentObserver(Phone.uri(path), true, observer)
        } catch (_: Exception) { /* provider missing: the read below reports it */ }
        try {
            ticks.trySend(Unit)
            for (t in ticks) value = withContext(Dispatchers.IO) { Phone.safely(read, cr) }
        } finally {
            cr.unregisterContentObserver(observer)
        }
    }
}
