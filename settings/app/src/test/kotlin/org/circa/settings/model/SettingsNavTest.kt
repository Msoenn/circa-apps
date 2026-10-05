package org.circa.settings.model

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsNavTest {
    @Test fun deepLinksOpenTheirPage() {
        assertEquals(SettingsPage.MAIN, SettingsNav.pageFor("android.settings.SETTINGS"))
        assertEquals(SettingsPage.WIFI, SettingsNav.pageFor("android.settings.WIFI_SETTINGS"))
        assertEquals(SettingsPage.WIFI, SettingsNav.pageFor("android.net.wifi.PICK_WIFI_NETWORK"))
        assertEquals(SettingsPage.STORAGE, SettingsNav.pageFor("android.settings.INTERNAL_STORAGE_SETTINGS"))
        assertEquals(SettingsPage.DATETIME, SettingsNav.pageFor("android.settings.DATE_SETTINGS"))
        assertEquals(SettingsPage.BLUETOOTH, SettingsNav.pageFor("android.settings.BLUETOOTH_SETTINGS"))
        assertEquals(SettingsPage.BT_PAIR, SettingsNav.pageFor("android.settings.BLUETOOTH_PAIRING_SETTINGS"))
        assertEquals(SettingsPage.LANGUAGE, SettingsNav.pageFor("android.settings.LOCALE_SETTINGS"))
        assertEquals(SettingsPage.ACCESSIBILITY, SettingsNav.pageFor("android.settings.ACCESSIBILITY_SETTINGS"))
        assertEquals(SettingsPage.FONT_SIZE, SettingsNav.pageFor("android.settings.TEXT_READING_SETTINGS"))
        assertEquals(SettingsPage.CONNECTIVITY, SettingsNav.pageFor("android.settings.AIRPLANE_MODE_SETTINGS"))
        assertEquals(SettingsPage.CONNECTIVITY, SettingsNav.pageFor("android.settings.WIRELESS_SETTINGS"))
        assertEquals(SettingsPage.DISPLAY, SettingsNav.pageFor("android.settings.DISPLAY_SETTINGS"))
        assertEquals(SettingsPage.SOUND, SettingsNav.pageFor("android.settings.SOUND_SETTINGS"))
        assertEquals(SettingsPage.BATTERY, SettingsNav.pageFor("android.settings.BATTERY_SAVER_SETTINGS"))
        assertEquals(SettingsPage.BATTERY_USAGE, SettingsNav.pageFor("android.intent.action.POWER_USAGE_SUMMARY"))
        assertEquals(SettingsPage.SECURITY, SettingsNav.pageFor("android.settings.SECURITY_SETTINGS"))
        assertEquals(SettingsPage.ABOUT, SettingsNav.pageFor("android.settings.DEVICE_INFO_SETTINGS"))
    }

    @Test fun appDeepLinksCarryThePackage() {
        val none: (String) -> String? = { null }
        assertEquals(SettingsPage.APPS_LIST, SettingsNav.pageFor("android.settings.APPLICATION_SETTINGS"))
        assertEquals(SettingsPage.APPS_LIST, SettingsNav.pageFor("android.settings.MANAGE_ALL_APPLICATIONS_SETTINGS"))
        assertEquals(SettingsPage.NOTIF_APPS, SettingsNav.pageFor("android.settings.ALL_APPS_NOTIFICATION_SETTINGS"))
        assertEquals(SettingsPage.NOTIF_APPS, SettingsNav.pageFor("android.settings.NOTIFICATION_SETTINGS"))
        assertEquals(
            "org.circa.launcher",
            SettingsNav.argFor("android.settings.APPLICATION_DETAILS_SETTINGS", "package:org.circa.launcher", none),
        )
        assertEquals(
            "a.b",
            SettingsNav.argFor("android.settings.APP_NOTIFICATION_SETTINGS", null) {
                if (it == SettingsNav.EXTRA_APP_PACKAGE) "a.b" else null
            },
        )
        assertEquals(
            "c.d",
            SettingsNav.argFor("android.settings.APP_NOTIFICATION_SETTINGS", null) {
                if (it == SettingsNav.EXTRA_APP_PACKAGE_LEGACY) "c.d" else null
            },
        )
        assertEquals(
            "e.f",
            SettingsNav.argFor("android.intent.action.MANAGE_APP_PERMISSIONS", null) {
                if (it == SettingsNav.EXTRA_PACKAGE_NAME) "e.f" else null
            },
        )
        assertNull(SettingsNav.argFor("android.settings.APPLICATION_DETAILS_SETTINGS", "package:", none))
        assertNull(SettingsNav.argFor("android.settings.APPLICATION_DETAILS_SETTINGS", "http://x", none))
        assertNull(SettingsNav.argFor("android.settings.WIFI_SETTINGS", "package:a.b", none))
        // A per-app page without its app falls back to the list.
        assertEquals(SettingsPage.APPS_LIST, SettingsNav.entryPage(SettingsPage.APP_INFO, null))
        assertEquals(SettingsPage.APP_INFO, SettingsNav.entryPage(SettingsPage.APP_INFO, "a.b"))
        assertEquals(SettingsPage.NOTIF_APPS, SettingsNav.entryPage(SettingsPage.NOTIF_APPS, null))
    }

    @Test fun launcherAndUnknownOpenTheMainList() {
        assertEquals(SettingsPage.MAIN, SettingsNav.pageFor("android.intent.action.MAIN"))
        assertEquals(SettingsPage.MAIN, SettingsNav.pageFor(null))
        assertEquals(SettingsPage.MAIN, SettingsNav.pageFor("android.settings.NO_SUCH_SETTINGS"))
    }

    @Test fun pageExtraWinsWhenValid() {
        assertEquals(SettingsPage.TIMEOUT, SettingsNav.pageFor("android.settings.SETTINGS", "timeout"))
        assertEquals(SettingsPage.SOUND, SettingsNav.pageFor("android.settings.SOUND_SETTINGS", "nope"))
    }

    @Test fun backFromTheEntryPageLeaves() {
        assertNull(SettingsNav.back(SettingsPage.MAIN, SettingsPage.MAIN))
        assertNull(SettingsNav.back(SettingsPage.DISPLAY, SettingsPage.DISPLAY))
        assertNull(SettingsNav.back(SettingsPage.ABOUT, SettingsPage.ABOUT))
    }

    @Test fun backBelowTheEntryClimbs() {
        assertEquals(SettingsPage.DISPLAY, SettingsNav.back(SettingsPage.BRIGHTNESS, SettingsPage.DISPLAY))
        assertEquals(SettingsPage.MAIN, SettingsNav.back(SettingsPage.DISPLAY, SettingsPage.MAIN))
        assertEquals(SettingsPage.SYSTEM, SettingsNav.back(SettingsPage.ABOUT, SettingsPage.MAIN))
    }

    /** Every claimed action has a manifest filter at priority 100, and the manifest claims nothing else. */
    @Test fun manifestFiltersMatchTheTable() {
        // Only MainActivity's filters: the pairing receiver also has a priority-100 filter.
        val full = File("src/main/AndroidManifest.xml").readText()
        val manifest = full.substring(full.indexOf("android:name=\".MainActivity\""))
            .substringBefore("</activity>")
        val filters = Regex("""<intent-filter android:priority="100">\s*<action android:name="([^"]+)" />""")
            .findAll(manifest).map { it.groupValues[1] }.toSet()
        assertEquals(SettingsNav.ACTIONS.keys, filters)
    }

    @Test fun lockWhenTakenOffDefaultsOn() {
        assertTrue(SharedKeys.parseLockWhenTakenOff(null))
        assertTrue(SharedKeys.parseLockWhenTakenOff("1"))
        assertFalse(SharedKeys.parseLockWhenTakenOff("0"))
    }

    @Test fun accentFromArgb() {
        Accent.entries.forEach { assertEquals(it, Accent.fromArgb(it.argb.toInt())) }
        assertNull(Accent.fromArgb(0x12345678))
        assertNull(Accent.fromArgb(null))
        assertEquals(Accent.BLUE, Accent.fromArgb(-7686920)) // 0xff8ab4f8 as `settings put secure` takes it
    }

    @Test fun batch3Claims() {
        assertEquals(SettingsPage.BATTERY_USAGE, SettingsNav.pageFor("android.intent.action.POWER_USAGE_SUMMARY"))
        assertEquals(SettingsPage.DEFAULT_APPS, SettingsNav.pageFor("android.settings.HOME_SETTINGS"))
        assertEquals(SettingsPage.DEFAULT_APPS, SettingsNav.pageFor("android.settings.MANAGE_DEFAULT_APPS_SETTINGS"))
        assertEquals(SettingsPage.NOT_ON_WATCH, SettingsNav.pageFor("android.settings.ADD_ACCOUNT_SETTINGS"))
        assertEquals("users", SettingsNav.argFor("android.settings.USER_SETTINGS", null) { null })
        assertEquals("com.foo", SettingsNav.argFor("android.settings.MANAGE_APP_ALL_FILES_ACCESS_PERMISSION", "package:com.foo") { null })
        assertEquals(SettingsPage.APP_PERMS, SettingsNav.entryPage(SettingsPage.APP_PERMS, "com.foo"))
        assertEquals(SettingsPage.APP_INFO, SettingsNav.entryPage(SettingsNav.pageFor("android.settings.APP_LOCALE_SETTINGS"), "com.foo"))
        assertEquals(SettingsPage.BATTERY, SettingsPage.BATTERY_USAGE.parent)
    }
}
