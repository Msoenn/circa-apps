package org.circa.companion

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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
import org.circa.companion.model.Forecast
import org.circa.companion.model.Fmt
import org.circa.companion.model.Wx
import org.circa.companion.ui.CurvedList
import org.circa.companion.ui.Message
import org.circa.symbols.WeatherGlyph
import org.circa.companion.ui.rememberWallNow

/** Weather: current conditions and the daily forecast from WatchLink's `/weather`. */
class WeatherActivity : CircaActivity() {
    @Composable override fun Content() = WeatherScreen()
}

@Composable
private fun WeatherScreen() {
    val weather by rememberPhone("weather", Phone::readWeather)
    val w = weather ?: return
    val data = w.value
    when {
        w.error != null -> Message("Weather unavailable", "WatchLink is not running", icon = null)
        data == null -> Message("No weather yet", "Set up a weather provider (such as Breezy Weather) in Gadgetbridge on your phone", icon = null)
        else -> WeatherList(data)
    }
}

@Composable
private fun WeatherList(d: WeatherNow) {
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
                Text("H ${Wx.temp(d.hiC, f)}  L ${Wx.temp(d.loC, f)}", style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary)
                d.location?.ifBlank { null }?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                }
                Text("Updated ${Fmt.age(d.updatedMs, now)}", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            }
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
