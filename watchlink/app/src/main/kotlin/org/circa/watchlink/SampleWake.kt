package org.circa.watchlink

/**
 * What one scheduled wake on the HR-sample / bucket grid should do, and whether the two jobs share it. Pure Kotlin,
 * host-tested; [WatchLinkService] runs the result on its handler thread.
 *
 * [HrSampleSchedule] anchors the 5-minute HR sample grid to the same epoch as the 10-minute activity buckets, and
 * [Buckets.BUCKET_MS] is a whole number of sample intervals, so every bucket boundary is also a sample point (the
 * host tests check both). That lets one wake do both jobs: the bucket close and its Gadgetbridge push run under
 * the HR window's single `"hr-window"` wakelock instead of the bucket alarm's bare handler post, and the
 * per-acquisition log records the batch.
 */
class WakePlan(val bucketEndMs: Long, val sampleDue: Boolean) {
    /** True when the bucket close shares this wake with the HR sample. */
    fun batched(): Boolean = bucketEndMs > SampleWake.NO_BUCKET && sampleDue

    /** True when this wake has any job at all. */
    fun anythingDue(): Boolean = bucketEndMs > SampleWake.NO_BUCKET || sampleDue
}

object SampleWake {
    /** [WakePlan.bucketEndMs] sentinel: no bucket boundary is due. */
    const val NO_BUCKET = 0L

    /** Tolerance for an alarm firing a little before its grid point (phone-synced clock slack). */
    const val EARLY_MS = 1_000L

    /**
     * Decide what an alarm that fired at [nowMs] should do, using the schedule state the service keeps. A bucket
     * is due when the last grid boundary at or before now is newer than [lastClosedEnd] and the alarm did not fire
     * early against the armed [nextBucketEnd]; the sample is due unless it is still more than [slackMs] away.
     */
    @JvmStatic
    fun plan(
        nowMs: Long,
        lastClosedEnd: Long,
        nextBucketEnd: Long,
        nextHrSampleAt: Long,
    ): WakePlan = plan(nowMs, lastClosedEnd, nextBucketEnd, nextHrSampleAt,
        HrSampleSchedule.PENDING_SLACK_MS, EARLY_MS)

    /** [plan] with explicit tolerances, so the host tests can probe the two edges. */
    @JvmStatic
    fun plan(
        nowMs: Long,
        lastClosedEnd: Long,
        nextBucketEnd: Long,
        nextHrSampleAt: Long,
        slackMs: Long,
        earlyMs: Long,
    ): WakePlan {
        val end = Buckets.lastBoundary(nowMs + earlyMs)
        val bucket = if (end <= lastClosedEnd || nowMs + earlyMs < nextBucketEnd) NO_BUCKET else end
        return WakePlan(bucket, nextHrSampleAt - nowMs <= slackMs)
    }
}
