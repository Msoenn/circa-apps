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

    /** One stored record: `ts` is the END of the interval, in ms (GB convention). */
    class Record(
        @JvmField val ts: Long,
        @JvmField val steps: Int,
        @JvmField val hr: Int,
        @JvmField val hrCount: Int,
    ) {
        fun csv(): String = "$ts,$steps,$hr,$hrCount"

        companion object {
            @JvmStatic
            fun fromCsv(l: String): Record? {
                val f = l.trim { it <= ' ' }.split(",")   // Java String.trim() semantics
                if (f.size < 4) return null
                return try {
                    Record(
                        java.lang.Long.parseLong(f[0]),
                        java.lang.Integer.parseInt(f[1]), java.lang.Integer.parseInt(f[2]),
                        java.lang.Integer.parseInt(f[3])
                    )
                } catch (e: NumberFormatException) {
                    null
                }
            }
        }
    }
}
