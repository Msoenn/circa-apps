package org.circa.exercise.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material3.EdgeButton
import androidx.wear.compose.material3.EdgeButtonSize
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.Text
import org.circa.exercise.model.Badge
import org.circa.exercise.model.BadgeKind
import org.circa.exercise.model.Badges
import org.circa.exercise.model.Fmt
import org.circa.exercise.model.Summary
import org.circa.symbols.CircaSymbols
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.random.Random

/** The celebratory summary: confetti, headline, key numbers, badge chips; scroll for details; Done saves. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SummaryScreen(s: Summary, onDone: () -> Unit) {
    Box(Modifier.fillMaxSize().testTag("screen_summary")) {
        CurvedList(
            showTime = false, top = 20.dp,
            edgeButton = {
                EdgeButton(
                    onClick = onDone,
                    modifier = Modifier.testTag("btn_done"),
                    buttonSize = EdgeButtonSize.Medium,
                    colors = ButtonDefaults.buttonColors(containerColor = ACCENT, contentColor = ON_ACCENT_DARK, iconColor = ON_ACCENT_DARK),
                ) {
                    Icon(CircaSymbols.Filled.Check, null, Modifier.size(24.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Done", maxLines = 1)
                }
            },
        ) { spec ->
            item(key = "hero") {
                Column(morph(spec, Modifier.fillMaxWidth()), horizontalAlignment = Alignment.CenterHorizontally) {
                    // Smaller type for the long ones ("First elliptical session!") so they fit the circle on one line.
                    val headline = Badges.headline(s)
                    Text(headline, fontSize = when { headline.length <= 18 -> 15.sp; headline.length <= 22 -> 13.sp; else -> 12.sp },
                        maxLines = 1, softWrap = false, color = Color.White,
                        modifier = Modifier.testTag("headline"))
                    val gpsHero = s.type.gps && s.distanceM >= 10
                    Text(if (gpsHero) "${Fmt.km(s.distanceM)} km" else Fmt.duration(s.activeMs), fontSize = 30.sp,
                        fontWeight = FontWeight.Light, maxLines = 1, softWrap = false, color = Color.White)
                    Text(
                        (if (gpsHero) Fmt.duration(s.activeMs) + "  ·  " else "") + "${s.kcal.toInt()} kcal",
                        fontSize = 12.sp, color = DIM, maxLines = 1, softWrap = false,
                    )
                    // The headline already says the top badge ("First run!", "New longest run!"): no chip repeating it.
                    val chips = s.badges.filter { it.kind != Badges.headlineKind(s) }
                    if (chips.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.padding(horizontal = 12.dp),
                        ) { chips.forEach { BadgeChip(it) } }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text("Scroll for details", fontSize = 10.sp, color = DIM, maxLines = 1)
                }
            }
            item(key = "stats_h") { ListHeader(morph(spec)) { Text("Details", maxLines = 1) } }
            val rows = buildList {
                add("Active time" to Fmt.duration(s.activeMs))
                if (s.type.gps) add("Distance" to "${Fmt.km(s.distanceM)} km")
                if (s.type.gps) add(if (s.type.speedNotPace) "Avg speed" to "${Fmt.speed(s.speedKmh)} km/h" else "Avg pace" to "${Fmt.pace(s.paceSecPerKm)} /km")
                add("Calories" to "${s.kcal.toInt()} kcal")
                add("Avg HR" to if (s.hrAvg > 0) "${s.hrAvg} bpm" else "--")
                add("Max HR" to if (s.hrMax > 0) "${s.hrMax} bpm" else "--")
                if (s.steps > 0) add("Steps" to s.steps.toString())
            }
            for ((k, v) in rows) item(key = "row_$k") { DetailRow(k, v, morph(spec)) }

            item(key = "zones_h") { ListHeader(morph(spec)) { Text("Heart-rate zones", maxLines = 1) } }
            val total = s.zoneMs.drop(1).sum().coerceAtLeast(1L)
            for (z in 5 downTo 1) item(key = "zone_$z") {
                ZoneRow(z, s.zoneMs.getOrElse(z) { 0L }, total, morph(spec))
            }
            if (s.splitsMs.isNotEmpty()) {
                item(key = "splits_h") { ListHeader(morph(spec)) { Text("Splits", maxLines = 1) } }
                s.splitsMs.forEachIndexed { i, ms ->
                    item(key = "split_$i") { DetailRow("km ${i + 1}", Fmt.duration(ms), morph(spec)) }
                }
            }
            if (s.route.size >= 2) {
                item(key = "route_h") { ListHeader(morph(spec)) { Text("Route", maxLines = 1) } }
                item(key = "route") { RouteOutline(s.route, morph(spec, Modifier.size(120.dp))) }
            }
        }
        Confetti()
    }
}

@Composable
private fun BadgeChip(b: Badge) {
    val (icon, tint, bg) = when (b.kind) {
        BadgeKind.STREAK -> Triple(CircaSymbols.Filled.LocalFireDepartment, RED, TONAL)
        BadgeKind.FIRST -> Triple(CircaSymbols.Filled.Check, GREEN, TONAL)
        else -> Triple(CircaSymbols.Filled.Trophy, AMBER, Color(0xFF3B2F00))
    }
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(bg).heightIn(min = 26.dp).padding(horizontal = 9.dp, vertical = 4.dp)
            .testTag("badge_${b.kind.name.lowercase()}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(4.dp))
        Text(b.label, fontSize = 11.sp, color = if (bg == TONAL) Color.White else AMBER, maxLines = 1, softWrap = false)
    }
}

@Composable
private fun DetailRow(label: String, value: String, modifier: Modifier) {
    Row(modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 13.sp, color = DIM, maxLines = 1, softWrap = false, modifier = Modifier.weight(1f))
        Text(value, fontSize = 14.sp, color = Color.White, maxLines = 1, softWrap = false)
    }
}

@Composable
private fun ZoneRow(z: Int, ms: Long, total: Long, modifier: Modifier) {
    Row(modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("Z$z", fontSize = 13.sp, color = ZONE_COLORS[z - 1], maxLines = 1, modifier = Modifier.width(26.dp))
        Box(Modifier.weight(1f).height(8.dp).clip(RoundedCornerShape(4.dp)).background(TRACK)) {
            Box(Modifier.fillMaxWidth((ms.toFloat() / total).coerceIn(0f, 1f)).height(8.dp).clip(RoundedCornerShape(4.dp)).background(ZONE_COLORS[z - 1]))
        }
        Spacer(Modifier.width(8.dp))
        Text(Fmt.zoneTime(ms), fontSize = 13.sp, color = Color.White, maxLines = 1, softWrap = false)
    }
}

/** The route as an outline, north up (equirectangular, scaled to fit). */
@Composable
private fun RouteOutline(route: List<DoubleArray>, modifier: Modifier) {
    Canvas(modifier.testTag("route")) {
        val lat0 = route.map { it[0] }.average()
        val k = cos(Math.toRadians(lat0))
        val xs = route.map { it[1] * k }; val ys = route.map { -it[0] }
        val minX = xs.min(); val maxX = xs.max(); val minY = ys.min(); val maxY = ys.max()
        val span = maxOf(maxX - minX, maxY - minY).takeIf { it > 0 } ?: 1.0
        val pad = 8.dp.toPx()
        val sc = (size.minDimension - 2 * pad) / span
        val ox = (size.width - (maxX - minX) * sc) / 2; val oy = (size.height - (maxY - minY) * sc) / 2
        val path = Path()
        route.indices.forEach { i ->
            val x = (ox + (xs[i] - minX) * sc).toFloat(); val y = (oy + (ys[i] - minY) * sc).toFloat()
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, ACCENT, style = Stroke(3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        val first = route.first(); val last = route.last()
        drawCircle(GREEN, 4.dp.toPx(), Offset((ox + (first[1] * k - minX) * sc).toFloat(), (oy + (-first[0] - minY) * sc).toFloat()))
        drawCircle(RED, 4.dp.toPx(), Offset((ox + (last[1] * k - minX) * sc).toFloat(), (oy + (-last[0] - minY) * sc).toFloat()))
    }
}

private class Piece(val x: Float, val y0: Float, val fall: Float, val spin: Float, val color: Color, val drift: Float)

/** The confetti band: the strip above the summary headline (a fraction of the screen height, see [Confetti]). */
private const val CONFETTI_BAND = 0.18f

/**
 * A short confetti burst in the band above the headline (2.4 s, then gone). The design mock keeps the pieces in the
 * strip over the hero, so the burst stays in the top [CONFETTI_BAND] of the screen: the headline starts ~20 % down
 * the 200 dp screen, and a piece is skipped once its (rotated) box would cross the band edge, so the headline, the
 * numbers and the badges are never covered.
 */
@Composable
private fun Confetti() {
    val progress = remember { Animatable(0f) }
    val pieces = remember {
        val r = Random(7)
        List(36) {
            Piece(r.nextFloat(), -0.25f * r.nextFloat(), 0.35f + r.nextFloat() * 0.35f, r.nextFloat() * 720f - 360f,
                ZONE_COLORS[it % ZONE_COLORS.size], (r.nextFloat() - 0.5f) * 0.15f)
        }
    }
    LaunchedEffect(Unit) { progress.animateTo(1f, tween(2400, easing = LinearEasing)) }
    if (progress.value >= 1f) return
    Canvas(Modifier.fillMaxSize().testTag("confetti")) {
        val t = progress.value
        val w = 5.dp.toPx(); val h = 9.dp.toPx()
        val r = hypot(w, h) / 2f // no corner of the rotated piece reaches farther than this from its centre
        val band = size.height * CONFETTI_BAND
        for (p in pieces) {
            val y = (p.y0 + t * p.fall) * band
            if (y < -r || y > band - r) continue
            val x = (p.x + p.drift * t) * size.width
            rotate(p.spin * t, Offset(x, y)) {
                drawRect(p.color.copy(alpha = 1f - t), Offset(x - w / 2, y - h / 2), Size(w, h))
            }
        }
    }
}
