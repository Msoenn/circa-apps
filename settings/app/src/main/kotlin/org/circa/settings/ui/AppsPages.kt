package org.circa.settings.ui

import org.circa.symbols.CircaSymbols
import android.content.Context
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.TransformingLazyColumnScope
import androidx.wear.compose.foundation.lazy.TransformingLazyColumnState
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.SwitchButton
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.lazy.TransformationSpec
import androidx.wear.compose.material3.lazy.transformedHeight
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.circa.settings.SettingsController
import org.circa.settings.data.AppStorage
import org.circa.settings.data.AppsData
import org.circa.settings.data.NotifState
import org.circa.settings.model.AppEntry
import org.circa.settings.model.AppsModel
import org.circa.settings.model.NotifModel
import org.circa.settings.model.PermGroup
import org.circa.settings.model.SettingsPage

/**
 * Apps & notifications: the installed-apps list, App info (version, storage, open, notifications,
 * permissions, force stop, disable, uninstall), the app's runtime permissions, and the per-app
 * notification switches. Every write goes to the platform through [AppsData] and the page re-reads
 * what the platform holds afterwards. Package-manager work is blocking binder IPC, so it runs on
 * Dispatchers.IO and the pages draw from state.
 */

/** App icons in rows: a little larger than the 24 dp symbol icons, as stock's app list draws them. */
private val APP_ICON = 30.dp

/**
 * State that outlives one page composition (PageFrame recomposes from scratch on every page change):
 * the loaded list, the "Show system apps" choice and the list's scroll position, so coming back from
 * App info lands where the user was. Held here rather than in SettingsController (shared file).
 */
private object AppsUi {
    private var data: AppsData? = null

    fun data(context: Context): AppsData =
        data ?: AppsData(context.applicationContext).also { data = it }

    val all: MutableState<List<AppEntry>?> = mutableStateOf(null)
    val showSystem = mutableStateOf(false)

    /** The list's scroll state; kept only while the user goes into an app and back. */
    var listState: TransformingLazyColumnState? = null
    var keepListState = false

    /** What the confirm page asks for (pageArg holds the package). */
    var pending: AppAction = AppAction.FORCE_STOP
}

private enum class AppAction(val verb: String) {
    FORCE_STOP("Force stop"),
    DISABLE("Disable"),
    UNINSTALL("Uninstall"),
}

@Composable
internal fun appsData(): AppsData {
    val context = LocalContext.current
    return remember { AppsUi.data(context) }
}

// ---- Apps list ------------------------------------------------------------------------------------

@Composable
internal fun AppsListPage(c: SettingsController) {
    val data = appsData()
    // Re-read on every visit (an app may have been installed, disabled or removed); the old list stays
    // on screen meanwhile, so coming back from App info does not flash "Loading".
    LaunchedEffect(Unit) { AppsUi.all.value = withContext(Dispatchers.IO) { data.listApps() } }
    val state = remember {
        val keep = AppsUi.keepListState
        AppsUi.keepListState = false
        (if (keep) AppsUi.listState else null) ?: TransformingLazyColumnState().also { AppsUi.listState = it }
    }
    val all = AppsUi.all.value
    val showSystem = AppsUi.showSystem.value
    val apps = remember(all, showSystem) { all?.let { AppsModel.visible(it, showSystem) } }
    ListPage(c, SettingsPage.APPS_LIST, scrollState = state) { spec ->
        if (apps == null) {
            messageItem("loading", "Loading…")
            return@ListPage
        }
        apps.forEach { a ->
            appRow("app_${a.pkg}", spec, a, AppsModel.secondary(a)) {
                AppsUi.keepListState = true
                c.openSettingsPage(SettingsPage.APP_INFO, a.pkg)
            }
        }
        toggleRow(
            "show_system", spec, "Show system apps", showSystem,
            secondary = "${all?.size ?: 0} packages",
        ) { AppsUi.showSystem.value = it }
    }
}

// ---- App info ---------------------------------------------------------------------------------------

/** What App info draws for one package, read off the main thread. */
private data class AppInfoState(
    val app: AppEntry?,
    val storage: AppStorage?,
    val canOpen: Boolean,
    val notif: NotifState?,
    val perms: List<PermGroup>,
)

@Composable
internal fun AppInfoPage(c: SettingsController) {
    val data = appsData()
    val pkg = c.pageArg.value
    val version = remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    val info by produceState<AppInfoState?>(null, pkg, version.intValue) {
        value = withContext(Dispatchers.IO) { pkg?.let { loadInfo(data, it) } }
    }
    fun change(block: AppsData.() -> Unit) {
        scope.launch {
            withContext(Dispatchers.IO) { data.block() }
            version.intValue++
        }
    }
    ListPage(c, SettingsPage.APP_INFO) { spec ->
        val s = info
        when {
            pkg == null -> { messageItem("none", "No app selected"); return@ListPage }
            s == null -> { messageItem("loading", "Loading…"); return@ListPage }
            s.app == null -> { messageItem("gone", "$pkg is not installed"); return@ListPage }
        }
        val a = s!!.app!!
        item(key = "app_header") { AppHeader(a) }
        if (s.canOpen && a.enabled && a.pkg != AppsModel.SELF) {
            navRow("app_open", spec, "Open") { data.open(a.pkg) }
        }
        s.notif?.let { n ->
            if (a.pkg != AppsModel.SELF) {
                toggleRow(
                    "app_notifications", spec, "Notifications", n.on,
                    secondary = n.secondary, enabled = n.changeable,
                ) { on -> change { setNotifications(a.pkg, a.uid, on) } }
            }
        }
        navRow("app_permissions", spec, "Permissions", secondary = permSummary(s.perms)) {
            c.openSettingsPage(SettingsPage.APP_PERMS, a.pkg)
        }
        infoRow("app_version", spec, "Version", AppsModel.versionLine(a.versionName, a.versionCode))
        infoRow(
            "app_storage", spec, "Storage",
            s.storage?.let { st ->
                "${data.formatSize(st.app + st.data + st.cache)} total\n" +
                    "App ${data.formatSize(st.app)} · Data ${data.formatSize(st.data + st.cache)}"
            } ?: "Unavailable",
        )
        if (AppsModel.canForceStop(a)) {
            navRow("app_force_stop", spec, "Force stop") { confirm(c, a.pkg, AppAction.FORCE_STOP) }
        }
        if (AppsModel.canDisable(a)) {
            if (a.enabled) {
                navRow("app_disable", spec, "Disable") { confirm(c, a.pkg, AppAction.DISABLE) }
            } else {
                navRow("app_enable", spec, "Enable", secondary = "Disabled") { change { setEnabled(a.pkg, true) } }
            }
        }
        if (AppsModel.canUninstall(a)) {
            navRow("app_uninstall", spec, "Uninstall") { confirm(c, a.pkg, AppAction.UNINSTALL) }
        }
    }
}

private fun loadInfo(data: AppsData, pkg: String): AppInfoState {
    val app = data.app(pkg) ?: return AppInfoState(null, null, false, null, emptyList())
    return AppInfoState(
        app = app,
        storage = data.storage(pkg),
        canOpen = data.launchIntent(pkg) != null,
        notif = data.notifState(pkg, app.uid),
        perms = data.permGroups(pkg),
    )
}

private fun permSummary(groups: List<PermGroup>): String {
    if (groups.isEmpty()) return "None requested"
    val allowed = groups.filter { it.granted }.map { it.label }
    return if (allowed.isEmpty()) "None allowed" else allowed.joinToString(", ")
}

private fun confirm(c: SettingsController, pkg: String, action: AppAction) {
    AppsUi.pending = action
    c.openSettingsPage(SettingsPage.APP_CONFIRM, pkg)
}

/** Icon, name and package, centred like stock's App info header. */
@Composable
private fun AppHeader(a: AppEntry) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 28.dp).padding(bottom = 6.dp).testTag("app_header"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        AppIcon(a.pkg, 40.dp)
        Text(
            a.label, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center,
            maxLines = 2, overflow = TextOverflow.Ellipsis,
        )
        Text(
            a.pkg, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
            // Package names are long and differ at the end: keep both ends ("org.circa…keyboard").
            overflow = TextOverflow.MiddleEllipsis,
        )
    }
}

// ---- confirm (force stop / disable / uninstall) ---------------------------------------------------------

/** "Force stop <app>?" with the app's icon and the round no / yes buttons of Restart / Power off. */
@Composable
internal fun AppConfirmPage(c: SettingsController) {
    val data = appsData()
    val pkg = c.pageArg.value
    val action = AppsUi.pending
    val scope = rememberCoroutineScope()
    val busy = remember { mutableStateOf(false) }
    val label by produceState(pkg ?: "", pkg) {
        value = withContext(Dispatchers.IO) { pkg?.let { data.app(it)?.label } ?: pkg ?: "" }
    }
    PageFrame(c, SettingsPage.APP_CONFIRM) {
        Box(Modifier.fillMaxSize().testTag(settingsPageTag(SettingsPage.APP_CONFIRM))) {
            Column(
                modifier = Modifier.align(Alignment.Center).padding(horizontal = 40.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (pkg != null) AppIcon(pkg, 32.dp)
                Text(
                    "${action.verb} $label?", textAlign = TextAlign.Center, style = MaterialTheme.typography.titleMedium,
                    maxLines = 3, overflow = TextOverflow.Ellipsis,
                )
                if (busy.value) {
                    Text("Working…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    RoundChoice("confirm_no", CircaSymbols.Filled.Close, "No", tonal = true) { c.settingsBack() }
                    RoundChoice("confirm_yes", CircaSymbols.Filled.Check, "Yes", tonal = false) {
                        if (pkg == null || busy.value) return@RoundChoice
                        busy.value = true
                        scope.launch {
                            val gone = withContext(Dispatchers.IO) { runAction(data, pkg, action) }
                            if (gone) c.openSettingsPage(SettingsPage.APPS_LIST) else c.settingsBack()
                        }
                    }
                }
            }
        }
    }
}

/** Runs [action]; true when the package is gone afterwards (uninstall), so App info has nothing to show. */
private suspend fun runAction(data: AppsData, pkg: String, action: AppAction): Boolean = when (action) {
    AppAction.FORCE_STOP -> { data.forceStop(pkg); false }
    AppAction.DISABLE -> { data.setEnabled(pkg, false); false }
    AppAction.UNINSTALL -> {
        // The result comes back asynchronously (after the platform's own confirmation, see
        // AppsData.uninstall); the package list is the final word.
        data.uninstall(pkg)
        !data.isInstalled(pkg)
    }
}

@Composable
internal fun RoundChoice(tag: String, icon: androidx.compose.ui.graphics.vector.ImageVector, desc: String, tonal: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.size(52.dp).testTag(tag),
        colors = if (tonal) ButtonDefaults.filledTonalButtonColors() else ButtonDefaults.buttonColors(),
        contentPadding = PaddingValues(0.dp),
        shape = CircleShape,
        label = {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = desc, modifier = Modifier.size(ICON))
            }
        },
    )
}

// ---- Permissions ------------------------------------------------------------------------------------------

@Composable
internal fun AppPermsPage(c: SettingsController) {
    val data = appsData()
    val pkg = c.pageArg.value
    val version = remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    val optimistic = remember { androidx.compose.runtime.mutableStateMapOf<String, Boolean>() }
    val groups by produceState<List<PermGroup>?>(null, pkg, version.intValue) {
        value = withContext(Dispatchers.IO) { pkg?.let { data.permGroups(it) } ?: emptyList() }
        optimistic.clear()
    }
    ListPage(c, SettingsPage.APP_PERMS) { spec ->
        val gs = groups
        when {
            gs == null -> messageItem("loading", "Loading…")
            gs.isEmpty() -> messageItem("none", "No permissions requested")
            else -> gs.forEach { g ->
                val granted = optimistic[g.label] ?: g.granted
                toggleRow(
                    "perm_${g.label.lowercase().replace(' ', '_')}", spec, g.label, granted,
                    secondary = when {
                        !g.changeable -> if (granted) "Allowed by system" else "Not allowed by system"
                        granted -> "Allowed"
                        else -> "Not allowed"
                    },
                    enabled = g.changeable,
                ) { on ->
                    optimistic[g.label] = on
                    scope.launch {
                        withContext(Dispatchers.IO) { data.setGroup(pkg!!, g, on) }
                        version.intValue++
                    }
                }
            }
        }
    }
}

// ---- Notifications ------------------------------------------------------------------------------------------

private data class NotifRow(val app: AppEntry, val state: NotifState)

@Composable
internal fun NotifAppsPage(c: SettingsController) {
    val data = appsData()
    val version = remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    // The switch flips at once; the platform's answer replaces it when the list re-reads (slow on a
    // busy watch: one binder round trip per app).
    val optimistic = remember { androidx.compose.runtime.mutableStateMapOf<String, Boolean>() }
    val rows by produceState<List<NotifRow>?>(null, version.intValue) {
        value = withContext(Dispatchers.IO) {
            val all = AppsUi.all.value ?: data.listApps().also { AppsUi.all.value = it }
            AppsModel.visible(all, showSystem = true).mapNotNull { a ->
                val requests = data.requestsPostNotifications(a.pkg)
                if (!NotifModel.listed(a, requests, posted = AppsModel.userVisible(a) || data.hasPosted(a.pkg, a.uid))) null
                else NotifRow(a, data.notifState(a.pkg, a.uid, requests))
                    // System packages whose switch is locked are noise here (stock hides them too).
                    .takeIf { AppsModel.userVisible(a) || it.state.changeable }
            }
        }
        optimistic.clear()
    }
    ListPage(c, SettingsPage.NOTIF_APPS) { spec ->
        val rs = rows
        when {
            rs == null -> messageItem("loading", "Loading…")
            rs.isEmpty() -> messageItem("none", "No apps")
            else -> rs.forEach { r ->
                val shown = optimistic[r.app.pkg]?.let { r.state.copy(on = it) } ?: r.state
                appToggleRow(
                    "notif_${r.app.pkg}", spec, r.app, shown.on,
                    secondary = shown.secondary, enabled = r.state.changeable,
                ) { on ->
                    optimistic[r.app.pkg] = on
                    scope.launch {
                        withContext(Dispatchers.IO) { data.setNotifications(r.app.pkg, r.app.uid, on) }
                        version.intValue++
                    }
                }
            }
        }
    }
}

// ---- rows ---------------------------------------------------------------------------------------------

/** The app's launcher icon, loaded off the main thread and cached in [AppsData]. */
@Composable
internal fun AppIcon(pkg: String, size: Dp) {
    val data = appsData()
    val px = with(LocalDensity.current) { size.roundToPx() }
    val bmp by produceState<ImageBitmap?>(null, pkg, px) {
        value = withContext(Dispatchers.IO) { data.icon(pkg, px) }
    }
    val b = bmp
    if (b != null) {
        Image(b, contentDescription = null, modifier = Modifier.size(size))
    } else {
        // Same footprint while loading, so the label does not jump sideways.
        Box(Modifier.size(size).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHigh))
    }
}

internal fun TransformingLazyColumnScope.appRow(
    id: String,
    spec: TransformationSpec,
    app: AppEntry,
    secondary: String,
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
        icon = { AppIcon(app.pkg, APP_ICON) },
        secondaryLabel = { Text(secondary, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        label = { Text(app.label, maxLines = 2, overflow = TextOverflow.Ellipsis) },
    )
}

private fun TransformingLazyColumnScope.appToggleRow(
    id: String,
    spec: TransformationSpec,
    app: AppEntry,
    checked: Boolean,
    secondary: String,
    enabled: Boolean,
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
        icon = { AppIcon(app.pkg, APP_ICON) },
        secondaryLabel = { Text(secondary, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        label = { Text(app.label, maxLines = 2, overflow = TextOverflow.Ellipsis) },
    )
}

/** A read-only fact (Version, Storage): a row like the About page's, with room for two lines. */
internal fun TransformingLazyColumnScope.infoRow(
    id: String,
    spec: TransformationSpec,
    label: String,
    secondary: String,
) = item(key = id) {
    FilledTonalButton(
        onClick = {},
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = ROW_MIN_HEIGHT)
            .transformedHeight(this, spec)
            .testTag(rowTag(id)),
        transformation = SurfaceTransformation(spec),
        secondaryLabel = { Text(secondary, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
    )
}

/** A centred line in place of rows (loading, empty states). */
private fun TransformingLazyColumnScope.messageItem(id: String, text: String) = item(key = id) {
    Text(
        text,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp).testTag("msg_$id"),
        textAlign = TextAlign.Center,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
