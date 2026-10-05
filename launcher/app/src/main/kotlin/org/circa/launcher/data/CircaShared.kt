package org.circa.launcher.data

import android.content.ContentResolver
import android.provider.Settings
import org.circa.launcher.model.Accent

/**
 * The settings the launcher shares with Circa Settings (org.circa.settings, which owns the UI for
 * them since the round Settings moved out of the launcher) and the Circa shade, in `Settings.Secure`.
 * A missing key means "never set system-wide": callers fall back to the launcher's own preference.
 */
object CircaShared {
    /** ARGB int; the shade's existing contract (frameworks CircaTray `circa_accent_color`). */
    const val ACCENT = "circa_accent_color"

    /** "1"/"0"; the off-body lock in [org.circa.launcher.WakeGestures] follows it. */
    const val LOCK_WHEN_TAKEN_OFF = "circa_lock_when_taken_off"

    fun accent(resolver: ContentResolver): Accent? = runCatching {
        Accent.fromArgb(Settings.Secure.getString(resolver, ACCENT)?.trim()?.toIntOrNull())
    }.getOrNull()

    /** Best effort (WRITE_SECURE_SETTINGS, platform signature). */
    fun setAccent(resolver: ContentResolver, accent: Accent) {
        runCatching { Settings.Secure.putInt(resolver, ACCENT, accent.argb.toInt()) }
    }

    fun lockWhenTakenOff(resolver: ContentResolver): Boolean? = runCatching {
        Settings.Secure.getString(resolver, LOCK_WHEN_TAKEN_OFF)?.let { it.trim() != "0" }
    }.getOrNull()
}
