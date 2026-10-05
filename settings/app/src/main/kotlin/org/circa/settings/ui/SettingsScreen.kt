package org.circa.settings.ui

import org.circa.symbols.CircaSymbols
import androidx.compose.foundation.background
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import kotlinx.coroutines.launch
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.ExperimentalWearFoundationApi
import androidx.wear.compose.foundation.lazy.TransformingLazyColumnItemScope
import androidx.wear.compose.foundation.lazy.TransformingLazyColumnScope
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.foundation.rememberSwipeToDismissBoxState
import androidx.wear.compose.foundation.rotary.RotaryScrollableDefaults
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.RadioButton
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.SwipeToDismissBox
import androidx.wear.compose.material3.SwitchButton
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.TimeText
import androidx.wear.compose.material3.lazy.TransformationSpec
import androidx.wear.compose.material3.lazy.rememberTransformationSpec
import androidx.wear.compose.material3.lazy.transformedHeight
import kotlinx.coroutines.delay
import org.circa.settings.SettingsController
import org.circa.settings.data.ToggleState
import org.circa.settings.model.Accent
import org.circa.settings.model.BatteryLabel
import org.circa.settings.model.BrightnessLevel
import org.circa.settings.model.LockTimeout
import org.circa.settings.model.AodBrightness
import org.circa.settings.model.TiltWake
import org.circa.settings.model.LockWhenTakenOffRow
import org.circa.settings.model.Ringer
import org.circa.settings.model.ScreenTimeout
import org.circa.settings.model.SettingsPage
import kotlin.math.abs
import kotlin.math.roundToInt

/** `resource-id` of a settings page root: `settings_page_<page id>` (same tags as the launcher's copy had). */
fun settingsPageTag(page: SettingsPage) = "settings_page_${page.id}"

/** `resource-id` of one row: `row_<id>`. */
fun rowTag(id: String) = "row_$id"

internal val ROW_MIN_HEIGHT = 52.dp
internal val ICON = 24.dp

/** Stock's Settings row icons: outlined, in a pale neutral lavender-grey (sampled #dbe2f9), not the accent. */
internal val ROW_ICON_TINT = Color(0xFFDBE2F9)

/**
 * Circa's round Settings (stock Wear's order), split out of the launcher (settings/README.md).
 * One page at a time; swipe-right / BACK goes up a level ([SettingsController.settingsBack]). The
 * snapshot behind every row is re-read once a second, so Bluetooth / Wi-Fi / airplane (which change
 * asynchronously) and anything changed from outside show what the platform really holds.
 */
@Composable
fun SettingsScreen(controller: SettingsController) {
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            controller.refreshSettings()
        }
    }
    val page = controller.settingsPage.value
    when (page) {
        SettingsPage.MAIN -> MainPage(controller)
        SettingsPage.CONNECTIVITY -> ConnectivityPage(controller)
        SettingsPage.DISPLAY -> DisplayPage(controller)
        SettingsPage.BRIGHTNESS -> BrightnessPage(controller)
        SettingsPage.TIMEOUT -> TimeoutPage(controller)
        SettingsPage.ACCENT -> AccentPage(controller)
        SettingsPage.AOD_BRIGHTNESS -> AodBrightnessPage(controller)
        SettingsPage.GESTURES -> GesturesPage(controller)
        SettingsPage.TILT_WAKE -> TiltWakePage(controller)
        SettingsPage.SOUND -> SoundPage(controller)
        SettingsPage.APPS -> AppsPage(controller)
        SettingsPage.SECURITY -> SecurityPage(controller)
        SettingsPage.LOCK_TIMEOUT -> LockTimeoutPage(controller)
        SettingsPage.BATTERY -> BatteryPage(controller)
        SettingsPage.BATTERY_USAGE -> BatteryUsagePage(controller)
        SettingsPage.DEFAULT_APPS -> DefaultAppsPage(controller)
        SettingsPage.NOT_ON_WATCH -> NotOnWatchPage(controller)
        SettingsPage.SYSTEM -> SystemPage(controller)
        SettingsPage.ABOUT -> AboutPage(controller)
        SettingsPage.LOCATION -> LocationPage(controller)
        SettingsPage.WIFI -> WifiPage(controller)
        SettingsPage.WIFI_NETWORK -> WifiNetworkPage(controller)
        SettingsPage.WIFI_JOIN -> WifiJoinPage(controller)
        SettingsPage.WIFI_SAVED -> WifiSavedPage(controller)
        SettingsPage.WIFI_ADD -> WifiAddPage(controller)
        SettingsPage.STORAGE -> StoragePage(controller)
        SettingsPage.BLUETOOTH -> BluetoothPage(controller)
        SettingsPage.BT_DEVICE -> BtDevicePage(controller)
        SettingsPage.BT_PAIR -> BtPairPage(controller)
        SettingsPage.BT_RENAME -> BtRenamePage(controller)
        SettingsPage.BT_FORGET -> BtForgetPage(controller)
        SettingsPage.LANGUAGE -> LanguagePage(controller)
        SettingsPage.ACCESSIBILITY -> AccessibilityPage(controller)
        SettingsPage.FONT_SIZE -> FontSizePage(controller)
        SettingsPage.APPS_LIST -> AppsListPage(controller)
        SettingsPage.APP_INFO -> AppInfoPage(controller)
        SettingsPage.APP_PERMS -> AppPermsPage(controller)
        SettingsPage.NOTIF_APPS -> NotifAppsPage(controller)
        SettingsPage.APP_CONFIRM -> AppConfirmPage(controller)
        SettingsPage.DATETIME -> DateTimePage(controller)
        SettingsPage.TIMEZONE -> TimeZonePage(controller)
        SettingsPage.PROFILE -> ProfilePage(controller)
        SettingsPage.PROFILE_VALUE, SettingsPage.PROFILE_MAX_HR_VALUE -> ProfileValuePage(controller, page)
        SettingsPage.PROFILE_SEX -> ProfileSexPage(controller)
        SettingsPage.PROFILE_MAX_HR -> ProfileMaxHrPage(controller)
        SettingsPage.BUTTONS -> ButtonsPage(controller)
        SettingsPage.SIDE_LONG_PRESS -> SideLongPressPage(controller)
        SettingsPage.PIN_NEW, SettingsPage.PIN_CHANGE, SettingsPage.PIN_REMOVE -> PinPage(controller)
        SettingsPage.RESTART -> ConfirmPage(controller, page, "Restart watch?", CircaSymbols.Filled.RestartAlt) {
            controller.systemSettings.reboot()
        }
        SettingsPage.POWER_OFF -> ConfirmPage(controller, page, "Power off watch?", CircaSymbols.Filled.PowerSettingsNew) {
            controller.systemSettings.shutdown()
        }
    }
}

// ---- scaffolds ---------------------------------------------------------------------------------

/** Swipe-right dismiss + curved clock + scroll indicator around [content], keyed by page. */
@Composable
internal fun PageFrame(
    controller: SettingsController,
    page: SettingsPage,
    showClock: Boolean = true,
    content: @Composable () -> Unit,
) {
    key(page) {
        // Swipe right = one level up. Hand-rolled like the launcher tray: Wear M3's SwipeToDismissBox
        // dropped slow drags around the scrolling list (measured on the emulator: only flings dismissed).
        val widthPx = with(LocalDensity.current) { 200.dp.toPx() }
        val drag = remember { Animatable(0f) }
        val scope = rememberCoroutineScope()
        Box(
            Modifier
                .fillMaxSize()
                .offset { IntOffset(drag.value.roundToInt(), 0) }
                .background(Color.Black)
                .draggable(
                    orientation = Orientation.Horizontal,
                    state = rememberDraggableState { delta ->
                        scope.launch { drag.snapTo((drag.value + delta).coerceAtLeast(0f)) }
                    },
                    onDragStopped = { velocity ->
                        if (drag.value >= widthPx * 0.3f || velocity > 900f) {
                            scope.launch { drag.animateTo(widthPx, tween(150)); controller.settingsBack() }
                        } else {
                            scope.launch { drag.animateTo(0f, tween(150)) }
                        }
                    },
                ),
        ) {
            AppScaffold(timeText = { if (showClock) TimeText() }) { content() }
        }
    }
}

@OptIn(ExperimentalWearFoundationApi::class)
@Composable
internal fun ListPage(
    controller: SettingsController,
    page: SettingsPage,
    title: String = page.title,
    /** Pass a state held outside the page to keep the scroll position across page changes. */
    scrollState: androidx.wear.compose.foundation.lazy.TransformingLazyColumnState? = null,
    content: TransformingLazyColumnScope.(TransformationSpec) -> Unit,
) {
    PageFrame(controller, page) {
        val scrollState = scrollState ?: rememberTransformingLazyColumnState()
        val focusRequester = remember { FocusRequester() }
        val rotary = RotaryScrollableDefaults.snapBehavior(scrollState, hapticFeedbackEnabled = true)
        val spec = rememberTransformationSpec()
        ScreenScaffold(
            scrollState = scrollState,
            timeText = { TimeText() },
            modifier = Modifier.fillMaxSize().testTag(settingsPageTag(page)),
        ) { padding ->
            PagedPillList(
                scrollState = scrollState,
                // Stock puts the title ~14 dp higher than the scaffold's default clock clearance.
                contentPadding = PaddingValues(
                    top = (padding.calculateTopPadding() - 14.dp).coerceAtLeast(0.dp),
                    // Extra room so a last line (empty-state note) can scroll clear of the round bottom edge.
                    bottom = padding.calculateBottomPadding() + 28.dp,
                ),
                focusRequester = focusRequester,
                rotaryBehavior = rotary,
            ) {
                item(key = "header") {
                    // No SurfaceTransformation on the title: its top-edge fade dims a one-line title,
                    // stock's stays full white.
                    ListHeader(
                        modifier = Modifier.transformedHeight(this, spec),
                    ) {
                        Text(
                            title,
                            modifier = Modifier.padding(horizontal = 22.dp),
                            textAlign = TextAlign.Center,
                            maxLines = 2,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
                content(spec)
            }
        }
    }
}

// ---- row helpers -------------------------------------------------------------------------------

internal fun TransformingLazyColumnScope.navRow(
    id: String,
    spec: TransformationSpec,
    label: String,
    icon: ImageVector? = null,
    secondary: String? = null,
    secondaryLines: Int = 1,
    onClick: () -> Unit,
) = item(key = id) {
    FilledTonalButton(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = ROW_MIN_HEIGHT)
            .transformedHeight(this, spec)
            .testTag(rowTag(id)),
        transformation = SurfaceTransformation(spec),
        icon = icon?.let { v -> { Icon(v, contentDescription = null, modifier = Modifier.size(ICON), tint = ROW_ICON_TINT) } },
        secondaryLabel = secondary?.let { s -> { Text(s, maxLines = secondaryLines, overflow = TextOverflow.Ellipsis) } },
        label = { Text(label, maxLines = 2, overflow = TextOverflow.Ellipsis) },
    )
}

internal fun TransformingLazyColumnScope.toggleRow(
    id: String,
    spec: TransformationSpec,
    label: String,
    checked: Boolean,
    icon: ImageVector? = null,
    secondary: String? = null,
    enabled: Boolean = true,
    onChange: (Boolean) -> Unit,
) = item(key = id) {
    SwitchButton(
        checked = checked,
        onCheckedChange = onChange,
        enabled = enabled,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = ROW_MIN_HEIGHT)
            .transformedHeight(this, spec)
            .testTag(rowTag(id)),
        transformation = SurfaceTransformation(spec),
        icon = icon?.let { v -> { Icon(v, contentDescription = null, modifier = Modifier.size(ICON), tint = ROW_ICON_TINT.copy(alpha = if (enabled) 1f else 0.38f)) } },
        secondaryLabel = secondary?.let { s -> { Text(s, maxLines = 2, overflow = TextOverflow.Ellipsis) } },
        label = { Text(label, maxLines = 2, overflow = TextOverflow.Ellipsis) },
    )
}

internal fun TransformingLazyColumnScope.radioRow(
    id: String,
    spec: TransformationSpec,
    label: String,
    selected: Boolean,
    icon: (@Composable () -> Unit)? = null,
    secondary: String? = null,
    onSelect: () -> Unit,
) = item(key = id) {
    RadioButton(
        selected = selected,
        onSelect = onSelect,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = ROW_MIN_HEIGHT)
            .transformedHeight(this, spec)
            .testTag(rowTag(id)),
        transformation = SurfaceTransformation(spec),
        icon = icon?.let { i -> { i() } },
        secondaryLabel = secondary?.let { t -> { Text(t, maxLines = 2, overflow = TextOverflow.Ellipsis) } },
        label = { Text(label, maxLines = 2, overflow = TextOverflow.Ellipsis) },
    )
}

internal fun onOff(on: Boolean) = if (on) "On" else "Off"

internal fun ToggleState.on() = this == ToggleState.ON

// ---- pages ---------------------------------------------------------------------------------------

@Composable
private fun MainPage(c: SettingsController) = ListPage(c, SettingsPage.MAIN) { spec ->
    navRow("connectivity", spec, "Connectivity", CircaSymbols.Outlined.CellTower) { c.openSettingsPage(SettingsPage.CONNECTIVITY) }
    navRow("display", spec, "Display", CircaSymbols.Outlined.BrightnessMedium) { c.openSettingsPage(SettingsPage.DISPLAY) }
    navRow("gestures", spec, "Gestures", CircaSymbols.Outlined.TouchApp) { c.openSettingsPage(SettingsPage.GESTURES) }
    navRow("buttons", spec, "Buttons", CircaSymbols.Outlined.Tune) { c.openSettingsPage(SettingsPage.BUTTONS) }
    navRow("sound", spec, "Sound & vibration", CircaSymbols.Outlined.VolumeUp) { c.openSettingsPage(SettingsPage.SOUND) }
    navRow("apps", spec, "Apps & notifications", CircaSymbols.Outlined.Apps) { c.openSettingsPage(SettingsPage.APPS) }
    navRow("location", spec, "Location", CircaSymbols.Filled.LocationOn) { c.openSettingsPage(SettingsPage.LOCATION) }
    navRow("security", spec, "Security", CircaSymbols.Outlined.Lock) { c.openSettingsPage(SettingsPage.SECURITY) }
    navRow("profile", spec, "Profile", CircaSymbols.Outlined.Favorite) { c.openSettingsPage(SettingsPage.PROFILE) }
    navRow("battery", spec, "Battery", CircaSymbols.Outlined.BatteryAndroidFull) { c.openSettingsPage(SettingsPage.BATTERY) }
    navRow("system", spec, "System", CircaSymbols.Outlined.Watch) { c.openSettingsPage(SettingsPage.SYSTEM) }
    navRow("android_settings", spec, "Android settings", CircaSymbols.Outlined.Settings) {
        c.systemSettings.openAndroidSettings()
    }
}

@Composable
private fun ConnectivityPage(c: SettingsController) = ListPage(c, SettingsPage.CONNECTIVITY) { spec ->
    val s = c.settingsState.value
    navRow(
        "bluetooth", spec, "Bluetooth", CircaSymbols.Outlined.Bluetooth,
        secondary = if (s.qs.bluetooth == ToggleState.UNAVAILABLE) "Unavailable" else onOff(s.qs.bluetooth.on()),
    ) { c.openSettingsPage(SettingsPage.BLUETOOTH) }
    navRow("wifi", spec, "Wi-Fi", CircaSymbols.Outlined.Wifi, secondary = wifiRowSecondary(c)) {
        c.openSettingsPage(SettingsPage.WIFI)
    }
    toggleRow("airplane", spec, "Airplane mode", s.airplane, CircaSymbols.Outlined.Flight, secondary = onOff(s.airplane)) {
        c.setAirplane(it)
    }
}

@Composable
private fun DisplayPage(c: SettingsController) = ListPage(c, SettingsPage.DISPLAY) { spec ->
    val s = c.settingsState.value
    navRow(
        "brightness", spec, "Brightness", CircaSymbols.Outlined.BrightnessMedium,
        secondary = if (s.qs.autoBrightness) "Auto" else "${BrightnessLevel.percent(s.qs.brightness)}%",
    ) { c.openSettingsPage(SettingsPage.BRIGHTNESS) }
    navRow(
        "timeout", spec, "Screen timeout", CircaSymbols.Outlined.Timer, secondary = ScreenTimeout.label(s.screenTimeoutMs),
    ) { c.openSettingsPage(SettingsPage.TIMEOUT) }
    toggleRow("aod", spec, "Always-on display", s.alwaysOn, CircaSymbols.Outlined.Visibility, secondary = onOff(s.alwaysOn)) {
        c.setAlwaysOn(it)
    }
    navRow(
        "aod_brightness", spec, "Always-on brightness", CircaSymbols.Outlined.Sunny,
        secondary = s.aodBrightness.label,
    ) { c.openSettingsPage(SettingsPage.AOD_BRIGHTNESS) }
    // The face is the launcher's own preference: this row opens the launcher's picker.
    navRow("watchface", spec, "Watch face", CircaSymbols.Outlined.Wallpaper) {
        c.systemSettings.openFacePicker()
    }
    navRow("accent", spec, "Accent colour", CircaSymbols.Outlined.Palette, secondary = s.accent.label) {
        c.openSettingsPage(SettingsPage.ACCENT)
    }
}

@Composable
private fun TimeoutPage(c: SettingsController) = ListPage(c, SettingsPage.TIMEOUT) { spec ->
    val current = c.settingsState.value.screenTimeoutMs
    ScreenTimeout.OPTIONS.forEach { ms ->
        radioRow("timeout_$ms", spec, ScreenTimeout.label(ms), current == ms) { c.setScreenTimeout(ms) }
    }
}

@Composable
private fun AodBrightnessPage(c: SettingsController) = ListPage(c, SettingsPage.AOD_BRIGHTNESS) { spec ->
    val current = c.settingsState.value.aodBrightness
    AodBrightness.entries.forEach { level ->
        radioRow("aod_brightness_${level.value}", spec, level.label, current == level, secondary = level.hint) {
            c.setAodBrightness(level)
        }
    }
}

@Composable
private fun AccentPage(c: SettingsController) = ListPage(c, SettingsPage.ACCENT) { spec ->
    Accent.entries.forEach { a ->
        radioRow(
            "accent_${a.id}", spec, a.label, c.settingsState.value.accent == a,
            icon = {
                Box(Modifier.size(ICON).clip(CircleShape).background(Color(a.argb)))
            },
        ) { c.setAccent(a) }
    }
}

@Composable
private fun GesturesPage(c: SettingsController) = ListPage(c, SettingsPage.GESTURES) { spec ->
    val rows = c.settingsState.value.gestures
    rows.forEach { row ->
        toggleRow("gesture_${row.gesture.id}", spec, row.gesture.label, row.on, secondary = onOff(row.on)) {
            c.setGesture(row, it)
        }
    }
    navRow(
        "tilt_wake", spec, "Tilt-to-wake",
        secondary = c.settingsState.value.tiltWake.label,
    ) { c.openSettingsPage(SettingsPage.TILT_WAKE) }
}

@Composable
private fun TiltWakePage(c: SettingsController) = ListPage(c, SettingsPage.TILT_WAKE) { spec ->
    val current = c.settingsState.value.tiltWake
    TiltWake.entries.forEach { level ->
        radioRow("tilt_wake_${level.value}", spec, level.label, current == level, secondary = level.hint) {
            c.setTiltWake(level)
        }
    }
}

@Composable
private fun SoundPage(c: SettingsController) = ListPage(c, SettingsPage.SOUND) { spec ->
    val s = c.settingsState.value
    Ringer.entries.forEach { r ->
        radioRow("ringer_${r.name.lowercase()}", spec, r.label, s.ringer == r) { c.setRinger(r) }
    }
    toggleRow("touch_vibration", spec, "Touch vibration", s.touchVibration, CircaSymbols.Outlined.Vibration) {
        c.setTouchVibration(it)
    }
}

@Composable
private fun AppsPage(c: SettingsController) = ListPage(c, SettingsPage.APPS) { spec ->
    navRow("apps_list", spec, "Apps", CircaSymbols.Outlined.Apps) { c.openSettingsPage(SettingsPage.APPS_LIST) }
    navRow("apps_notifications", spec, "Notifications", CircaSymbols.Outlined.Notifications) {
        c.openSettingsPage(SettingsPage.NOTIF_APPS)
    }
    navRow("apps_default", spec, "Default apps", CircaSymbols.Outlined.Apps) { c.openSettingsPage(SettingsPage.DEFAULT_APPS) }
}

@Composable
private fun SecurityPage(c: SettingsController) = ListPage(c, SettingsPage.SECURITY) { spec ->
    val s = c.settingsState.value
    // The PIN is entered on Circa's own keypad (ui/PinPages.kt), not AOSP's ChooseLockPassword form.
    if (!s.deviceSecure) {
        navRow("screen_lock", spec, "Set PIN", CircaSymbols.Outlined.Pin, secondary = "Screen lock: none") {
            c.openSettingsPage(SettingsPage.PIN_NEW)
        }
    } else {
        navRow("screen_lock", spec, "Change PIN", CircaSymbols.Outlined.Pin, secondary = "PIN set") {
            c.openSettingsPage(SettingsPage.PIN_CHANGE)
        }
        navRow("remove_pin", spec, "Remove PIN", CircaSymbols.Outlined.Lock) {
            c.openSettingsPage(SettingsPage.PIN_REMOVE)
        }
    }
    navRow(
        "lock_after", spec, "Lock after", CircaSymbols.Outlined.LockClock, secondary = LockTimeout.label(s.lockAfterMs),
    ) { c.openSettingsPage(SettingsPage.LOCK_TIMEOUT) }
    val on = s.lockWhenTakenOff
    toggleRow(
        "lock_when_taken_off", spec, "Off-wrist lock",
        LockWhenTakenOffRow.checked(s.deviceSecure, on), CircaSymbols.Outlined.Lock,
        secondary = LockWhenTakenOffRow.secondary(s.deviceSecure, on),
        enabled = LockWhenTakenOffRow.enabled(s.deviceSecure),
    ) { c.setLockWhenTakenOff(it) }
}

@Composable
private fun LockTimeoutPage(c: SettingsController) = ListPage(c, SettingsPage.LOCK_TIMEOUT) { spec ->
    val current = c.settingsState.value.lockAfterMs
    LockTimeout.OPTIONS.forEach { ms ->
        radioRow("lock_after_$ms", spec, LockTimeout.label(ms), current == ms) { c.setLockAfter(ms) }
    }
}

@Composable
private fun BatteryPage(c: SettingsController) = ListPage(c, SettingsPage.BATTERY) { spec ->
    val s = c.settingsState.value
    navRow("battery_level", spec, "Battery level", CircaSymbols.Outlined.BatteryAndroidFull, secondary = BatteryLabel.level(s.batteryPercent)) {}
    toggleRow(
        "battery_saver", spec, "Battery saver", s.qs.batterySaver.on(), CircaSymbols.Outlined.BatterySaver,
        secondary = if (s.qs.batterySaver == ToggleState.UNAVAILABLE) "Unavailable" else onOff(s.qs.batterySaver.on()),
        enabled = s.qs.batterySaver != ToggleState.UNAVAILABLE,
    ) { c.setBatterySaverTo(it) }
    navRow("battery_usage", spec, "Battery usage", CircaSymbols.Outlined.Tune) {
        c.openSettingsPage(SettingsPage.BATTERY_USAGE)
    }
}

@Composable
private fun SystemPage(c: SettingsController) = ListPage(c, SettingsPage.SYSTEM) { spec ->
    navRow("datetime", spec, "Date & time", CircaSymbols.Outlined.Schedule) { c.openSettingsPage(SettingsPage.DATETIME) }
    navRow("language", spec, "Language", CircaSymbols.Outlined.Language, secondary = currentLanguageLabel(), secondaryLines = 2) {
        c.openSettingsPage(SettingsPage.LANGUAGE)
    }
    navRow("accessibility", spec, "Accessibility", CircaSymbols.Outlined.AccessibilityNew) {
        c.openSettingsPage(SettingsPage.ACCESSIBILITY)
    }
    navRow("storage", spec, "Storage", CircaSymbols.Outlined.Storage) { c.openSettingsPage(SettingsPage.STORAGE) }
    navRow("about", spec, "About", CircaSymbols.Outlined.Info) { c.openSettingsPage(SettingsPage.ABOUT) }
    navRow("restart", spec, "Restart", CircaSymbols.Outlined.RestartAlt) { c.openSettingsPage(SettingsPage.RESTART) }
    navRow("power_off", spec, "Power off", CircaSymbols.Outlined.PowerSettingsNew) { c.openSettingsPage(SettingsPage.POWER_OFF) }
}

@Composable
private fun AboutPage(c: SettingsController) {
    val about = remember { c.systemSettings.about() }
    ListPage(c, SettingsPage.ABOUT) { spec ->
    navRow("about_android", spec, "Android", secondary = about.androidVersion) {}
    about.lineageVersion?.let { navRow("about_lineage", spec, "LineageOS", secondary = it) {} }
    navRow("about_patch", spec, "Security patch", secondary = about.securityPatch) {}
    navRow("about_settings", spec, "Circa Settings", secondary = about.settingsVersion) {}
    about.launcherVersion?.let { navRow("about_launcher", spec, "Aurora launcher", secondary = it) {} }
    about.watchLinkVersion?.let { navRow("about_watchlink", spec, "WatchLink", secondary = it) {} }
    }
}

// ---- brightness level control ------------------------------------------------------------------------

@Composable
private fun BrightnessPage(c: SettingsController) {
    PageFrame(c, SettingsPage.BRIGHTNESS) {
        val level = c.settingsState.value.qs.brightness
        val focus = remember { FocusRequester() }
        LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .testTag(settingsPageTag(SettingsPage.BRIGHTNESS))
                .onRotaryScrollEvent { e ->
                    val px = e.verticalScrollPixels
                    val clicks = (abs(px) / 40f).roundToInt().coerceAtLeast(1) * (if (px >= 0) 1 else -1)
                    c.setBrightness(BrightnessLevel.adjust(c.settingsState.value.qs.brightness, clicks))
                    true
                }
                .focusRequester(focus)
                .focusable(),
        ) {
            Column(
                modifier = Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(CircaSymbols.Filled.BrightnessMedium, contentDescription = null, modifier = Modifier.size(28.dp), tint = MaterialTheme.colorScheme.primary)
                Text(
                    "${BrightnessLevel.percent(level)}%",
                    style = MaterialTheme.typography.displaySmall,
                    modifier = Modifier.testTag("brightness_value"),
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    LevelButton("brightness_down", CircaSymbols.Filled.Remove) { c.setBrightness(BrightnessLevel.adjust(level, -1)) }
                    Box(
                        Modifier.size(width = 40.dp, height = 8.dp).clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceContainer),
                    ) {
                        Box(
                            Modifier
                                .fillMaxHeight()
                                .fillMaxWidth((BrightnessLevel.percent(level) / 100f).coerceIn(0.05f, 1f))
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary),
                        )
                    }
                    LevelButton("brightness_up", CircaSymbols.Filled.Add) { c.setBrightness(BrightnessLevel.adjust(level, 1)) }
                }
            }
        }
    }
}

@Composable
private fun LevelButton(tag: String, icon: ImageVector, onClick: () -> Unit) {
    FilledTonalButton(
        onClick = onClick,
        modifier = Modifier.size(46.dp).testTag(tag),
        contentPadding = PaddingValues(0.dp),
        label = {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(30.dp))
            }
        },
    )
}

// ---- confirmation (restart / power off) ----------------------------------------------------------------

@Composable
internal fun ConfirmPage(
    c: SettingsController,
    page: SettingsPage,
    question: String,
    icon: ImageVector,
    onYes: () -> Unit,
) {
    PageFrame(c, page) {
        Box(Modifier.fillMaxSize().testTag(settingsPageTag(page))) {
            Column(
                modifier = Modifier.align(Alignment.Center).padding(horizontal = 36.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(32.dp), tint = MaterialTheme.colorScheme.primary)
                Text(question, textAlign = TextAlign.Center, style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(
                        onClick = { c.settingsBack() },
                        modifier = Modifier.size(52.dp).testTag("confirm_no"),
                        colors = ButtonDefaults.filledTonalButtonColors(),
                        contentPadding = PaddingValues(0.dp),
                        shape = CircleShape,
                        label = {
                            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                Icon(CircaSymbols.Filled.Close, contentDescription = "No", modifier = Modifier.size(ICON))
                            }
                        },
                    )
                    Button(
                        onClick = onYes,
                        modifier = Modifier.size(52.dp).testTag("confirm_yes"),
                        contentPadding = PaddingValues(0.dp),
                        shape = CircleShape,
                        label = {
                            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                Icon(CircaSymbols.Filled.Check, contentDescription = "Yes", modifier = Modifier.size(ICON))
                            }
                        },
                    )
                }
            }
        }
    }
}
