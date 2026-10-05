package org.circa.settings.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsModelTest {
    @Test fun timeoutPresetsAreStockValues() {
        assertEquals(listOf(15_000, 30_000, 60_000, 120_000, 300_000), ScreenTimeout.OPTIONS)
        assertEquals(
            listOf("15 seconds", "30 seconds", "1 minute", "2 minutes", "5 minutes"),
            ScreenTimeout.OPTIONS.map { ScreenTimeout.label(it) },
        )
    }

    @Test fun timeoutNeverAndCustom() {
        assertEquals("Never", ScreenTimeout.label(Int.MAX_VALUE))
        assertEquals("Never", ScreenTimeout.label(0))
        assertEquals("45 seconds", ScreenTimeout.label(45_000))
        assertFalse(ScreenTimeout.isPreset(Int.MAX_VALUE))
        assertTrue(ScreenTimeout.isPreset(30_000))
    }

    @Test fun lockTimeoutLabels() {
        assertEquals("Immediately", LockTimeout.label(0))
        assertEquals("5 seconds", LockTimeout.label(5_000))
        assertEquals("1 minute", LockTimeout.label(60_000))
        assertEquals("5 minutes", LockTimeout.label(300_000))
    }

    @Test fun ringerRoundTrip() {
        Ringer.entries.forEach { assertEquals(it, Ringer.fromMode(it.mode)) }
        assertEquals(Ringer.SOUND, Ringer.fromMode(99))
    }

    private val wearKeys = setOf(
        SettingKey(SettingsTable.GLOBAL, "ambient_touch_to_wake"),
        SettingKey(SettingsTable.SECURE, "wake_gesture_enabled"),
    )

    @Test fun gestureResolvePrefersWearKey() {
        val k = GestureModel.resolve(Gesture.TOUCH_TO_WAKE) { it in wearKeys }
        assertEquals("ambient_touch_to_wake", k?.name)
    }

    @Test fun gestureFallsBackToAosp() {
        val only = setOf(
            SettingKey(SettingsTable.SECURE, "double_tap_to_wake"),
            SettingKey(SettingsTable.SECURE, "wake_gesture_enabled"),
        )
        assertEquals("double_tap_to_wake", GestureModel.resolve(Gesture.TOUCH_TO_WAKE) { it in only }?.name)
    }

    @Test fun missingKeysHideTheRow() {
        assertNull(GestureModel.resolve(Gesture.TOUCH_TO_WAKE) { false })
        assertTrue(GestureModel.rows({ false }, { null }).isEmpty())
    }

    @Test fun rowsReadValues() {
        val rows = GestureModel.rows({ it in wearKeys }) { "0" }
        assertEquals(1, rows.size)
        assertFalse(rows.first { it.gesture == Gesture.TOUCH_TO_WAKE }.on)
    }

    @Test fun tiltWakeUnsetIsOff() = assertEquals(TiltWake.OFF, TiltWakeModel.resolve(null, null))
    @Test fun tiltWakeLegacyOneIsLow() = assertEquals(TiltWake.LOW, TiltWakeModel.resolve(null, "1"))
    @Test fun tiltWakeLegacyZeroIsOff() = assertEquals(TiltWake.OFF, TiltWakeModel.resolve(null, "0"))
    @Test fun tiltWakeSecureWinsOverLegacy() = assertEquals(TiltWake.NORMAL, TiltWakeModel.resolve("2", "0"))
    @Test fun tiltWakeGarbageSecureFallsBack() {
        assertEquals(TiltWake.LOW, TiltWakeModel.resolve("abc", "1"))
        assertEquals(TiltWake.OFF, TiltWakeModel.resolve("7", "0"))
    }
    @Test fun tiltWakeLegacyValue() {
        assertEquals("0", TiltWakeModel.legacyValue(TiltWake.OFF))
        assertEquals("1", TiltWakeModel.legacyValue(TiltWake.LOW))
        assertEquals("1", TiltWakeModel.legacyValue(TiltWake.NORMAL))
    }

    @Test fun parseAndEncode() {
        assertTrue(GestureModel.parse("1"))
        assertFalse(GestureModel.parse("0"))
        assertFalse(GestureModel.parse(null))
        assertEquals("1", GestureModel.encode(true))
        assertEquals("0", GestureModel.encode(false))
    }

    @Test fun pageTree() {
        assertNull(SettingsPage.MAIN.parent)
        assertEquals(SettingsPage.DISPLAY, SettingsPage.BRIGHTNESS.parent)
        assertEquals(SettingsPage.SYSTEM, SettingsPage.RESTART.parent)
        assertEquals(2, SettingsPage.ABOUT.depth)
        SettingsPage.entries.filter { it != SettingsPage.MAIN }.forEach { assertTrue(it.depth >= 1) }
    }

    @Test fun brightnessAdjustClamps() {
        assertEquals(BrightnessLevel.MIN, BrightnessLevel.adjust(6, -5))
        assertEquals(BrightnessLevel.MAX, BrightnessLevel.adjust(250, 5))
        assertEquals(100 + BrightnessLevel.STEP, BrightnessLevel.adjust(100, 1))
        assertEquals(100, BrightnessLevel.percent(255))
    }

    @Test fun lockWhenTakenOffRowNeedsACredential() {
        assertFalse(LockWhenTakenOffRow.enabled(false))
        assertTrue(LockWhenTakenOffRow.enabled(true))
        assertEquals("Needs a PIN", LockWhenTakenOffRow.secondary(false, true))
        assertEquals("Needs a PIN", LockWhenTakenOffRow.secondary(false, false))
        assertEquals("On", LockWhenTakenOffRow.secondary(true, true))
        assertEquals("Off", LockWhenTakenOffRow.secondary(true, false))
        // Without a PIN the default-on preference must not show as checked.
        assertFalse(LockWhenTakenOffRow.checked(false, true))
        assertTrue(LockWhenTakenOffRow.checked(true, true))
        assertFalse(LockWhenTakenOffRow.checked(true, false))
    }
}
