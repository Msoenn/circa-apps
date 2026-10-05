package org.circa.watchlink

import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.ApplicationInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Process
import android.util.Log

/**
 * Phone data for the Circa apps (weather, media, agenda, find phone): `content://org.circa.watchlink.data/…`.
 * Contract: watchlink/DATA-CONTRACT.md. Reads and `call()` commands are limited by [CallerPolicy]
 * (system `org.circa.*` packages and the launcher); everything else, including the adb shell, gets a SecurityException.
 * Observers are notified by [PhoneStore] on every change.
 */
class PhoneDataProvider : ContentProvider() {

    override fun onCreate(): Boolean {
        PhoneStore.attach(context ?: return false)
        return true
    }

    override fun query(
        uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?,
    ): Cursor {
        enforceCaller()
        val path = uri.pathSegments.firstOrNull()
        val cols: List<String>
        val rows = ArrayList<List<Any?>>()
        when (path) {
            "status" -> {
                cols = projection?.toList() ?: STATUS_COLS
                val (conn, seen) = PhoneStore.status()
                rows.add(listOf(if (conn) 1 else 0, seen))
            }
            "weather" -> {
                cols = projection?.toList() ?: WEATHER_COLS
                PhoneStore.weather()?.let { w ->
                    rows.add(listOf(w.updatedMs, w.location, w.tempC, w.hiC, w.loC, w.code, w.text, w.humidity, w.windKmh,
                        w.windDir, w.uv, w.rainPct, w.forecastJson))
                }
            }
            "music" -> {
                cols = projection?.toList() ?: MUSIC_COLS
                PhoneStore.music()?.let { m ->
                    rows.add(listOf(m.updatedMs, m.state, m.artist, m.album, m.track, m.durationS, m.positionS, m.positionAtMs))
                }
            }
            "calendar" -> {
                cols = projection?.toList() ?: CALENDAR_COLS
                for (e in PhoneStore.calendar()) {
                    rows.add(listOf(e.id, e.title, e.startMs, e.endMs, if (e.allDay) 1 else 0, e.location, e.calendar, e.color))
                }
            }
            else -> throw IllegalArgumentException("unknown watchlink data uri: $uri")
        }
        val all = when (path) {
            "status" -> STATUS_COLS
            "weather" -> WEATHER_COLS
            "music" -> MUSIC_COLS
            else -> CALENDAR_COLS
        }
        val c = MatrixCursor(cols.toTypedArray(), rows.size)
        for (r in rows) c.addRow(cols.map { col -> val i = all.indexOf(col); if (i >= 0) r[i] else null }.toTypedArray())
        c.setNotificationUri(context?.contentResolver, uri)
        return c
    }

    /** Commands: `music` (arg play|pause|playpause|next|previous|volumeup|volumedown) and `findPhone` (start|stop). */
    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        enforceCaller()
        val out = Bundle()
        val msg: Map<String, Any?>? = when (method) {
            "music" -> if (arg in MUSIC_ARGS) linkedMapOf("t" to "music", "n" to arg) else null
            "findPhone" -> when (arg) {
                "start" -> linkedMapOf("t" to "findPhone", "n" to true)
                "stop" -> linkedMapOf("t" to "findPhone", "n" to false)
                else -> null
            }
            else -> null
        }
        if (msg == null) {
            out.putBoolean("ok", false)
            out.putString("error", "bad_arg")
        } else if (WatchLinkService.sendToPhone(msg)) {
            out.putBoolean("ok", true)
        } else {
            out.putBoolean("ok", false)
            out.putString("error", "not_connected")
        }
        Log.i(TAG, "phone data call $method ${arg ?: ""} -> ${out.getBoolean("ok")} ${out.getString("error") ?: ""}")
        return out
    }

    override fun getType(uri: Uri): String? = when (uri.pathSegments.firstOrNull()) {
        "status", "weather", "music" -> "vnd.android.cursor.item/vnd.org.circa.watchlink.data.${uri.pathSegments[0]}"
        "calendar" -> "vnd.android.cursor.dir/vnd.org.circa.watchlink.data.calendar"
        else -> null
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException("read-only")
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException("read-only")
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("read-only")

    private fun enforceCaller() {
        val uid = Binder.getCallingUid()
        if (uid == Process.myUid()) return
        val c = context ?: throw SecurityException("phone data provider not created")
        val pm = c.packageManager
        val pkgs = try { pm.getPackagesForUid(uid)?.toList() } catch (e: Exception) { null } ?: emptyList()
        val sys = pkgs.map { p ->
            try { (pm.getApplicationInfo(p, 0).flags and ApplicationInfo.FLAG_SYSTEM) != 0 } catch (e: Exception) { false }
        }
        if (!CallerPolicy.allowed(pkgs, sys, c.packageName)) {
            Log.i(TAG, "phone data: denied uid $uid (packages=${pkgs.joinToString()})")
            throw SecurityException("uid $uid may not use $AUTHORITY")
        }
    }

    companion object {
        const val AUTHORITY = "org.circa.watchlink.data"
        private const val TAG = "WatchLink"
        private val MUSIC_ARGS = setOf("play", "pause", "playpause", "next", "previous", "volumeup", "volumedown")
        private val STATUS_COLS = listOf("connected", "last_seen_ms")
        private val WEATHER_COLS = listOf("updated_ms", "location", "temp_c", "hi_c", "lo_c", "code", "text", "humidity",
            "wind_kmh", "wind_dir", "uv", "rain_pct", "forecast_json")
        private val MUSIC_COLS = listOf("updated_ms", "state", "artist", "album", "track", "duration_s", "position_s", "position_at_ms")
        private val CALENDAR_COLS = listOf("id", "title", "start_ms", "end_ms", "all_day", "location", "calendar", "color")
    }
}
