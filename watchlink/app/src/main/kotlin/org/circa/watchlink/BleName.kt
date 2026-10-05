package org.circa.watchlink

import java.util.Locale

/**
 * The advertised Bluetooth name. Gadgetbridge only offers a device as a Bangle.js when its name starts with
 * "Bangle.js", so the name is "Bangle.js " plus four hex digits.
 *
 * If the adapter already carries such a name, keep it: the name lives in the Bluetooth stack's config (not in this
 * app's data), survives a reinstall, and is what the paired phone knows. A new suffix is only derived on a fresh
 * device, from the last four digits of ANDROID_ID (which depends on the app's signing key, so it would otherwise
 * change whenever the signing key does). Pure, host-tested.
 */
object BleName {
    private val PATTERN = Regex("^Bangle\\.js [0-9a-f]{4}$")

    @JvmStatic
    fun choose(current: String?, androidId: String?): String {
        if (current != null && PATTERN.matches(current)) return current
        val suffix = if (androidId != null && androidId.length >= 4)
            androidId.substring(androidId.length - 4).lowercase(Locale.US) else "0000"
        return "Bangle.js $suffix"
    }
}
