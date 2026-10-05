package org.circa.settings.ui

import org.circa.symbols.CircaSymbols
import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.RadioButton
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.lazy.transformedHeight
import kotlinx.coroutines.delay
import org.circa.settings.SettingsController
import org.circa.settings.data.DateTimeData
import org.circa.settings.data.StorageData
import org.circa.settings.model.SettingsPage
import org.circa.settings.model.StorageModel
import org.circa.settings.model.TimeZoneModel
import org.circa.settings.model.ZoneRow
import java.util.Date
import java.util.TimeZone

// ---- Storage -------------------------------------------------------------------------------------

@Composable
internal fun StoragePage(c: SettingsController) {
    val ctx = LocalContext.current
    var info by remember { mutableStateOf(StorageData.read(ctx)) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(5000)
            info = StorageData.read(ctx)
        }
    }
    ListPage(c, SettingsPage.STORAGE) { spec ->
        val s = info
        item(key = "storage_summary") {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 4.dp)
                    .transformedHeight(this, spec).testTag(rowTag("storage_summary")),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    "${StorageModel.size(s.used)} used",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                )
                Box(
                    Modifier.fillMaxWidth().height(8.dp).clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceContainer),
                ) {
                    Box(
                        Modifier.fillMaxHeight()
                            .fillMaxWidth(StorageModel.usedFraction(s.used, s.total).coerceAtLeast(0.03f))
                            .clip(CircleShape).background(MaterialTheme.colorScheme.primary),
                    )
                }
                Text(
                    "${StorageModel.percent(s.used, s.total)}% of ${StorageModel.size(s.total)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
        navRow("storage_free", spec, "Free", CircaSymbols.Outlined.Storage, secondary = StorageModel.size(s.free)) {}
        s.apps?.let { navRow("storage_apps", spec, "Apps", CircaSymbols.Outlined.Apps, secondary = StorageModel.size(it)) {} }
        s.other?.let { navRow("storage_system", spec, "System & other", CircaSymbols.Outlined.Watch, secondary = StorageModel.size(it)) {} }
    }
}

// ---- Date & time ---------------------------------------------------------------------------------

@Composable
internal fun DateTimePage(c: SettingsController) {
    val ctx = LocalContext.current
    val data = remember { DateTimeData(ctx) }
    var st by remember { mutableStateOf(data.read()) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            now = System.currentTimeMillis()
            st = data.read()
        }
    }
    ListPage(c, SettingsPage.DATETIME) { spec ->
        val s = st
        val zone = TimeZone.getTimeZone(s.zoneId)
        val timeFmt = java.text.SimpleDateFormat(if (s.use24) "HH:mm" else "h:mm a", java.util.Locale.getDefault())
            .apply { timeZone = zone }
        val dateFmt = java.text.SimpleDateFormat(
            DateFormat.getBestDateTimePattern(java.util.Locale.getDefault(), "EEEdMMMyyyy"), java.util.Locale.getDefault(),
        ).apply { timeZone = zone }
        navRow("dt_now", spec, timeFmt.format(Date(now)), secondary = dateFmt.format(Date(now))) {}
        toggleRow(
            "dt_auto_time", spec, "Automatic date & time", s.autoTime,
            secondary = if (s.autoTime) "Network time" else "Off",
        ) { data.setAutoTime(it); st = data.read() }
        toggleRow(
            "dt_auto_zone", spec, "Automatic time zone", s.autoZone,
            secondary = if (s.autoZone) "Network time zone" else "Off",
        ) { data.setAutoZone(it); st = data.read() }
        item(key = "dt_zone") {
            // Greyed out while the zone is automatic, like stock; it opens the picker only when manual.
            FilledTonalButton(
                onClick = { c.openSettingsPage(SettingsPage.TIMEZONE) },
                enabled = !s.autoZone,
                modifier = Modifier.fillMaxWidth().heightIn(min = ROW_MIN_HEIGHT)
                    .transformedHeight(this, spec).testTag(rowTag("dt_zone")),
                transformation = SurfaceTransformation(spec),
                secondaryLabel = {
                    Text(
                        "${TimeZoneModel.cityFromId(s.zoneId)}, ${TimeZoneModel.offsetLabel(zone.getOffset(now))}",
                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                },
                label = { Text("Time zone", maxLines = 1) },
            )
        }
        toggleRow("dt_24h", spec, "24-hour clock", s.use24, secondary = if (s.use24) "13:00" else "1:00 PM") {
            data.set24(it); st = data.read()
        }
    }
}

@Composable
internal fun TimeZonePage(c: SettingsController) {
    val ctx = LocalContext.current
    val data = remember { DateTimeData(ctx) }
    val zones = remember { data.zones() }
    var query by remember { mutableStateOf("") }
    var current by remember { mutableStateOf(data.currentZoneId()) }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    TitledListPage(c, SettingsPage.TIMEZONE, ime = true) { spec ->
        item(key = "tz_search") {
            PillTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = "Search",
                tag = "tz_search_field",
                focusRequester = focus,
                modifier = Modifier.transformedHeight(this, spec),
                imeAction = ImeAction.Search,
                onImeAction = { keyboard?.hide() },
                leadingIcon = CircaSymbols.Outlined.Search,
            )
        }
        val shown = TimeZoneModel.filter(zones, query)
        if (shown.isEmpty()) noteItem("tz_none", "No matching time zone")
        shown.forEach { z -> zoneRow(spec, z, z.id == current) {
            if (data.setZone(z.id)) current = z.id
            keyboard?.hide()
            c.settingsBack()
        } }
    }
}

private fun androidx.wear.compose.foundation.lazy.TransformingLazyColumnScope.zoneRow(
    spec: androidx.wear.compose.material3.lazy.TransformationSpec,
    z: ZoneRow,
    selected: Boolean,
    onSelect: () -> Unit,
) = item(key = "tz_${z.id}") {
    RadioButton(
        selected = selected,
        onSelect = onSelect,
        modifier = Modifier.fillMaxWidth().heightIn(min = ROW_MIN_HEIGHT)
            .transformedHeight(this, spec).testTag(rowTag("tz_${z.id}")),
        transformation = SurfaceTransformation(spec),
        secondaryLabel = {
            Text(
                listOf(TimeZoneModel.offsetLabel(z.offsetMs), z.region).filter { it.isNotEmpty() }.joinToString(" · "),
                maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
        },
        label = { Text(z.city, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Start) },
    )
}
