package org.circa.launcher.model

/**
 * The panel policy of the always-on face (the platform's doze component), as a pure state machine:
 * no Android dependency, so the ordering that keeps the application processor suspendable is unit
 * tested rather than only observable on hardware.
 *
 * **Why the panel state matters.** While the panel is in `Display.STATE_DOZE` the display still
 * needs the application processor: the power manager may not suspend (and with the framework's
 * legacy coupling, `PowerManagerService` keeps its `PowerManager.SuspendLockout` kernel wake lock
 * held for as long as any display is not off). Only in `Display.STATE_DOZE_SUSPEND` does the panel
 * keep the last frame in its own memory and the AP become suspendable - which is exactly what an
 * always-on display is for. A doze dream that leaves the panel in `STATE_DOZE` therefore keeps the
 * CPU running for the whole time the AOD is showing.
 *
 * **What AOSP does** (`SystemUI`'s `DozeService`/`DozeScreenState`/`DozeUi`): the always-on state
 * maps to `STATE_DOZE_SUSPEND` (`DozeMachine.State.screenState()`: `DOZE_AOD` ->
 * `STATE_DOZE_SUSPEND`), the panel is only raised into `STATE_DOZE` to push a new frame, and on the
 * way into doze that frame is given `ENTER_DOZE_DELAY` (4000 ms) to reach the panel before the state
 * changes (`DozeScreenState.transitionTo`). The minute update is scheduled as an exact *wakeup*
 * alarm for the next whole minute (`DozeUi.scheduleTimeTick` -> `AlarmTimeout` ->
 * `AlarmManager.setExact(ELAPSED_REALTIME_WAKEUP, ...)`) because the platform's own
 * `ACTION_TIME_TICK` is a non-wakeup (`ELAPSED_REALTIME`) alarm and cannot arrive while the AP is
 * suspended.
 *
 * **This machine** has two phases:
 *
 * | event                        | phase    | panel state to request |
 * |------------------------------|----------|------------------------|
 * | dream started (first frame) | HOLDING  | `DOZE`, suspend after [entryHoldMillis]  |
 * | minute tick (redraw)         | HOLDING  | `DOZE`, suspend after [redrawHoldMillis] |
 * | hold elapsed                 | SUSPENDED| `DOZE_SUSPEND` |
 * | dream stopped                | SUSPENDED| `DOZE_SUSPEND` |
 *
 * i.e. `draw -> DOZE_SUSPEND` and `tick -> DOZE -> draw -> DOZE_SUSPEND`. The hold is the time the
 * panel is kept scanning out while the just-drawn frame is composited and pushed; it costs at most a
 * couple of seconds per minute and is the only time the AP is forced awake while the AOD shows.
 */
class AmbientDozeStateMachine(
    /**
     * How long the panel stays in `DOZE` after the *first* frame of a doze session. Matches AOSP's
     * `DozeScreenState.ENTER_DOZE_DELAY`: the screen-off transition around the dream's start may
     * still be running, and a frame pushed too early would be lost.
     */
    private val entryHoldMillis: Long = ENTRY_HOLD_MILLIS,
    /**
     * How long the panel stays in `DOZE` after a minute redraw (AOSP's `DozeUi` pushes a second
     * frame 500 ms after entering doze because "the first frame may arrive when the display isn't
     * ready yet"; this is comfortably longer than that and still ~1.7 % of a minute).
     */
    private val redrawHoldMillis: Long = REDRAW_HOLD_MILLIS,
) {

    /** The panel state the service must request, mapped there to `Display.STATE_DOZE[_SUSPEND]`. */
    enum class Screen { DOZE, DOZE_SUSPEND }

    /** Whether a just-drawn frame is still being given time to reach the panel. */
    enum class Phase { HOLDING, SUSPENDED }

    var phase: Phase = Phase.SUSPENDED
        private set

    /** Wall-clock time the current hold ends, or [NEVER] while suspended. */
    var holdUntilMillis: Long = NEVER
        private set

    /** The state to request now: `DOZE` for as long as a frame is on its way, else `DOZE_SUSPEND`. */
    val screen: Screen
        get() = if (phase == Phase.HOLDING) Screen.DOZE else Screen.DOZE_SUSPEND

    /**
     * The dream started and is drawing its first frame. Returns how long to wait before
     * [onHoldElapsed].
     */
    fun onStarted(nowMillis: Long): Long = hold(nowMillis, entryHoldMillis)

    /**
     * A minute elapsed and the face is being redrawn. Returns how long to wait before
     * [onHoldElapsed]. A tick that lands while a hold is still running simply extends that hold: the
     * frame it draws is the newest one and needs its own time on the panel.
     */
    fun onMinuteTick(nowMillis: Long): Long = hold(nowMillis, redrawHoldMillis)

    /** The hold elapsed: the frame is on the panel, so it may be suspended again. */
    fun onHoldElapsed() {
        phase = Phase.SUSPENDED
        holdUntilMillis = NEVER
    }

    /** The dream stopped (the panel is the platform's again): the machine is idle and suspended. */
    fun onStopped() = onHoldElapsed()

    private fun hold(nowMillis: Long, holdMillis: Long): Long {
        phase = Phase.HOLDING
        holdUntilMillis = nowMillis + holdMillis
        return holdMillis
    }

    companion object {
        /** AOSP `DozeScreenState.ENTER_DOZE_DELAY`. */
        const val ENTRY_HOLD_MILLIS = 4_000L

        /** The panel hold after a minute redraw; see [redrawHoldMillis]. */
        const val REDRAW_HOLD_MILLIS = 1_000L

        /** No hold pending. `Long.MIN_VALUE` cannot be a wall-clock time. */
        const val NEVER = Long.MIN_VALUE

        private const val MINUTE_MILLIS = 60_000L

        /**
         * Milliseconds from [nowMillis] to the next whole minute of wall-clock time - AOSP
         * `DozeUi.roundToNextMinute`, which is what keeps the minute alarm aligned to the clock
         * instead of drifting with the alarm's own dispatch latency. `floorDiv` rather than `/` so a
         * clock set before 1970 still lands on the next minute instead of the previous one.
         */
        fun millisUntilNextMinute(nowMillis: Long): Long =
            (Math.floorDiv(nowMillis, MINUTE_MILLIS) + 1) * MINUTE_MILLIS - nowMillis
    }
}
