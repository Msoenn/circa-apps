package org.circa.settings.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProfileModelTest {

    @Test
    fun parseTreatsZeroAndGarbageAsUnset() {
        val p = ProfileModel.parse("0", "abc", null, "other", "0")
        assertEquals(Profile(), p)
        val q = ProfileModel.parse(" 1985 ", "72.5", "180", "Female", "186")
        assertEquals(Profile(1985, 72.5, 180, Sex.FEMALE, 186), q)
    }

    @Test
    fun encodeMatchesTheExerciseContract() {
        assertEquals("72.5", ProfileModel.encode(ProfileField.WEIGHT, 72.5))
        assertEquals("70.0", ProfileModel.encode(ProfileField.WEIGHT, 70.0))
        assertEquals("1985", ProfileModel.encode(ProfileField.BIRTH_YEAR, 1985.0))
        assertEquals("180", ProfileModel.encode(ProfileField.HEIGHT, 180.0))
        assertEquals("male", Sex.MALE.value)
        assertEquals("female", Sex.FEMALE.value)
    }

    @Test
    fun adjustStepsAndClamps() {
        assertEquals(73.0, ProfileModel.adjust(ProfileField.WEIGHT, 72.5, 1, 2026), 1e-9)
        assertEquals(30.0, ProfileModel.adjust(ProfileField.WEIGHT, 31.0, -10, 2026), 1e-9)
        assertEquals(2021.0, ProfileModel.adjust(ProfileField.BIRTH_YEAR, 2020.0, 5, 2026), 1e-9)
        assertEquals(230.0, ProfileModel.adjust(ProfileField.MAX_HR, 229.0, 3, 2026), 1e-9)
        // A stored weight off the 0.5 grid snaps onto it.
        assertEquals(72.5, ProfileModel.clamp(ProfileField.WEIGHT, 72.3, 2026), 1e-9)
    }

    @Test
    fun startValues() {
        assertEquals(190.0, ProfileModel.startValue(ProfileField.MAX_HR, Profile(), 2026), 1e-9)
        assertEquals(180.0, ProfileModel.startValue(ProfileField.MAX_HR, Profile(birthYear = 1986), 2026), 1e-9)
        assertEquals(175.0, ProfileModel.startValue(ProfileField.MAX_HR, Profile(birthYear = 1986, maxHr = 175), 2026), 1e-9)
        assertEquals(1990.0, ProfileModel.startValue(ProfileField.BIRTH_YEAR, Profile(), 2026), 1e-9)
    }

    @Test
    fun rowLabels() {
        val p = Profile(weightKg = 72.5, heightCm = 180)
        assertEquals("Not set", ProfileModel.rowLabel(ProfileField.BIRTH_YEAR, p))
        assertEquals("72.5 kg", ProfileModel.rowLabel(ProfileField.WEIGHT, p))
        assertEquals("180 cm", ProfileModel.rowLabel(ProfileField.HEIGHT, p))
        assertEquals("Auto", ProfileModel.rowLabel(ProfileField.MAX_HR, p))
        assertEquals("1985", ProfileModel.rowLabel(ProfileField.BIRTH_YEAR, Profile(birthYear = 1985)))
        assertEquals("Not set", Sex.label(null))
    }

    @Test
    fun longPressDefaultsToList() {
        assertEquals(LongPressAction.LIST, LongPressAction.fromRaw(null))
        assertEquals(LongPressAction.LIST, LongPressAction.fromRaw("bogus"))
        assertEquals(LongPressAction.LAST, LongPressAction.fromRaw("last"))
        assertEquals(LongPressAction.POWER, LongPressAction.fromRaw(" power "))
        assertEquals(listOf("list", "last", "power"), LongPressAction.entries.map { it.value })
    }

    @Test
    fun autoDetectDefaultsToOn() {
        assertEquals("circa_auto_detect", ProfileKeys.AUTO_DETECT)
        assertEquals(true, ProfileModel.autoDetectOn(null))
        assertEquals(true, ProfileModel.autoDetectOn("1"))
        assertEquals(true, ProfileModel.autoDetectOn("garbage"))
        assertEquals(false, ProfileModel.autoDetectOn("0"))
        assertEquals(false, ProfileModel.autoDetectOn(" 0 "))
    }

    @Test
    fun sleepTrackingDefaultsToOn() {
        assertEquals("circa_sleep_tracking", ProfileKeys.SLEEP_TRACKING)
        assertEquals(true, ProfileModel.sleepTrackingOn(null))
        assertEquals(true, ProfileModel.sleepTrackingOn("1"))
        assertEquals(true, ProfileModel.sleepTrackingOn("garbage"))
        assertEquals(false, ProfileModel.sleepTrackingOn("0"))
        assertEquals(false, ProfileModel.sleepTrackingOn(" 0 "))
    }

    @Test
    fun usbDataModes() {
        assertEquals("circa_usb_data", ProfileKeys.USB_DATA)
        assertEquals(UsbDataMode.ON, UsbDataMode.parse(null))
        assertEquals(UsbDataMode.ON, UsbDataMode.parse("1"))
        assertEquals(UsbDataMode.ON, UsbDataMode.parse("garbage"))
        assertEquals(UsbDataMode.CHARGING_ONLY, UsbDataMode.parse("0"))
        assertEquals(UsbDataMode.CHARGING_ONLY, UsbDataMode.parse(" 0 "))
        assertEquals(UsbDataMode.CHARGING_WHEN_LOCKED, UsbDataMode.parse("2"))
        assertEquals("Charging only when locked", UsbDataMode.CHARGING_WHEN_LOCKED.label)
        assertEquals("USB debugging off; use Wi-Fi", UsbDataMode.CHARGING_ONLY.hint)
    }

    @Test
    fun heartAlertThresholdsDefaultAndTurnOff() {
        assertEquals("circa_hr_high_bpm", HealthKeys.HR_HIGH_BPM)
        assertEquals("circa_hr_low_bpm", HealthKeys.HR_LOW_BPM)
        assertEquals(120, HeartAlertSettings.high(null))
        assertEquals(120, HeartAlertSettings.high("garbage"))
        assertEquals(130, HeartAlertSettings.high("130"))
        assertEquals(130, HeartAlertSettings.high(" 130 "))
        assertEquals(0, HeartAlertSettings.high("0"))
        assertEquals(40, HeartAlertSettings.low(null))
        assertEquals(35, HeartAlertSettings.low("35"))
        assertEquals(0, HeartAlertSettings.low("0"))
        assertEquals(listOf(0, 100, 110, 120, 130, 140, 150), HeartAlertSettings.HIGH_OPTIONS)
        assertEquals(listOf(0, 35, 40, 45, 50), HeartAlertSettings.LOW_OPTIONS)
        assertEquals("Off", HeartAlertSettings.option(0))
        assertEquals("120 bpm", HeartAlertSettings.option(120))
        assertEquals("High 120 · Low 40", HeartAlertSettings.summary(120, 40))
        assertEquals("Off", HeartAlertSettings.summary(0, 0))
        assertEquals("High off · Low 40", HeartAlertSettings.summary(0, 40))
        assertEquals("High 120 · Low off", HeartAlertSettings.summary(120, 0))
    }

    @Test
    fun phoneLeftAlertDefaultsToOn() {
        assertEquals("circa_phone_left_alert", HealthKeys.PHONE_LEFT_ALERT)
        assertEquals(true, PhoneLeftAlertSetting.on(null))
        assertEquals(true, PhoneLeftAlertSetting.on("1"))
        assertEquals(true, PhoneLeftAlertSetting.on("garbage"))
        assertEquals(false, PhoneLeftAlertSetting.on("0"))
        assertEquals(false, PhoneLeftAlertSetting.on(" 0 "))
    }

    @Test
    fun pagesHangUnderMain() {
        assertEquals(SettingsPage.PROFILE, SettingsPage.HR_ALERTS.parent)
        assertEquals(SettingsPage.HR_ALERTS, SettingsPage.HR_HIGH.parent)
        assertEquals(SettingsPage.HR_ALERTS, SettingsPage.HR_LOW.parent)
        assertEquals(SettingsPage.PROFILE, SettingsNav.back(SettingsPage.HR_ALERTS, SettingsPage.MAIN))
        assertEquals(SettingsPage.MAIN, SettingsPage.PROFILE.parent)
        assertEquals(SettingsPage.PROFILE_MAX_HR, SettingsPage.PROFILE_MAX_HR_VALUE.parent)
        assertEquals(SettingsPage.BUTTONS, SettingsPage.SIDE_LONG_PRESS.parent)
        assertNull(SettingsNav.back(SettingsPage.PROFILE, SettingsPage.PROFILE))
        assertEquals(SettingsPage.PROFILE, SettingsNav.pageFor(null, "profile"))
    }
}
