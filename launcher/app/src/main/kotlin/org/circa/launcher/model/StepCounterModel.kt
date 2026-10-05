package org.circa.launcher.model

/**
 * `Sensor.TYPE_STEP_COUNTER` reports steps since the last reboot, not since midnight. The launcher
 * keeps, per local day, the counter value that the day started from ([baseline]) and the last
 * counter it saw ([last]); today's steps are `counter - baseline`.
 */
data class StepState(val epochDay: Long, val baseline: Long, val last: Long)

object StepCounterModel {

    data class Reading(val steps: Int, val state: StepState)

    /**
     * Fold one counter reading ([counter], steps since boot) taken on local day [epochDay] into
     * [previous]:
     *  - no history: today starts here (0 steps, the install is the first sighting);
     *  - the day rolled over: the day starts from the last counter seen yesterday, i.e. the steps
     *    walked between the last reading and midnight are attributed to the new day (the best
     *    available estimate of the counter at midnight);
     *  - the counter went down (a reboot reset it): everything counted since boot is today's,
     *    the baseline restarts at 0 (steps walked earlier today before the reboot are lost).
     */
    fun update(previous: StepState?, counter: Long, epochDay: Long): Reading {
        val baseline = when {
            previous == null -> counter
            counter < previous.last -> 0L
            epochDay != previous.epochDay -> previous.last
            else -> previous.baseline
        }
        val steps = (counter - baseline).coerceAtLeast(0L).coerceAtMost(Int.MAX_VALUE.toLong())
        return Reading(steps.toInt(), StepState(epochDay, baseline, counter))
    }
}
