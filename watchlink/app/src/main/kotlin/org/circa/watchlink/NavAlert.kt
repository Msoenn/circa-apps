package org.circa.watchlink

/**
 * Alert decision for one navigation update (GB `t=nav`): when the watch should buzz for the maneuver Maps is
 * showing. Pure Kotlin, no Android imports, host-tested; [WatchLinkService] does the vibrating.
 *
 * One "turn coming" alert per maneuver, the first time its distance reaches [TURN_M]. A maneuver is new when the
 * instruction or the action changes. A maneuver the watch first sees already inside [TURN_M] still alerts - the
 * watch was not there for the approach - except when it is the first maneuver of the route: Maps was already
 * showing it when the route started, so that crossing is long past.
 *
 * `finish` alerts once, as soon as it arrives: that is the arrival itself, not a threshold, so it needs no distance.
 */
class NavAlert {
    /** What the service should do for the update it just fed in. */
    enum class Kind { NONE, TURN, FINISH }

    /** Instruction + action of the maneuver the last update was for (null until the route's first update). */
    private var key: Pair<String?, String?>? = null

    /** Whether an active route has been seen since the last [onEnd] (no route active until then). */
    private var started = false

    /** Whether the current maneuver already had its alert, so it buzzes at most once. */
    private var alerted = false

    /**
     * Feed the state the provider serves: [PhoneData.Nav]'s instruction, action, and
     * [PhoneData.parseDistanceMeters] value (a negative one is unparseable and never alerts).
     */
    fun onNav(instruction: String?, action: String?, distanceM: Long): Kind {
        val maneuver = instruction to action
        val firstOfRoute = !started
        val known = started && maneuver == key
        key = maneuver
        started = true
        if (!known) {
            alerted = false        // a new maneuver gets its own alert
            if (action == ACTION_FINISH) {
                alerted = true     // the arrival buzz replaces the threshold one
                return Kind.FINISH
            }
        }
        if (!alerted && distanceM in 0..TURN_M) {
            alerted = true
            return if (firstOfRoute) Kind.NONE else Kind.TURN
        }
        return Kind.NONE
    }

    /** Navigation ended (GB's blank message, or the state went stale): the next route starts from scratch. */
    fun onEnd() {
        key = null
        started = false
        alerted = false
    }

    companion object {
        /** The maneuver alerts the first time its distance is at or under this. */
        const val TURN_M = 150L

        /** Gadgetbridge's action name for the arrival maneuver (BangleJSDeviceSupport.navActions). */
        const val ACTION_FINISH = "finish"

        /** "Turn coming": two pulses of [TURN_ON_MS] separated by [TURN_GAP_MS] (a VibrationEffect waveform). */
        const val TURN_ON_MS = 60L
        const val TURN_GAP_MS = 80L

        /** The arrival buzz. */
        const val FINISH_MS = 300L
    }
}
