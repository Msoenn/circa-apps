package org.circa.settings

import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import org.circa.settings.model.SettingsNav
import org.circa.settings.ui.SettingsScreen
import org.circa.settings.ui.Theme

/**
 * Circa Settings' only activity. It answers `android.settings.SETTINGS`, the launcher entry and the
 * deep links in [SettingsNav.ACTIONS] (manifest intent filters at priority 100, above AOSP
 * Settings); each opens its page, and BACK from that page leaves the app.
 */
class MainActivity : ComponentActivity() {

    private lateinit var controller: SettingsController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        hideSystemBars()
        controller = SettingsController(this, ::finish)
        controller.onPinChanged = { setResult(RESULT_OK) }
        handleIntent(intent)
        // BACK (key or gesture) = the swipe-right dismiss: one level up, out from the entry page.
        onBackPressedDispatcher.addCallback(this) { controller.settingsBack() }
        setContent {
            val configuration = androidx.compose.ui.platform.LocalConfiguration.current
            val systemDensity = androidx.compose.ui.platform.LocalDensity.current
            val widthPx = (configuration.screenWidthDp * systemDensity.density).toInt()
            val density = androidx.compose.ui.unit.Density(
                org.circa.settings.model.Density.densityFor(widthPx), systemDensity.fontScale)
            androidx.compose.runtime.CompositionLocalProvider(
                androidx.compose.ui.platform.LocalDensity provides density) {
            Theme(controller.settingsState.value.accent) {
                androidx.compose.foundation.layout.Box(
                    modifier = androidx.compose.ui.Modifier
                        .fillMaxSize()
                        .background(androidx.compose.ui.graphics.Color.Black)
                        .semantics { testTagsAsResourceId = true },
                ) { SettingsScreen(controller) }
            }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        controller.refreshSettings()
    }

    private fun handleIntent(intent: Intent?) {
        var page = SettingsNav.pageFor(intent?.action, intent?.getStringExtra(SettingsNav.EXTRA_PAGE))
        // The app-detail deep links name their app in the data URI or an extra (SettingsNav.argFor).
        val arg = SettingsNav.argFor(intent?.action, intent?.dataString) { intent?.getStringExtra(it) }
        page = SettingsNav.entryPage(page, arg)
        // "Set a new password" on a watch that already has a PIN means change it.
        if (page == org.circa.settings.model.SettingsPage.PIN_NEW &&
            getSystemService(android.app.KeyguardManager::class.java)?.isDeviceSecure == true) {
            page = org.circa.settings.model.SettingsPage.PIN_CHANGE
        }
        controller.enterAt(page, arg)
    }

    /** Edge to edge on black, system bars hidden (they come back transiently on an edge swipe). */
    private fun hideSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.setBackgroundDrawable(ColorDrawable(Color.BLACK))
        WindowInsetsControllerCompat(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }
}
