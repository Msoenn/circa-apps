package org.circa.settings.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppsModelTest {
    private fun app(
        pkg: String, label: String = pkg, system: Boolean = false, launchable: Boolean = true,
        enabled: Boolean = true, versionName: String? = "1.0",
    ) = AppEntry(pkg, label, versionName, 7, system, enabled, launchable, 10_000)

    @Test fun defaultListHidesNonLaunchableSystemPackages() {
        val all = listOf(
            app("com.android.providers.media", "Media", system = true, launchable = false),
            app("org.example.b", "beta"),
            app("org.example.a", "Alpha"),
            app("org.example.svc", "Svc", launchable = false), // user-installed: shown
            app("com.android.camera", "Camera", system = true),
        )
        assertEquals(listOf("Alpha", "beta", "Camera", "Svc"), AppsModel.visible(all, false).map { it.label })
        assertEquals(5, AppsModel.visible(all, true).size)
    }

    @Test fun secondaryLine() {
        assertEquals("Disabled", AppsModel.secondary(app("a", enabled = false)))
        assertEquals("2.1", AppsModel.secondary(app("a", versionName = "2.1 (build 9)")))
        assertEquals("System", AppsModel.secondary(app("a", system = true, versionName = null)))
        assertEquals("Version 7", AppsModel.secondary(app("a", versionName = "")))
        assertEquals("2.1 (7)", AppsModel.versionLine("2.1", 7))
        assertEquals("7", AppsModel.versionLine(null, 7))
    }

    @Test fun actionsOfferedOnlyWhereSafe() {
        val user = app("org.example.a")
        val sys = app("com.android.camera", system = true)
        assertTrue(AppsModel.canUninstall(user))
        assertFalse(AppsModel.canUninstall(sys))
        assertTrue(AppsModel.canDisable(sys))
        // A user-installed org.circa.* app may be disabled; the preinstalled one may not.
        assertTrue(AppsModel.canDisable(app("org.circa.sensorprobe")))
        assertFalse(AppsModel.canDisable(app("org.circa.launcher", system = true)))
        listOf("android", "com.android.systemui", "com.android.settings", "com.android.phone",
            "org.circa.launcher", "org.circa.settings", "com.android.providers.settings").forEach {
            assertFalse(it, AppsModel.canDisable(app(it, system = true)))
        }
        val self = app(AppsModel.SELF)
        assertFalse(AppsModel.canDisable(self) || AppsModel.canUninstall(self) || AppsModel.canForceStop(self))
        // "android." prefix must not swallow other packages: exact name only.
        assertTrue(AppsModel.canDisable(app("android.ext.services2", system = true)))
    }

    @Test fun permissionsGroupLikeStock() {
        val perms = listOf(
            RuntimePerm("android.permission.ACTIVITY_RECOGNITION", false, false),
            RuntimePerm("android.permission.BODY_SENSORS", true, false),
            RuntimePerm("android.permission.health.READ_HEART_RATE", false, false),
            RuntimePerm("android.permission.ACCESS_FINE_LOCATION", false, true),
            RuntimePerm("com.example.CUSTOM", false, false),
        )
        val groups = PermGroups.group(perms) { if (it == "com.example.CUSTOM") "Custom" else null }
        assertEquals(listOf("Location", "Body sensors", "Physical activity", "Custom"), groups.map { it.label })
        val body = groups[1]
        assertTrue(body.granted)
        assertEquals(listOf("android.permission.health.READ_HEART_RATE"), body.toChange(true).map { it.name })
        assertFalse(groups[0].changeable)
        assertEquals("Other", PermGroups.labelFor("x.y.Z"))
    }

    @Test fun notificationSwitch() {
        assertTrue(NotifModel.changeable(requestsPost = true, fixed = false))
        assertFalse(NotifModel.changeable(requestsPost = true, fixed = true))
        assertFalse(NotifModel.changeable(requestsPost = false, fixed = false))
        assertEquals("Not requested by app", NotifModel.secondary(false, requestsPost = false, fixed = false))
        assertEquals("Set by system", NotifModel.secondary(true, requestsPost = true, fixed = true))
        assertEquals("Off", NotifModel.secondary(false, requestsPost = true, fixed = false))
        assertTrue(NotifModel.listed(app("org.example.svc", launchable = false, system = true), requestsPost = true))
        assertFalse(NotifModel.listed(app("com.android.x", launchable = false, system = true), requestsPost = false))
        assertFalse(NotifModel.listed(app(AppsModel.SELF), true))
        // a system app that never posted (Ad Privacy) or core plumbing (Android System) is not listed
        assertFalse(NotifModel.listed(app("org.example.svc", launchable = false, system = true), requestsPost = true, posted = false))
        assertFalse(NotifModel.listed(app("android", launchable = false, system = true), requestsPost = true, posted = true))
    }
}
