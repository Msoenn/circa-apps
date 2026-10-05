package org.circa.exercise.model

/**
 * Who may read [org.circa.exercise.data.StateProvider]: installed system (FLAG_SYSTEM) packages named `org.circa.*`
 * (the launcher `org.circa.launcher` among them) and this app. Shell, other apps and non-system packages are denied.
 * The same rule WatchLink's CallerPolicy uses for its providers; pure Kotlin, host-tested.
 */
object StateCallerPolicy {
    const val PREFIX = "org.circa."

    fun allowed(packages: List<String>, isSystem: List<Boolean>, selfPackage: String): Boolean {
        for (i in packages.indices) {
            val p = packages[i]
            if (p == selfPackage) return true
            val sys = i < isSystem.size && isSystem[i]
            if (sys && p.startsWith(PREFIX)) return true
        }
        return false
    }
}
