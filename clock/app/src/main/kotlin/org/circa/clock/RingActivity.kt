package org.circa.clock

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import org.circa.clock.data.RingService
import org.circa.clock.data.RingState
import org.circa.clock.model.Accent
import org.circa.clock.ui.RingScreen
import org.circa.clock.ui.Theme

/**
 * The full-screen alert, over the lock screen with the screen turned on. Buttons: Snooze, Dismiss. Keys: the
 * side button (KEYCODE_STEM_PRIMARY) dismisses; the crown's press is POWER, which an app never receives: the
 * framework goes home, which leaves this activity -> snooze (onUserLeaveHint; see clock/README.md).
 * The POWER branch below only matters if it ever reaches the app. BACK dismisses nothing (so a stray swipe
 * cannot silence the alarm); the screen stays on while ringing.
 */
class RingActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or WindowManager.LayoutParams.FLAG_ALLOW_LOCK_WHILE_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.setBackgroundDrawable(ColorDrawable(Color.BLACK))
        WindowInsetsControllerCompat(window, window.decorView).hide(WindowInsetsCompat.Type.systemBars())
        // BACK must not silence the alarm by accident.
        onBackPressedDispatcher.addCallback(this) { }
        setContent {
            val configuration = LocalConfiguration.current
            val systemDensity = LocalDensity.current
            val widthPx = (configuration.screenWidthDp * systemDensity.density).toInt()
            val density = androidx.compose.ui.unit.Density(org.circa.clock.model.Density.densityFor(widthPx), systemDensity.fontScale)
            // Close as soon as the service says the ringing is over (snooze/dismiss from the notification, timeout).
            LaunchedEffect(RingState.active) { if (!RingState.active) finish() }
            CompositionLocalProvider(LocalDensity provides density) {
                Theme(Accent.DEFAULT) {
                    Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black).semantics { testTagsAsResourceId = true }) {
                        RingScreen(onSnooze = { act(RingService.ACTION_SNOOZE) }, onDismiss = { act(RingService.ACTION_DISMISS) })
                    }
                }
            }
        }
    }

    /**
     * The side button never reaches an app: with the screen on and unlocked Circa's PhoneWindowManager answers
     * it by opening Recents (buttons.md), which leaves this screen with a "user leave" hint. Treat that as the
     * side-button snooze (a timer is dismissed). With the keyguard up the button turns the screen off instead,
     * which [org.circa.clock.data.RingService] turns into the same snooze.
     */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (RingState.active) act(if (RingState.canSnooze) RingService.ACTION_SNOOZE else RingService.ACTION_DISMISS)
    }

    private fun act(action: String) {
        val s = RingService.instance
        if (s != null) { if (action == RingService.ACTION_SNOOZE) s.snooze() else s.dismiss() }
        else RingService.send(this, action)
        finish()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean = when (keyCode) {
        KEYCODE_STEM_PRIMARY -> { act(RingService.ACTION_DISMISS); true }
        KeyEvent.KEYCODE_POWER -> { act(if (RingState.canSnooze) RingService.ACTION_SNOOZE else RingService.ACTION_DISMISS); true }
        else -> super.onKeyDown(keyCode, event)
    }

    companion object { const val KEYCODE_STEM_PRIMARY = 264 }
}
