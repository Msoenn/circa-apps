package org.circa.settings.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsModelTest {
    @Test fun timeoutPresetsAreCircaValues() {
        assertEquals(listOf(5_000, 10_000, 15_000, 30_000, 60_000), ScreenTimeout.OPTIONS)
        assertEquals(
            listOf("5 seconds", "10 seconds", "15 seconds", "30 seconds", "1 minute"),
            ScreenTimeout.OPTIONS.map { ScreenTimeout.label(it) },
        )
        assertEquals(5_000, ScreenTimeout.DEFAULT_MS)
    }

    @Test fun timeoutMigratesOldDefaultsOnce() {
        // Android's old 30 s default (nobody chose it) and the 10 s set by hand both become 5 s.
        assertEquals(5_000, ScreenTimeout.migrationValue(30_000, null))
        assertEquals(5_000, ScreenTimeout.migrationValue(10_000, null))
        // A real choice, including the new 5 s, is left alone.
        assertNull(ScreenTimeout.migrationValue(15_000, null))
        assertNull(ScreenTimeout.migrationValue(5_000, null))
        assertNull(ScreenTimeout.migrationValue(Int.MAX_VALUE, null))
        // Once the flag is set it never touches the value again.
        assertNull(ScreenTimeout.migrationValue(30_000, "1"))
        assertNull(ScreenTimeout.migrationValue(10_000, "1"))
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

    @Test fun tiltWakeHasFourOptions() {
        assertEquals(
            listOf("Off", "Low", "Medium", "High"),
            TiltWake.entries.map { it.label },
        )
        assertEquals(
            listOf("Press or tap only", "Face up and still", "Raise to look", "Easier to wake"),
            TiltWake.entries.map { it.hint },
        )
    }

    @Test fun aodBrightnessDefaultsToNormal() {
        assertEquals(listOf("Low", "Normal", "High"), AodBrightness.entries.map { it.label })
        assertEquals(listOf(0, 1, 2), AodBrightness.entries.map { it.value })
        assertEquals(AodBrightness.NORMAL, AodBrightnessModel.resolve(null))
        assertEquals(AodBrightness.NORMAL, AodBrightnessModel.resolve("7"))
        assertEquals(AodBrightness.NORMAL, AodBrightnessModel.resolve("x"))
        assertEquals(AodBrightness.LOW, AodBrightnessModel.resolve(" 0 "))
        assertEquals(AodBrightness.HIGH, AodBrightnessModel.resolve("2"))
        assertEquals(SettingsPage.DISPLAY, SettingsPage.AOD_BRIGHTNESS.parent)
    }

    @Test fun tiltWakeValuesKeepLegacyNormal() {
        // High keeps the old "Normal" value 2 so a stored choice survives the rename; Medium 3 is new.
        assertEquals(listOf(0, 1, 3, 2), TiltWake.entries.map { it.value })
        assertEquals(TiltWake.HIGH, TiltWakeModel.parse("2"))
        assertEquals(TiltWake.HIGH, TiltWakeModel.parse("normal"))
        assertEquals(TiltWake.MEDIUM, TiltWakeModel.parse("3"))
        assertEquals(TiltWake.MEDIUM, TiltWakeModel.parse("medium"))
    }

    @Test fun tiltWakeUnsetDefaultsToMedium() {
        assertEquals(TiltWake.MEDIUM, TiltWakeModel.resolve(null, null, null))
        assertEquals(TiltWake.MEDIUM, TiltWakeModel.resolve(null, "1", null))
        assertEquals(TiltWake.OFF, TiltWakeModel.resolve(null, "0", null))
        assertEquals(TiltWake.OFF, TiltWakeModel.resolve("7", "0", null))
        assertEquals(TiltWake.MEDIUM, TiltWakeModel.resolve("abc", "1", null))
    }

    @Test fun tiltWakeSecureWinsOverLegacy() {
        assertEquals(TiltWake.OFF, TiltWakeModel.resolve("0", "1", null))
        assertEquals(TiltWake.MEDIUM, TiltWakeModel.resolve("3", "0", null))
        assertEquals(TiltWake.HIGH, TiltWakeModel.resolve("2", "0", null))
    }

    @Test fun storedLowMigratesToMediumOnce() {
        // Low was the old default and was never chosen: it reads as Medium until the flag is set.
        assertEquals(TiltWake.MEDIUM, TiltWakeModel.resolve("1", null, null))
        assertEquals(TiltWake.LOW, TiltWakeModel.resolve("1", null, "1"))
        assertEquals(
            mapOf(TiltWakeModel.SECURE_KEY to "3", TiltWakeModel.MIGRATED_KEY to "1"),
            TiltWakeModel.migrationWrites("1", null),
        )
        assertEquals(emptyMap<String, String>(), TiltWakeModel.migrationWrites("1", "1"))
        // Anything else only records that the migration ran, so a later explicit Low is honoured.
        assertEquals(
            mapOf(TiltWakeModel.MIGRATED_KEY to "1"),
            TiltWakeModel.migrationWrites("2", null),
        )
        assertEquals(
            mapOf(TiltWakeModel.MIGRATED_KEY to "1"),
            TiltWakeModel.migrationWrites(null, null),
        )
    }

    @Test fun tiltWakeLegacyValue() {
        assertEquals("0", TiltWakeModel.legacyValue(TiltWake.OFF))
        assertEquals("1", TiltWakeModel.legacyValue(TiltWake.LOW))
        assertEquals("1", TiltWakeModel.legacyValue(TiltWake.MEDIUM))
        assertEquals("1", TiltWakeModel.legacyValue(TiltWake.HIGH))
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
