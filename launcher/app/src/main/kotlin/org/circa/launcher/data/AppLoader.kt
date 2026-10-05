package org.circa.launcher.data

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.app.usage.UsageStatsManager
import android.graphics.drawable.Drawable
import android.net.Uri
import android.provider.Settings
import org.circa.launcher.model.AppEntry
import org.circa.launcher.model.AppListModel
import org.circa.launcher.model.RecentApps
import org.circa.launcher.model.Shortcuts
import org.circa.launcher.model.UsageEntry

/**
 * Queries and launches installed launcher activities. Android-dependent by design; kept out of the
 * pure [org.circa.launcher.model] package.
 */
object AppLoader {

    /** How far back to ask for usage stats (a day; INTERVAL_DAILY buckets never span more). */
    private const val USAGE_WINDOW_MILLIS = 24L * 60 * 60 * 1000

    /** All launchable apps, excluding this launcher, sorted for display. */
    fun loadApps(context: Context): List<AppEntry> {
        val pm = context.packageManager
        val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val infos = pm.queryIntentActivities(main, 0)
        val selfPackage = context.packageName

        val entries = infos.mapNotNull { info ->
            val label = info.loadLabel(pm)?.toString()
            if (label.isNullOrBlank()) {
                null
            } else {
                AppEntry(
                    label = label,
                    packageName = info.activityInfo.packageName,
                    className = info.activityInfo.name,
                )
            }
        }
        return AppListModel.sort(AppListModel.filterSelf(entries, selfPackage))
    }

    /**
     * Recently used apps, most recent first, capped at [limit]. Empty when the usage-stats appop is
     * not granted (queryUsageStats then returns nothing) or no launchable app was used yet, which is
     * exactly the "no Recents section" degradation the launcher wants.
     */
    fun loadRecentApps(context: Context, limit: Int = 3): List<AppEntry> {
        val manager = context.getSystemService(UsageStatsManager::class.java) ?: return emptyList()
        val now = System.currentTimeMillis()
        val stats = runCatching {
            manager.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, now - USAGE_WINDOW_MILLIS, now)
        }.getOrNull() ?: return emptyList()
        val launchable = loadApps(context)
        val usage = stats
            .filter { it.packageName != context.packageName }
            .map { UsageEntry(it.packageName, it.lastTimeUsed) }
        return RecentApps.select(usage, launchable, limit)
    }

    /** The Shortcuts tile's apps, resolved from [Shortcuts.DEFAULTS] against the installed apps. */
    fun loadShortcuts(context: Context): List<AppEntry> = Shortcuts.resolve(loadApps(context))

    /** Launcher icon for [entry], or null when the package manager cannot resolve one. */
    fun loadIcon(context: Context, entry: AppEntry): Drawable? =
        runCatching {
            context.packageManager.getActivityIcon(
                ComponentName(entry.packageName, entry.className),
            )
        }.getOrNull()

    /** Start [entry] as a new task (launchers have no task affinity to reuse). */
    fun launchApp(context: Context, entry: AppEntry) {
        val intent = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setComponent(ComponentName(entry.packageName, entry.className))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        entry.clockPage?.let { intent.putExtra("page", it) }
        context.startActivity(intent)
    }

    /** Open the system App info screen for [entry] (long-press on a list row). */
    fun openAppInfo(context: Context, entry: AppEntry) {
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", entry.packageName, null),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }
}
