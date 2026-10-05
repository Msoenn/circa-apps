package org.circa.settings.model

import java.util.Locale

/**
 * Pure logic behind the Bluetooth, Language and Accessibility pages (unit tested in
 * `BtLangA11yModelTest`); `data/BluetoothData`, `data/LocaleData` and `data/A11yData` apply it.
 */

/** How the pairing screen behaves for one `BluetoothDevice.EXTRA_PAIRING_VARIANT`. */
enum class PairingMode {
    /** Numeric comparison: show the 6 digits, user confirms they match (setPairingConfirmation). */
    CONFIRM_CODE,

    /** "Just works" / OOB consent: only ask (setPairingConfirmation). */
    CONSENT,

    /** Legacy PIN (or 16-digit PIN): the user types it on the keypad (setPin). */
    ENTER_PIN,

    /** SSP passkey entry: the user types the 6 digits the other device shows (IBluetooth.setPasskey). */
    ENTER_PASSKEY,

    /** The other device asks the user to type the code shown here; the stack is told "displayed". */
    SHOW_CODE,

    /** Unknown variant: nothing can be answered, only cancelled. */
    UNSUPPORTED,
}

object Pairing {
    // BluetoothDevice.PAIRING_VARIANT_* (@SystemApi constants, stable since API 19).
    const val PIN = 0
    const val PASSKEY = 1
    const val PASSKEY_CONFIRMATION = 2
    const val CONSENT = 3
    const val DISPLAY_PASSKEY = 4
    const val DISPLAY_PIN = 5
    const val OOB_CONSENT = 6
    const val PIN_16_DIGITS = 7

    fun mode(variant: Int): PairingMode = when (variant) {
        PASSKEY_CONFIRMATION -> PairingMode.CONFIRM_CODE
        CONSENT, OOB_CONSENT -> PairingMode.CONSENT
        PIN, PIN_16_DIGITS -> PairingMode.ENTER_PIN
        PASSKEY -> PairingMode.ENTER_PASSKEY
        DISPLAY_PASSKEY, DISPLAY_PIN -> PairingMode.SHOW_CODE
        else -> PairingMode.UNSUPPORTED
    }

    /** The code as shown: passkeys are always 6 digits (leading zeros kept), a display PIN 4. */
    fun formatKey(variant: Int, key: Int): String? = when {
        key < 0 -> null
        variant == DISPLAY_PIN -> "%04d".format(key)
        else -> "%06d".format(key)
    }

    /** Most digits the keypad accepts for [variant]. */
    fun maxLength(variant: Int): Int = if (variant == PASSKEY) 6 else 16

    /** Whether [entered] may be sent (AOSP BluetoothPairingController.isPasskeyValid rules). */
    fun canSubmit(variant: Int, entered: String): Boolean = when (variant) {
        PIN -> entered.length in 1..16
        PIN_16_DIGITS -> entered.length == 16
        PASSKEY -> entered.length == 6
        else -> false
    }

    /** The 6-digit passkey as the 4 bytes IBluetooth.setPasskey expects (native order int). */
    fun passkeyBytes(entered: String, littleEndian: Boolean = true): ByteArray {
        val v = entered.toInt()
        val b = ByteArray(4)
        for (i in 0 until 4) {
            val shift = if (littleEndian) 8 * i else 8 * (3 - i)
            b[i] = (v ushr shift).toByte()
        }
        return b
    }

    /** Short line under a finished pairing attempt (BluetoothDevice.UNBOND_REASON_*). */
    fun failureLabel(reason: Int): String = when (reason) {
        // AUTH_FAILED is also what a link-level reject gives, so it does not claim a wrong PIN.
        1 -> "Couldn't pair"
        2 -> "Rejected by the device"
        3, 8 -> "Cancelled"
        4 -> "Device not responding"
        6 -> "Timed out"
        7 -> "Too many attempts"
        else -> "Couldn't pair"
    }
}

/** The icon family of a remote device, from its Bluetooth class of device (major class). */
enum class BtKind { PHONE, COMPUTER, AUDIO, WEARABLE, INPUT, HEALTH, OTHER }

object BtLabels {
    /** Shown when a device has neither an alias nor a name (we never show addresses). */
    const val UNNAMED = "Unnamed device"

    fun label(alias: String?, name: String?): String =
        alias?.trim()?.takeIf { it.isNotEmpty() } ?: name?.trim()?.takeIf { it.isNotEmpty() } ?: UNNAMED

    /** BluetoothClass.Device.Major.* (0x0100 steps). */
    fun kind(majorClass: Int?): BtKind = when (majorClass) {
        0x0100 -> BtKind.COMPUTER
        0x0200 -> BtKind.PHONE
        0x0400 -> BtKind.AUDIO
        0x0500 -> BtKind.INPUT
        0x0700 -> BtKind.WEARABLE
        0x0900 -> BtKind.HEALTH
        else -> BtKind.OTHER
    }

    fun status(connected: Boolean): String = if (connected) "Connected" else "Paired"

    /**
     * A user alias: trimmed, at most 248 bytes is the BT name limit; null when it would be empty
     * (BluetoothDevice.setAlias rejects "" and null clears it back to the device's own name).
     */
    fun cleanAlias(raw: String): String? = raw.trim().takeIf { it.isNotEmpty() }?.take(64)
}

/** Stock Wear's four text sizes (Settings.System.FONT_SCALE). */
object FontScale {
    val STEPS: List<Float> = listOf(0.85f, 1.0f, 1.15f, 1.3f)
    val LABELS: List<String> = listOf("Small", "Default", "Large", "Largest")

    /** Index of the step nearest to [scale] (a value set elsewhere, e.g. 1.1, still marks one). */
    fun nearest(scale: Float): Int = STEPS.indices.minBy { kotlin.math.abs(STEPS[it] - scale) }

    fun label(scale: Float): String = LABELS[nearest(scale)]

    /** Settings.Secure.FONT_WEIGHT_ADJUSTMENT: 300 = bold (stock's value), 0 = normal. */
    const val BOLD_WEIGHT_ADJUSTMENT = 300

    fun isBold(raw: String?): Boolean = (raw?.trim()?.toIntOrNull() ?: 0) > 0
}

/** The system language list. */
object LocaleModel {
    /** Pseudo-locales (en-XA accented, ar-XB bidi) are developer tools, not languages. */
    private val PSEUDO = setOf("en-XA", "ar-XB")

    /**
     * Language tags from the framework's asset locales: dropped empty / pseudo ones, de-duplicated,
     * and a bare language ("de") dropped when the image also has it with a region ("de-DE"), so each
     * row is one choice; sorted by the name each language has in itself.
     */
    fun choices(assetLocales: Collection<String>): List<Locale> {
        val all = assetLocales.asSequence()
            .map { it.trim().replace('_', '-') }
            .filter { it.isNotEmpty() && it !in PSEUDO && it != "und" }
            .map { Locale.forLanguageTag(it) }
            .filter { it.language.isNotEmpty() }
            .distinctBy { it.toLanguageTag() }
            .toList()
        val withRegion = all.filter { it.country.isNotEmpty() }.map { it.language }.toSet()
        return all.filter { it.country.isNotEmpty() || it.language !in withRegion }
            .sortedBy { nativeName(it).lowercase(it) }
    }

    /** "Deutsch (Deutschland)", "English (United States)": the name in its own language, capitalised. */
    fun nativeName(locale: Locale): String =
        locale.getDisplayName(locale).replaceFirstChar { if (it.isLowerCase()) it.titlecase(locale) else it.toString() }

    /** The language name in the current UI language ("German (Germany)"), the second line of a row and a search key. */
    fun localName(locale: Locale): String = locale.getDisplayName(Locale.getDefault())

    /**
     * Search: every word of [query] has to start a word of the native name, the local name or the language tag
     * (case-insensitive). A blank query keeps everything.
     */
    fun filter(all: List<Locale>, query: String): List<Locale> {
        val words = query.trim().lowercase().split(Regex("[\\s()\\-_,]+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return all
        return all.filter { l ->
            val hay = (nativeName(l) + " " + localName(l) + " " + l.toLanguageTag()).lowercase()
                .split(Regex("[\\s()\\-_,]+")).filter { it.isNotEmpty() }
            words.all { w -> hay.any { it.startsWith(w) } }
        }
    }

    /** Whether [candidate] is the current first system locale [current] (tag compare). */
    fun isCurrent(candidate: Locale, current: Locale?): Boolean =
        current != null && candidate.toLanguageTag() == current.toLanguageTag()
}
