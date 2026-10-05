package org.circa.settings

import android.content.Context
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import org.circa.settings.data.QuickSettings
import org.circa.settings.data.SettingsSnapshot
import org.circa.settings.data.SystemSettings
import org.circa.settings.model.Accent
import org.circa.settings.model.GestureRow
import org.circa.settings.model.LongPressAction
import org.circa.settings.model.ProfileField
import org.circa.settings.model.Sex
import org.circa.settings.model.AodBrightness
import org.circa.settings.model.TiltWake
import org.circa.settings.model.Ringer
import org.circa.settings.model.SettingsNav
import org.circa.settings.model.SettingsPage

/**
 * Circa Settings' state (what the launcher's `LauncherController` held for its Settings screen): the
 * page on screen, the page the activity was entered at, the platform snapshot every row draws, and
 * the writes. [finish] leaves the app (BACK from the entry page).
 */
class SettingsController(context: Context, private val finish: () -> Unit) {

    val appContext: Context = context.applicationContext

    private val quickSettings = QuickSettings(context)
    val systemSettings = SystemSettings(context, quickSettings)

    /** The page on screen. */
    val settingsPage: MutableState<SettingsPage> = mutableStateOf(SettingsPage.MAIN)

    /**
     * What the page on screen acts on: a Wi-Fi network key, a Bluetooth address, a package name, ...
     * (null for pages that act on nothing). Kept when going back, so the parent detail page still has it.
     */
    val pageArg: MutableState<String?> = mutableStateOf(null)

    /** Where this visit started (MAIN, or the page a deep link asked for); BACK there leaves. */
    private var entryPage: SettingsPage = SettingsPage.MAIN

    /** Everything the pages draw; re-read after each change and once a second while on screen. */
    val settingsState: MutableState<SettingsSnapshot> = mutableStateOf(systemSettings.read())

    /** Set by the activity: a PIN was stored/removed (the caller of SET_NEW_PASSWORD gets RESULT_OK). */
    var onPinChanged: () -> Unit = {}

    /** The PIN flow succeeded: tell the caller, refresh, and go back to where the flow was opened from. */
    fun pinChanged() {
        onPinChanged()
        refreshSettings()
        settingsBack()
    }

    fun refreshSettings() {
        settingsState.value = systemSettings.read()
    }

    /** An intent arrived (launch or onNewIntent): show [page] and make it the new entry point. */
    fun enterAt(page: SettingsPage, arg: String? = null) {
        entryPage = page
        pageArg.value = arg
        refreshSettings()
        settingsPage.value = page
    }

    fun openSettingsPage(page: SettingsPage, arg: String? = null) {
        refreshSettings()
        if (arg != null) pageArg.value = arg
        settingsPage.value = page
    }

    /** Swipe-right / BACK: one level up, and out of the app from the entry page. */
    fun settingsBack() {
        val target = SettingsNav.back(settingsPage.value, entryPage)
        if (target == null) finish() else settingsPage.value = target
    }

    /** Run a write, then show what the platform really holds. */
    private inline fun change(block: SystemSettings.() -> Unit) {
        systemSettings.block()
        refreshSettings()
    }

    fun setBluetooth(on: Boolean) = change { setBluetooth(on) }
    fun setWifi(on: Boolean) = change { setWifi(on) }
    fun setAirplane(on: Boolean) = change { setAirplane(on) }
    fun setBrightness(level: Int) = change { setBrightness(level) }
    fun setScreenTimeout(ms: Int) = change { setScreenTimeout(ms) }
    fun setAlwaysOn(on: Boolean) = change { setAlwaysOn(on) }
    fun setAodBrightness(level: AodBrightness) = change { setAodBrightness(level) }
    fun setAccent(accent: Accent) = change { setAccent(accent) }
    fun setGesture(row: GestureRow, on: Boolean) = change { setGesture(row, on) }
    fun setTiltWake(level: TiltWake) = change { setTiltWake(level) }
    fun setRinger(ringer: Ringer) = change { setRinger(ringer) }
    fun setTouchVibration(on: Boolean) = change { setTouchVibration(on) }
    fun setLockAfter(ms: Long) = change { setLockAfter(ms) }
    fun setLockWhenTakenOff(on: Boolean) = change { setLockWhenTakenOff(on) }
    fun setBatterySaverTo(on: Boolean) = change { quickSettings.setBatterySaver(on) }
    fun setProfileValue(field: ProfileField, value: Double) = change { setProfileValue(field, value) }
    fun setMaxHrAuto() = change { setMaxHrAuto() }
    fun setSex(sex: Sex?) = change { setSex(sex) }
    fun setLongPress(action: LongPressAction) = change { setLongPress(action) }
}
