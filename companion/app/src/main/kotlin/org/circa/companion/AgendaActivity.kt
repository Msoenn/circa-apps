package org.circa.companion

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.lazy.transformedHeight
import java.time.ZoneId
import org.circa.companion.data.Phone
import org.circa.companion.data.rememberPhone
import org.circa.companion.model.Agenda
import org.circa.companion.model.CalEvent
import org.circa.companion.model.Fmt
import org.circa.companion.ui.CurvedList
import org.circa.companion.ui.Message
import org.circa.companion.ui.rememberWallNow
import java.time.Instant

/** Agenda: today and the next 7 days from WatchLink's `/calendar`, grouped by day; a tap opens the event. */
class AgendaActivity : CircaActivity() {
    @Composable override fun Content() = AgendaScreen()
}

@Composable
private fun AgendaScreen() {
    val cal by rememberPhone("calendar", Phone::readCalendar)
    val c = cal ?: return
    val now = rememberWallNow(periodMs = 60_000)
    val zone = ZoneId.systemDefault()
    val is24 = android.text.format.DateFormat.is24HourFormat(LocalContext.current)
    var selected by rememberSaveable { mutableStateOf<Long?>(null) }
    BackHandler(selected != null) { selected = null }

    val groups = Agenda.group(c.value.orEmpty(), now, zone)
    val event = selected?.let { id -> groups.firstNotNullOfOrNull { g -> g.events.firstOrNull { it.id == id } } }
    when {
        c.error != null -> Message("Agenda unavailable", "WatchLink is not running", icon = null)
        event != null -> EventDetail(event, zone, is24)
        groups.isEmpty() -> Message("No upcoming events", "Turn on calendar sync for this watch in Gadgetbridge", icon = null)
        else -> {
            val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
            CurvedList(Modifier.testTag("agenda_list")) { spec ->
                for (g in groups) {
                    item(key = "h${g.date}") {
                        ListHeader(Modifier.transformedHeight(this, spec), transformation = SurfaceTransformation(spec)) {
                            Text(Fmt.dayHeader(g.date, today))
                        }
                    }
                    items(g.events.size, key = { "e${g.date}-${g.events[it].id}" }) { i ->
                        val ev = g.events[i]
                        FilledTonalButton(
                            onClick = { selected = ev.id },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).transformedHeight(this, spec).testTag("event_${ev.id}"),
                            transformation = SurfaceTransformation(spec),
                            icon = { Dot(ev.color) },
                            secondaryLabel = { Text(Fmt.range(ev.startMs, ev.endMs, ev.allDay, zone, is24), maxLines = 1) },
                            label = { Text(ev.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Dot(argb: Int, size: Int = 10) {
    // The calendar colour is an opaque ARGB int; 0 (unknown) falls back to the accent.
    val c = if (argb == 0) MaterialTheme.colorScheme.primary else Color(argb or 0xFF000000.toInt())
    Box(Modifier.size(size.dp).clip(CircleShape).background(c))
}

@Composable
private fun EventDetail(ev: CalEvent, zone: ZoneId, is24: Boolean) {
    CurvedList(Modifier.testTag("event_detail")) { spec ->
        item {
            Column(Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 4.dp).transformedHeight(this, spec), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(ev.title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center, maxLines = 4, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(4.dp))
                Text(Fmt.dayHeader(java.time.Instant.ofEpochMilli(ev.startMs).atZone(
                    if (ev.allDay && ev.startMs % Agenda.DAY_MS == 0L) java.time.ZoneOffset.UTC else zone).toLocalDate(),
                    java.time.LocalDate.now(zone)),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary, textAlign = TextAlign.Center)
                Text(Fmt.range(ev.startMs, ev.endMs, ev.allDay, zone, is24), style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
            }
        }
        ev.location?.let { loc ->
            item { Detail("Location", loc, spec, this) }
        }
        ev.calendar?.let { name ->
            item {
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp).transformedHeight(this, spec), Arrangement.Center, Alignment.CenterVertically) {
                    Dot(ev.color, 8)
                    Spacer(Modifier.size(6.dp))
                    Text(name, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun Detail(label: String, value: String, spec: androidx.wear.compose.material3.lazy.TransformationSpec, scope: androidx.wear.compose.foundation.lazy.TransformingLazyColumnItemScope) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp).transformedHeight(scope, spec), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center, maxLines = 3, overflow = TextOverflow.Ellipsis)
    }
}
