package org.circa.watchlink

/**
 * Whether WatchLink should hold the HR sensor open for live values ("screen" reason). Pure Kotlin, host-tested.
 *
 * Live HR is wanted exactly while the watch is interactive and not in theater mode. An interactive display already
 * keeps the AP awake, so the sensor is registered without a wakelock; on screen-off it is unregistered immediately
 * (the 5-minute sample windows still run on their own wakelock). Theater mode — `Settings.Global.theater_mode_on`
 * (Wear-specific, not in the public SDK) — turns the display off but can leave the framework reporting interactive
 * for a moment, so it is honoured explicitly.
 */
class LiveHrPolicy {
    private var screenOn = false
    private var theaterOn = false

    /** Feed a screen on/off transition or the current interactivity; returns whether live HR should now be on. */
    fun setScreenOn(on: Boolean): Boolean {
        screenOn = on
        return wanted()
    }

    /** Feed the theater-mode setting; returns whether live HR should now be on. */
    fun setTheaterMode(on: Boolean): Boolean {
        theaterOn = on
        return wanted()
    }

    fun wanted(): Boolean = screenOn && !theaterOn
}
