package org.circa.settings.data

import android.content.Context
import android.provider.Settings
import org.circa.settings.model.BedtimeKeys
import org.circa.settings.model.BedtimeSchedule
import org.circa.settings.model.BedtimeState

/** Reads and writes the Bedtime keys; the Circa shade follows them (docs/watch-ui/circa/bedtime.md). */
class BedtimeData(context: Context) {
    private val cr = context.applicationContext.contentResolver

    fun read(): BedtimeState = BedtimeState(
        on = Settings.Global.getInt(cr, BedtimeKeys.STATE, 0) != 0,
        schedule = BedtimeSchedule.decode(Settings.Secure.getString(cr, BedtimeKeys.SCHEDULE)),
        startWhenAsleep = Settings.Secure.getInt(cr, BedtimeKeys.ON_SLEEP, 0) != 0,
    )

    fun setOn(on: Boolean) {
        if (on) Settings.Secure.putString(cr, BedtimeKeys.SOURCE, "manual")
        Settings.Global.putInt(cr, BedtimeKeys.STATE, if (on) 1 else 0)
    }

    fun setSchedule(s: BedtimeSchedule) {
        Settings.Secure.putString(cr, BedtimeKeys.SCHEDULE, s.encode())
    }

    fun setStartWhenAsleep(on: Boolean) {
        Settings.Secure.putInt(cr, BedtimeKeys.ON_SLEEP, if (on) 1 else 0)
    }
}
