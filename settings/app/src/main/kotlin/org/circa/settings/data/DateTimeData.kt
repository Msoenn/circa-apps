package org.circa.settings.data

import android.app.AlarmManager
import android.content.Context
import android.icu.text.TimeZoneNames
import android.icu.util.TimeZone as IcuTimeZone
import android.provider.Settings
import android.text.format.DateFormat
import android.util.Log
import java.util.Locale
import java.util.TimeZone
import org.circa.settings.model.TimeZoneModel
import org.circa.settings.model.ZoneRow

data class DateTimeState(
    val autoTime: Boolean,
    val autoZone: Boolean,
    val use24: Boolean,
    val zoneId: String,
)

/**
 * Date & time through the platform:
 *  * "Automatic date & time" / "Automatic time zone" are Settings.Global auto_time / auto_time_zone
 *    (WRITE_SECURE_SETTINGS); the time and time-zone detector services observe both keys.
 *  * A manual zone is set with AlarmManager.setTimeZone (SET_TIME_ZONE, signature|privileged), which
 *    writes persist.sys.timezone and broadcasts ACTION_TIMEZONE_CHANGED.
 *  * 24-hour is Settings.System time_12_24 ("24"/"12"; WRITE_SETTINGS): TextClock, Wear's TimeText and
 *    DateFormat.is24HourFormat read it, so clocks follow without a broadcast.
 */
class DateTimeData(context: Context) {
    private val app = context.applicationContext
    private val resolver get() = app.contentResolver

    fun read(): DateTimeState = DateTimeState(
        autoTime = Settings.Global.getInt(resolver, Settings.Global.AUTO_TIME, 1) != 0,
        autoZone = Settings.Global.getInt(resolver, Settings.Global.AUTO_TIME_ZONE, 1) != 0,
        use24 = DateFormat.is24HourFormat(app),
        zoneId = currentZoneId(),
    )

    /** persist.sys.timezone (the process default can lag behind a change made from here). */
    fun currentZoneId(): String = runCatching {
        Class.forName("android.os.SystemProperties")
            .getMethod("get", String::class.java, String::class.java)
            .invoke(null, "persist.sys.timezone", "") as String
    }.getOrNull()?.takeIf { it.isNotBlank() } ?: TimeZone.getDefault().id

    fun setAutoTime(on: Boolean) = putGlobal(Settings.Global.AUTO_TIME, on)
    fun setAutoZone(on: Boolean) = putGlobal(Settings.Global.AUTO_TIME_ZONE, on)

    private fun putGlobal(key: String, on: Boolean): Boolean =
        runCatching { Settings.Global.putInt(resolver, key, if (on) 1 else 0) }
            .onFailure { Log.w(TAG, "put $key", it) }.getOrDefault(false)

    fun set24(on: Boolean): Boolean =
        runCatching { Settings.System.putString(resolver, Settings.System.TIME_12_24, if (on) "24" else "12") }
            .onFailure { Log.w(TAG, "time_12_24", it) }.getOrDefault(false)

    fun setZone(id: String): Boolean = runCatching {
        app.getSystemService(AlarmManager::class.java).setTimeZone(id)
        TimeZone.setDefault(null)
        true
    }.onFailure { Log.w(TAG, "setTimeZone", it) }.getOrDefault(false)

    /** Canonical location zones (ICU's list, ~420: one per city, no aliases), with names in the user's locale. */
    fun zones(): List<ZoneRow> {
        val locale = Locale.getDefault()
        val names = TimeZoneNames.getInstance(locale)
        val now = System.currentTimeMillis()
        val ids = IcuTimeZone.getAvailableIDs(IcuTimeZone.SystemTimeZoneType.CANONICAL_LOCATION, null, null)
        return TimeZoneModel.sorted(ids.map { id ->
            val region = IcuTimeZone.getRegion(id)
            ZoneRow(
                id = id,
                city = names.getExemplarLocationName(id) ?: TimeZoneModel.cityFromId(id),
                region = if (region.isNullOrEmpty() || region == "001") "" else Locale("", region).getDisplayCountry(locale),
                offsetMs = TimeZone.getTimeZone(id).getOffset(now),
            )
        })
    }

    companion object {
        private const val TAG = "CircaDateTime"
    }
}
