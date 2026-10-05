package org.circa.settings.ui

import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import org.circa.settings.SettingsController
import org.circa.settings.model.SettingsPage

/**
 * Default apps (audit A13): what answers Home, links and typing. Read-only; the home app is changed by
 * installing another launcher, which the watch does not offer.
 */
@Composable
internal fun DefaultAppsPage(c: SettingsController) {
    val ctx = LocalContext.current
    val rows = remember {
        val pm = ctx.packageManager
        fun labelOf(intent: Intent): String? = pm.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
            ?.takeIf { it.activityInfo != null && it.activityInfo.packageName != "android" }
            ?.loadLabel(pm)?.toString()
        val home = labelOf(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME))
        val browser = labelOf(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://example.org")).addCategory(Intent.CATEGORY_BROWSABLE))
        val ime = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
            ?.substringBefore('/')?.let { runCatching { pm.getApplicationInfo(it, 0).loadLabel(pm).toString() }.getOrNull() }
        listOf("Home app" to home, "Browser" to browser, "Keyboard" to ime)
    }
    ListPage(c, SettingsPage.DEFAULT_APPS) { spec ->
        rows.forEach { (label, value) -> infoRow("default_${label.lowercase().replace(' ', '_')}", spec, label, value ?: "None") }
    }
}

/** Accounts, sync and users: this watch has none of them (audit A13); one plain page instead of AOSP's phone UI. */
@Composable
internal fun NotOnWatchPage(c: SettingsController) {
    val topic = c.pageArg.value
    val text = when (topic) {
        "users" -> "This watch has one user."
        else -> "Accounts and sync stay on your phone."
    }
    ListPage(c, SettingsPage.NOT_ON_WATCH) { _ ->
        noteItem("not_on_watch_text", text)
    }
}
