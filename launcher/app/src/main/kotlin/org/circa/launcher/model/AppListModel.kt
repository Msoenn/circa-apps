package org.circa.launcher.model

import java.util.Locale

/**
 * Pure (Android-free) ordering/filtering rules for the app list, kept separate so they can be
 * unit-tested on the JVM.
 */
object AppListModel {

    /**
     * Sort by case-insensitive display label, then package name, then activity class name for a
     * deterministic tie-break.
     */
    fun sort(entries: List<AppEntry>): List<AppEntry> =
        entries.sortedWith(
            compareBy(
                { it.label.lowercase(Locale.ROOT) },
                { it.packageName },
                { it.className },
            ),
        )

    /**
     * Remove the launcher's own entry so it never lists itself.
     */
    fun filterSelf(entries: List<AppEntry>, selfPackage: String): List<AppEntry> =
        entries.filter { it.packageName != selfPackage }
}
