package org.circa.watchlink

/**
 * Heart-rate sampling grid, independent of the 10-minute activity buckets (spec §4.3). Pure Kotlin, host-tested.
 *
 * WatchLink used to open the HR sensor once per 10-minute bucket. 0.7 adds this separate 5-minute cadence so the
 * launcher's health provider gets a fresh reading at least every [HR_SAMPLE_INTERVAL_MS], while the bucket mean
 * (unchanged GB contract) just accumulates whatever readings land inside its interval.
 *
 * The grid is aligned to the epoch — the same origin as [Buckets] — so every 10-minute bucket boundary is also a
 * sample point; there is no phase to keep in sync between the two alarms. The sample is scheduled from WatchLink's
 * phone-synced clock, so a clock jump is handled by re-arming: [dueAt] keeps a pending sample that is still in the
 * future instead of skipping it.
 */
class HrSampleSchedule(
    private val intervalMs: Long = HR_SAMPLE_INTERVAL_MS,
    private val slackMs: Long = PENDING_SLACK_MS,
) {
    /** Next grid point strictly after [nowMs]. */
    fun nextAt(nowMs: Long): Long = (Math.floorDiv(nowMs, intervalMs) + 1) * intervalMs

    /**
     * The sample time to arm: [pendingAtMs] while it is still at least [slackMs] in the future, else the next grid
     * point after [nowMs]. [pendingAtMs] = 0 (or any past value) means nothing is armed.
     */
    fun dueAt(nowMs: Long, pendingAtMs: Long): Long =
        if (pendingAtMs - nowMs > slackMs) pendingAtMs else nextAt(nowMs)

    companion object {
        /** Separate HR sampling cadence: every 5 minutes, half the 10-minute activity bucket. */
        const val HR_SAMPLE_INTERVAL_MS = 5L * 60 * 1000

        /** A pending sample closer than this is treated as due, so an early-firing alarm re-arms the next grid. */
        const val PENDING_SLACK_MS = 1_000L
    }
}
