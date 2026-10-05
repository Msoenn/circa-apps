package org.circa.settings.ui

import org.circa.symbols.CircaSymbols
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.wear.compose.material3.lazy.transformedHeight
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.TransformingLazyColumnScope
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import kotlinx.coroutines.delay
import org.circa.settings.SettingsController
import org.circa.settings.data.A11yData
import org.circa.settings.data.A11yState
import org.circa.settings.data.BluetoothData
import org.circa.settings.data.BtDeviceInfo
import org.circa.settings.data.LocaleData
import org.circa.settings.model.BtKind
import org.circa.settings.model.BtLabels
import org.circa.settings.model.FontScale
import org.circa.settings.model.LocaleModel
import org.circa.settings.model.Pairing
import org.circa.settings.model.SettingsPage

// ---- shared bits ---------------------------------------------------------------------------------

/** [read] now and then once a second while the page is shown (Bluetooth changes asynchronously). */
@Composable
private fun <T> polled(vararg keys: Any?, read: () -> T): MutableState<T> {
    val state = remember(*keys) { mutableStateOf(read()) }
    LaunchedEffect(*keys) {
        while (true) {
            delay(1000)
            state.value = read()
        }
    }
    return state
}

/** A receiver for [actions] registered while the calling composable is on screen. */
@Composable
private fun BroadcastEffect(vararg actions: String, onReceive: (Intent) -> Unit) {
    val context = LocalContext.current
    DisposableEffect(Unit) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) = onReceive(intent)
        }
        val filter = IntentFilter().apply { actions.forEach(::addAction) }
        // The senders are the Bluetooth stack (another uid): exported, but all are protected broadcasts.
        context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        onDispose { runCatching { context.unregisterReceiver(receiver) } }
    }
}

/** A centred grey line inside a list (empty states, hints). */
private fun TransformingLazyColumnScope.note(id: String, text: String) = item(key = id) {
    Text(
        text,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp).testTag(rowTag(id)),
        textAlign = TextAlign.Center,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** A small section title between rows (stock's "Paired devices" / "Available devices"). */
private fun TransformingLazyColumnScope.section(id: String, text: String) = item(key = id) {
    Text(
        text,
        modifier = Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 8.dp, bottom = 2.dp),
        textAlign = TextAlign.Center,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

internal fun kindIcon(kind: BtKind): ImageVector = when (kind) {
    BtKind.PHONE -> CircaSymbols.Outlined.Smartphone
    BtKind.COMPUTER -> CircaSymbols.Outlined.Computer
    BtKind.AUDIO -> CircaSymbols.Outlined.Headphones
    BtKind.WEARABLE -> CircaSymbols.Outlined.Watch
    BtKind.INPUT -> CircaSymbols.Outlined.Keyboard
    BtKind.HEALTH, BtKind.OTHER -> CircaSymbols.Outlined.Bluetooth
}

/** Row ids must be stable and unique; the address minus colons (never shown). */
private fun devId(address: String) = address.replace(":", "").lowercase()

// ---- Bluetooth -----------------------------------------------------------------------------------

@Composable
internal fun BluetoothPage(c: SettingsController) {
    val context = LocalContext.current
    val bt = remember { BluetoothData(context) }
    val paired by polled { bt.paired() }
    val s = c.settingsState.value
    val on = s.qs.bluetooth.on()
    ListPage(c, SettingsPage.BLUETOOTH) { spec ->
        toggleRow(
            "bt_toggle", spec, "Bluetooth", on, CircaSymbols.Outlined.Bluetooth,
            secondary = if (s.qs.bluetooth == org.circa.settings.data.ToggleState.UNAVAILABLE) "Unavailable" else onOff(on),
            enabled = s.qs.bluetooth != org.circa.settings.data.ToggleState.UNAVAILABLE,
        ) { c.setBluetooth(it) }
        if (!on) {
            note("bt_off_note", "Turn on Bluetooth to pair a device")
            return@ListPage
        }
        // Empty state first: after two full-height rows it would sit on the bottom rim of the circle and be
        // clipped; above "Pair new device" it is fully inside, and the pill below can run off the bottom.
        if (paired.isEmpty()) note("bt_none", "No paired devices")
        navRow("bt_pair", spec, "Pair new device", CircaSymbols.Outlined.Add) { c.openSettingsPage(SettingsPage.BT_PAIR) }
        if (paired.isNotEmpty()) {
            section("bt_paired_header", "Paired devices")
            paired.forEach { d ->
                navRow("bt_dev_${devId(d.address)}", spec, d.label, kindIcon(d.kind), secondary = BtLabels.status(d.connected)) {
                    c.openSettingsPage(SettingsPage.BT_DEVICE, d.address)
                }
            }
        }
    }
}

@Composable
internal fun BtDevicePage(c: SettingsController) {
    val context = LocalContext.current
    val bt = remember { BluetoothData(context) }
    val address = c.pageArg.value
    val info by polled(address) { bt.device(address)?.let(bt::info) }
    // "Connecting..." until the link really changes (connect() only queues the request).
    var pending by remember(address) { mutableStateOf<Boolean?>(null) }
    val d = info
    if (pending != null && d != null && d.connected == pending) pending = null
    ListPage(c, SettingsPage.BT_DEVICE, title = d?.label ?: SettingsPage.BT_DEVICE.title) { spec ->
        if (d == null || !d.bonded) {
            note("bt_dev_gone", "This device isn't paired")
            return@ListPage
        }
        val label = when (pending) {
            true -> "Connecting…"
            false -> "Disconnecting…"
            null -> if (d.connected) "Disconnect" else "Connect"
        }
        navRow(
            "bt_connect", spec, label, if (d.connected) CircaSymbols.Outlined.LinkOff else CircaSymbols.Outlined.Link,
            secondary = if (d.connected) "Connected" else "Not connected",
        ) {
            if (pending != null) return@navRow
            val want = !d.connected
            if (if (want) bt.connect(d.address) else bt.disconnect(d.address)) pending = want
        }
        navRow("bt_rename", spec, "Rename", CircaSymbols.Outlined.Edit, secondary = d.label) {
            c.openSettingsPage(SettingsPage.BT_RENAME)
        }
        navRow("bt_forget", spec, "Forget", CircaSymbols.Outlined.Delete) { c.openSettingsPage(SettingsPage.BT_FORGET) }
    }
    // Give up on the spinner text if nothing changes (device out of range, profile refused).
    LaunchedEffect(pending) {
        if (pending != null) {
            delay(20_000)
            pending = null
        }
    }
}

/** Forget = removeBond, only from this explicit confirm (WatchLink's phone bond must never go by itself). */
@Composable
internal fun BtForgetPage(c: SettingsController) {
    val context = LocalContext.current
    val bt = remember { BluetoothData(context) }
    val address = c.pageArg.value
    val label = remember(address) { bt.device(address)?.let(bt::labelOf) ?: "device" }
    ConfirmPage(c, SettingsPage.BT_FORGET, "Forget $label?", CircaSymbols.Outlined.Delete) {
        if (address != null) bt.forget(address)
        c.openSettingsPage(SettingsPage.BLUETOOTH)
    }
}

/** Rename with the system keyboard: the field takes focus and asks for the IME as soon as it shows. */
@Composable
internal fun BtRenamePage(c: SettingsController) {
    val context = LocalContext.current
    val bt = remember { BluetoothData(context) }
    val address = c.pageArg.value
    val current = remember(address) { bt.device(address)?.let(bt::labelOf).orEmpty() }
    var value by remember(address) { mutableStateOf(TextFieldValue(current, TextRange(0, current.length))) }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val save = {
        if (address != null) bt.rename(address, BtLabels.cleanAlias(value.text))
        keyboard?.hide()
        c.settingsBack()
    }
    PageFrame(c, SettingsPage.BT_RENAME) {
        LaunchedEffect(Unit) {
            // Focus first; the IME is asked for once the field really holds focus (onFocusChanged
            // below) and again a few frames later, when Compose's text-input session is bound.
            // Measured on the emulator: show() in the same frame as requestFocus() is dropped
            // (IMS reports the IME shown, but the window never makes the ime inset visible).
            runCatching { focus.requestFocus() }
            repeat(3) { androidx.compose.runtime.withFrameNanos { } }
            keyboard?.show()
        }
        Box(Modifier.fillMaxSize().testTag(settingsPageTag(SettingsPage.BT_RENAME))) {
            Column(
                modifier = Modifier.align(Alignment.Center).padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("Rename", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                BasicTextField(
                    value = value,
                    onValueChange = { value = it },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(
                        color = MaterialTheme.colorScheme.onSurface, textAlign = TextAlign.Center,
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { save() }),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(26.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainer)
                        .padding(horizontal = 14.dp, vertical = 14.dp)
                        .focusRequester(focus)
                        .onFocusChanged { if (it.isFocused) keyboard?.show() }
                        .testTag("bt_rename_field"),
                )
                RoundIconButton("bt_rename_save", CircaSymbols.Filled.Check, "Save", enabled = BtLabels.cleanAlias(value.text) != null) { save() }
            }
        }
    }
}

/** A 52 dp round icon button, the ConfirmPage look. */
@Composable
internal fun RoundIconButton(
    tag: String,
    icon: ImageVector,
    description: String,
    tonal: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(52.dp).testTag(tag),
        colors = if (tonal) androidx.wear.compose.material3.ButtonDefaults.filledTonalButtonColors()
        else androidx.wear.compose.material3.ButtonDefaults.buttonColors(),
        contentPadding = PaddingValues(0.dp),
        shape = CircleShape,
        label = {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = description, modifier = Modifier.size(ICON))
            }
        },
    )
}

/**
 * Discovery: starts on entry, stops on leaving (inquiry costs radio time and slows paging).
 * Lists named devices that aren't bonded yet; a tap bonds, the row shows progress and the result,
 * and a successful bond returns to the Bluetooth page where the device now is in the paired list.
 * The PIN / code itself is answered on the round PairingActivity the PAIRING_REQUEST opens.
 */
@Composable
internal fun BtPairPage(c: SettingsController) {
    val context = LocalContext.current
    val bt = remember { BluetoothData(context) }
    val order = remember { mutableStateListOf<String>() }
    val found = remember { mutableStateMapOf<String, BtDeviceInfo>() }
    val status = remember { mutableStateMapOf<String, String>() }
    var scanning by remember { mutableStateOf(false) }
    var started by remember { mutableStateOf(false) }
    var target by remember { mutableStateOf<String?>(null) }

    fun add(d: BluetoothDevice) {
        val name = runCatching { d.name }.getOrNull()
        if (name.isNullOrBlank()) return // stock hides nameless devices (mostly LE beacons)
        val i = bt.info(d)
        if (i.bonded && d.address != target) return
        if (d.address !in found) order.add(d.address)
        found[d.address] = i
    }

    BroadcastEffect(
        BluetoothDevice.ACTION_FOUND,
        BluetoothDevice.ACTION_NAME_CHANGED,
        BluetoothDevice.ACTION_BOND_STATE_CHANGED,
        BluetoothAdapter.ACTION_DISCOVERY_STARTED,
        BluetoothAdapter.ACTION_DISCOVERY_FINISHED,
    ) { intent ->
        val d = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        when (intent.action) {
            BluetoothAdapter.ACTION_DISCOVERY_STARTED -> scanning = true
            BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> scanning = false
            BluetoothDevice.ACTION_FOUND, BluetoothDevice.ACTION_NAME_CHANGED -> d?.let(::add)
            BluetoothDevice.ACTION_BOND_STATE_CHANGED -> if (d != null && d.address in found) {
                when (intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR)) {
                    BluetoothDevice.BOND_BONDING -> status[d.address] = "Pairing…"
                    BluetoothDevice.BOND_BONDED -> {
                        status[d.address] = "Paired"
                        if (d.address == target) c.openSettingsPage(SettingsPage.BLUETOOTH)
                    }
                    BluetoothDevice.BOND_NONE -> {
                        // BluetoothDevice.EXTRA_UNBOND_REASON is @hide: "android.bluetooth.device.extra.REASON".
                        val reason = intent.getIntExtra("android.bluetooth.device.extra.REASON", -1)
                        status[d.address] = Pairing.failureLabel(reason)
                        if (d.address == target) target = null
                    }
                }
                found[d.address] = bt.info(d)
            }
        }
    }
    DisposableEffect(Unit) {
        started = bt.startDiscovery()
        scanning = started
        onDispose { bt.cancelDiscovery() }
    }

    ListPage(c, SettingsPage.BT_PAIR) { spec ->
        if (!bt.isOn) {
            note("bt_pair_off", "Bluetooth is off")
            return@ListPage
        }
        if (scanning) {
            navRow("bt_scan", spec, "Searching…", CircaSymbols.Outlined.BluetoothSearching) {}
        } else {
            navRow("bt_scan", spec, "Search again", CircaSymbols.Outlined.BluetoothSearching, secondary = if (started) null else "Couldn't search") {
                started = bt.startDiscovery()
                scanning = started
            }
        }
        if (order.isEmpty()) {
            if (!scanning) note("bt_pair_none", "No devices found")
        } else {
            section("bt_avail_header", "Available devices")
        }
        order.forEach { a ->
            val d = found[a] ?: return@forEach
            navRow("bt_found_${devId(a)}", spec, d.label, kindIcon(d.kind), secondary = status[a] ?: "Tap to pair") {
                if (target != null) return@navRow
                status[a] = "Pairing…"
                target = a
                if (!bt.createBond(a)) {
                    status[a] = "Couldn't pair"
                    target = null
                }
            }
        }
    }
}

// ---- Language ------------------------------------------------------------------------------------

/** The current system language's own name, for the System page row (re-read on every recomposition). */
internal fun currentLanguageLabel(): String? = LocaleData.systemLocale()?.let(LocaleModel::nativeName)

@Composable
internal fun LanguagePage(c: SettingsController) {
    val context = LocalContext.current
    val data = remember { LocaleData(context) }
    val choices = remember { data.choices() }
    // Re-read after the configuration change the apply causes (the activity handles locale itself).
    val config = androidx.compose.ui.platform.LocalConfiguration.current
    val current = remember(config) { data.current() }
    var query by remember { mutableStateOf("") }
    val focus = remember { androidx.compose.ui.focus.FocusRequester() }
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    TitledListPage(c, SettingsPage.LANGUAGE, ime = true) { spec ->
        // Search first (stock has it), then the current language, so the page opens on the selected row.
        item(key = "lang_search") {
            PillTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = "Search",
                tag = "lang_search_field",
                focusRequester = focus,
                modifier = Modifier.transformedHeight(this, spec),
                imeAction = androidx.compose.ui.text.input.ImeAction.Search,
                onImeAction = { keyboard?.hide() },
                leadingIcon = CircaSymbols.Outlined.Search,
            )
        }
        if (choices.size <= 1) note("lang_only", "This watch has one language installed")
        val shown = LocaleModel.filter(choices, query).sortedByDescending { LocaleModel.isCurrent(it, current) }
        if (shown.isEmpty()) note("lang_none", "No matching language")
        shown.forEach { l ->
            val native = LocaleModel.nativeName(l)
            val local = LocaleModel.localName(l)
            radioRow(
                "lang_${l.toLanguageTag()}", spec, native, LocaleModel.isCurrent(l, current),
                secondary = local.takeIf { !it.equals(native, ignoreCase = true) },
            ) {
                if (!LocaleModel.isCurrent(l, current)) data.apply(l)
                keyboard?.hide()
            }
        }
    }
}

// ---- Accessibility -------------------------------------------------------------------------------

@Composable
internal fun AccessibilityPage(c: SettingsController) {
    val context = LocalContext.current
    val data = remember { A11yData(context) }
    val state = polled { data.read() }
    val s: A11yState = state.value
    fun change(block: () -> Unit) { block(); state.value = data.read() }
    ListPage(c, SettingsPage.ACCESSIBILITY) { spec ->
        s.talkBack?.let { on ->
            toggleRow("a11y_talkback", spec, "TalkBack", on, CircaSymbols.Outlined.AccessibilityNew, secondary = onOff(on)) {
                change { data.setTalkBack(it) }
            }
        }
        navRow("a11y_font_size", spec, "Font size", CircaSymbols.Outlined.FormatSize, secondary = FontScale.label(s.fontScale)) {
            c.openSettingsPage(SettingsPage.FONT_SIZE)
        }
        toggleRow("a11y_bold", spec, "Bold text", s.bold, CircaSymbols.Outlined.FormatBold, secondary = onOff(s.bold)) {
            change { data.setBold(it) }
        }
        toggleRow("a11y_inversion", spec, "Colour inversion", s.inversion, CircaSymbols.Outlined.InvertColors, secondary = onOff(s.inversion)) {
            change { data.setInversion(it) }
        }
        toggleRow("a11y_correction", spec, "Colour correction", s.correction, CircaSymbols.Outlined.Palette, secondary = onOff(s.correction)) {
            change { data.setCorrection(it) }
        }
    }
}

/**
 * Stock Wear's four text sizes. The page itself follows the new scale at once (MainActivity passes
 * the system fontScale into its fixed-width density), so the preview line is real text at that size.
 */
@Composable
internal fun FontSizePage(c: SettingsController) {
    val context = LocalContext.current
    val data = remember { A11yData(context) }
    val state = polled { data.read() }
    val selected = FontScale.nearest(state.value.fontScale)
    ListPage(c, SettingsPage.FONT_SIZE) { spec ->
        item(key = "font_preview") {
            Text(
                "Aa  The quick brown fox",
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).testTag("font_preview"),
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        FontScale.STEPS.forEachIndexed { i, scale ->
            radioRow("font_$i", spec, FontScale.LABELS[i], i == selected) {
                data.setFontScale(scale)
                state.value = data.read()
            }
        }
    }
}
