package org.circa.watchlink

/**
 * "Phone left behind?" decision. Pure Kotlin, host-tested; [WatchLinkService] tracks the disconnection, feeds the
 * step counter and posts the notification.
 *
 * The alert is armed when the Gadgetbridge link drops while the watch is worn. It fires when at least
 * [MIN_STEPS] steps are walked within [WINDOW_MS] while the link stays down, at most once per disconnection;
 * reconnecting disarms it and clears the notification. Settings.Secure [SETTING_KEY] absent = on, "0" = off.
 */
object PhoneLeftAlert {
    const val SETTING_KEY = "circa_phone_left_alert"

    const val WINDOW_MS = 5L * 60_000L
    const val MIN_STEPS = 40

    /** One 400 ms pulse, a pause, then two short pulses. */
    @JvmField
    val VIBRATION = longArrayOf(0, 400, 200, 120, 120, 120)

    /** `circa_phone_left_alert`: only an explicit 0 turns the alert off. */
    @JvmStatic
    fun enabled(raw: String?): Boolean = raw?.trim() != "0"

    /**
     * Fire now? [connected] is the link state at this step observation, [wornAtDrop] the wrist state when the link
     * dropped, [stepsSince] the steps walked since then, [elapsedMs] the time since the drop and [fired] whether
     * this disconnection already alerted.
     */
    @JvmStatic
    fun shouldAlert(
        connected: Boolean,
        wornAtDrop: Boolean,
        stepsSince: Int,
        elapsedMs: Long,
        fired: Boolean,
        enabled: Boolean,
    ): Boolean = enabled && !connected && wornAtDrop && !fired &&
            elapsedMs >= 0 && elapsedMs <= WINDOW_MS && stepsSince >= MIN_STEPS
}
