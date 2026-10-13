package org.circa.companion

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.lazy.transformedHeight
import androidx.wear.compose.material3.Text
import java.time.ZoneId
import java.util.Locale
import org.circa.companion.data.Phone
import org.circa.companion.data.WeatherNow
import org.circa.companion.data.rememberPhone
import org.circa.companion.model.DetailIcon
import org.circa.companion.model.DetailValues
import org.circa.companion.model.Details
import org.circa.companion.model.Forecast
import org.circa.companion.model.Fmt
import org.circa.companion.model.Wx
import org.circa.companion.ui.CurvedList
import org.circa.companion.ui.Message
import org.circa.symbols.CircaSymbols
import org.circa.symbols.WeatherGlyph
import org.circa.companion.ui.rememberWallNow

/** Weather: current conditions, today's details and the daily forecast from WatchLink's `/weather`. */
class WeatherActivity : CircaActivity() {
    @Composable override fun Content() = WeatherScreen()
}

@Composable
private fun WeatherScreen() {
    val weather by rememberPhone("weather", Phone::readWeather)
    val w = weather ?: return
    val data = w.value
    var details by rememberSaveable { mutableStateOf(false) }
    BackHandler(details) { details = false }
    when {
        w.error != null -> Message("Weather unavailable", "WatchLink is not running", icon = null)
        data == null -> Message("No weather yet", "Set up a weather provider (such as Breezy Weather) in Gadgetbridge on your phone", icon = null)
        details -> TodayDetails(data)
        else -> WeatherList(data) { details = true }
    }
}

@Composable
private fun WeatherList(d: WeatherNow, onDetails: () -> Unit) {
    val now = rememberWallNow(periodMs = 30_000)
    val zone = ZoneId.systemDefault()
    val f = Wx.usesFahrenheit(Locale.getDefault().country)
    val icon = Wx.icon(d.code)
    val days = Forecast.visible(Forecast.parse(d.forecastJson), now, zone)
    CurvedList { spec ->
        item {
            Column(Modifier.fillMaxWidth().padding(top = 8.dp).transformedHeight(this, spec), horizontalAlignment = Alignment.CenterHorizontally) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    WeatherGlyph(icon, 40.dp)
                    Text(Wx.temp(d.tempC, f), style = MaterialTheme.typography.displayMedium)
                }
                Text(d.text?.ifBlank { null }?.replaceFirstChar { it.uppercase() } ?: Wx.fallbackText(icon),
                    style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("H ${Wx.temp(d.hiC, f)}  L ${Wx.temp(d.loC, f)}", style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary)
                    Wx.uvBadge(d.uv)?.let { (uv, uvArgb) ->
                        Text("  ·  $uv", style = MaterialTheme.typography.bodyMedium, color = Color(uvArgb))
                    }
                }
                d.location?.ifBlank { null }?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                }
                Text("Updated ${Fmt.age(d.updatedMs, now)}", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            }
        }
        item {
            // The second entry point above the forecast, in the same header style and size as its "Forecast" line.
            ListHeader(
                Modifier.clickable(onClick = onDetails).transformedHeight(this, spec).testTag("weather_details_button"),
                transformation = SurfaceTransformation(spec),
            ) { Text("Today's details") }
        }
        if (days.isNotEmpty()) {
            item {
                ListHeader(Modifier.transformedHeight(this, spec), transformation = SurfaceTransformation(spec)) { Text("Forecast") }
            }
            items(days.size) { i ->
                val day = days[i]
                Row(
                    Modifier.fillMaxWidth().height(48.dp).transformedHeight(this, spec)
                        .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(24.dp))
                        .padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(Forecast.label(day.dayMs, now, zone), style = MaterialTheme.typography.labelLarge, maxLines = 1,
                        modifier = Modifier.width(52.dp))
                    WeatherGlyph(Wx.icon(day.code), 26.dp)
                    Spacer(Modifier.width(10.dp))
                    Spacer(Modifier.weight(1f))
                    Text(Wx.temp(day.hiC, f), style = MaterialTheme.typography.labelLarge, maxLines = 1)
                    Spacer(Modifier.width(8.dp))
                    Text(Wx.temp(day.loC, f), style = MaterialTheme.typography.labelLarge, maxLines = 1,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(38.dp), textAlign = TextAlign.End)
                }
            }
        }
    }
}

/**
 * "Today's details": one card per available value (Feels like, Wind, Humidity, Chance of rain, UV index, Sunrise,
 * Sunset, Pressure, Visibility, Dew point, Cloud cover), built by [Details.rows]. A value the phone has not sent has
 * no card. The page keeps the forecast page's scaffolding: the same header style, rounded cards and crown scrolling.
 */
@Composable
private fun TodayDetails(d: WeatherNow) {
    val zone = ZoneId.systemDefault()
    val is24 = android.text.format.DateFormat.is24HourFormat(LocalContext.current)
    val f = Wx.usesFahrenheit(Locale.getDefault().country)
    val rows = Details.rows(
        DetailValues(
            feelsC = d.feelsC, windKmh = d.windKmh, windDir = d.windDir, humidity = d.humidity, rainPct = d.rainPct,
            uv = d.uv, sunriseMs = d.sunriseMs, sunsetMs = d.sunsetMs, pressureHpa = d.pressureHpa,
            visibilityM = d.visibilityM, dewC = d.dewC, cloudPct = d.cloudPct,
        ),
        fahrenheit = f, zone = zone, is24h = is24,
    )
    CurvedList(Modifier.testTag("weather_details")) { spec ->
        item {
            ListHeader(Modifier.transformedHeight(this, spec), transformation = SurfaceTransformation(spec)) { Text("Today") }
        }
        items(rows.size) { i ->
            val row = rows[i]
            Row(
                Modifier.fillMaxWidth().heightIn(min = 52.dp).transformedHeight(this, spec)
                    .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(24.dp))
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(detailIcon(row.icon), contentDescription = null, tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(row.label, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    Text(row.value, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        color = row.valueArgb?.let { Color(it) } ?: MaterialTheme.colorScheme.onSurface)
                }
            }
        }
    }
}

/** The Material Symbol of one details row. */
private fun detailIcon(icon: DetailIcon): ImageVector = when (icon) {
    DetailIcon.THERMOSTAT -> CircaSymbols.Filled.DeviceThermostat
    DetailIcon.WIND -> CircaSymbols.Filled.Air
    DetailIcon.HUMIDITY -> CircaSymbols.Filled.HumidityPercentage
    DetailIcon.RAIN -> CircaSymbols.Filled.Umbrella
    DetailIcon.UV -> CircaSymbols.Filled.WbSunny
    DetailIcon.SUNRISE -> CircaSymbols.Filled.WbTwilight
    DetailIcon.SUNSET -> CircaSymbols.Filled.NightsStay
    DetailIcon.PRESSURE -> CircaSymbols.Filled.Compress
    DetailIcon.VISIBILITY -> CircaSymbols.Filled.Visibility
    DetailIcon.DEW_POINT -> CircaSymbols.Filled.DewPoint
    DetailIcon.CLOUD -> CircaSymbols.Filled.Cloud
}
