package org.circa.settings.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivacyModelTest {

    @Test
    fun autoRebootDefaultsTo18HoursAndZeroIsOff() {
        assertEquals(64_800_000L, AutoReboot.parse(null))
        assertEquals(64_800_000L, AutoReboot.parse("garbage"))
        assertEquals(64_800_000L, AutoReboot.parse("-5"))
        assertEquals(0L, AutoReboot.parse("0"))
        assertEquals(28_800_000L, AutoReboot.parse(" 28800000 "))
    }

    @Test
    fun autoRebootLabelsAndOptions() {
        assertEquals(listOf(0L, 28_800_000L, 43_200_000L, 64_800_000L, 86_400_000L, 129_600_000L, 259_200_000L), AutoReboot.OPTIONS)
        assertEquals(listOf("Off", "8 hours", "12 hours", "18 hours", "24 hours", "36 hours", "72 hours"), AutoReboot.OPTIONS.map { AutoReboot.option(it) })
        assertEquals("After 18 hours", AutoReboot.summary(AutoReboot.DEFAULT_MS))
        assertEquals("Off", AutoReboot.summary(0L))
        assertEquals("Needs a PIN", AutoReboot.secondary(false, AutoReboot.DEFAULT_MS))
        assertEquals("After 18 hours", AutoReboot.secondary(true, AutoReboot.DEFAULT_MS))
        assertFalse(AutoReboot.enabled(false))
        assertTrue(AutoReboot.enabled(true))
    }

    @Test
    fun wifiAutoOff() {
        assertEquals(900_000L, WifiAutoOff.parse(null))
        assertEquals(0L, WifiAutoOff.parse("0"))
        assertEquals(300_000L, WifiAutoOff.parse("300000"))
        assertEquals(listOf("Off", "5 min", "15 min", "30 min", "60 min"), WifiAutoOff.OPTIONS.map { WifiAutoOff.option(it) })
        assertEquals("After 15 min", WifiAutoOff.summary(900_000L))
        assertEquals("Off", WifiAutoOff.summary(0L))
    }

    @Test
    fun internetCheckTreatsUnsetAsOff() {
        assertEquals(InternetCheck.OFF, InternetCheck.parse(null))
        assertEquals(InternetCheck.OFF, InternetCheck.parse("0"))
        assertEquals(InternetCheck.OFF, InternetCheck.parse("2"))
        assertEquals(InternetCheck.STANDARD, InternetCheck.parse("1"))
        assertEquals("Standard (Google)", InternetCheck.STANDARD.label)
        assertEquals("Hotel sign-in pages won't be detected", InternetCheck.OFF.hint)
    }

    @Test
    fun wifiMacRandomizationOnlyTreatsOneAsOn() {
        assertEquals("non_persistent_mac_randomization_force_enabled", WifiMacRandomization.KEY)
        assertTrue(WifiMacRandomization.parse("1"))
        assertTrue(WifiMacRandomization.parse(" 1 "))
        assertFalse(WifiMacRandomization.parse(null))
        assertFalse(WifiMacRandomization.parse("0"))
        assertFalse(WifiMacRandomization.parse("2"))
        assertEquals("1", WifiMacRandomization.encode(true))
        assertEquals("0", WifiMacRandomization.encode(false))
    }

    @Test
    fun backgroundScanningNeedsBothKeys() {
        assertEquals("wifi_scan_always_enabled", BackgroundScanning.WIFI_KEY)
        assertEquals("ble_scan_always_enabled", BackgroundScanning.BLE_KEY)
        assertTrue(BackgroundScanning.parse("1", "1"))
        assertFalse(BackgroundScanning.parse("1", "0"))
        assertFalse(BackgroundScanning.parse("0", "1"))
        assertFalse(BackgroundScanning.parse(null, "1"))
        assertFalse(BackgroundScanning.parse("1", null))
        assertFalse(BackgroundScanning.parse(null, null))
        assertEquals("1", BackgroundScanning.encode(true))
        assertEquals("0", BackgroundScanning.encode(false))
    }

    @Test
    fun scramblePinPad() {
        assertFalse(ScramblePinPad.parse(null))
        assertFalse(ScramblePinPad.parse("0"))
        assertTrue(ScramblePinPad.parse("1"))
        assertEquals("1", ScramblePinPad.encode(true))
        assertEquals("0", ScramblePinPad.encode(false))
        assertFalse(ScramblePinPad.checked(false, true))
        assertTrue(ScramblePinPad.checked(true, true))
    }

    private fun plan(flag: String?, vararg cur: Pair<String, String>) =
        PrivacyMigration.plan(flag, cur.toMap()::get).toMap()

    @Test
    fun migrationOnFreshWatchWritesEverything() {
        assertEquals(
            mapOf(
                "captive_portal_mode" to "0",
                "ble_scan_always_enabled" to "0",
                "wifi_wakeup_enabled" to "0",
                "non_persistent_mac_randomization_force_enabled" to "1",
                "wifi_scan_always_enabled" to "0",
            ),
            plan(null),
        )
    }

    @Test
    fun migrationRewritesAospDefaultsOfThreeKeysButKeepsOtherChoices() {
        val p = plan(
            null,
            "captive_portal_mode" to "1",
            "ble_scan_always_enabled" to "1",
            "wifi_wakeup_enabled" to "1",
            "non_persistent_mac_randomization_force_enabled" to "0",
            "wifi_scan_always_enabled" to "1",
        )
        assertEquals(mapOf("captive_portal_mode" to "0", "ble_scan_always_enabled" to "0", "wifi_wakeup_enabled" to "0"), p)
    }

    @Test
    fun migrationLeavesOtherExplicitValuesAlone() {
        val p = plan(null, "captive_portal_mode" to "2", "ble_scan_always_enabled" to "0", "wifi_wakeup_enabled" to "0")
        assertFalse(p.containsKey("captive_portal_mode"))
        assertFalse(p.containsKey("ble_scan_always_enabled"))
        assertFalse(p.containsKey("wifi_wakeup_enabled"))
    }

    @Test
    fun migrationDoesNothingOnceFlagged() {
        assertEquals(emptyMap<String, String>(), plan("1"))
        assertEquals("circa_privacy_defaults_v1", PrivacyMigration.FLAG)
    }

    @Test
    fun newPagesHangUnderTheirParents() {
        assertEquals(SettingsPage.SECURITY, SettingsPage.AUTO_REBOOT.parent)
        assertEquals(SettingsPage.CONNECTIVITY, SettingsPage.USB_DATA.parent)
        assertEquals(SettingsPage.WIFI, SettingsPage.WIFI_AUTO_OFF.parent)
        assertEquals(SettingsPage.WIFI, SettingsPage.INTERNET_CHECK.parent)
    }
}
