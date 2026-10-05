package org.circa.settings.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.circa.settings.SettingsController
import org.circa.settings.data.BatteryData
import org.circa.settings.data.BatteryUsage
import org.circa.settings.model.AppEntry
import org.circa.settings.model.BatteryUsageModel
import org.circa.settings.model.SettingsPage

/**
 * Battery usage (audit A07): the level and charge state, the screen-on time and the apps that used
 * the battery since the last full charge, all read by `data/BatteryData`. Tapping an app opens its App info.
 */
@Composable
internal fun BatteryUsagePage(c: SettingsController) {
    val ctx = LocalContext.current
    val data = remember { BatteryData(ctx) }
    val usage by produceState<BatteryUsage?>(null) {
        while (true) {
            value = withContext(Dispatchers.IO) { data.read() }
            delay(15_000)
        }
    }
    ListPage(c, SettingsPage.BATTERY_USAGE) { spec ->
        val u = usage
        if (u == null) {
            noteItem("usage_loading", "Loading…")
            return@ListPage
        }
        val now = System.currentTimeMillis()
        infoRow("usage_level", spec, "Battery", BatteryUsageModel.statusLine(u.percent, u.charging))
        BatteryUsageModel.remaining(u.remainingMs, u.charging)?.let { infoRow("usage_remaining", spec, "Estimate", it) }
        infoRow("usage_screen", spec, "Screen on", BatteryUsageModel.duration(u.screenOnMs))
        noteItem("usage_period", BatteryUsageModel.periodLabel(now, u.sinceMs))
        val rows = BatteryUsageModel.rows(u.apps)
        if (rows.isEmpty()) {
            noteItem("usage_none", "No app has used the battery yet")
        } else {
            noteItem("usage_apps_header", if (u.hasPower) "App battery use" else "App screen time")
            rows.forEach { r ->
                val pkg = r.pkg
                if (pkg != null) {
                    appRow("usage_$pkg", spec, AppEntry(pkg, r.label, null, 0, false, true, true, 0), r.secondary) {
                        c.openSettingsPage(SettingsPage.APP_INFO, pkg)
                    }
                }
            }
        }
    }
}
