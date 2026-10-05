package org.circa.symbols

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.wear.compose.material3.Icon

private val SUN = Color(0xFFFDD663)
private val CLOUD_DIM = Color(0xFF8F9099)
private val RAIN = Color(0xFF8AB4F8)
private val SNOW = Color(0xFFFFFFFF)
private val BOLT = Color(0xFFFDD663)
private val FOG = Color(0xFFA9ADB5)

/**
 * The weather glyph for [icon], a Material Symbols (Rounded) [Icon] of [size]. Shared by the launcher
 * watch-face complication and the companion Weather app (it used to be a hand-drawn Canvas glyph
 * duplicated in both). The tint keeps the dominant colour of the hand-drawn glyph it replaces.
 */
@Composable
internal fun WeatherGlyph(icon: WxIcon, size: Dp, modifier: Modifier = Modifier) {
    val glyph = when (icon) {
        WxIcon.CLEAR -> CircaSymbols.Filled.Sunny to SUN
        WxIcon.PARTLY -> CircaSymbols.Filled.PartlyCloudyDay to SUN
        WxIcon.CLOUDS -> CircaSymbols.Filled.Cloud to CLOUD_DIM
        WxIcon.RAIN -> CircaSymbols.Filled.Rainy to RAIN
        WxIcon.STORM -> CircaSymbols.Filled.Thunderstorm to BOLT
        WxIcon.SNOW -> CircaSymbols.Filled.WeatherSnowy to SNOW
        WxIcon.FOG -> CircaSymbols.Filled.Foggy to FOG
    }
    Icon(glyph.first, contentDescription = null, tint = glyph.second, modifier = modifier.size(size))
}
