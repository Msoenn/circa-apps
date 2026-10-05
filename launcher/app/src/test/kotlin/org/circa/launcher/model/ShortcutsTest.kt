package org.circa.launcher.model

import org.junit.Assert.assertEquals
import org.junit.Test

class ShortcutsTest {

    @Test
    fun resolvesCandidatesInPreferenceOrder() {
        val installed = listOf(
            AppEntry("Calendar", "com.android.calendar", "CalendarActivity"),
            AppEntry("Clock", "com.google.android.deskclock", "DeskClock"),
            AppEntry("Settings", "com.android.settings", "Settings"),
            AppEntry("Contacts", "com.android.contacts", "ContactsActivity"),
            AppEntry("Phone", "com.google.android.dialer", "Dialer"),
        )
        assertEquals(
            listOf("Settings", "Clock", "Calendar", "Contacts", "Phone"),
            Shortcuts.resolve(installed).map { it.label },
        )
    }

    @Test
    fun skipsMissingSlots() {
        // Only Settings is installed: the other slots drop out.
        val installed = listOf(AppEntry("Settings", "com.android.settings", "Settings"))
        assertEquals(listOf("Settings"), Shortcuts.resolve(installed).map { it.label })
    }

    @Test
    fun prefersTheFirstCandidateWhenBothExist() {
        val installed = listOf(
            AppEntry("DeskClock (AOSP)", "com.android.deskclock", "DeskClock"),
            AppEntry("Clock (Google)", "com.google.android.deskclock", "DeskClock"),
        )
        assertEquals(listOf("Clock (Google)"), Shortcuts.resolve(installed).map { it.label })
    }

    @Test
    fun circaClockSeedsAlarmTimerStopwatchAndSettings() {
        val installed = listOf(
            AppEntry("Clock", "org.circa.clock", "MainActivity"),
            AppEntry("Settings", "org.circa.settings", "SettingsActivity"),
            AppEntry("Calendar", "com.android.calendar", "CalendarActivity"),
        )
        val result = Shortcuts.resolve(installed)
        assertEquals(listOf("Alarm", "Timer", "Stopwatch", "Settings"), result.map { it.label })
        assertEquals(listOf(0, 1, 2, null), result.map { it.clockPage })
        assertEquals("org.circa.clock", result[2].packageName)
    }

    @Test
    fun withoutCircaClockTheOldDefaultsStay() {
        val installed = listOf(AppEntry("Settings", "com.android.settings", "Settings"))
        assertEquals(listOf(null), Shortcuts.resolve(installed).map { it.clockPage })
    }
}
