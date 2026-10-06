package org.circa.exercise

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.ViewConfiguration
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.circa.exercise.data.ExerciseService
import org.circa.exercise.data.GpsState
import org.circa.exercise.data.Haptics
import org.circa.exercise.data.Live
import org.circa.exercise.data.Notifs
import org.circa.exercise.data.Storage
import org.circa.exercise.model.ActivityType
import org.circa.exercise.model.Phase
import org.circa.exercise.ui.Actions
import org.circa.exercise.ui.ExerciseApp
import org.circa.exercise.ui.Screen
import org.circa.exercise.ui.Theme

/**
 * Circa Exercise: activity list -> live pages -> controls -> summary, one activity. The recording itself lives in
 * [ExerciseService]; this activity only shows [Live] and sends the service actions.
 *
 * Side button (KEYCODE_STEM_PRIMARY, see [dispatchKeyEvent] and exercise/README.md).
 */
class MainActivity : ComponentActivity(), Actions {
    private var screen by mutableStateOf<Screen>(Screen.List)
    private var last by mutableStateOf<ActivityType?>(null)
    private var pendingStart: ActivityType? = null
    private val handler = Handler(Looper.getMainLooper())
    private var stemTracking = false
    private var stemLongFired = false
    private val stemLong = Runnable {
        stemLongFired = true
        Haptics.tap(this)
        if (recording()) screen = Screen.ConfirmEnd
    }

    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        pendingStart?.let { pendingStart = null; startNow(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        hideSystemBars()
        Notifs.ensureChannels(this)
        Live.ensureLoaded(this)
        last = Storage(this).lastType()
        restoreIfNeeded()
        screen = resolve()
        if (savedInstanceState == null) handleIntent(intent)
        onBackPressedDispatcher.addCallback(this) { back() }
        // A finished workout (End -> Save) shows its summary, wherever it was ended from.
        lifecycleScope.launch {
            var shown = Live.pending.value != null
            Live.pending.collect { p ->
                if (p != null && !shown) { shown = true; screen = Screen.Summary; Haptics.happy(this@MainActivity) }
                if (p == null) shown = false
            }
        }
        setContent {
            val configuration = LocalConfiguration.current
            val systemDensity = LocalDensity.current
            val widthPx = (configuration.screenWidthDp * systemDensity.density).toInt()
            val density = androidx.compose.ui.unit.Density(widthPx / 200f, systemDensity.fontScale)
            CompositionLocalProvider(LocalDensity provides density) {
                Theme {
                    Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black).semantics { testTagsAsResourceId = true }) {
                        ExerciseApp(screen, last, this@MainActivity)
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // An auto workout that started in the background could not take the location service type (and so no GPS):
        // the app is visible now, which allows it. Re-promoting the service is harmless when GPS already works.
        Live.view.value?.let { if (it.type.gps && it.gps == GpsState.SEARCHING) ExerciseService.send(this, ExerciseService.ACTION_RESTORE) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        restoreIfNeeded()
        if (screen == Screen.List || screen == Screen.More) screen = resolve()
        handleIntent(intent)
    }

    /** The session is on disk but no service has it in memory (the process died): restart the service. */
    private fun restoreIfNeeded() {
        if (Live.view.value == null && Live.isRecording(this)) ExerciseService.send(this, ExerciseService.ACTION_RESTORE)
    }

    private fun recording(): Boolean = Live.view.value?.phase.let { it == Phase.RECORDING || it == Phase.PAUSED } || Live.isRecording(this)

    private fun resolve(): Screen = when {
        recording() -> Screen.Live
        Live.pending.value != null -> Screen.Summary
        else -> Screen.List
    }

    private fun handleIntent(i: Intent?) {
        when (i?.getStringExtra(EXTRA_ENTRY)) {
            ENTRY_END -> if (recording()) screen = Screen.ConfirmEnd
            ENTRY_COUNTDOWN -> {
                val t = ActivityType.fromId(i.getStringExtra(EXTRA_TYPE))
                if (!recording() && Live.pending.value == null) screen = if (t != null) Screen.Countdown(t) else Screen.List
            }
            ENTRY_LIST -> if (!recording() && Live.pending.value == null) screen = Screen.List
        }
    }

    // ---- Actions ----------------------------------------------------------------------------------------------

    override fun pick(type: ActivityType) {
        val needed = buildList {
            add(if (Build.VERSION.SDK_INT >= 36) PERM_READ_HEART_RATE else Manifest.permission.BODY_SENSORS)
            add(Manifest.permission.ACTIVITY_RECOGNITION)
            add(Manifest.permission.POST_NOTIFICATIONS)
            if (type.gps) { add(Manifest.permission.ACCESS_FINE_LOCATION); add(Manifest.permission.ACCESS_COARSE_LOCATION) }
        }.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (needed.isEmpty()) startNow(type) else { pendingStart = type; permissions.launch(needed.toTypedArray()) }
    }

    private fun startNow(type: ActivityType) {
        // Only a saved workout becomes "Last used" (Storage.commit); starting/discarding one leaves it alone.
        ExerciseService.send(this, ExerciseService.ACTION_START, type)
        screen = Screen.Live
    }

    override fun go(s: Screen) { screen = s }

    /** BACK and swipe right: one level up; on the live screen it opens the controls (design: swipe right = Resume/End). */
    override fun back() {
        when (screen) {
            Screen.List -> finish()
            Screen.More, is Screen.Countdown -> screen = Screen.List
            Screen.Live -> screen = Screen.Controls
            Screen.Controls -> screen = Screen.Live
            Screen.ConfirmEnd -> screen = if (recording()) Screen.Controls else resolve()
            Screen.ConfirmDiscard -> screen = Screen.ConfirmEnd
            Screen.Summary -> Unit // "Done" saves; nothing is lost by accident
        }
    }

    override fun toggle() { ExerciseService.send(this, ExerciseService.ACTION_TOGGLE) }

    override fun save() {
        ExerciseService.send(this, ExerciseService.ACTION_END)
        // The summary appears when the service publishes it (Live.pending).
    }

    override fun discard() {
        ExerciseService.send(this, ExerciseService.ACTION_DISCARD)
        screen = Screen.List
    }

    override fun keepAuto() { ExerciseService.send(this, ExerciseService.ACTION_AUTO_KEEP) }

    override fun discardAuto() {
        ExerciseService.send(this, ExerciseService.ACTION_AUTO_DISCARD)
        screen = Screen.List
    }

    override fun done() {
        val s = Live.pending.value ?: run { screen = Screen.List; return }
        lifecycleScope.launch {
            withContext(Dispatchers.IO) { Storage(this@MainActivity).commit(s) }
            Live.pending.value = null
            last = Storage(this@MainActivity).lastType()
            screen = Screen.List
        }
    }

    // ---- Side button ------------------------------------------------------------------------------------------

    /**
     * KEYCODE_STEM_PRIMARY reaches this window first because the app holds OVERRIDE_SYSTEM_KEY_BEHAVIOR_IN_FOCUSED_WINDOW.
     * PhoneWindowManager runs its deferred short- and long-press actions only when the initial DOWN comes back
     * unconsumed (DeferredKeyActionExecutor: one decision per gesture), so it is all or nothing:
     *
     * - Not recording: never consumed -> the platform's short press (notifications) and long press
     *   (EXERCISE_LONG_PRESS -> LongPressActivity) work as everywhere else.
     * - Recording or paused: the whole gesture is consumed. Released before the long-press timeout = pause/resume;
     *   held past it = the End offer, which is exactly what the platform's long press would have opened here.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_STEM_PRIMARY) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                stemTracking = recording() && screen != Screen.Summary
                if (stemTracking) {
                    stemLongFired = false
                    handler.removeCallbacks(stemLong)
                    handler.postDelayed(stemLong, ViewConfiguration.getLongPressTimeout().toLong().coerceAtLeast(400L))
                }
            }
            if (stemTracking) {
                if (event.action == KeyEvent.ACTION_UP) {
                    handler.removeCallbacks(stemLong)
                    stemTracking = false
                    if (!stemLongFired && !event.isCanceled) toggle()
                }
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun hideSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.setBackgroundDrawable(ColorDrawable(Color.BLACK))
        WindowInsetsControllerCompat(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    companion object {
        const val EXTRA_ENTRY = "org.circa.exercise.extra.ENTRY"
        const val EXTRA_TYPE = "org.circa.exercise.extra.TYPE"
        const val ENTRY_END = "end"
        const val ENTRY_LIST = "list"
        const val ENTRY_COUNTDOWN = "countdown"
        const val PERM_READ_HEART_RATE = "android.permission.health.READ_HEART_RATE"
    }
}
