package org.circa.launcher.ui

import org.circa.symbols.CircaSymbols
import org.circa.symbols.WeatherGlyph
import android.text.format.DateFormat
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sin
import kotlinx.coroutines.delay
import org.circa.launcher.LauncherController
import org.circa.launcher.model.ComplicationFormat
import org.circa.launcher.model.EventView
import org.circa.launcher.model.FaceStyle
import org.circa.launcher.model.Gauge
import org.circa.launcher.model.PhoneComplications
import org.circa.launcher.model.WeatherView
import org.circa.launcher.model.Density as DensityUtil

/** testTag of the watch face root; exposed to uiautomator as `resource-id` (see [LauncherRoot]). */
const val WATCH_FACE_TAG = "watch_face"

/** One tag per face style, so a test can tell which face is drawing. */
const val FACE_DIGITAL_TAG = "face_digital"
const val FACE_CONCENTRIC_TAG = "face_concentric"
const val FACE_ANALOG_TAG = "face_analog"
const val WEATHER_COMPLICATION_TAG = "cplx_weather"
const val EVENT_COMPLICATION_TAG = "cplx_event"

fun faceTag(style: FaceStyle): String = when (style) {
    FaceStyle.DIGITAL -> FACE_DIGITAL_TAG
    FaceStyle.CONCENTRIC -> FACE_CONCENTRIC_TAG
    FaceStyle.ANALOG -> FACE_ANALOG_TAG
}

/** Everything a face draws, so the live face and the picker's previews render the same data. */
data class FaceData(
    val now: LocalDateTime,
    val is24Hour: Boolean,
    val battery: Int?,
    val steps: Int?,
    val heartRate: Int?,
    /** Phone-fed complications (null = no data, so the slot is not drawn at all). */
    val weather: WeatherView? = null,
    val event: EventView? = null,
    /** Taps open Circa Companion; null in the face picker's previews. */
    val onWeatherTap: (() -> Unit)? = null,
    val onEventTap: (() -> Unit)? = null,
    /** A long press on a complication still opens the face picker, as anywhere else on the face. */
    val onLongPress: (() -> Unit)? = null,
)

@Composable
fun rememberFaceData(controller: LauncherController): FaceData {
    val context = LocalContext.current
    val is24Hour = remember(context) { DateFormat.is24HourFormat(context) }
    val now = rememberCurrentTime()
    val nowMs = now.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    val fahrenheit = remember { PhoneComplications.usesFahrenheit(Locale.getDefault().country) }
    return FaceData(
        now = now,
        is24Hour = is24Hour,
        battery = controller.batteryPercent.value,
        steps = controller.health.value.stepsToday,
        heartRate = controller.health.value.hrBpm,
        weather = PhoneComplications.weather(controller.phoneWeather.value, nowMs, fahrenheit),
        event = PhoneComplications.nextEvent(controller.phoneEvents.value, nowMs, ZoneId.systemDefault(), is24Hour),
        onWeatherTap = { controller.openCompanion("WeatherActivity") },
        onEventTap = { controller.openCompanion("AgendaActivity") },
        onLongPress = { controller.openFacePicker() },
    )
}

/**
 * The home page: the chosen face, full screen. A long press opens the face picker (stock's gesture),
 * also exposed as an accessibility long-click action.
 */
@Composable
fun WatchFaceScreen(controller: LauncherController) {
    val data = rememberFaceData(controller)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .testTag(WATCH_FACE_TAG)
            .semantics {
                onLongClick(label = "Change watch face") {
                    controller.openFacePicker()
                    true
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(onLongPress = { controller.openFacePicker() })
            },
    ) {
        WatchFace(controller.face.value, data)
    }
}

/** Draws [style] over the full 200 dp square it is given (the picker scales it down). */
@Composable
fun WatchFace(style: FaceStyle, data: FaceData, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize()) {
        when (style) {
            FaceStyle.DIGITAL -> DigitalFace(data)
            FaceStyle.CONCENTRIC -> ConcentricFace(data)
            FaceStyle.ANALOG -> AnalogFace(data)
        }
    }
}

// ---- B: big digital + three rings ------------------------------------------------------------

/** The time is never wider than this fraction of the screen diameter. */
private const val MAX_TIME_WIDTH_FRACTION = 0.60f
private const val MAX_DIGIT_FONT_SP = 58f
/** AM/PM is drawn at this fraction of the digit size. */
private const val TIME_SUFFIX_SCALE = 0.30f

/**
 * Line height equal to the font size and the leading trimmed: the text node's box is then the
 * glyphs' height instead of a 1.2x line box, so a box corner stays inside the circle where the ink
 * does (the smoke test checks node bounds against the circle).
 */
fun tightStyle(fontSizeSp: Float, weight: FontWeight? = null) = TextStyle(
    fontWeight = weight,
    lineHeight = fontSizeSp.sp,
    lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both),
)

private val TIME_STYLE = TextStyle(
    fontWeight = FontWeight.Light,
    lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both),
)

/**
 * Big light-weight time, the date, and three identical ring complications on one baseline:
 * battery, heart rate, steps.
 */
@Composable
private fun DigitalFace(d: FaceData) {
    Box(Modifier.fillMaxSize().testTag(FACE_DIGITAL_TAG)) {
        Column(
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 27.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            DigitalTime(d)
            // Fixed height: the weather complication's 36 dp tap target overflows it, centred, instead of
            // pushing the date down.
            Row(
                modifier = Modifier.padding(top = 5.dp).height(17.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = ComplicationFormat.formatDate(d.now.toLocalDate()),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 15.sp,
                    style = tightStyle(15f),
                    maxLines = 1,
                )
                d.weather?.let { WeatherComplication(it, d.onWeatherTap, d.onLongPress) }
            }
        }
        d.event?.let { EventComplication(it, d.onEventTap, d.onLongPress, Modifier.align(Alignment.TopCenter).padding(top = EVENT_TOP)) }
        Row(
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 111.dp),
            horizontalArrangement = Arrangement.Center,
        ) {
            HealthRings(d)
        }
    }
}

/** Top of the 36 dp event row; its text is centred, so the text sits at 156 + 18 = 174 dp, under the ring labels. */
private val EVENT_TOP = 156.dp
private const val STALE_ALPHA = 0.45f

/**
 * Tap opens the app; a long press must still reach the face (picker), which `clickable` would swallow (it also
 * clicks on release after a long press). Inert when [onTap] is null (the picker's previews).
 */
private fun Modifier.complicationTap(label: String, onTap: (() -> Unit)?, onLongPress: (() -> Unit)?): Modifier =
    if (onTap == null) this else this
        .pointerInput(onTap, onLongPress) {
            detectTapGestures(onTap = { onTap() }, onLongPress = { onLongPress?.invoke() })
        }
        .semantics {
            role = Role.Button
            onClick(label = label) { onTap(); true }
        }

/**
 * Weather beside the date: glyph + temperature. Dimmed when the reading is more than 3 h old. Tap opens the
 * Weather app. The tap target is 36 dp tall (the text is 15 sp), as wide as its content plus padding.
 */
@Composable
private fun WeatherComplication(w: WeatherView, onTap: (() -> Unit)?, onLongPress: (() -> Unit)?) {
    Row(
        modifier = Modifier
            .requiredHeight(36.dp)
            .testTag(WEATHER_COMPLICATION_TAG)
            .alpha(if (w.stale) STALE_ALPHA else 1f)
            .complicationTap("Open Weather", onTap, onLongPress)
            .semantics(mergeDescendants = true) { contentDescription = w.description }
            .padding(start = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        WeatherGlyph(w.icon, 17.dp)
        Text(
            text = w.text,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 15.sp,
            style = tightStyle(15f),
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.padding(start = 2.dp),
        )
    }
}

/**
 * Next event under the rings: short title, then the time in the accent colour, one line, at most 118 dp wide
 * (the circle is 144 dp wide at this height). Tap opens Agenda.
 */
@Composable
private fun EventComplication(e: EventView, onTap: (() -> Unit)?, onLongPress: (() -> Unit)?, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .requiredHeight(36.dp)
            .testTag(EVENT_COMPLICATION_TAG)
            .widthIn(max = 118.dp)
            .complicationTap("Open Agenda", onTap, onLongPress)
            .semantics(mergeDescendants = true) { contentDescription = e.description },
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = e.title,
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 12.sp,
            style = tightStyle(12f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        Text(
            text = e.whenText,
            color = MaterialTheme.colorScheme.primary,
            fontSize = 12.sp,
            style = tightStyle(12f),
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.padding(start = 5.dp),
        )
    }
}

@Composable
private fun HealthRings(d: FaceData) {
    RingComplication(
        icon = CircaSymbols.Filled.BatteryAndroidFull,
        progress = Gauge.battery(d.battery),
        value = ComplicationFormat.formatBattery(d.battery),
        description = "Battery",
        ringSize = 38.dp,
        modifier = Modifier.width(44.dp),
    )
    RingComplication(
        icon = CircaSymbols.Filled.Favorite,
        progress = Gauge.heartRate(d.heartRate),
        value = ComplicationFormat.formatHeartRate(d.heartRate),
        description = "Heart rate",
        ringSize = 38.dp,
        modifier = Modifier.width(44.dp),
    )
    RingComplication(
        icon = CircaSymbols.Filled.DirectionsWalk,
        progress = Gauge.steps(d.steps),
        value = ComplicationFormat.formatSteps(d.steps),
        description = "Steps",
        ringSize = 38.dp,
        modifier = Modifier.width(44.dp),
    )
}

/**
 * The clock, auto-fitted to [MAX_TIME_WIDTH_FRACTION] of the screen diameter. In 12-hour mode the
 * digits are drawn large and the AM/PM marker at [TIME_SUFFIX_SCALE] of that size beside them.
 */
@Composable
private fun DigitalTime(d: FaceData) {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val parts = remember(d.now.hour, d.now.minute, d.is24Hour) {
        ComplicationFormat.timeParts(d.now.toLocalTime(), d.is24Hour)
    }
    val maxWidthPx = with(density) {
        (DensityUtil.TARGET_SCREEN_WIDTH_DP * MAX_TIME_WIDTH_FRACTION).dp.roundToPx()
    }
    val digitSizeSp = remember(parts, maxWidthPx, density.fontScale) {
        fitDigitFontSize(measurer, parts, maxWidthPx)
    }
    Text(
        text = timeAnnotatedString(parts, digitSizeSp),
        color = MaterialTheme.colorScheme.onSurface,
        style = TIME_STYLE.copy(lineHeight = (digitSizeSp * 0.85f).sp),
        maxLines = 1,
        softWrap = false,
    )
}

private fun timeAnnotatedString(
    parts: ComplicationFormat.TimeParts,
    digitSizeSp: Float,
): AnnotatedString = buildAnnotatedString {
    withStyle(SpanStyle(fontSize = digitSizeSp.sp)) { append(parts.digits) }
    parts.suffix?.let { suffix ->
        withStyle(SpanStyle(fontSize = (digitSizeSp * TIME_SUFFIX_SCALE).sp)) {
            append(' ')
            append(suffix)
        }
    }
}

/** Largest digit size at or below [MAX_DIGIT_FONT_SP] whose clock is at most [maxWidthPx] wide. */
private fun fitDigitFontSize(
    measurer: TextMeasurer,
    parts: ComplicationFormat.TimeParts,
    maxWidthPx: Int,
): Float {
    var size = MAX_DIGIT_FONT_SP
    repeat(3) {
        val width = measurer.measure(
            text = timeAnnotatedString(parts, size),
            style = TIME_STYLE,
            maxLines = 1,
            softWrap = false,
        ).size.width
        if (width == 0 || width <= maxWidthPx) return size
        size *= maxWidthPx.toFloat() / width
    }
    return size
}

// ---- A: concentric ----------------------------------------------------------------------------

private val RING_DIM = Color(0xFF6F7078)

/**
 * Stock's Concentric: the hour big in the middle, two rings of minute numbers turning around it so
 * the current minute always sits at 3 o'clock, where an outlined pill holds the minutes.
 */
@Composable
private fun ConcentricFace(d: FaceData) {
    val measurer = rememberTextMeasurer()
    val onSurface = MaterialTheme.colorScheme.onSurface
    val tickColor = MaterialTheme.colorScheme.outline
    val minute = d.now.minute
    Box(Modifier.fillMaxSize().testTag(FACE_CONCENTRIC_TAG)) {
        Canvas(Modifier.fillMaxSize()) {
            val dp = density
            val c = Offset(size.width / 2f, size.height / 2f)
            drawMinuteTicks(c, 92f * dp, 98f * dp, tickColor, minute)
            drawMinuteTicks(c, 38f * dp, 43f * dp, RING_DIM, minute)
            drawMinuteNumbers(measurer, c, 80f * dp, 13f * dp, onSurface, minute, FontWeight.Medium)
            drawMinuteNumbers(measurer, c, 57f * dp, 12f * dp, RING_DIM, minute, FontWeight.Normal)
        }
        val hour = if (d.is24Hour) d.now.hour else (d.now.hour % 12).let { if (it == 0) 12 else it }
        Text(
            text = "%02d".format(hour),
            color = onSurface,
            fontSize = 54.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.align(Alignment.Center).offset(x = (-4).dp),
        )
        // The minutes pill: outlined and fully inside the dial, over the minute-number ring.
        Box(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .offset(x = (-14).dp)
                .size(width = 58.dp, height = 40.dp)
                .background(Color.Black, RoundedCornerShape(20.dp))
                .border(1.5.dp, onSurface, RoundedCornerShape(20.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "%02d".format(minute),
                color = onSurface,
                fontSize = 23.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

/** Screen angle (degrees, clockwise from 3 o'clock) of minute value [value] when it is [minute] now. */
private fun ringAngle(value: Int, minute: Int): Double = ((minute - value) * 6).toDouble()

private fun DrawScope.drawMinuteTicks(
    c: Offset,
    innerR: Float,
    outerR: Float,
    color: Color,
    minute: Int,
) {
    for (v in 0 until 60) {
        val major = v % 5 == 0
        val a = Math.toRadians(ringAngle(v, minute))
        val r0 = if (major) innerR - (outerR - innerR) * 0.35f else innerR
        drawLine(
            color = if (major) color else color.copy(alpha = 0.55f),
            start = Offset(c.x + (r0 * cos(a)).toFloat(), c.y + (r0 * sin(a)).toFloat()),
            end = Offset(c.x + (outerR * cos(a)).toFloat(), c.y + (outerR * sin(a)).toFloat()),
            strokeWidth = if (major) 1.6f * density else 1f * density,
            cap = StrokeCap.Butt,
        )
    }
}

private fun DrawScope.drawMinuteNumbers(
    measurer: TextMeasurer,
    c: Offset,
    radius: Float,
    textPx: Float,
    color: Color,
    minute: Int,
    weight: FontWeight,
) {
    val style = TextStyle(
        color = color,
        fontSize = (textPx / fontScale / density).sp,
        fontWeight = weight,
    )
    for (v in 0 until 60 step 5) {
        val a = Math.toRadians(ringAngle(v, minute))
        val layout = measurer.measure("%02d".format(v), style)
        drawText(
            layout,
            topLeft = Offset(
                c.x + (radius * cos(a)).toFloat() - layout.size.width / 2f,
                c.y + (radius * sin(a)).toFloat() - layout.size.height / 2f,
            ),
        )
    }
}

// ---- D: analog --------------------------------------------------------------------------------

/** Hour markers, hour and minute hands, and two small complications (battery, steps). */
@Composable
private fun AnalogFace(d: FaceData) {
    val accent = MaterialTheme.colorScheme.primary
    val white = MaterialTheme.colorScheme.onSurface
    val dim = MaterialTheme.colorScheme.outline
    Box(Modifier.fillMaxSize().testTag(FACE_ANALOG_TAG)) {
        Canvas(Modifier.fillMaxSize()) {
            val dp = density
            val c = Offset(size.width / 2f, size.height / 2f)
            for (i in 0 until 60) {
                val a = Math.toRadians(i * 6.0 - 90.0)
                val hour = i % 5 == 0
                val quarter = i % 15 == 0
                val outer = 97f * dp
                val len = when {
                    quarter -> 12f * dp
                    hour -> 8f * dp
                    else -> 3f * dp
                }
                drawLine(
                    color = when {
                        quarter -> white
                        hour -> dim
                        else -> dim.copy(alpha = 0.5f)
                    },
                    start = Offset(c.x + ((outer - len) * cos(a)).toFloat(), c.y + ((outer - len) * sin(a)).toFloat()),
                    end = Offset(c.x + (outer * cos(a)).toFloat(), c.y + (outer * sin(a)).toFloat()),
                    strokeWidth = (if (quarter) 3f else if (hour) 2.2f else 1f) * dp,
                    cap = StrokeCap.Round,
                )
            }
        }
        Row(
            modifier = Modifier.align(Alignment.Center).offset(y = 48.dp),
            horizontalArrangement = Arrangement.spacedBy(30.dp),
        ) {
            RingComplication(
                icon = CircaSymbols.Filled.BatteryAndroidFull,
                progress = Gauge.battery(d.battery),
                value = ComplicationFormat.formatBattery(d.battery),
                description = "Battery",
                ringSize = 32.dp,
                stroke = 2.5.dp,
                iconSize = 15.dp,
                valueSize = 11.sp,
                modifier = Modifier.width(40.dp),
            )
            RingComplication(
                icon = CircaSymbols.Filled.DirectionsWalk,
                progress = Gauge.steps(d.steps),
                value = ComplicationFormat.formatSteps(d.steps),
                description = "Steps",
                ringSize = 32.dp,
                stroke = 2.5.dp,
                iconSize = 15.dp,
                valueSize = 11.sp,
                modifier = Modifier.width(40.dp),
            )
        }
        Canvas(Modifier.fillMaxSize()) {
            val dp = density
            val c = Offset(size.width / 2f, size.height / 2f)
            val hourAngle = Math.toRadians(((d.now.hour % 12) + d.now.minute / 60.0) * 30.0 - 90.0)
            val minuteAngle = Math.toRadians((d.now.minute + d.now.second / 60.0) * 6.0 - 90.0)
            fun hand(angle: Double, length: Float, width: Float, color: Color) {
                val end = Offset(c.x + (length * cos(angle)).toFloat(), c.y + (length * sin(angle)).toFloat())
                // Black underlay: the hands cross the complications and the markers.
                drawLine(Color.Black, c, end, strokeWidth = width + 3f * dp, cap = StrokeCap.Round)
                drawLine(color, c, end, strokeWidth = width, cap = StrokeCap.Round)
            }
            hand(hourAngle, 50f * dp, 5f * dp, white)
            hand(minuteAngle, 76f * dp, 3.5f * dp, accent)
            drawCircle(Color.Black, 6.5f * dp, c)
            drawCircle(accent, 4.5f * dp, c)
        }
    }
}

/** Wall-clock time, refreshed every 20s so the face stays current without burning CPU. */
@Composable
fun rememberCurrentTime(): LocalDateTime {
    return produceState(initialValue = LocalDateTime.now()) {
        while (true) {
            delay(20_000)
            value = LocalDateTime.now()
        }
    }.value
}
