package org.circa.launcher

/**
 * The Circa platform's button contract, launcher side (launcher/README.md).
 *
 * The watch has two buttons: the **crown**, whose press is `KEYCODE_POWER`, and the **side button**,
 * `KEYCODE_STEM_PRIMARY`. An app never receives POWER. The Circa framework answers both:
 * crown short press (`config_shortPressOnPowerBehavior = 101`) -> on the face an explicit
 * [android.content.Intent.ACTION_ALL_APPS] to this app (which toggles face <-> app list), everywhere
 * else -> home; side button short press (`config_shortPressOnStemPrimaryBehavior = 100`) -> the Circa
 * shade's notifications screen; long press of either -> the power menu.
 *
 * Because the framework owns the stem key, the launcher must *not* swallow `KEYCODE_STEM_PRIMARY`:
 * an unconsumed key makes PhoneWindowManager execute its own deferred action, while consuming it
 * drops that action (which is exactly how the power menu once became unreachable from the face).
 *
 * On a build without a Circa stem value (the stock GSI the watch ran before, `= 2`), the launcher
 * still answers the raw key itself, as it always did.
 */
object CircaButtons {

    /** Older mapping (power short press -> Recents, `config_circaPowerShortPressOpensRecents`). */
    const val ACTION_SHOW_RECENTS = "org.circa.intent.action.SHOW_RECENTS"

    /** `config_shortPressOnStemPrimaryBehavior` = 3: the stem key's app-list press (before 2026-10-04). */
    const val SHORT_PRESS_PRIMARY_CIRCA = 3

    /** `config_shortPressOnStemPrimaryBehavior` = 100: the side button opens the notifications screen. */
    const val SHORT_PRESS_PRIMARY_CIRCA_NOTIFICATIONS = 100

    /**
     * True when PhoneWindowManager owns the stem key's (side button's) short/long press, from the
     * platform's `config_shortPressOnStemPrimaryBehavior` value. The launcher then leaves the key alone.
     */
    fun stemHandledByPlatform(shortPressOnStemPrimaryBehavior: Int): Boolean =
        shortPressOnStemPrimaryBehavior == SHORT_PRESS_PRIMARY_CIRCA ||
            shortPressOnStemPrimaryBehavior == SHORT_PRESS_PRIMARY_CIRCA_NOTIFICATIONS

    /**
     * What [ACTION_SHOW_RECENTS] shows: the Recents page, or back to the face when Recents is
     * already showing (the framework always just re-sends the intent, the launcher decides).
     */
    fun recentsIntentTarget(current: Screen): Screen =
        if (current == Screen.RECENTS) Screen.HOME else Screen.RECENTS

    /** What one crown press (the framework's [android.content.Intent.ACTION_ALL_APPS] toggle) does. */
    enum class CrownAction { UNLOCK_THEN_FACE, CLOSE_TRAY, OPEN_RECENTS, SHOW_FACE }

    /**
     * The crown toggle: face -> app list (Recents page), app list -> face, an open tray closes.
     *
     * Locked, the press only asks for the PIN and the face stays after the unlock. The press that
     * brought up the bouncer is spent: continuing it into the app list after the unlock (the old
     * `requireUnlock { toggle() }`) is what opened Recents behind the PIN (launcher/README.md).
     */
    fun crownAction(locked: Boolean, trayOpen: Boolean, current: Screen): CrownAction = when {
        locked -> CrownAction.UNLOCK_THEN_FACE
        trayOpen -> CrownAction.CLOSE_TRAY
        current == Screen.HOME -> CrownAction.OPEN_RECENTS
        else -> CrownAction.SHOW_FACE
    }
}
