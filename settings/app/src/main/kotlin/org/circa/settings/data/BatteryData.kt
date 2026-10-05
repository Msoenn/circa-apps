package org.circa.settings.data

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.util.Log
import org.circa.settings.model.BatteryUsageModel
import org.circa.settings.model.RawAppUsage

/** Everything the Battery usage page draws. */
data class BatteryUsage(
    val percent: Int?,
    val charging: Boolean,
    /** Wall-clock start of the stats window (last full charge), or null = the last 24 hours. */
    val sinceMs: Long?,
    val screenOnMs: Long,
    val remainingMs: Long?,
    val apps: List<RawAppUsage>,
    /** BatteryUsageStats answered with per-app power (false on an emulator without a power profile). */
    val hasPower: Boolean,
)

/**
 * Battery usage for the round page: per-app power from `BatteryStatsManager.getBatteryUsageStats`
 * (@SystemApi / hidden, needs BATTERY_STATS, signature permission of this platform-signed app;
 * called by reflection like the other platform-only APIs here), foreground time per app and the
 * screen-on time from UsageStatsManager (PACKAGE_USAGE_STATS). Each source fails on its own.
 */
class BatteryData(context: Context) {
    private val app = context.applicationContext
    private val pm = app.packageManager

    fun read(): BatteryUsage {
        val now = System.currentTimeMillis()
        val sticky = app.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = sticky?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = sticky?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val percent = if (level >= 0 && scale > 0) level * 100 / scale else null
        val plugged = (sticky?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0

        val stats = platformStats()
        val from = stats?.startMs?.takeIf { it in 1 until now } ?: (now - DAY_MS)
        val usage = app.getSystemService(UsageStatsManager::class.java)
        val fg: Map<String, Long> = runCatching {
            usage.queryAndAggregateUsageStats(from, now).mapValues { it.value.totalTimeInForeground }
        }.onFailure { Log.w(TAG, "queryAndAggregateUsageStats", it) }.getOrDefault(emptyMap())
        val screen = runCatching {
            val ev = usage.queryEvents(from, now)
            val list = ArrayList<Pair<Int, Long>>()
            val e = UsageEvents.Event()
            while (ev.hasNextEvent()) { ev.getNextEvent(e); list += e.eventType to e.timeStamp }
            BatteryUsageModel.screenOnMs(list, from, now)
        }.onFailure { Log.w(TAG, "queryEvents", it) }.getOrDefault(0L)

        // One entry per package: power from the uid consumers, foreground time from UsageStats.
        val byPkg = LinkedHashMap<String, RawAppUsage>()
        stats?.perUid?.forEach { (uid, mah) ->
            val pkg = pm.getPackagesForUid(uid)?.firstOrNull() ?: return@forEach
            val prev = byPkg[pkg]
            byPkg[pkg] = RawAppUsage(uid, pkg, label(pkg), (prev?.powerMah ?: 0.0) + mah, fg[pkg] ?: 0L)
        }
        fg.forEach { (pkg, ms) ->
            if (pkg !in byPkg && ms > 0L) {
                val uid = runCatching { pm.getApplicationInfo(pkg, 0).uid }.getOrDefault(-1)
                byPkg[pkg] = RawAppUsage(uid, pkg, label(pkg), 0.0, ms)
            }
        }
        return BatteryUsage(
            percent = percent, charging = plugged, sinceMs = stats?.startMs?.takeIf { it in 1 until now },
            screenOnMs = screen, remainingMs = stats?.remainingMs?.takeIf { it > 0L },
            apps = byPkg.values.filter { it.pkg != null && isShown(it.pkg) },
            hasPower = stats != null && stats.perUid.values.any { it > 0.0 },
        )
    }

    /** Hide packages without a launcher entry that are not user apps (framework plumbing). */
    private fun isShown(pkg: String?): Boolean = pkg != null && runCatching {
        val ai = pm.getApplicationInfo(pkg, 0)
        val system = ai.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM != 0
        !system || pm.getLaunchIntentForPackage(pkg) != null
    }.getOrDefault(true)

    private fun label(pkg: String): String = runCatching {
        pm.getApplicationInfo(pkg, 0).loadLabel(pm).toString()
    }.getOrDefault(pkg).ifBlank { pkg }

    private class PlatformStats(val startMs: Long, val remainingMs: Long, val perUid: Map<Int, Double>)

    private fun platformStats(): PlatformStats? = runCatching {
        val bsm = app.getSystemService("batterystats") ?: error("no batterystats service")
        val queryCls = Class.forName("android.os.BatteryUsageStatsQuery")
        val builder = Class.forName("android.os.BatteryUsageStatsQuery\$Builder").getConstructor().newInstance()
        val query = builder.javaClass.getMethod("build").invoke(builder)
        val stats = bsm.javaClass.getMethod("getBatteryUsageStats", queryCls).invoke(bsm, query)!!
        try {
            val c = stats.javaClass
            val start = c.getMethod("getStatsStartTimestamp").invoke(stats) as Long
            val remaining = runCatching { c.getMethod("getBatteryTimeRemainingMs").invoke(stats) as Long }.getOrDefault(-1L)
            val uids = c.getMethod("getUidBatteryConsumers").invoke(stats) as List<*>
            val perUid = HashMap<Int, Double>()
            for (u in uids) {
                u ?: continue
                val uid = u.javaClass.getMethod("getUid").invoke(u) as Int
                val mah = u.javaClass.getMethod("getConsumedPower").invoke(u) as Double
                perUid[uid] = (perUid[uid] ?: 0.0) + mah
            }
            Log.i(TAG, "BatteryUsageStats: ${uids.size} uid consumers, total ${perUid.values.sum()} mAh, start=$start remaining=$remaining")
            PlatformStats(start, remaining, perUid)
        } finally {
            runCatching { (stats as AutoCloseable).close() }
        }
    }.onFailure { Log.w(TAG, "BatteryUsageStats", it) }.getOrNull()

    companion object {
        private const val TAG = "CircaBattery"
        private const val DAY_MS = 24L * 60 * 60 * 1000
    }
}
