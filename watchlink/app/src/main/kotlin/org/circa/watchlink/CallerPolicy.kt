package org.circa.watchlink

/**
 * Who may read [PhoneDataProvider] or call its commands: system (FLAG_SYSTEM) packages named `org.circa.*` (the
 * launcher `org.circa.launcher` among them), and WatchLink itself. Shell, other apps and non-system packages are denied.
 * Pure Kotlin, host-tested; the provider feeds it the calling UID's packages and their FLAG_SYSTEM bits.
 */
object CallerPolicy {
    const val PREFIX_CIRCA = "org.circa."

    @JvmStatic
    fun allowed(packages: List<String>, isSystem: List<Boolean>, selfPackage: String): Boolean {
        for (i in packages.indices) {
            val p = packages[i]
            if (p == selfPackage) return true
            val sys = i < isSystem.size && isSystem[i]
            if (sys && p.startsWith(PREFIX_CIRCA)) return true
        }
        return false
    }
}
