package org.circa.settings.ui

import org.circa.symbols.CircaSymbols
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.wear.compose.foundation.lazy.TransformingLazyColumnScope
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.lazy.TransformationSpec
import androidx.wear.compose.material3.lazy.transformedHeight
import kotlinx.coroutines.delay
import org.circa.settings.SettingsController
import org.circa.settings.data.WifiData
import org.circa.settings.data.WifiSnapshot
import org.circa.settings.model.JoinOutcome
import org.circa.settings.model.SettingsPage
import org.circa.settings.model.WifiEntry
import org.circa.settings.model.WifiModel
import org.circa.settings.model.WifiRowState
import org.circa.settings.model.WifiSecurity

/*
 * Wi-Fi: the network list (WifiPage), one network's details (WifiNetworkPage), the password page
 * (WifiJoinPage), saved networks (WifiSavedPage) and a hidden network (WifiAddPage). Platform access is
 * data/WifiData; merging and labels are model/WifiModel (unit tested).
 */

/** The page's WifiData and its snapshot, re-read every second (join progress too) while composed. */
private class WifiHolder(val data: WifiData, val snap: MutableState<WifiSnapshot>) {
    fun refresh() {
        snap.value = data.read()
    }
}

@Composable
private fun rememberWifi(scan: Boolean): WifiHolder {
    val ctx = LocalContext.current
    val holder = remember { WifiData(ctx).let { WifiHolder(it, mutableStateOf(it.read())) } }
    LaunchedEffect(scan) {
        // Scan on entry and every 10 s while the list is on screen (NETWORK_SETTINGS is exempt from
        // the 4-scans-per-2-minutes throttle); results are read every second.
        if (scan) holder.data.startScan()
        var tick = 0
        while (true) {
            delay(1000)
            val s = holder.data.read()
            holder.data.pollJoin(s)
            holder.snap.value = s
            if (scan && ++tick % 10 == 0 && s.enabled) holder.data.startScan()
        }
    }
    return holder
}

/** The row's second line: a join in flight for it wins over the merged state. */
private fun secondaryFor(e: WifiEntry): String {
    val j = WifiData.join.value
    if (j != null && j.ssid == e.ssid && j.outcome != JoinOutcome.CONNECTED &&
        e.state != WifiRowState.CONNECTED
    ) return j.outcome.label
    if (e.state == WifiRowState.SECURED && !e.security.joinable) return "Enterprise: not supported"
    return e.state.label
}

private fun TransformingLazyColumnScope.networkRow(
    spec: TransformationSpec,
    e: WifiEntry,
    onClick: () -> Unit,
) = item(key = "net_${e.ssid}") {
    FilledTonalButton(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = ROW_MIN_HEIGHT)
            .transformedHeight(this, spec)
            .testTag(rowTag("net_${e.ssid}")),
        transformation = SurfaceTransformation(spec),
        icon = { WifiSignalIcon(e.level, e.security.secured) },
        secondaryLabel = { Text(secondaryFor(e), maxLines = 1, overflow = TextOverflow.Ellipsis) },
        label = { Text(e.ssid, maxLines = 1, overflow = TextOverflow.Ellipsis) },
    )
}

/** A full-width primary action at the end of a page. */
private fun TransformingLazyColumnScope.actionButton(
    id: String,
    spec: TransformationSpec,
    label: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) = item(key = id) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = ROW_MIN_HEIGHT)
            .transformedHeight(this, spec)
            .testTag(rowTag(id)),
        transformation = SurfaceTransformation(spec),
        label = { Text(label, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center, maxLines = 1) },
    )
}

// ---- the list ------------------------------------------------------------------------------------

@Composable
internal fun WifiPage(c: SettingsController) {
    val w = rememberWifi(scan = true)
    ListPage(c, SettingsPage.WIFI) { spec ->
        val s = w.snap.value
        toggleRow("wifi_toggle", spec, "Wi-Fi", s.enabled, CircaSymbols.Outlined.Wifi, secondary = onOff(s.enabled)) { on ->
            w.data.setEnabled(on)
            w.refresh()
            c.refreshSettings()
        }
        if (!s.enabled) {
            noteItem("wifi_off_note", "Turn on Wi-Fi to see networks")
            return@ListPage
        }
        val entries = WifiModel.entries(s.scans, s.saved, s.current)
        if (entries.isEmpty()) noteItem("wifi_searching", "Searching for networks…")
        entries.forEach { e ->
            networkRow(spec, e) {
                when (WifiModel.tap(e)) {
                    WifiModel.Tap.DETAILS -> c.openSettingsPage(SettingsPage.WIFI_NETWORK, e.ssid)
                    WifiModel.Tap.ASK_PASSWORD -> c.openSettingsPage(SettingsPage.WIFI_JOIN, e.ssid)
                    WifiModel.Tap.CONNECT_OPEN -> {
                        w.data.joinNew(e.ssid, e.security, null)
                        w.refresh()
                    }
                    WifiModel.Tap.UNSUPPORTED -> Unit
                }
            }
        }
        navRow("wifi_saved", spec, "Saved networks", CircaSymbols.Outlined.Wifi,
            secondary = s.saved.distinctBy { it.ssid }.size.let { if (it == 1) "1 network" else "$it networks" },
        ) { c.openSettingsPage(SettingsPage.WIFI_SAVED) }
        navRow("wifi_add", spec, "Add network", CircaSymbols.Outlined.Add) { c.openSettingsPage(SettingsPage.WIFI_ADD) }
    }
}

@Composable
internal fun WifiSavedPage(c: SettingsController) {
    val w = rememberWifi(scan = false)
    ListPage(c, SettingsPage.WIFI_SAVED) { spec ->
        val s = w.snap.value
        val saved = WifiModel.savedEntries(s.scans, s.saved, s.current)
        if (saved.isEmpty()) noteItem("saved_none", "No saved networks")
        saved.forEach { e ->
            val sec = when {
                e.state == WifiRowState.CONNECTED -> "Connected"
                e.level < 0 -> "Not in range"
                else -> "In range"
            }
            item(key = "saved_${e.ssid}") {
                FilledTonalButton(
                    onClick = { c.openSettingsPage(SettingsPage.WIFI_NETWORK, e.ssid) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = ROW_MIN_HEIGHT)
                        .transformedHeight(this, spec)
                        .testTag(rowTag("saved_${e.ssid}")),
                    transformation = SurfaceTransformation(spec),
                    icon = { WifiSignalIcon(e.level, e.security.secured) },
                    secondaryLabel = { Text(sec, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    label = { Text(e.ssid, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                )
            }
        }
    }
}

// ---- one network ---------------------------------------------------------------------------------

@Composable
internal fun WifiNetworkPage(c: SettingsController) {
    val w = rememberWifi(scan = false)
    val ssid = c.pageArg.value ?: ""
    TitledListPage(c, SettingsPage.WIFI_NETWORK, title = ssid.ifEmpty { "Network" }) { spec ->
        val s = w.snap.value
        val e = WifiModel.entries(s.scans, s.saved, s.current).firstOrNull { it.ssid == ssid }
            ?: WifiModel.savedEntries(s.scans, s.saved, s.current).firstOrNull { it.ssid == ssid }
        if (e == null) {
            noteItem("net_gone", "Network not found")
            return@TitledListPage
        }
        val connected = e.state == WifiRowState.CONNECTED
        val j = WifiData.join.value?.takeIf { it.ssid == ssid && !connected }
        val status = when {
            connected -> "Connected"
            j != null -> j.outcome.label
            e.state == WifiRowState.CONNECTING -> "Connecting…"
            e.level < 0 -> "Saved, not in range"
            else -> "Saved"
        }
        navRow("net_status", spec, "Status", secondary = status) {}
        val level = if (connected && s.rssi != null) WifiModel.level(s.rssi) else e.level
        navRow("net_signal", spec, "Signal", secondary = WifiModel.levelLabel(level)) {}
        navRow("net_security", spec, "Security", secondary = e.security.label) {}
        if (connected) {
            s.ip?.let { navRow("net_ip", spec, "IP address", secondary = it) {} }
            s.linkMbps?.let { navRow("net_speed", spec, "Link speed", secondary = "$it Mbps") {} }
        }
        if (e.saved && !connected && e.level >= 0 && j?.outcome?.done != false) {
            navRow("net_connect", spec, "Connect", CircaSymbols.Outlined.Link) {
                w.data.connectSaved(e.netId, e.ssid)
                w.refresh()
            }
        }
        if (connected) {
            navRow("net_disconnect", spec, "Disconnect", CircaSymbols.Outlined.LinkOff) {
                w.data.disconnect(e.ssid)
                w.refresh()
            }
        }
        if (e.saved) {
            navRow("net_forget", spec, "Forget", CircaSymbols.Outlined.Delete) {
                w.data.forget(e.netId)
                w.refresh()
                c.settingsBack()
            }
        }
    }
}

// ---- joining -------------------------------------------------------------------------------------

@Composable
internal fun WifiJoinPage(c: SettingsController) = JoinPage(c, adding = false)

/** "Add network": a hidden (non-broadcast) network by name, security and password. */
@Composable
internal fun WifiAddPage(c: SettingsController) = JoinPage(c, adding = true)

@Composable
private fun JoinPage(c: SettingsController, adding: Boolean) {
    val page = if (adding) SettingsPage.WIFI_ADD else SettingsPage.WIFI_JOIN
    val w = rememberWifi(scan = false)
    val target = if (adding) null else c.pageArg.value
    var ssid by remember { mutableStateOf(target ?: "") }
    var security by remember {
        mutableStateOf(
            target?.let { t -> w.snap.value.scans.filter { it.ssid == t }.maxByOrNull { it.rssi }?.security }
                ?: WifiSecurity.PSK,
        )
    }
    var password by remember { mutableStateOf("") }
    var revealed by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    var submittedSsid by remember { mutableStateOf<String?>(null) }
    val ssidFocus = remember { FocusRequester() }
    val pwFocus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val listState = rememberTransformingLazyColumnState()
    val join = WifiData.join.value?.takeIf { submittedSsid != null && it.ssid == submittedSsid }

    // Focus the first field and raise the IME on entry (the list's rotary focus is requested first,
    // so wait a frame).
    LaunchedEffect(Unit) {
        delay(200)
        runCatching { if (adding) ssidFocus.requestFocus() else if (security.needsPassword) pwFocus.requestFocus() }
        keyboard?.show()
    }
    LaunchedEffect(join?.outcome) {
        when (join?.outcome) {
            JoinOutcome.CONNECTED -> {
                delay(1200)
                c.openSettingsPage(SettingsPage.WIFI)
            }
            JoinOutcome.WRONG_PASSWORD -> {
                runCatching { pwFocus.requestFocus() }
                keyboard?.show()
            }
            else -> Unit
        }
    }

    fun submit() {
        val name = ssid.trim()
        problem = when {
            name.isEmpty() -> "Enter the network name"
            else -> WifiModel.passwordProblem(security, password)
        }
        if (problem != null) return
        keyboard?.hide()
        submittedSsid = name
        w.data.joinNew(name, security, password.takeIf { security.needsPassword }, hidden = adding)
        w.refresh()
    }

    val err = MaterialTheme.colorScheme.error
    TitledListPage(c, page, title = if (adding) page.title else ssid, ime = true, scrollState = listState) { spec ->
        if (adding) {
            item(key = "ssid_field") {
                PillTextField(
                    value = ssid,
                    onValueChange = { ssid = it; problem = null },
                    placeholder = "Network name",
                    tag = "wifi_ssid_field",
                    focusRequester = ssidFocus,
                    modifier = Modifier.transformedHeight(this, spec),
                    imeAction = if (security.needsPassword) ImeAction.Next else ImeAction.Done,
                    onImeAction = { if (security.needsPassword) runCatching { pwFocus.requestFocus() } else submit() },
                )
            }
            listOf(WifiSecurity.OPEN, WifiSecurity.PSK, WifiSecurity.SAE).forEach { sec ->
                radioRow("sec_${sec.name.lowercase()}", spec, if (sec == WifiSecurity.OPEN) "None" else sec.label, security == sec) {
                    security = sec
                    problem = null
                }
            }
        } else {
            noteItem("join_security", security.label)
        }
        if (security.needsPassword) {
            item(key = "password_field") {
                PillTextField(
                    value = password,
                    onValueChange = { password = it; problem = null },
                    placeholder = "Password",
                    tag = "wifi_password_field",
                    focusRequester = pwFocus,
                    modifier = Modifier.transformedHeight(this, spec),
                    password = true,
                    revealed = revealed,
                    onToggleReveal = { revealed = !revealed },
                    imeAction = ImeAction.Done,
                    onImeAction = { submit() },
                )
            }
        }
        when {
            problem != null -> noteItem("join_status", problem!!, color = err)
            join?.outcome == JoinOutcome.WRONG_PASSWORD -> noteItem("join_status", "Wrong password. Try again.", color = err)
            // A hidden network stays saved: it may just be out of range now.
            join?.outcome == JoinOutcome.FAILED && adding -> noteItem("join_status", "Saved. Network not found yet.")
            join?.outcome == JoinOutcome.FAILED -> noteItem("join_status", "Couldn't connect", color = err)
            join != null -> noteItem("join_status", join.outcome.label)
        }
        val busy = join?.outcome == JoinOutcome.CONNECTING || join?.outcome == JoinOutcome.CONNECTED
        actionButton("join_button", spec, if (busy) join!!.outcome.label else "Join", enabled = !busy) { submit() }
    }
}

/** Connectivity's Wi-Fi row text: the network the watch is on, else On / Off. */
internal fun wifiRowSecondary(c: SettingsController): String {
    val s = c.settingsState.value
    return when (s.qs.wifi) {
        org.circa.settings.data.ToggleState.UNAVAILABLE -> "Unavailable"
        org.circa.settings.data.ToggleState.ON -> "On"
        else -> "Off"
    }
}
