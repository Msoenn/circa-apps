package org.circa.clock

import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.provider.AlarmClock
import android.text.format.DateFormat
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import org.circa.clock.data.ClockModel
import org.circa.clock.data.Notifs
import org.circa.clock.data.Screen
import org.circa.clock.model.AlarmIntents
import org.circa.clock.model.Accent
import org.circa.clock.model.PickerState
import org.circa.clock.ui.ClockApp
import org.circa.clock.ui.Theme

/**
 * Circa Clock: Alarm, Timer and Stopwatch as three pages of one activity. Also the target of the AlarmClock
 * intents (SET_ALARM, SHOW_ALARMS, SET_TIMER, SHOW_TIMERS), which the manifest claims at priority 100 so that
 * nothing else (DeskClock) opens.
 */
class MainActivity : ComponentActivity() {
    private lateinit var model: ClockModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        hideSystemBars()
        Notifs.ensureChannels(this)
        model = ClockModel(this)
        if (savedInstanceState == null) handleIntent(intent)
        onBackPressedDispatcher.addCallback(this) { if (!model.pop()) finish() }
        setContent {
            val configuration = LocalConfiguration.current
            val systemDensity = LocalDensity.current
            val widthPx = (configuration.screenWidthDp * systemDensity.density).toInt()
            val density = androidx.compose.ui.unit.Density(org.circa.clock.model.Density.densityFor(widthPx), systemDensity.fontScale)
            CompositionLocalProvider(LocalDensity provides density) {
                Theme(accent()) {
                    Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black).semantics { testTagsAsResourceId = true }) {
                        ClockApp(model, DateFormat.is24HourFormat(this@MainActivity), ::finish)
                    }
                }
            }
        }
    }

    private fun accent(): Accent {
        val argb = runCatching { android.provider.Settings.Secure.getInt(contentResolver, "circa_accent_color") }.getOrNull()
        return Accent.fromArgb(argb) ?: Accent.DEFAULT
    }

    override fun onResume() { super.onResume(); model.refresh() }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        model.refresh()
        handleIntent(intent)
    }

    private fun handleIntent(i: Intent?) {
        i ?: return
        val is24 = DateFormat.is24HourFormat(this)
        model.page = i.getIntExtra(EXTRA_PAGE, model.page)
        when (i.action) {
            AlarmClock.ACTION_SHOW_ALARMS -> { model.page = 0 }
            AlarmClock.ACTION_SHOW_TIMERS -> { model.page = 1 }
            AlarmClock.ACTION_SET_ALARM -> {
                model.page = 0
                val hour = i.getIntExtra(AlarmClock.EXTRA_HOUR, -1)
                if (hour !in 0..23) {
                    model.push(Screen.AlarmPicker(null, PickerState(7, 0, is24h = is24)))
                } else {
                    val minute = i.getIntExtra(AlarmClock.EXTRA_MINUTES, 0).coerceIn(0, 59)
                    val days = AlarmIntents.daysMask(i.getIntegerArrayListExtra(AlarmClock.EXTRA_DAYS))
                    val existing = AlarmIntents.findDuplicate(model.alarms, hour, minute, days)
                    if (existing != null) model.setEnabled(existing.id, true)
                    else model.addAlarm(hour, minute, days, i.getStringExtra(AlarmClock.EXTRA_MESSAGE).orEmpty())
                    if (i.getBooleanExtra(AlarmClock.EXTRA_SKIP_UI, false)) { setResult(RESULT_OK); finish() }
                }
            }
            AlarmClock.ACTION_SET_TIMER -> {
                model.page = 1
                val ms = AlarmIntents.timerMs(i.getIntExtra(AlarmClock.EXTRA_LENGTH, 0))
                if (ms != null) {
                    model.startTimer(ms)
                    if (i.getBooleanExtra(AlarmClock.EXTRA_SKIP_UI, false)) { setResult(RESULT_OK); finish() }
                }
            }
        }
    }

    private fun hideSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.setBackgroundDrawable(ColorDrawable(Color.BLACK))
        WindowInsetsControllerCompat(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    companion object { const val EXTRA_PAGE = "page" }
}
