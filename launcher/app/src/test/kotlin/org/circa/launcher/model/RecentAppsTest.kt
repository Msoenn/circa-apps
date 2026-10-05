package org.circa.launcher.model

import org.junit.Assert.assertEquals
import org.junit.Test

class RecentAppsTest {

    private val apps = listOf(
        AppEntry("Calendar", "com.android.calendar", "CalendarActivity"),
        AppEntry("Settings", "com.android.settings", "Settings"),
        AppEntry("Clock", "com.android.deskclock", "DeskClock"),
    )

    @Test
    fun ordersByMostRecentAndCaps() {
        val usage = listOf(
            UsageEntry("com.android.settings", 100),
            UsageEntry("com.android.deskclock", 300),
            UsageEntry("com.android.calendar", 200),
        )
        val picked = RecentApps.select(usage, apps, limit = 2)
        assertEquals(listOf("Clock", "Calendar"), picked.map { it.label })
    }

    @Test
    fun dedupesPackageKeepingNewestUse() {
        val usage = listOf(
            UsageEntry("com.android.settings", 100),
            UsageEntry("com.android.settings", 400),
            UsageEntry("com.android.calendar", 200),
        )
        assertEquals(listOf("Settings", "Calendar"), RecentApps.select(usage, apps, 3).map { it.label })
    }

    @Test
    fun dropsPackagesWithoutALaunchableEntry() {
        val usage = listOf(
            UsageEntry("com.example.gone", 500),
            UsageEntry("com.android.settings", 100),
        )
        assertEquals(listOf("Settings"), RecentApps.select(usage, apps, 3).map { it.label })
    }

    @Test
    fun ignoresNonPositiveTimestamps() {
        val usage = listOf(
            UsageEntry("com.android.settings", 0),
            UsageEntry("com.android.calendar", -1),
        )
        assertEquals(emptyList<AppEntry>(), RecentApps.select(usage, apps, 3))
    }

    @Test
    fun emptyUsageYieldsEmptyList() {
        assertEquals(emptyList<AppEntry>(), RecentApps.select(emptyList(), apps, 3))
    }
}
