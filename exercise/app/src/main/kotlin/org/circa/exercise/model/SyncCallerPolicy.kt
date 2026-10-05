package org.circa.exercise.model

/**
 * Who may call the sync provider: system uids (< 10000), this app, and preinstalled (FLAG_SYSTEM) packages named
 * `org.circa.*` (WatchLink is `org.circa.watchlink` and signed with a different key than this app, so a signature
 * check would not do). Same idea as WatchLink's CallerPolicy. Pure, host-tested.
 */
object SyncCallerPolicy {
    val PREFIXES = listOf("org.circa.")

    fun allowed(uid: Int, myUid: Int, packages: List<String>, isSystem: List<Boolean>): Boolean {
        if (uid < 10000 || uid == myUid) return true
        return packages.indices.any { i ->
            isSystem.getOrElse(i) { false } && PREFIXES.any { packages[i].startsWith(it) }
        }
    }
}
