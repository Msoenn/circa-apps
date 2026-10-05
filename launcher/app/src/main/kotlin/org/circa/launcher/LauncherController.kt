package org.circa.launcher

import android.app.AlarmManager
import android.content.Context
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableStateOf
import android.content.Intent
import android.provider.Settings
import org.circa.launcher.data.AppLoader
import org.circa.launcher.data.CircaShared
import org.circa.launcher.data.ExerciseStateRepository
import org.circa.launcher.data.HealthRepository
import org.circa.launcher.data.NotificationStore
import org.circa.launcher.data.PhoneDataRepository
import org.circa.launcher.data.QuickSettings
import org.circa.launcher.data.QuickSettingsState
import org.circa.launcher.data.SharedPrefsStore
import org.circa.launcher.data.ToggleState
import java.time.LocalDate
import org.circa.launcher.model.Accent
import org.circa.launcher.model.AppEntry
import org.circa.launcher.model.CalendarEvent
import org.circa.launcher.model.FaceStyle
import org.circa.launcher.model.HealthData
import org.circa.launcher.model.HealthMapping
import org.circa.launcher.model.HrSample
import org.circa.launcher.model.LauncherSettings
import org.circa.launcher.model.MusicState
import org.circa.launcher.model.StepCounterModel
import org.circa.launcher.model.WeatherReading
import org.circa.launcher.model.NotificationItem

/**
 * Which screen the launcher is showing. Home is the face/tile carousel; Recents and AllApps are the
 * two app-list screens (stock's `RecentsActivity` / `AllAppsLauncherActivity`).
 */
enum class Screen { HOME, RECENTS, ALL_APPS, FACE_PICKER }

/** Index of the carousel pages: face first, then the tiles (health, media, agenda, shortcuts). */
object CarouselPage {
    const val FACE = 0
    const val HEALTH_TILE = 1
    const val MEDIA_TILE = 2
    const val AGENDA_TILE = 3
    const val SHORTCUTS_TILE = 4
    const val COUNT = 5
}

/**
 * Which end of the tray the next open shows: the quick-settings top when the face is swiped down,
 * the notification stream when it is swiped up (stock's model).
 */
enum class TrayEnd { QUICK_SETTINGS, NOTIFICATIONS }

/**
 * Observable state shared between the activity (intents, keys, broadcasts) and the Compose UI.
 */
class LauncherController(private val context: Context) {

    val screen: MutableState<Screen> = mutableStateOf(Screen.HOME)

    /**
     * True while the system keyguard is locked (a real Android PIN is set and the device has not been
     * unlocked since the screen went off). The launcher face is shown over the keyguard then and
     * nothing else is reachable: see [LockedFaceScreen] and launcher/README.md.
     */
    val locked: MutableState<Boolean> = mutableStateOf(false)

    /**
     * Set by the activity: asks the system to dismiss the keyguard (the PIN bouncer) and runs the
     * given continuation after a successful unlock. Null until the activity is created.
     */
    var unlockRequester: ((() -> Unit) -> Unit)? = null

    /**
     * Run [then] now when the device is unlocked, else bring up the PIN bouncer first and run it
     * after a successful unlock (a cancelled or failed unlock drops it).
     */
    fun requireUnlock(then: () -> Unit) {
        val requester = unlockRequester
        if (!locked.value || requester == null) then() else requester(then)
    }

    /** The activity reports the keyguard state; locking drops back to the bare face. */
    fun setLocked(isLocked: Boolean) {
        if (isLocked && !locked.value) showFace()
        locked.value = isLocked
    }

    private val settings = LauncherSettings(
        SharedPrefsStore(context.getSharedPreferences("launcher", Context.MODE_PRIVATE)),
    )

    /** The watch face on the carousel's first page; persisted, chosen in the face picker. */
    val face: MutableState<FaceStyle> = mutableStateOf(settings.face)

    /**
     * The accent colour the whole UI is themed with: chosen in the face picker or in Circa Settings,
     * stored system-wide in `Settings.Secure circa_accent_color` (the shade follows it too), the
     * launcher's own preference until that is set. Re-read by [reloadShared] when the launcher comes back.
     */
    val accent: MutableState<Accent> = mutableStateOf(sharedAccent())

    private fun sharedAccent(): Accent = CircaShared.accent(context.contentResolver) ?: settings.accent

    /** The activity's onResume: pick up an accent changed in Circa Settings meanwhile. */
    fun reloadShared() {
        accent.value = sharedAccent()
    }

    /**
     * WatchLink's health values, read from its ContentProvider (see [HealthRepository] and
     * watchlink/DATA-CONTRACT.md); started/stopped with the launcher.
     */
    private val healthRepository = HealthRepository(context)

    /**
     * The exercise app's running workout, read from its state provider (see [ExerciseStateRepository]);
     * started/stopped with the launcher. Drives the small activity indicator on the face.
     */
    private val exerciseState = ExerciseStateRepository(context)

    /** The running workout's activity id (null = nothing recording), for the face's indicator. */
    val exerciseActivity: State<String?> get() = exerciseState.activity

    /** WatchLink's phone data (weather, calendar, music, status) for the complications and tiles. */
    private val phoneData = PhoneDataRepository(context)
    val phoneWeather: State<WeatherReading?> get() = phoneData.weather
    val phoneEvents: State<List<CalendarEvent>> get() = phoneData.events
    val phoneMusic: State<MusicState?> get() = phoneData.music
    val phoneConnected: State<Boolean?> get() = phoneData.connected

    /** A media transport button (`playpause` / `next` / `previous`), sent to the phone through WatchLink. */
    fun musicCommand(command: String) = phoneData.sendMusicCommand(command)

    /** Steps from the launcher's own `TYPE_STEP_COUNTER`, used only when WatchLink has none. */
    private val sensorSteps: MutableState<Int?> = mutableStateOf(null)

    /** Values injected by the DUMP-protected `DEMO_HEALTH` broadcast while it is active, else null. */
    private val demoHealth: MutableState<HealthData?> = mutableStateOf(null)

    /**
     * What the face complications and the health tile draw, the single source the UI reads:
     * the demo hook while it is active, else WatchLink, else the sensor fallback for steps
     * ([HealthMapping.preferredSteps]).
     */
    val health: State<HealthData> = derivedStateOf {
        val demo = demoHealth.value
        val watchLink = healthRepository.state.value
        HealthData(
            hrBpm = demo?.hrBpm ?: watchLink.hrBpm,
            hrTimeMs = demo?.hrTimeMs ?: watchLink.hrTimeMs,
            stepsToday = demo?.stepsToday
                ?: HealthMapping.preferredSteps(watchLink.stepsToday, sensorSteps.value),
            history = demo?.history?.takeIf { it.isNotEmpty() } ?: watchLink.history,
        )
    }

    /** True when WatchLink's health provider answers (or the demo hook is feeding the tile). */
    val healthProviderReachable: State<Boolean> = derivedStateOf {
        demoHealth.value != null || healthRepository.reachable.value
    }

    /** Index of the carousel page showing home, see [CarouselPage]. */
    val page: MutableState<Int> = mutableStateOf(CarouselPage.FACE)

    val batteryPercent: MutableState<Int?> = mutableStateOf(null)
    val nextAlarmMillis: MutableState<Long?> = mutableStateOf(null)
    val apps: MutableState<List<AppEntry>> = mutableStateOf(emptyList())
    val recentApps: MutableState<List<AppEntry>> = mutableStateOf(emptyList())
    val shortcuts: MutableState<List<AppEntry>> = mutableStateOf(emptyList())

    /** The tray is an overlay over the face, not a screen of its own. */
    val trayOpen: MutableState<Boolean> = mutableStateOf(false)
    val trayEnd: MutableState<TrayEnd> = mutableStateOf(TrayEnd.QUICK_SETTINGS)

    private val quickSettings = QuickSettings(context)

    /** Everything the quick-settings grid draws; refreshed when the tray opens and after each tap. */
    val qsState: MutableState<QuickSettingsState> = mutableStateOf(quickSettings.read())

    fun refreshApps() {
        val loaded = AppLoader.loadApps(context)
        apps.value = loaded
        shortcuts.value = AppLoader.loadShortcuts(context)
    }

    fun refreshRecentApps() {
        recentApps.value = AppLoader.loadRecentApps(context)
    }

    fun refreshNextAlarm() {
        val alarmManager = context.getSystemService(AlarmManager::class.java)
        nextAlarmMillis.value = alarmManager?.nextAlarmClock?.triggerTime
    }

    // ---- faces, accent, health data -----------------------------------------------------------

    /** Long-press on the face (or Circa Settings > Display > Watch face): stock's face picker. */
    fun openFacePicker() {
        closeTray()
        screen.value = Screen.FACE_PICKER
    }

    /** Picker tap: make [style] the face (persisted) and go back to it. */
    fun chooseFace(style: FaceStyle) {
        settings.face = style
        face.value = style
        leavePicker()
    }

    /** Leave the picker: back to the face. */
    fun leavePicker() = showFace()

    fun chooseAccent(choice: Accent) {
        settings.accent = choice
        CircaShared.setAccent(context.contentResolver, choice)
        accent.value = choice
    }

    /** A `TYPE_STEP_COUNTER` reading (steps since boot) -> today's steps, baseline persisted. */
    fun onStepCounter(counter: Long) {
        val reading = StepCounterModel.update(
            settings.stepState,
            counter,
            LocalDate.now().toEpochDay(),
        )
        settings.stepState = reading.state
        sensorSteps.value = reading.steps
    }

    /** Start reading WatchLink; the activity calls this from `onStart`. */
    fun startHealth() {
        healthRepository.start()
        phoneData.start()
    }

    /** Stop reading WatchLink (unregister the observer); the activity calls this from `onStop`. */
    fun stopHealth() {
        healthRepository.stop()
        phoneData.stop()
    }

    /** Start observing the exercise app's running workout; the activity calls this from `onStart`. */
    fun startExerciseState() {
        exerciseState.start()
    }

    /** Stop observing the exercise app; the activity calls this from `onStop`. */
    fun stopExerciseState() {
        exerciseState.stop()
    }

    /**
     * The face's running indicator: open the exercise app, which resolves to its live screen while a workout is
     * recording (or paused) and to its list otherwise. Best effort.
     */
    fun openExercise() {
        runCatching {
            context.startActivity(
                Intent().setClassName("org.circa.exercise", "org.circa.exercise.MainActivity")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    /** A face complication tap: open one of Circa Companion's activities (Weather, Agenda). Best effort. */
    fun openCompanion(activity: String) {
        runCatching {
            context.startActivity(
                Intent().setClassName("org.circa.companion", "org.circa.companion.$activity")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    /**
     * Demo/diagnostic hook behind the DUMP-protected `DEMO_HEALTH` broadcast (see MainActivity). While
     * active these values override WatchLink's, so a screenshot can show data on an emulator with no
     * health sensors. Sending `--ei hr -1 --ei steps -1` (no history) clears the override and lets the
     * repository show through again.
     */
    fun setDemoHealth(heartRate: Int?, steps: Int?, history: List<Int>) {
        demoHealth.value =
            if (heartRate == null && steps == null && history.isEmpty()) {
                null
            } else {
                HealthData(
                    hrBpm = heartRate,
                    hrTimeMs = null,
                    stepsToday = steps,
                    // The demo hook carries no timestamps; the tile's sparkline draws bpm only.
                    history = history.map { HrSample(timeMs = 0L, bpm = it) },
                )
            }
    }

    /** Open the app list, stock's "recents" page. */
    fun openRecents() {
        closeTray()
        refreshRecentApps()
        screen.value = Screen.RECENTS
    }

    /**
     * The Circa side button (POWER short press, screen on, unlocked): PhoneWindowManager's explicit
     * `org.circa.intent.action.SHOW_RECENTS`. Opens Recents from anywhere, and goes back to the face
     * when Recents is already showing (the framework re-sends the intent, the launcher decides;
     * launcher/README.md).
     */
    fun showRecents() {
        if (CircaButtons.recentsIntentTarget(screen.value) == Screen.HOME) {
            showFace()
        } else {
            requireUnlock { openRecents() }
        }
    }

    /** Open the full app list, stock's "All apps" grid (here: the same pills, sorted by label). */
    fun openAllApps() {
        closeTray()
        screen.value = Screen.ALL_APPS
    }

    /** Back to home, always on the face (stock: crown press / HOME land on page 0). */
    fun showFace() {
        closeTray()
        page.value = CarouselPage.FACE
        screen.value = Screen.HOME
    }

    /**
     * Crown press: home -> app list, any app-list screen -> face (stock behaviour #2). Locked: the
     * PIN bouncer, and the face after the unlock ([CircaButtons.crownAction]).
     */
    fun toggle() {
        when (CircaButtons.crownAction(locked.value, trayOpen.value, screen.value)) {
            CircaButtons.CrownAction.UNLOCK_THEN_FACE -> requireUnlock { showFace() }
            CircaButtons.CrownAction.CLOSE_TRAY -> closeTray()
            CircaButtons.CrownAction.OPEN_RECENTS -> openRecents()
            CircaButtons.CrownAction.SHOW_FACE -> showFace()
        }
    }

    /**
     * The crown press when the *platform* is the one that saw it: PhoneWindowManager's stem-key short
     * press starts this activity by component whenever the launcher is not the app on screen
     * (`config_shortPressOnStemPrimaryBehavior = 2` +
     * `config_primaryShortPressTargetActivity = org.circa.launcher/.MainActivity`,
     * launcher/README.md), and the activity reports whether the launcher was in front when the
     * intent arrived.
     *
     * In front: the same toggle as the key event the launcher normally answers itself (face -> app
     * list, app list -> face). Not in front - the crown was pressed inside another app - stock goes
     * back to the watch face, not into the app list, so that is what this does; the device is then
     * brought to the front on the face. Locked, it asks for the PIN first, like every other gesture
     * on the locked face.
     */
    fun crownPress(launcherInFront: Boolean) {
        if (launcherInFront) {
            toggle()
        } else if (locked.value) {
            requireUnlock { showFace() }
        } else {
            showFace()
        }
    }

    // ---- the tray -----------------------------------------------------------------------------

    /**
     * Open the tray over the face at [end]. The quick-settings state is read fresh here: a toggle
     * can have been changed from outside the launcher while the tray was closed.
     */
    fun openTray(end: TrayEnd) {
        // Circa: SystemUI's Circa shade is the only tray (launcher/README.md).
        // The prototype tray below is only for builds without it. A swipe up on the face opens
        // nothing there: notifications open only from the side button (STEM_PRIMARY), decisions.md
        // "Notifications only via the side button (2026-10-04)".
        if (circaShadePresent && end == TrayEnd.NOTIFICATIONS) return
        if (openSystemTray(end)) return
        refreshQuickSettings()
        trayEnd.value = end
        trayOpen.value = true
    }

    /**
     * Opens the Circa shade's page through StatusBarManager (EXPAND_STATUS_BAR; the hidden
     * expandSettingsPanel / expandNotificationsPanel, reachable because the launcher is platform
     * signed). False when there is no Circa shade (SystemUI's `config_circaWearShade` is off and the
     * `circa_wear_shade` test switch is unset) or the call fails.
     */
    private fun openSystemTray(end: TrayEnd): Boolean {
        if (!circaShadePresent) return false
        return runCatching {
            val sbm = context.getSystemService(Context.STATUS_BAR_SERVICE) ?: return false
            val method = if (end == TrayEnd.QUICK_SETTINGS) "expandSettingsPanel" else "expandNotificationsPanel"
            sbm.javaClass.getMethod(method).invoke(sbm)
            true
        }.getOrDefault(false)
    }

    private val circaShadePresent: Boolean by lazy {
        Settings.Global.getInt(context.contentResolver, "circa_wear_shade", 0) != 0 ||
            runCatching {
                val res = context.packageManager.getResourcesForApplication("com.android.systemui")
                val id = res.getIdentifier("config_circaWearShade", "bool", "com.android.systemui")
                id != 0 && res.getBoolean(id)
            }.getOrDefault(false)
    }

    fun closeTray() {
        trayOpen.value = false
    }

    fun refreshQuickSettings() {
        qsState.value = quickSettings.read()
    }

    fun toggleDnd() {
        quickSettings.setDnd(qsState.value.dnd != ToggleState.ON)
        refreshQuickSettings()
    }

    fun toggleBluetooth() {
        quickSettings.setBluetooth(qsState.value.bluetooth != ToggleState.ON)
        refreshQuickSettings()
    }

    fun toggleWifi() {
        quickSettings.setWifi(qsState.value.wifi != ToggleState.ON)
        refreshQuickSettings()
    }

    fun toggleBatterySaver() {
        quickSettings.setBatterySaver(qsState.value.batterySaver != ToggleState.ON)
        refreshQuickSettings()
    }

    fun cycleBrightness() {
        quickSettings.cycleBrightness()
        refreshQuickSettings()
    }

    /**
     * The tray's Settings tile: `Settings.ACTION_SETTINGS`, which Circa Settings (org.circa.settings)
     * answers on Circa (AOSP Settings where it is not installed).
     */
    fun openSettings() {
        closeTray()
        quickSettings.openSettings()
    }

    /** The phone pill: the Connectivity page (Circa Settings answers `ACTION_WIRELESS_SETTINGS`). */
    fun openConnectivitySettings() {
        closeTray()
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_WIRELESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    /** Card tap: fire the notification's content intent and close the tray (stock's behaviour). */
    fun openNotification(item: NotificationItem) {
        runCatching { NotificationStore.contentIntent(item.key)?.send() }
        closeTray()
    }

    /** Card swipe: cancel the notification for real, through the launcher's own listener. */
    fun dismissNotification(key: String) {
        LauncherNotificationListener.cancelNotification(key)
        NotificationStore.remove(key)
    }

    /**
     * Back one level: tray -> face, All apps -> Recents -> face. Returns false on the face, where
     * back must be swallowed (this is HOME, so it must never finish the activity).
     */
    fun back(): Boolean =
        when (screen.value) {
            Screen.HOME -> {
                if (locked.value) {
                    false
                } else if (trayOpen.value) {
                    closeTray()
                    true
                } else {
                    false
                }
            }
            Screen.RECENTS -> {
                showFace()
                true
            }
            Screen.ALL_APPS -> {
                screen.value = Screen.RECENTS
                true
            }
            Screen.FACE_PICKER -> {
                leavePicker()
                true
            }
        }
}
