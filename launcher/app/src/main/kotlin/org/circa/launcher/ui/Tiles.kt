package org.circa.launcher.ui

import org.circa.symbols.CircaSymbols
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import org.circa.launcher.LauncherController
import org.circa.launcher.data.AppLoader
import org.circa.launcher.model.AgendaModel
import org.circa.launcher.model.AgendaTileItem
import org.circa.launcher.model.AppEntry
import org.circa.launcher.model.ComplicationFormat
import org.circa.launcher.model.HealthData
import org.circa.launcher.model.MediaModel
import org.circa.launcher.model.MediaView
import org.circa.launcher.model.Shortcuts
import org.circa.launcher.model.Sparkline
import android.text.format.DateFormat
import java.time.ZoneId
import kotlinx.coroutines.delay

/** testTags of the tiles; published as `resource-id` (see [LauncherRoot]). */
const val HEALTH_TILE_TAG = "tile_health"
const val SHORTCUTS_TILE_TAG = "tile_shortcuts"
const val HEALTH_EMPTY_TAG = "tile_health_empty"
const val MEDIA_TILE_TAG = "tile_media"
const val MEDIA_TITLE_TAG = "media_title"
const val MEDIA_STATE_TAG = "media_state"
const val MEDIA_PREV_TAG = "media_prev"
const val MEDIA_PLAYPAUSE_TAG = "media_playpause"
const val MEDIA_NEXT_TAG = "media_next"
const val AGENDA_TILE_TAG = "tile_agenda"
const val AGENDA_EMPTY_TAG = "tile_agenda_empty"

private val SHORTCUT_SIZE = 52.dp
private val SHORTCUT_GAP = 6.dp

/**
 * Health tile (mockup B): heart rate as one big number with a sparkline of the recent history, the
 * day's steps below. The values come from WatchLink's health provider (or the `DEMO_HEALTH` hook while
 * it is active, see *Health data* in launcher/README.md); with no live heart rate or steps the
 * tile shows a calm "No data" state - dimmed title, a dashed flat line - rather than dashes or a zero.
 */
@Composable
fun HealthTile(controller: LauncherController) {
    val health = controller.health.value
    val hr = health.hrBpm
    val history = health.history.map { it.bpm }
    val steps = health.stepsToday
    val accent = MaterialTheme.colorScheme.primary
    val dim = MaterialTheme.colorScheme.onSurfaceVariant

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .testTag(HEALTH_TILE_TAG),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(30.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.alpha(if (hr != null) 1f else NO_DATA_ALPHA),
        ) {
            Icon(CircaSymbols.Filled.Favorite, contentDescription = null, tint = accent, modifier = Modifier.size(15.dp))
            Spacer(Modifier.size(4.dp))
            Text("Heart rate", color = accent, fontSize = 14.sp, maxLines = 1)
        }
        Box(Modifier.height(58.dp), contentAlignment = Alignment.Center) {
            if (hr != null) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        text = hr.toString(),
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 52.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                    )
                    Text(
                        text = " bpm",
                        color = dim,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(bottom = 10.dp),
                    )
                }
            } else {
                val reachable = controller.healthProviderReachable.value
                Text(
                    HealthData.emptyHeartRateText(reachable),
                    color = dim.copy(alpha = NO_DATA_ALPHA),
                    fontSize = if (reachable) 19.sp else 15.sp,
                    fontWeight = FontWeight.Light,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    modifier = Modifier.padding(horizontal = 28.dp).testTag(HEALTH_EMPTY_TAG),
                )
            }
        }
        SparklineChart(history, modifier = Modifier.size(width = 112.dp, height = 28.dp))
        Spacer(Modifier.height(14.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.alpha(if (steps != null) 1f else NO_DATA_ALPHA),
        ) {
            Icon(CircaSymbols.Filled.DirectionsWalk, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(18.dp))
            Spacer(Modifier.size(5.dp))
            if (steps != null) {
                Text(
                    text = ComplicationFormat.formatStepsFull(steps) ?: "",
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 20.sp,
                    maxLines = 1,
                )
                Text(" steps", color = dim, fontSize = 13.sp, maxLines = 1)
            } else {
                Text("No steps yet", color = dim, fontSize = 16.sp, maxLines = 1)
            }
        }
    }
}

/** The heart-rate history as a smooth accent line with a soft fill; a dashed flat line when empty. */
@Composable
private fun SparklineChart(history: List<Int>, modifier: Modifier) {
    val accent = MaterialTheme.colorScheme.primary
    val track = MaterialTheme.colorScheme.outlineVariant
    Canvas(modifier) {
        val strokeW = 2.5f * density
        val pad = strokeW
        val w = size.width - 2 * pad
        val h = size.height - 2 * pad
        val pts = Sparkline.points(history, w, h)
        if (pts.isEmpty()) {
            drawLine(
                color = track,
                start = Offset(pad, size.height / 2f),
                end = Offset(size.width - pad, size.height / 2f),
                strokeWidth = 2f * density,
                cap = StrokeCap.Round,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(2f * density, 6f * density)),
            )
            return@Canvas
        }
        val line = Path()
        pts.forEachIndexed { i, p ->
            val x = pad + p.x
            val y = pad + p.y
            if (i == 0) line.moveTo(x, y) else line.lineTo(x, y)
        }
        val fill = Path().apply {
            addPath(line)
            lineTo(pad + pts.last().x, size.height)
            lineTo(pad + pts.first().x, size.height)
            close()
        }
        drawPath(fill, Brush.verticalGradient(listOf(accent.copy(alpha = 0.28f), Color.Transparent)))
        drawPath(line, accent, style = Stroke(strokeW, cap = StrokeCap.Round, join = StrokeJoin.Round))
        val last = pts.last()
        drawCircle(accent, 3.5f * density, Offset(pad + last.x, pad + last.y))
    }
}

/**
 * Shortcuts tile (mockup A): up to five big round app buttons in the stock timer tile's cluster
 * (two over three), the app icons filling their circles and no labels. Missing apps are skipped;
 * the cluster re-balances with the number of buttons that remain.
 */
@Composable
fun ShortcutsTile(controller: LauncherController) {
    val context = LocalContext.current
    val shortcuts = controller.shortcuts.value.take(5)
    val rows = when {
        shortcuts.size <= 2 -> listOf(shortcuts)
        else -> listOf(shortcuts.take(shortcuts.size / 2), shortcuts.drop(shortcuts.size / 2))
    }.filter { it.isNotEmpty() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .testTag(SHORTCUTS_TILE_TAG),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(SHORTCUT_GAP, Alignment.CenterVertically),
    ) {
        for (row in rows) {
            Row(horizontalArrangement = Arrangement.spacedBy(SHORTCUT_GAP)) {
                for (entry in row) {
                    ShortcutButton(entry) { AppLoader.launchApp(context, entry) }
                }
            }
        }
    }
}

@Composable
private fun ShortcutButton(entry: AppEntry, onClick: () -> Unit) {
    val modifier = Modifier
        .semantics { contentDescription = entry.label }
        .clickable(role = Role.Button, onClickLabel = "Open ${entry.label}", onClick = onClick)
    if (entry.clockPage != null) {
        ClockPageButton(entry.clockPage, modifier)
    } else {
        AppIcon(entry = entry, size = SHORTCUT_SIZE, modifier = modifier)
    }
}

/** An Alarm / Timer / Stopwatch button of the seeded defaults: a tonal circle with an accent glyph. */
@Composable
private fun ClockPageButton(page: Int, modifier: Modifier) {
    val tint = MaterialTheme.colorScheme.primary
    Box(
        modifier = modifier
            .size(SHORTCUT_SIZE)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
        contentAlignment = Alignment.Center,
    ) {
        when (page) {
            Shortcuts.CLOCK_PAGE_ALARM ->
                Icon(CircaSymbols.Filled.Alarm, contentDescription = null, tint = tint, modifier = Modifier.size(28.dp))
            Shortcuts.CLOCK_PAGE_STOPWATCH ->
                Icon(CircaSymbols.Filled.Timer, contentDescription = null, tint = tint, modifier = Modifier.size(28.dp))
            else -> Icon(CircaSymbols.Filled.HourglassTop, contentDescription = null, tint = tint, modifier = Modifier.size(28.dp))
        }
    }
}

/** `now` in wall-clock ms, refreshed every [periodMs] so a stale music row / agenda can drop out. */
@Composable
private fun rememberNowMs(periodMs: Long = 30_000L): Long =
    produceState(initialValue = System.currentTimeMillis(), periodMs) {
        while (true) {
            delay(periodMs)
            value = System.currentTimeMillis()
        }
    }.value

/**
 * Media tile: the phone's current track (title + artist, one line each, ellipsized) with previous /
 * play-pause / next round buttons; taps on the title open Companion's Media screen. Follows the same
 * states as Companion's Media: "Phone not connected" from `/status` and "Nothing playing" for a
 * missing or stale `/music` row. Look matches Companion: black background, accent play/pause.
 */
@Composable
fun MediaTile(controller: LauncherController) {
    val now = rememberNowMs()
    val view = MediaModel.view(controller.phoneMusic.value, controller.phoneConnected.value, now)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .testTag(MEDIA_TILE_TAG),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        when (view) {
            MediaView.NotConnected -> MediaEmptyState(CircaSymbols.Filled.MobileOff, "Phone not connected")
            MediaView.NothingPlaying -> MediaEmptyState(CircaSymbols.Filled.Headphones, "Nothing playing")
            is MediaView.NowPlaying -> {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .padding(horizontal = 20.dp)
                        .clickable(role = Role.Button, onClickLabel = "Open media controls") {
                            controller.openCompanion("MediaActivity")
                        }
                        .testTag(MEDIA_TITLE_TAG),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = view.title,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        if (view.artist.isNotEmpty()) {
                            Text(
                                text = view.artist,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 13.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
                Spacer(Modifier.height(14.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    MediaButton(
                        CircaSymbols.Filled.SkipPrevious, "Previous", MEDIA_PREV_TAG,
                        primary = false, size = 48.dp, iconSize = 24.dp,
                    ) { controller.musicCommand("previous") }
                    MediaButton(
                        if (view.playing) CircaSymbols.Filled.Pause else CircaSymbols.Filled.PlayArrow,
                        if (view.playing) "Pause" else "Play", MEDIA_PLAYPAUSE_TAG,
                        primary = true, size = 60.dp, iconSize = 34.dp,
                    ) { controller.musicCommand("playpause") }
                    MediaButton(
                        CircaSymbols.Filled.SkipNext, "Next", MEDIA_NEXT_TAG,
                        primary = false, size = 48.dp, iconSize = 24.dp,
                    ) { controller.musicCommand("next") }
                }
            }
        }
    }
}

/** "Phone not connected" / "Nothing playing": an icon and a centred line. */
@Composable
private fun MediaEmptyState(icon: ImageVector, text: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(30.dp),
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = text,
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 18.sp,
            textAlign = TextAlign.Center,
            maxLines = 2,
            modifier = Modifier.padding(horizontal = 24.dp).testTag(MEDIA_STATE_TAG),
        )
    }
}

/** A round transport button (at least 48 dp); the accent-filled one is play/pause. */
@Composable
private fun MediaButton(
    icon: ImageVector,
    description: String,
    tag: String,
    primary: Boolean,
    size: Dp,
    iconSize: Dp,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(if (primary) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(role = Role.Button, onClickLabel = description, onClick = onClick)
            .semantics { contentDescription = description }
            .testTag(tag),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = if (primary) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(iconSize),
        )
    }
}

/** Agenda pill surfaces and the secondary text grey (#9aa0a6), from the tile's layout spec. */
private val AGENDA_PILL_COLOR = Color(0xFF2D2F33)
private val AGENDA_SECONDARY = Color(0xFF9AA0A6)
private val AGENDA_PILL_WIDTH = 168.dp
private val AGENDA_PILL_HEIGHT = 52.dp
private val AGENDA_PILL_RADIUS = 26.dp
private val AGENDA_PILL_GAP = 8.dp
private val AGENDA_DOT_SIZE = 8.dp

/**
 * Agenda tile: a "Today" header (or "Tomorrow" when nothing is left today) and up to two upcoming
 * events, each a tonal pill - colour dot, then title over start time ("All day" / "Now") - all-day
 * events first. A "+N more" line follows when the day has further events. Tapping anywhere opens
 * Companion's Agenda; with neither day carrying an event it shows the `event_available` empty state.
 */
@Composable
fun AgendaTile(controller: LauncherController) {
    val context = LocalContext.current
    val is24Hour = remember(context) { DateFormat.is24HourFormat(context) }
    val now = rememberNowMs()
    val view = AgendaModel.view(controller.phoneEvents.value, now, ZoneId.systemDefault(), is24Hour)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .clickable(role = Role.Button, onClickLabel = "Open agenda") {
                controller.openCompanion("AgendaActivity")
            }
            .testTag(AGENDA_TILE_TAG),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = if (view == null) Arrangement.Center else Arrangement.Top,
    ) {
        if (view == null) {
            Icon(
                CircaSymbols.Filled.EventAvailable,
                contentDescription = null,
                tint = AGENDA_SECONDARY,
                modifier = Modifier.size(28.dp),
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "No upcoming events",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 14.sp,
                textAlign = TextAlign.Center,
                maxLines = 2,
                modifier = Modifier.padding(horizontal = 24.dp).testTag(AGENDA_EMPTY_TAG),
            )
        } else {
            Spacer(Modifier.height(28.dp))
            Text(
                text = view.header,
                color = MaterialTheme.colorScheme.primary,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
            )
            Spacer(Modifier.height(12.dp))
            view.items.forEachIndexed { index, item ->
                if (index > 0) Spacer(Modifier.height(AGENDA_PILL_GAP))
                AgendaPill(item)
            }
            if (view.moreCount > 0) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "+${view.moreCount} more",
                    color = AGENDA_SECONDARY,
                    fontSize = 11.sp,
                    maxLines = 1,
                )
            }
        }
    }
}

/** One agenda pill: the calendar colour dot (80 % over the pill) then the title over the time. */
@Composable
private fun AgendaPill(item: AgendaTileItem) {
    Row(
        modifier = Modifier
            .width(AGENDA_PILL_WIDTH)
            .height(AGENDA_PILL_HEIGHT)
            .clip(RoundedCornerShape(AGENDA_PILL_RADIUS))
            .background(AGENDA_PILL_COLOR)
            .padding(start = 14.dp, end = 12.dp)
            .semantics { contentDescription = item.description },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(AGENDA_DOT_SIZE)
                .clip(CircleShape)
                .background(
                    (if (item.color == 0) MaterialTheme.colorScheme.primary
                    else Color(item.color or 0xFF000000.toInt())).copy(alpha = 0.8f),
                ),
        )
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.title,
                color = Color.White,
                fontSize = 15.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = item.timeText,
                color = AGENDA_SECONDARY,
                fontSize = 11.sp,
                maxLines = 1,
            )
        }
    }
}
