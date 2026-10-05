package org.circa.launcher.ui

import org.circa.symbols.CircaSymbols
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.runtime.withFrameNanos
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.ExperimentalWearFoundationApi
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.TransformingLazyColumnState
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.foundation.requestFocusOnHierarchyActive
import androidx.wear.compose.foundation.rotary.RotaryScrollableBehavior
import androidx.wear.compose.foundation.rotary.rotaryScrollable
import androidx.wear.compose.material3.AppCard
import androidx.wear.compose.material3.CardDefaults
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.lazy.rememberTransformationSpec
import androidx.wear.compose.material3.lazy.transformedHeight
import kotlin.math.abs
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.circa.launcher.LauncherController
import org.circa.launcher.TrayEnd
import org.circa.launcher.data.NotificationStore
import org.circa.launcher.data.ToggleState
import org.circa.launcher.model.BrightnessCycle
import org.circa.launcher.model.ComplicationFormat
import org.circa.launcher.model.Gauge
import org.circa.launcher.model.NotificationItem
import org.circa.launcher.model.NotificationTime
import org.circa.launcher.model.PhoneStatus
import org.circa.launcher.model.Density as DensityUtil

/** testTag of the tray root, of the quick-settings panel, of the empty stream and of every card. */
const val TRAY_TAG = "tray"
const val QUICK_SETTINGS_TAG = "quick_settings"
const val NOTIFICATIONS_TAG = "notifications"
const val NOTIFICATION_CARD_TAG = "notif_card"

/** testTag of each quick-settings tile (also the handle the smoke test finds buttons by). */
const val QS_DND_TAG = "qs_dnd"
const val QS_BLUETOOTH_TAG = "qs_bluetooth"
const val QS_WIFI_TAG = "qs_wifi"
const val QS_BATTERY_TAG = "qs_battery"
const val QS_PHONE_TAG = "qs_phone"
const val QS_BRIGHTNESS_TAG = "qs_brightness"
const val QS_SETTINGS_TAG = "qs_settings"

/** Stock's quick-settings buttons are ~53 dp circles with ~5 dp between them. */
private val TILE_SIZE = 52.dp
private val TILE_GAP = 5.dp
private val TILE_ICON = 26.dp

/** The panel is 36 dp taller than before (room for the pill to scroll clear) and Wear centres it: move the content down by half. */
private val QS_SHIFT = 18.dp

/** Top of the 3 + 3 grid; the phone pill sits right under it, inside the circle's bottom chord. */
private val GRID_TOP = 52.dp + QS_SHIFT

/** Fraction of the width a horizontal drag must cover to dismiss the tray / a card. */
private const val DISMISS_FRACTION = 0.25f
private const val CARD_DISMISS_FRACTION = 0.35f

/** Snap animation for a drag that does not dismiss. */
private const val DRAG_SETTLE_MILLIS = 180

/** The tray's lazy items: the quick-settings panel, then the stream (a leading spacer, cards). */
private const val QUICK_SETTINGS_ITEM = 0
private const val NOTIFICATIONS_ITEM = 1

/** Where the first card's top sits when the tray opens at the notifications end. */
private val STREAM_TOP = 40.dp

/** Space below the last card: lets it scroll clear of the narrow bottom bezel. */
private val STREAM_BOTTOM_SPACE = 30.dp
private val STREAM_SHORT_BOTTOM_SPACE = 120.dp

/**
 * The tray: one vertically scrolling column over the face, quick settings at the top and the
 * notification stream below them (stock Wear's model).
 *
 * Entered by swiping down on the face for the quick-settings end and up for the notifications end
 * (the direction decides where it opens, `LauncherController.openTray`). The crown scrolls it and
 * scrolling past the quick-settings top dismisses it ([TrayRotaryBehavior]); a right-swipe or BACK
 * dismisses it as well (the same gesture as every other screen).
 *
 * It is a `TransformingLazyColumn`, so every notification card narrows and fades with the circle
 * toward the top and bottom bezel (stock's behaviour) instead of running under it, and the
 * `ScreenScaffold` draws the curved scroll indicator. The quick-settings panel is one screen tall,
 * so while the tray sits at that end no card is composed: off-screen text would otherwise land in
 * the `uiautomator dump` the smoke test's round-panel check reads.
 */
@OptIn(ExperimentalWearFoundationApi::class)
@Composable
fun TrayScreen(controller: LauncherController) {
    val startItem = if (controller.trayEnd.value == TrayEnd.NOTIFICATIONS) {
        NOTIFICATIONS_ITEM
    } else {
        QUICK_SETTINGS_ITEM
    }
    val listState = rememberTransformingLazyColumnState(initialAnchorItemIndex = startItem, initialAnchorItemScrollOffset = 0)
    val focusRequester = remember { FocusRequester() }
    val onScrollPastTop by rememberUpdatedState { controller.closeTray() }
    val behavior = remember(listState) { TrayRotaryBehavior(listState) { onScrollPastTop() } }
    val spec = rememberTransformationSpec()

    val trayWidthPx = with(LocalDensity.current) { DensityUtil.TARGET_SCREEN_WIDTH_DP.dp.toPx() }
    val dragOffset = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val items = NotificationStore.items.toList()
    val now = rememberNotificationClock()

    // Wear's list centres its anchor item, so a tray opened at the notifications end first lands
    // with the first card mid-screen; scroll it up under the top bezel, hidden until it is there.
    val density = LocalDensity.current
    var positioned by remember { mutableStateOf(startItem == QUICK_SETTINGS_ITEM) }
    LaunchedEffect(Unit) {
        if (positioned) return@LaunchedEffect
        val target = with(density) { STREAM_TOP.toPx() }
        fun streamTop() = listState.layoutInfo.visibleItems
            .firstOrNull { it.index == NOTIFICATIONS_ITEM }?.offset?.toFloat()
        snapshotFlow { streamTop() }.filterNotNull().first()
        // Each scroll re-measures the list (and Wear re-centres its anchor), so correct until the
        // card sits where it should; a handful of passes is plenty.
        repeat(8) {
            withFrameNanos { }
            val gap = (streamTop() ?: return@repeat) - target
            if (abs(gap) < 2f) return@repeat
            listState.scrollBy(gap)
        }
        positioned = true
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .offset { IntOffset(dragOffset.value.roundToInt(), 0) }
            .background(Color.Black)
            .alpha(if (positioned) 1f else 0f)
            .testTag(TRAY_TAG)
            // Swipe right dismisses the tray, the same back gesture every other screen takes (and
            // the same as BACK). Hand-rolled rather than Wear M3's SwipeToDismissBox: that one does
            // not pick the gesture up around a scrollable here (measured), and this is the pattern
            // the carousel already uses.
            .draggable(
                orientation = Orientation.Horizontal,
                state = rememberDraggableState { delta ->
                    scope.launch { dragOffset.snapTo((dragOffset.value + delta).coerceAtLeast(0f)) }
                },
                onDragStopped = {
                    if (dragOffset.value >= trayWidthPx * DISMISS_FRACTION) {
                        scope.launch {
                            dragOffset.animateTo(trayWidthPx, tween(DRAG_SETTLE_MILLIS))
                            controller.closeTray()
                        }
                    } else {
                        scope.launch { dragOffset.animateTo(0f, tween(DRAG_SETTLE_MILLIS)) }
                    }
                },
            )
            .requestFocusOnHierarchyActive()
            .rotaryScrollable(behavior = behavior, focusRequester = focusRequester),
    ) {
        val screenHeight = maxHeight
        ScreenScaffold(
            scrollState = listState,
            modifier = Modifier.fillMaxSize(),
            scrollIndicator = {},
        ) { _ ->
            TransformingLazyColumn(
                state = listState,
                contentPadding = PaddingValues(horizontal = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                rotaryScrollableBehavior = null,
                modifier = Modifier.fillMaxSize(),
            ) {
                item(key = "quick_settings") { QuickSettingsPanel(controller, screenHeight) }
                if (items.isEmpty()) {
                    item(key = "stream_empty") {
                        Box(
                            Modifier.fillMaxWidth().height(60.dp).testTag(NOTIFICATIONS_TAG),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = "No notifications",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 14.sp,
                            )
                        }
                    }
                } else {
                    items(count = items.size, key = { items[it].key }) { index ->
                        NotificationCard(
                            controller = controller,
                            item = items[index],
                            nowMillis = now,
                            modifier = Modifier
                                .fillMaxWidth()
                                .transformedHeight(this, spec),
                            transformation = SurfaceTransformation(spec),
                        )
                    }
                }
                // With one card (or none) the list would be too short for that card to reach the
                // top of the screen, so the tray could not open on it; pad the end to allow it.
                item(key = "stream_bottom") {
                    Box(Modifier.height(if (items.size <= 1) STREAM_SHORT_BOTTOM_SPACE else STREAM_BOTTOM_SPACE))
                }
            }
        }
        CurvedScrollIndicator(listState)
    }
}

/**
 * The crown, for the tray. The Wear default behaviour cannot express "scrolling past the top
 * dismisses", so this one scrolls the same list state the touch path scrolls and, when the list is
 * already at the quick-settings top and the crown keeps turning that way, dismisses the tray.
 */
@OptIn(ExperimentalWearFoundationApi::class)
private class TrayRotaryBehavior(
    private val listState: TransformingLazyColumnState,
    private val onScrollPastTop: () -> Unit,
) : RotaryScrollableBehavior {

    override suspend fun CoroutineScope.performScroll(
        timestampMillis: Long,
        delta: Float,
        inputDeviceId: Int,
        orientation: Orientation,
    ) {
        // delta is in pixels, positive scrolling forward (toward the notifications end).
        if (delta < 0f && !listState.canScrollBackward) {
            onScrollPastTop()
            return
        }
        listState.scrollBy(delta)
    }
}

// ---- quick settings -----------------------------------------------------------------------------

/**
 * Stock's quick-settings grid, fixed: a small status row on top, then 3 + 3 round buttons that all
 * sit inside the circle - DND, Bluetooth, Wi-Fi; battery (same size, % inside, tap = battery saver),
 * brightness (a level control: a ring that shows the level, not an on/off fill), Settings.
 */
@Composable
private fun QuickSettingsPanel(controller: LauncherController, height: androidx.compose.ui.unit.Dp) {
    val state = controller.qsState.value
    Box(
        modifier = Modifier
            .fillMaxWidth()
            // Taller than the screen: when the tray opens at the notifications end the panel's bottom
            // edge sits at the first card's top (40 dp), and the phone pill (bottom at ~188 dp of the
            // panel) must be clear of the top of the circle there, not peeking above the cards.
            .height(height + 48.dp)
            .testTag(QUICK_SETTINGS_TAG),
    ) {
        Row(
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 22.dp + QS_SHIFT),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            StatusPip(CircaSymbols.Filled.Flight, "Airplane mode", state.airplane)
            StatusPip(CircaSymbols.Filled.LocationOn, "Location", state.location)
            StatusPip(CircaSymbols.Filled.BrightnessAuto, "Auto brightness", state.autoBrightness)
        }
        Column(
            modifier = Modifier.align(Alignment.TopCenter).padding(top = GRID_TOP),
            verticalArrangement = Arrangement.spacedBy(TILE_GAP),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(TILE_GAP)) {
                QsButton(
                    tag = QS_DND_TAG,
                    icon = CircaSymbols.Filled.DoNotDisturbOn,
                    label = "Do Not Disturb",
                    style = toggleStyle(state.dnd),
                    enabled = state.dnd != ToggleState.UNAVAILABLE,
                    onClick = { controller.toggleDnd() },
                )
                QsButton(
                    tag = QS_BLUETOOTH_TAG,
                    icon = CircaSymbols.Filled.Bluetooth,
                    label = "Bluetooth",
                    style = toggleStyle(state.bluetooth),
                    enabled = state.bluetooth != ToggleState.UNAVAILABLE,
                    onClick = { controller.toggleBluetooth() },
                )
                QsButton(
                    tag = QS_WIFI_TAG,
                    icon = CircaSymbols.Filled.Wifi,
                    label = "Wi-Fi",
                    style = toggleStyle(state.wifi),
                    enabled = state.wifi != ToggleState.UNAVAILABLE,
                    onClick = { controller.toggleWifi() },
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(TILE_GAP)) {
                BatteryButton(
                    percent = controller.batteryPercent.value,
                    saver = state.batterySaver,
                    onClick = { controller.toggleBatterySaver() },
                )
                BrightnessButton(level = state.brightness, onClick = { controller.cycleBrightness() })
                QsButton(
                    tag = QS_SETTINGS_TAG,
                    icon = CircaSymbols.Filled.Settings,
                    label = "Settings",
                    style = TileStyle.INACTIVE,
                    onClick = { controller.openSettings() },
                )
            }
        }
        PhonePill(
            connected = state.phoneConnected,
            modifier = Modifier.align(Alignment.TopCenter).padding(top = GRID_TOP + TILE_SIZE * 2 + TILE_GAP + 4.dp),
            onClick = { controller.openConnectivitySettings() },
        )
    }
}

/** Stock's pill under the grid: the phone connection (WatchLink's GATT-server link), read-only; a tap opens Connectivity. */
@Composable
private fun PhonePill(connected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val label = PhoneStatus.label(connected)
    Row(
        modifier = modifier
            .height(24.dp)
            .clip(CircleShape)
            .background(scheme.surfaceContainer)
            .clickable(role = Role.Button, onClickLabel = "Connectivity", onClick = onClick)
            .padding(horizontal = 9.dp)
            .semantics { contentDescription = label }
            .testTag(QS_PHONE_TAG),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Icon(
            imageVector = if (connected) CircaSymbols.Outlined.Smartphone else CircaSymbols.Outlined.MobileOff,
            contentDescription = null,
            tint = if (connected) scheme.onSurface else scheme.outline,
            modifier = Modifier.size(15.dp),
        )
        Text(
            // The full "Phone connected" / "Phone disconnected" is the content description; the longer
            // text does not fit the circle's bottom chord at a readable size.
            text = PhoneStatus.short(connected),
            color = if (connected) scheme.onSurface else scheme.outline,
            fontSize = 10.sp,
            maxLines = 1,
        )
    }
}

/** A small read-only status pill of the top row: lit in the accent while that state is on. */
@Composable
private fun StatusPip(icon: ImageVector, label: String, on: Boolean) {
    val scheme = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .size(width = 30.dp, height = 20.dp)
            .clip(CircleShape)
            .background(if (on) scheme.primaryContainer else scheme.surfaceContainerLow)
            .semantics { contentDescription = if (on) "$label on" else "$label off" },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (on) scheme.primary else scheme.outline,
            modifier = Modifier.size(13.dp),
        )
    }
}

/** One round tile: accent-filled when on, dark when off, dimmed when the platform won't do it. */
@Composable
private fun QsButton(
    tag: String,
    icon: ImageVector,
    label: String,
    style: TileStyle,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .size(TILE_SIZE)
            .clip(CircleShape)
            .background(style.background(scheme))
            .clickable(enabled = enabled, role = Role.Button, onClickLabel = label, onClick = onClick)
            .testTag(tag),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = style.tint(scheme),
            modifier = Modifier.size(TILE_ICON),
        )
    }
}

/**
 * The battery tile: same size as every other, the charge as text under the icon (stock draws the
 * percentage there too). Tapping toggles battery saver; while it is on the tile lights up with
 * the saver icon.
 */
@Composable
private fun BatteryButton(percent: Int?, saver: ToggleState, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val style = toggleStyle(saver)
    val label = ComplicationFormat.formatBattery(percent)
    Box(
        modifier = Modifier
            .size(TILE_SIZE)
            .clip(CircleShape)
            .background(style.background(scheme))
            .clickable(
                enabled = saver != ToggleState.UNAVAILABLE,
                role = Role.Button,
                onClickLabel = "Battery saver",
                onClick = onClick,
            )
            .testTag(QS_BATTERY_TAG),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = if (saver == ToggleState.ON) CircaSymbols.Filled.BatterySaver else CircaSymbols.Filled.BatteryAndroidFull,
                contentDescription = "Battery",
                tint = style.tint(scheme),
                modifier = Modifier.size(22.dp),
            )
            Text(
                text = label ?: " ",
                color = style.tint(scheme),
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
        }
    }
}

/**
 * Brightness is a level, not a switch: the tile stays dark and a ring around its rim shows the
 * current step (a third / two thirds / full of the 0-255 range); a tap steps to the next level.
 */
@Composable
private fun BrightnessButton(level: Int, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .size(TILE_SIZE)
            .clip(CircleShape)
            .background(scheme.surfaceContainer)
            .clickable(role = Role.Button, onClickLabel = "Brightness", onClick = onClick)
            .testTag(QS_BRIGHTNESS_TAG),
        contentAlignment = Alignment.Center,
    ) {
        GaugeRing(
            progress = Gauge.fraction(level, BrightnessCycle.MAX),
            stroke = 3.dp,
            modifier = Modifier.size(TILE_SIZE),
        )
        Icon(
            imageVector = CircaSymbols.Filled.BrightnessMedium,
            contentDescription = "Brightness",
            tint = scheme.onSurface,
            modifier = Modifier.size(TILE_ICON),
        )
    }
}

/** How a toggle's [ToggleState] is drawn. */
private enum class TileStyle {
    ACTIVE, INACTIVE, DISABLED;

    fun background(scheme: androidx.wear.compose.material3.ColorScheme): Color = when (this) {
        ACTIVE -> scheme.primary
        INACTIVE -> scheme.surfaceContainer
        DISABLED -> scheme.surfaceContainerLow
    }

    fun tint(scheme: androidx.wear.compose.material3.ColorScheme): Color = when (this) {
        ACTIVE -> scheme.onPrimary
        INACTIVE -> scheme.onSurface
        DISABLED -> scheme.outlineVariant
    }
}

private fun toggleStyle(state: ToggleState): TileStyle = when (state) {
    ToggleState.ON -> TileStyle.ACTIVE
    ToggleState.OFF -> TileStyle.INACTIVE
    ToggleState.UNAVAILABLE -> TileStyle.DISABLED
}

// ---- notifications ------------------------------------------------------------------------------

/**
 * One notification card, stock-shaped (Wear M3 `AppCard`): the app's icon, name and age on the first
 * line, the title in the accent colour and up to two lines of text, in stock's type sizes. Tap fires
 * the notification's content intent and closes the tray; a horizontal swipe dismisses it - unless
 * the notification is ongoing, which is shown but not dismissable.
 */
@Composable
private fun NotificationCard(
    controller: LauncherController,
    item: NotificationItem,
    nowMillis: Long,
    modifier: Modifier,
    transformation: SurfaceTransformation,
) {
    val cardWidthPx = with(LocalDensity.current) { 170.dp.toPx() }
    val dragOffset = remember(item.key) { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val scheme = MaterialTheme.colorScheme
    AppCard(
        onClick = { controller.openNotification(item) },
        appName = { Text(item.appName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        time = { Text(NotificationTime.format(nowMillis, item.postTimeMillis), maxLines = 1) },
        appImage = { PackageIcon(item.packageName, size = 24.dp) },
        title = { Text(item.title.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis) },
        colors = CardDefaults.cardColors(titleColor = scheme.primary),
        transformation = transformation,
        modifier = modifier
            .offset { IntOffset(dragOffset.value.roundToInt(), 0) }
            // Swipe left or right to dismiss; an ongoing notification is not dismissable, so the
            // drag is switched off for it (the card is still tappable and still scrolls).
            .draggable(
                orientation = Orientation.Horizontal,
                enabled = !item.ongoing,
                state = rememberDraggableState { delta ->
                    scope.launch { dragOffset.snapTo(dragOffset.value + delta) }
                },
                onDragStopped = {
                    if (abs(dragOffset.value) >= cardWidthPx * CARD_DISMISS_FRACTION) {
                        controller.dismissNotification(item.key)
                    } else {
                        scope.launch { dragOffset.animateTo(0f, tween(DRAG_SETTLE_MILLIS)) }
                    }
                },
            )
            .testTag(NOTIFICATION_CARD_TAG),
    ) {
        item.text?.let { Text(it, maxLines = 2, overflow = TextOverflow.Ellipsis) }
    }
}

/** Wall clock for the cards' age labels, refreshed while the tray is open. */
@Composable
private fun rememberNotificationClock(): Long =
    produceState(initialValue = System.currentTimeMillis()) {
        while (true) {
            delay(30_000)
            value = System.currentTimeMillis()
        }
    }.value
