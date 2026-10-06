package org.circa.exercise.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.Text
import kotlinx.coroutines.delay
import org.circa.exercise.data.GpsState
import org.circa.exercise.data.LiveView
import org.circa.exercise.model.ActivityType
import org.circa.exercise.model.Fmt
import org.circa.exercise.model.Phase
import org.circa.exercise.model.Zones
import org.circa.symbols.CircaSymbols
import kotlin.math.cos
import kotlin.math.sin

/** The crown pages of the live screen. Distance is left out for activities without GPS. */
internal enum class LivePage { TIME, HR, DISTANCE, CALORIES }

internal fun pagesFor(t: ActivityType): List<LivePage> =
    if (t.gps) LivePage.entries else listOf(LivePage.TIME, LivePage.HR, LivePage.CALORIES)

private const val ARC_START = 135f
private const val ARC_SWEEP = 270f

/**
 * Outer ring: fills with the HR (zone scale) in the current zone colour (grey while paused). Inner thin ring: the five
 * zone bands, dimmed, with a white marker at the current HR - always on.
 */
@Composable
internal fun ZoneRings(v: LiveView) {
    Canvas(Modifier.fillMaxSize()) {
        val outerW = 7.dp.toPx(); val innerW = 4.dp.toPx()
        val c = Offset(size.width / 2, size.height / 2)
        val rOuter = size.minDimension / 2 - 4.dp.toPx() - outerW / 2
        val rInner = rOuter - outerW / 2 - 4.dp.toPx() - innerW / 2
        fun arc(r: Float, start: Float, sweep: Float, color: Color, w: Float, round: Boolean = true) = drawArc(
            color, start, sweep, false, Offset(c.x - r, c.y - r), Size(2 * r, 2 * r),
            style = Stroke(w, cap = if (round) StrokeCap.Round else StrokeCap.Butt),
        )
        arc(rOuter, ARC_START, ARC_SWEEP, TRACK, outerW)
        val scale = v.hrScale
        if (scale > 0f) {
            val col = if (v.phase == Phase.PAUSED) Color(0xFF5F6368) else ZONE_COLORS[(v.zone - 1).coerceIn(0, 4)]
            arc(rOuter, ARC_START, ARC_SWEEP * scale, col, outerW)
        }
        val band = ARC_SWEEP / Zones.COUNT
        for (i in 0 until Zones.COUNT) arc(rInner, ARC_START + i * band + 1.2f, band - 2.4f, ZONE_COLORS[i].copy(alpha = 0.55f), innerW, round = false)
        if (v.hr != null) {
            val a = Math.toRadians((ARC_START + ARC_SWEEP * scale).toDouble())
            val p = Offset(c.x + rInner * cos(a).toFloat(), c.y + rInner * sin(a).toFloat())
            drawCircle(Color.Black, 7.dp.toPx(), p)
            drawCircle(Color.White, 5.dp.toPx(), p)
        }
    }
}

@Composable
private fun GpsIcon(state: GpsState, modifier: Modifier) {
    if (state != GpsState.SEARCHING && state != GpsState.FIXED_NEW) return
    var on by remember { mutableStateOf(true) }
    LaunchedEffect(state) { while (state == GpsState.SEARCHING) { delay(500); on = !on }; on = true }
    Icon(
        CircaSymbols.Filled.SatelliteAlt, contentDescription = if (state == GpsState.SEARCHING) "Finding GPS" else "GPS ready",
        tint = if (state == GpsState.SEARCHING) AMBER else GREEN,
        modifier = modifier.size(18.dp).alpha(if (on) 1f else 0.25f).testTag("gps_icon"),
    )
}

@Composable
private fun BigStat(value: String, label: String, size: TextUnit = 40.sp) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, fontSize = if (value.length > 5) (size.value * 0.85f).sp else size, fontWeight = FontWeight.Light,
            maxLines = 1, softWrap = false, color = Color.White, modifier = Modifier.testTag("big_stat"))
        Text(label, fontSize = 11.sp, color = DIM, maxLines = 1, softWrap = false)
    }
}

@Composable
private fun SmallStat(icon: ImageVector?, iconTint: Color, value: String, label: String) {
    Column(Modifier.width(72.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) Icon(icon, null, tint = iconTint, modifier = Modifier.size(14.dp))
            Text(value, fontSize = 17.sp, maxLines = 1, softWrap = false, color = Color.White)
        }
        Text(label, fontSize = 10.sp, color = DIM, maxLines = 1, softWrap = false)
    }
}

/** The live screen: one big stat per crown page inside the two zone rings. */
@Composable
internal fun LiveScreen(v: LiveView, page: Int, onPage: (Int) -> Unit, onKeepAuto: () -> Unit = {}, onDiscardAuto: () -> Unit = {}) {
    val pages = pagesFor(v.type)
    val p = pages[page.coerceIn(0, pages.size - 1)]
    val searching = v.gps == GpsState.SEARCHING
    val focus = remember { FocusRequester() }
    var acc by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Box(
        Modifier.fillMaxSize().testTag("screen_live")
            .onRotaryScrollEvent { e ->
                acc += e.verticalScrollPixels
                val step = 60f
                if (acc > step) { acc = 0f; onPage((page + 1).coerceAtMost(pages.size - 1)) }
                else if (acc < -step) { acc = 0f; onPage((page - 1).coerceAtLeast(0)) }
                true
            }
            .focusRequester(focus).focusable(),
        contentAlignment = Alignment.Center,
    ) {
        ZoneRings(v)
        if (v.phase != Phase.PAUSED && !v.autoPending) GpsIcon(v.gps, Modifier.align(Alignment.TopCenter).offset(y = 24.dp))
        AnimatedContent(targetState = p, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "page") { pg ->
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                // An undecided auto workout: the banner takes the top, the stat moves down a little.
                modifier = Modifier.offset(y = if (v.autoPending) 25.dp else 0.dp).testTag("page_${pg.name.lowercase()}"),
            ) {
                when (pg) {
                    LivePage.TIME -> {
                        // GPS activities: "finding GPS…" under the clock until the first fix (design round 2, GPS mock)
                        BigStat(Fmt.duration(v.activeMs), if (searching) "finding GPS…" else "time")
                        Spacer(Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.Center) {
                            SmallStat(CircaSymbols.Filled.Favorite, RED, v.hr?.toString() ?: "--", "bpm")
                            if (v.type.gps) SmallStat(CircaSymbols.Filled.Distance, DIM, Fmt.km(v.distanceM), "km")
                            else SmallStat(CircaSymbols.Filled.LocalFireDepartment, RED, v.kcal.toInt().toString(), "kcal")
                        }
                    }
                    LivePage.HR -> {
                        BigStat(v.hr?.toString() ?: "--", if (v.zone > 0) "bpm · zone ${v.zone}" else "bpm", 44.sp)
                        Spacer(Modifier.height(6.dp))
                        // The current zone always, plus the zone with the most time besides it.
                        val busiest = (1..5).filter { it != v.zone && v.zoneMs.getOrElse(it) { 0L } >= 1000 }
                            .maxByOrNull { v.zoneMs[it] }
                        val top = (listOfNotNull(v.zone.takeIf { it > 0 && v.zoneMs.getOrElse(it) { 0L } >= 1000 }) +
                            listOfNotNull(busiest)).sorted()
                        Text(
                            if (top.isEmpty()) "No zone time yet" else top.joinToString("  ·  ") { "Z$it ${Fmt.zoneTime(v.zoneMs[it])}" },
                            fontSize = 12.sp, color = DIM, maxLines = 1, softWrap = false,
                        )
                    }
                    LivePage.DISTANCE -> {
                        BigStat(Fmt.km(v.distanceM), if (searching) "km · finding GPS…" else "km")
                        Spacer(Modifier.height(6.dp))
                        if (v.type.speedNotPace) SmallStat(CircaSymbols.Filled.Speed, DIM, Fmt.speed(v.speedKmh), "km/h")
                        else SmallStat(CircaSymbols.Filled.AvgPace, DIM, Fmt.pace(v.paceSecPerKm), "/km")
                    }
                    LivePage.CALORIES -> {
                        BigStat(v.kcal.toInt().toString(), "kcal")
                        Spacer(Modifier.height(6.dp))
                        Row {
                            SmallStat(null, DIM, Fmt.duration(v.activeMs), "time")
                            if (v.steps > 0) SmallStat(CircaSymbols.Filled.Steps, DIM, v.steps.toString(), "steps")
                        }
                    }
                }
            }
        }
        if (v.phase == Phase.PAUSED && !v.autoPending) {
            Text("PAUSED", color = AMBER, fontSize = 12.sp, letterSpacing = 1.5.sp, maxLines = 1,
                modifier = Modifier.align(Alignment.TopCenter).offset(y = 26.dp).testTag("paused_label"))
        }
        if (!v.autoPending) PageDots(pages.size, pages.indexOf(p), Modifier.align(Alignment.BottomCenter).offset(y = (-28).dp))
        if (v.autoPending) AutoBanner(v, onKeepAuto, onDiscardAuto, Modifier.align(Alignment.TopCenter).offset(y = 27.dp))
    }
}

/** "Walk detected" with the same Keep / Discard choice as the notification (no answer = keep after 2 minutes). */
@Composable
private fun AutoBanner(v: LiveView, onKeep: () -> Unit, onDiscard: () -> Unit, modifier: Modifier) {
    Column(modifier.testTag("auto_banner"), horizontalAlignment = Alignment.CenterHorizontally) {
        // Paused (the side button works as ever): the title says so, in the PAUSED label's amber.
        if (v.phase == Phase.PAUSED) Text("PAUSED", fontSize = 12.sp, color = AMBER, letterSpacing = 1.5.sp, maxLines = 1,
            modifier = Modifier.testTag("auto_title"))
        else Text("${v.type.label} detected", fontSize = 12.sp, color = Color.White, maxLines = 1, softWrap = false,
            modifier = Modifier.testTag("auto_title"))
        Spacer(Modifier.height(3.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            BannerButton("Keep", CircaSymbols.Filled.Check, ACCENT, ON_ACCENT_DARK, "btn_auto_keep", onKeep)
            BannerButton("Discard", CircaSymbols.Filled.Delete, TONAL, Color.White, "btn_auto_discard", onDiscard)
        }
    }
}

@Composable
private fun BannerButton(label: String, icon: ImageVector, bg: Color, fg: Color, tag: String, onClick: () -> Unit) {
    Row(
        Modifier.height(28.dp).clip(RoundedCornerShape(50)).background(bg).clickable(onClick = onClick)
            .padding(horizontal = 7.dp).testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = fg, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(2.dp))
        Text(label, fontSize = 11.sp, color = fg, maxLines = 1, softWrap = false)
    }
}

@Composable
private fun PageDots(n: Int, current: Int, modifier: Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        repeat(n) { i ->
            Box(Modifier.size(5.dp).clip(CircleShape).background(if (i == current) Color.White else Color(0xFF5F6368)))
        }
    }
}

/** Swipe right from the live screen: Pause/Resume and End. */
@Composable
internal fun ControlsScreen(v: LiveView, onToggle: () -> Unit, onEnd: () -> Unit) {
    Box(Modifier.fillMaxSize().testTag("screen_controls"), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (v.phase == Phase.PAUSED) {
                Icon(CircaSymbols.Filled.PauseCircle, null, tint = AMBER, modifier = Modifier.size(28.dp))
                Text("PAUSED", color = AMBER, fontSize = 13.sp, letterSpacing = 1.sp, maxLines = 1)
            } else {
                Text(Fmt.duration(v.activeMs), fontSize = 22.sp, fontWeight = FontWeight.Light, maxLines = 1, color = Color.White)
                Text(v.type.label, fontSize = 11.sp, color = DIM, maxLines = 1, softWrap = false)
            }
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                if (v.phase == Phase.PAUSED) DiscButton(CircaSymbols.Filled.PlayArrow, "Resume", TONAL, Color.White, "btn_resume", onClick = onToggle)
                else DiscButton(CircaSymbols.Filled.Pause, "Pause", TONAL, Color.White, "btn_pause", onClick = onToggle)
                DiscButton(CircaSymbols.Filled.Stop, "End", RED, ON_RED, "btn_end", onClick = onEnd)
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** End asks to save. */
@Composable
internal fun ConfirmEndScreen(onSave: () -> Unit, onDiscard: () -> Unit) {
    Box(Modifier.fillMaxSize().testTag("screen_confirm_end"), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Save workout?", fontSize = 16.sp, maxLines = 1, softWrap = false, color = Color.White)
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                DiscButton(CircaSymbols.Filled.Delete, "Discard", TONAL, Color.White, "btn_discard", onClick = onDiscard)
                DiscButton(CircaSymbols.Filled.Check, "Save", ACCENT, ON_ACCENT_DARK, "btn_save", onClick = onSave)
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}

/** Discarding is final, so it asks once more. */
@Composable
internal fun ConfirmDiscardScreen(onKeep: () -> Unit, onDiscard: () -> Unit) {
    Box(Modifier.fillMaxSize().testTag("screen_confirm_discard"), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Discard workout?", fontSize = 16.sp, maxLines = 1, softWrap = false, color = Color.White)
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                DiscButton(CircaSymbols.Filled.Undo, "Keep", TONAL, Color.White, "btn_keep", onClick = onKeep)
                DiscButton(CircaSymbols.Filled.Delete, "Discard", RED, ON_RED, "btn_discard_confirm", onClick = onDiscard)
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}

/** "Start last exercise": a 3 s countdown with Cancel. */
@Composable
internal fun CountdownScreen(type: ActivityType, onGo: () -> Unit, onCancel: () -> Unit) {
    var left by remember { mutableIntStateOf(3) }
    var frac by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(type) {
        val t0 = System.currentTimeMillis()
        while (true) {
            val e = System.currentTimeMillis() - t0
            frac = (e / 3000f).coerceAtMost(1f)
            left = 3 - (e / 1000).toInt()
            if (e >= 3000) break
            delay(40)
        }
        onGo()
    }
    Box(Modifier.fillMaxSize().testTag("screen_countdown"), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val w = 6.dp.toPx(); val inset = 5.dp.toPx() + w / 2
            val s = Size(size.width - 2 * inset, size.height - 2 * inset)
            drawArc(TRACK, 0f, 360f, false, Offset(inset, inset), s, style = Stroke(w))
            drawArc(ACCENT, -90f, 360f * frac, false, Offset(inset, inset), s, style = Stroke(w, cap = StrokeCap.Round))
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconDisc(type.icon(), type.color(), 22.dp)
                Spacer(Modifier.width(6.dp))
                Text(type.label, fontSize = 14.sp, maxLines = 1, softWrap = false, color = Color.White)
            }
            Text(left.coerceAtLeast(1).toString(), fontSize = 56.sp, fontWeight = FontWeight.Light, color = Color.White,
                maxLines = 1, modifier = Modifier.testTag("countdown_value"))
            DiscButton(CircaSymbols.Filled.Close, "Cancel", TONAL, Color.White, "btn_cancel", size = 56.dp, onClick = onCancel)
        }
    }
}
