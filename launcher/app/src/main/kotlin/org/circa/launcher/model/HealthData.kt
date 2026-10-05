package org.circa.launcher.model

/** One heart-rate sample from WatchLink's `/history` (WatchLink's clock; see the provider doc). */
data class HrSample(val timeMs: Long, val bpm: Int)

/**
 * What the launcher knows about the wearer's health, read from WatchLink's ContentProvider
 * (`org.circa.watchlink.health`, see watchlink/DATA-CONTRACT.md):
 *
 *  - [hrBpm] is the newest accepted reading, null when there is none *live* - WatchLink reports a
 *    reading older than 15 minutes as absent, so a stale number never reaches the face;
 *  - [hrTimeMs] is that reading's time (WatchLink's clock domain; only meaningful relative to the
 *    other samples);
 *  - [stepsToday] is steps since local midnight, null until WatchLink has observed the step counter
 *    (a hard 0 would otherwise hide the launcher's own `TYPE_STEP_COUNTER` fallback);
 *  - [history] is the last 60 minutes of heart-rate samples, oldest first, for the tile's sparkline.
 */
data class HealthData(
    val hrBpm: Int?,
    val hrTimeMs: Long?,
    val stepsToday: Int?,
    val history: List<HrSample>,
) {
    companion object {
        /** Nothing known yet, or WatchLink absent/empty: the UI's no-data state. */
        /**
         * What the health tile says in place of the heart-rate number: WatchLink's provider could not
         * be reached (not installed / not running / refused us) versus reachable but without a live sample.
         */
        fun emptyHeartRateText(providerReachable: Boolean): String =
            if (providerReachable) "No reading yet" else "Waiting for WatchLink"

        val EMPTY = HealthData(hrBpm = null, hrTimeMs = null, stepsToday = null, history = emptyList())
    }
}
