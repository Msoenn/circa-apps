package org.circa.launcher.model

/**
 * A launchable app as it will be shown in the app list.
 */
data class AppEntry(
    val label: String,
    val packageName: String,
    val className: String,
    /** Circa Clock page to open (0 alarm, 1 timer, 2 stopwatch); null for an ordinary app shortcut. */
    val clockPage: Int? = null,
)
