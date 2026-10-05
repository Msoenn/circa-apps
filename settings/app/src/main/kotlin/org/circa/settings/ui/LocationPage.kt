package org.circa.settings.ui

import org.circa.symbols.CircaSymbols
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.delay
import org.circa.settings.SettingsController
import org.circa.settings.data.LocationData
import org.circa.settings.model.SettingsPage

/** Location: the master switch (apps' own location permissions are under Apps > permissions). */
@Composable
internal fun LocationPage(c: SettingsController) {
    val data = remember { LocationData(c.appContext) }
    var on by remember { mutableStateOf(data.isEnabled()) }
    // The platform applies the change asynchronously (and other apps can flip it): re-read while on screen.
    LaunchedEffect(Unit) { while (true) { on = data.isEnabled(); delay(1000) } }
    ListPage(c, SettingsPage.LOCATION) { spec ->
        toggleRow("location_switch", spec, "Location", on, CircaSymbols.Filled.LocationOn, secondary = onOff(on)) {
            data.setEnabled(it)
            on = data.isEnabled()
        }
    }
}
