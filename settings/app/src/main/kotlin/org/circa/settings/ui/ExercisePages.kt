package org.circa.settings.ui

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import org.circa.settings.SettingsController
import org.circa.settings.model.LongPressAction
import org.circa.settings.model.ProfileField
import org.circa.settings.model.ProfileModel
import org.circa.settings.model.SettingsPage
import org.circa.settings.model.Sex
import org.circa.symbols.CircaSymbols
import java.util.Calendar
import kotlin.math.abs
import kotlin.math.roundToInt

/*
 * Profile and Buttons (Exercise, 2026-10-04; settings/README.md). The profile is entered on
 * the watch only: Gadgetbridge sends no user profile to a Bangle.js, which is what WatchLink impersonates.
 */

private fun currentYear(): Int = Calendar.getInstance().get(Calendar.YEAR)

@Composable
internal fun ProfilePage(c: SettingsController) = ListPage(c, SettingsPage.PROFILE) { spec ->
    val p = c.settingsState.value.profile
    listOf(
        ProfileField.BIRTH_YEAR to CircaSymbols.Outlined.Cake,
        ProfileField.WEIGHT to CircaSymbols.Outlined.MonitorWeight,
        ProfileField.HEIGHT to CircaSymbols.Outlined.Height,
    ).forEach { (f, icon) ->
        navRow("profile_${f.id}", spec, f.label, icon, secondary = ProfileModel.rowLabel(f, p)) {
            c.openSettingsPage(SettingsPage.PROFILE_VALUE, f.id)
        }
    }
    navRow("profile_sex", spec, "Sex", CircaSymbols.Outlined.Person, secondary = Sex.label(p.sex)) {
        c.openSettingsPage(SettingsPage.PROFILE_SEX)
    }
    navRow(
        "profile_max_hr", spec, "Max heart rate", CircaSymbols.Outlined.Favorite,
        secondary = ProfileModel.rowLabel(ProfileField.MAX_HR, p),
    ) { c.openSettingsPage(SettingsPage.PROFILE_MAX_HR) }
    noteItem("profile_note", "Exercise uses these for calories and heart-rate zones")
}

@Composable
internal fun ProfileSexPage(c: SettingsController) = ListPage(c, SettingsPage.PROFILE_SEX) { spec ->
    val current = c.settingsState.value.profile.sex
    Sex.entries.forEach { s ->
        radioRow("sex_${s.value}", spec, s.label, current == s) { c.setSex(s) }
    }
    radioRow("sex_unset", spec, "Not set", current == null) { c.setSex(null) }
}

@Composable
internal fun ProfileMaxHrPage(c: SettingsController) = ListPage(c, SettingsPage.PROFILE_MAX_HR) { spec ->
    val p = c.settingsState.value.profile
    radioRow("max_hr_auto", spec, "Auto", p.maxHr == null, secondary = "Estimated from age") { c.setMaxHrAuto() }
    radioRow(
        "max_hr_custom", spec, "Custom", p.maxHr != null,
        secondary = p.maxHr?.let { "$it bpm" } ?: "Set your own",
    ) { c.openSettingsPage(SettingsPage.PROFILE_MAX_HR_VALUE, ProfileField.MAX_HR.id) }
}

/**
 * One number: the crown or -/+ change it, the check button saves and goes back (BACK / swipe right leaves
 * without saving). Laid out for the 200 dp circle: label, value, then one row of three round buttons.
 */
@Composable
internal fun ProfileValuePage(c: SettingsController, page: SettingsPage) {
    val field = ProfileField.entries.firstOrNull { it.id == c.pageArg.value }
        ?: if (page == SettingsPage.PROFILE_MAX_HR_VALUE) ProfileField.MAX_HR else ProfileField.WEIGHT
    val year = remember { currentYear() }
    var value by remember(field) {
        mutableDoubleStateOf(ProfileModel.startValue(field, c.settingsState.value.profile, year))
    }
    PageFrame(c, page) {
        val focus = remember { FocusRequester() }
        LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .testTag(settingsPageTag(page))
                .onRotaryScrollEvent { e ->
                    val px = e.verticalScrollPixels
                    val clicks = (abs(px) / 40f).roundToInt().coerceAtLeast(1) * (if (px >= 0) 1 else -1)
                    value = ProfileModel.adjust(field, value, clicks, year)
                    true
                }
                .focusRequester(focus)
                .focusable(),
        ) {
            Column(
                modifier = Modifier.align(Alignment.Center).padding(top = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    field.label,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        ProfileModel.formatNumber(field, value),
                        style = MaterialTheme.typography.displaySmall,
                        modifier = Modifier.testTag("profile_value"),
                    )
                    if (field.unit.isNotEmpty()) {
                        Text(
                            field.unit,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 6.dp),
                        )
                    }
                }
                Row(
                    modifier = Modifier.padding(top = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    RoundIconButton("profile_down", CircaSymbols.Filled.Remove, "Less", tonal = true) {
                        value = ProfileModel.adjust(field, value, -1, year)
                    }
                    RoundIconButton("profile_save", CircaSymbols.Filled.Check, "Save", tonal = false) {
                        c.setProfileValue(field, value)
                        // Saving a custom max HR from Max heart rate > Custom returns to the Profile list.
                        if (page == SettingsPage.PROFILE_MAX_HR_VALUE) c.openSettingsPage(SettingsPage.PROFILE)
                        else c.settingsBack()
                    }
                    RoundIconButton("profile_up", CircaSymbols.Filled.Add, "More", tonal = true) {
                        value = ProfileModel.adjust(field, value, 1, year)
                    }
                }
            }
        }
    }
}

@Composable
private fun RoundIconButton(tag: String, icon: ImageVector, description: String, tonal: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.size(52.dp).testTag(tag),
        colors = if (tonal) ButtonDefaults.filledTonalButtonColors() else ButtonDefaults.buttonColors(),
        contentPadding = PaddingValues(0.dp),
        shape = CircleShape,
        label = {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = description, modifier = Modifier.size(ICON))
            }
        },
    )
}

@Composable
internal fun ButtonsPage(c: SettingsController) = ListPage(c, SettingsPage.BUTTONS) { spec ->
    navRow(
        "side_long_press", spec, "Side long press",
        secondary = c.settingsState.value.longPress.label,
    ) { c.openSettingsPage(SettingsPage.SIDE_LONG_PRESS) }
    noteItem("buttons_note", "Crown long press: power menu")
}

@Composable
internal fun SideLongPressPage(c: SettingsController) = ListPage(c, SettingsPage.SIDE_LONG_PRESS) { spec ->
    val current = c.settingsState.value.longPress
    LongPressAction.entries.forEach { a ->
        radioRow("long_press_${a.value}", spec, a.option, current == a, secondary = a.hint) { c.setLongPress(a) }
    }
}
