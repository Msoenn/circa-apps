package org.circa.watchlink

/** 10-minute activity grid helpers (spec §4.3). Pure Kotlin, host-tested. */
object Buckets {
    const val BUCKET_MS = 10 * 60 * 1000L

    /** Next grid boundary strictly after t. */
    @JvmStatic
    fun nextBoundary(t: Long): Long = (Math.floorDiv(t, BUCKET_MS) + 1) * BUCKET_MS

    /** Latest grid boundary at or before t. */
    @JvmStatic
    fun lastBoundary(t: Long): Long = Math.floorDiv(t, BUCKET_MS) * BUCKET_MS

    /**
     * One stored record: `ts` is the END of the interval, in ms (GB convention). [still], [restless] and [worn] are
     * the sleep-tracking measures of the bucket (see [MotionEpochs] / [WornClock]); -1 = not measured (sleep tracking
     * off, no accelerometer, or a record written before they existed). The CSV keeps the old 4-field form for those.
     */
    class Record @JvmOverloads constructor(
        @JvmField val ts: Long,
        @JvmField val steps: Int,
        @JvmField val hr: Int,
        @JvmField val hrCount: Int,
        /** Minutes of the bucket the wrist was still. */
        @JvmField val still: Int = -1,
        /** 5-second epochs of the bucket whose arm angle changed by more than [MotionEpochs.ANGLE_DEG]. */
        @JvmField val restless: Int = -1,
        /** Minutes of the bucket the watch was on the wrist and not charging. */
        @JvmField val worn: Int = -1,
    ) {
        fun measured(): Boolean = still >= 0 && worn >= 0

        fun csv(): String =
            if (still < 0 && restless < 0 && worn < 0) "$ts,$steps,$hr,$hrCount"
            else "$ts,$steps,$hr,$hrCount,$still,$restless,$worn"

        /** The same measures at another end time (legacy off-grid records are snapped to the grid). */
        fun at(newTs: Long): Record = Record(newTs, steps, hr, hrCount, still, restless, worn)

        companion object {
            @JvmStatic
            fun fromCsv(l: String): Record? {
                val f = l.trim { it <= ' ' }.split(",")   // Java String.trim() semantics
                if (f.size < 4) return null
                return try {
                    val ex = f.size >= 7
                    Record(
                        java.lang.Long.parseLong(f[0]),
                        java.lang.Integer.parseInt(f[1]), java.lang.Integer.parseInt(f[2]),
                        java.lang.Integer.parseInt(f[3]),
                        if (ex) java.lang.Integer.parseInt(f[4]) else -1,
                        if (ex) java.lang.Integer.parseInt(f[5]) else -1,
                        if (ex) java.lang.Integer.parseInt(f[6]) else -1,
                    )
                } catch (e: NumberFormatException) {
                    null
                }
            }
        }
    }
}
