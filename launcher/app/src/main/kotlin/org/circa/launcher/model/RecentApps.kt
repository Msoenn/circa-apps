package org.circa.launcher.model

/**
 * One entry of `UsageStatsManager.queryUsageStats`: a package and when it was last used. Kept
 * Android-free so the selection rules can be unit-tested on the JVM.
 */
data class UsageEntry(
    val packageName: String,
    val lastTimeUsed: Long,
)

/**
 * Picks the "Recents" pills from raw usage stats. Pure rules, no platform:
 * dedupe by package (newest use wins), keep only apps the launcher can actually start, order by
 * most recent first, and cap the list.
 */
object RecentApps {

    /**
     * @param usage raw usage entries (may contain duplicates, self, and non-launchable packages)
     * @param launchable all launchable apps, as returned by the app loader (label-sorted)
     * @param limit maximum number of pills
     * @return up to [limit] distinct [AppEntry]s, most recently used first
     */
    fun select(usage: List<UsageEntry>, launchable: List<AppEntry>, limit: Int): List<AppEntry> {
        if (limit <= 0 || launchable.isEmpty()) return emptyList()
        val newestByPackage = HashMap<String, Long>()
        for (entry in usage) {
            if (entry.lastTimeUsed <= 0L) continue
            val previous = newestByPackage[entry.packageName]
            if (previous == null || entry.lastTimeUsed > previous) {
                newestByPackage[entry.packageName] = entry.lastTimeUsed
            }
        }
        if (newestByPackage.isEmpty()) return emptyList()
        // One representative (the first, i.e. alphabetically first) launcher activity per package.
        val entryByPackage = launchable.groupBy { it.packageName }
            .mapValues { (_, entries) -> entries.first() }
        return newestByPackage.entries
            .sortedByDescending { it.value }
            .mapNotNull { entryByPackage[it.key] }
            .take(limit)
    }
}
