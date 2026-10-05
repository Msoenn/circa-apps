package org.circa.launcher.model

/** What to do with one `TYPE_WRIST_TILT_GESTURE` event. */
enum class TiltAction { IGNORE, WAKE }

/** What to do with one `TYPE_LOW_LATENCY_OFFBODY_DETECT` event. */
enum class OffBodyAction { IGNORE, LOCK }

/**
 * The pure decisions behind the two wake gestures (launcher/README.md): whether each
 * wake-up sensor should be registered right now and what a sensor event means. Nothing here touches
 * Android, so it is unit tested in `WearGestureLogicTest`; `WakeGestures` applies it to the platform.
 *
 * The tilt sensor is a `REPORTING_MODE_SPECIAL_TRIGGER` one: the hub fires exactly one event per
 * wrist raise and the sensor stays registered (research §2.5). A raise therefore maps to *at most
 * one* wake, and [tiltAction] additionally debounces: a second event within [tiltDebounceMs] of the
 * last wake is ignored, so a burst from the hub can never wake the panel twice. In theater mode
 * ([theaterModeOn]) tilt-to-wake is off entirely: only POWER may wake the screen then, so a raise
 * neither registers the sensor nor wakes if a stale event arrives.
 */
class WearGestureLogic(private val tiltDebounceMs: Long = TILT_DEBOUNCE_MS) {

    /** Uptime of the last tilt that produced a wake, or null before the first one. */
    private var lastTiltWakeAtMs: Long? = null

    /** True while the off-body sensor says the watch is not on the wrist (0.0). */
    var offBody: Boolean = false
        private set

    /**
     * Tilt-to-wake is worth listening for only while it is enabled, the screen is off or dozing
     * (never while it is interactive: a raise then has nothing to wake), theater mode is off (only
     * POWER may wake then; launcher/README.md) and the sensor exists.
     */
    fun tiltShouldListen(
        enabled: Boolean,
        interactive: Boolean,
        theaterModeOn: Boolean,
        sensorAvailable: Boolean,
    ): Boolean = enabled && !interactive && !theaterModeOn && sensorAvailable

    /**
     * [value] is `SensorEvent.values[0]` from the tilt sensor. The special-trigger sensor only ever
     * reports 1.0; anything else is not a raise. A raise is dropped while off-body (no point waking a
     * watch off the wrist), while theater mode is on (only POWER may wake the screen then), and while
     * inside the debounce window of the previous wake.
     */
    fun tiltAction(nowUptimeMs: Long, value: Float, theaterModeOn: Boolean): TiltAction {
        if (value <= 0f || offBody || theaterModeOn) return TiltAction.IGNORE
        val last = lastTiltWakeAtMs
        if (last != null && nowUptimeMs - last < tiltDebounceMs) return TiltAction.IGNORE
        lastTiltWakeAtMs = nowUptimeMs
        return TiltAction.WAKE
    }

    /**
     * True while [nowUptimeMs] is within [ARMING_GRACE_MS] of the tilt sensor being (re)registered
     * at [armedAtUptimeMs]. The emulator's sensor HAL posts a tilt event on every activation (goldfish
     * `activationOnChangeSensorEvent` reports WRIST_TILT_GESTURE as 1.0), which woke the screen again
     * right after every screen-off; a hub could do the same. A real raise inside the first second of
     * the screen going off is not plausible, so such events are dropped.
     */
    fun inArmingGrace(nowUptimeMs: Long, armedAtUptimeMs: Long?): Boolean =
        armedAtUptimeMs != null && nowUptimeMs - armedAtUptimeMs in 0 until ARMING_GRACE_MS

    /**
     * Off-body auto-lock is worth listening for only while the preference is on, a secure credential
     * (a PIN) is set and the sensor exists. With no credential there is nothing to lock, and the
     * sensor would only drain the hub for nothing.
     */
    fun offBodyShouldListen(enabled: Boolean, secure: Boolean, sensorAvailable: Boolean): Boolean =
        enabled && secure && sensorAvailable

    /**
     * [value] is `SensorEvent.values[0]` from the off-body sensor: 0.0 = off the wrist, 1.0 = on it.
     * Updates [offBody] and returns LOCK for the transition off the wrist, but only while the
     * preference is on and a secure credential is set - never lock a device that has none.
     */
    fun offBodyAction(value: Float, secure: Boolean, prefEnabled: Boolean): OffBodyAction {
        offBody = value <= 0f
        return if (offBody && prefEnabled && secure) OffBodyAction.LOCK else OffBodyAction.IGNORE
    }

    companion object {
        /** A tilt inside this window of the last wake is ignored. */
        const val TILT_DEBOUNCE_MS = 1_500L

        /** Tilt events this soon after the sensor was registered are activation artefacts. */
        const val ARMING_GRACE_MS = 1_000L
    }
}
