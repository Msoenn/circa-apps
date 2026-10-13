package org.circa.launcher.model

/**
 * `Sensor.TYPE_STEP_COUNTER` reports steps since the last reboot, not since midnight. The launcher
 * keeps, per local day, the counter value that the day started from ([baseline]) and the last
 * counter it saw ([last]); today's steps are `counter - baseline`. [baseline] may be negative: a
 * reboot carries the steps already walked today into the new counter's origin (see [StepCounterModel]).
 * [bootCount] is `Settings.Global.BOOT_COUNT` as read by the caller, -1 when unknown.
 */
data class StepState(val epochDay: Long, val baseline: Long, val last: Long, val bootCount: Int = -1)

object StepCounterModel {

    data class Reading(val steps: Int, val state: StepState)

    /**
     * Fold one counter reading ([counter], steps since boot) taken on local day [epochDay] with the
     * platform boot count [bootCount] into [previous]:
     *  - no history: today starts here (0 steps, the install is the first sighting);
     *  - a reboot (a different boot count, or a counter that went backwards when the boot count is
     *    unknown) on the same day: the steps already walked today are carried over the reset, i.e.
     *    the new baseline is `-(last - baseline)` (negative) so `counter` continues where `last` left off;
     *  - a reboot on a new day: nothing is carried, the day starts from 0 (all steps since boot);
     *  - the day rolled over without a reboot: the day starts from the last counter seen yesterday,
     *    i.e. the steps walked between the last reading and midnight are attributed to the new day
     *    (the best available estimate of the counter at midnight).
     */
    fun update(previous: StepState?, counter: Long, epochDay: Long, bootCount: Int): Reading {
        val baseline = when {
            previous == null -> counter
            isReboot(previous, counter, bootCount) ->
                if (epochDay == previous.epochDay) -(previous.last - previous.baseline).coerceAtLeast(0L) else 0L
            epochDay != previous.epochDay -> previous.last
            else -> previous.baseline
        }
        val steps = (counter - baseline).coerceAtLeast(0L).coerceAtMost(Int.MAX_VALUE.toLong())
        return Reading(steps.toInt(), StepState(epochDay, baseline, counter, bootCount))
    }

    /**
     * A different boot count (when both are known) means the device rebooted; a counter below the last
     * reading is the fallback for a reboot that the boot count cannot report (-1 on some builds), and
     * catches a counter reset within the same boot.
     */
    private fun isReboot(previous: StepState, counter: Long, bootCount: Int): Boolean =
        counter < previous.last ||
            (bootCount >= 0 && previous.bootCount >= 0 && bootCount != previous.bootCount)
}
