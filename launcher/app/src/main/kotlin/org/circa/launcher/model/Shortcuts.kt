package org.circa.launcher.model

/**
 * The Shortcuts tile's fixed app slots (up to five big buttons). Each logical app has candidate package names (AOSP and
 * Google builds ship different ones); the first candidate that exists as a launchable app wins,
 * and a slot with no installed candidate is skipped.
 */
object Shortcuts {

    /** Logical slot -> candidate package names, in preference order. */
    val DEFAULTS: List<List<String>> = listOf(
        listOf("com.android.settings"),
        listOf("com.google.android.deskclock", "com.android.deskclock"),
        listOf("com.google.android.calendar", "com.android.calendar"),
        listOf("com.google.android.contacts", "com.android.contacts"),
        listOf("com.google.android.dialer", "com.android.dialer"),
    )

    /**
     * Resolve [DEFAULTS] against the installed launchable apps (order = preference), one entry per
     * slot, missing slots dropped.
     */
    fun resolve(launchable: List<AppEntry>): List<AppEntry> {
        val byPackage = launchable.associateBy { it.packageName }
        val clock = byPackage[CLOCK_PACKAGE]
        if (clock != null) return circaDefaults(clock, byPackage)
        return DEFAULTS.mapNotNull { candidates ->
            candidates.firstNotNullOfOrNull { byPackage[it] }
        }
    }

    const val CLOCK_PACKAGE = "org.circa.clock"
    const val CLOCK_PAGE_ALARM = 0
    const val CLOCK_PAGE_TIMER = 1
    const val CLOCK_PAGE_STOPWATCH = 2

    /**
     * The Circa defaults when Circa Clock is installed (one app, three pages): Alarm, Timer and Stopwatch
     * buttons that open its matching page, then Settings. There is no user-editable shortcut list yet, so
     * nothing saved can be overwritten; when there is one, these are only the seed for an empty list.
     */
    private fun circaDefaults(clock: AppEntry, byPackage: Map<String, AppEntry>): List<AppEntry> =
        listOfNotNull(
            clock.copy(label = "Alarm", clockPage = CLOCK_PAGE_ALARM),
            clock.copy(label = "Timer", clockPage = CLOCK_PAGE_TIMER),
            clock.copy(label = "Stopwatch", clockPage = CLOCK_PAGE_STOPWATCH),
            listOf("org.circa.settings", "com.android.settings").firstNotNullOfOrNull { byPackage[it] },
        )
}
