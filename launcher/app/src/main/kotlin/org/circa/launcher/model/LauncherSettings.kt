package org.circa.launcher.model

/** Minimal key/value store, so the persistence rules are testable without Android. */
interface KeyValueStore {
    fun getString(key: String): String?
    fun putString(key: String, value: String)
    fun getLong(key: String): Long?
    fun putLong(key: String, value: Long)
}

/**
 * What the launcher remembers between runs: the chosen watch face, the accent colour and the
 * step-counter baseline. Unknown or missing values read back as the defaults, so a corrupted or
 * older preferences file can never leave the launcher without a face or a colour.
 */
class LauncherSettings(private val store: KeyValueStore) {

    var face: FaceStyle
        get() = FaceStyle.fromId(store.getString(KEY_FACE))
        set(value) = store.putString(KEY_FACE, value.id)

    var accent: Accent
        get() = Accent.fromId(store.getString(KEY_ACCENT))
        set(value) = store.putString(KEY_ACCENT, value.id)

    /**
     * "Lock when taken off": lock the device when the off-body sensor says it left the wrist
     * (launcher/README.md). On by default; a later UI round adds the toggle, so there is
     * no `Accent`/`FaceStyle`-style enum here, just the boolean. A missing value (or anything but "0")
     * reads back as on, so an older preferences file gets the stock default.
     */
    var lockWhenTakenOff: Boolean
        get() = store.getString(KEY_LOCK_WHEN_TAKEN_OFF) != "0"
        set(value) = store.putString(KEY_LOCK_WHEN_TAKEN_OFF, if (value) "1" else "0")

    /** The persisted step-counter state, or null before the first reading. */
    var stepState: StepState?
        get() {
            val day = store.getLong(KEY_STEP_DAY) ?: return null
            val baseline = store.getLong(KEY_STEP_BASELINE) ?: return null
            val last = store.getLong(KEY_STEP_LAST) ?: return null
            return StepState(day, baseline, last)
        }
        set(value) {
            if (value == null) return
            store.putLong(KEY_STEP_DAY, value.epochDay)
            store.putLong(KEY_STEP_BASELINE, value.baseline)
            store.putLong(KEY_STEP_LAST, value.last)
        }

    private companion object {
        const val KEY_FACE = "face"
        const val KEY_ACCENT = "accent"
        const val KEY_LOCK_WHEN_TAKEN_OFF = "lock_when_taken_off"
        const val KEY_STEP_DAY = "step_day"
        const val KEY_STEP_BASELINE = "step_baseline"
        const val KEY_STEP_LAST = "step_last"
    }
}
