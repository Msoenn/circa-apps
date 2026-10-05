package org.circa.settings.model

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RequestModelTest {
    @Test fun actionsMapToKinds() {
        assertEquals(RequestKind.BT_ENABLE, RequestModel.kindFor("android.bluetooth.adapter.action.REQUEST_ENABLE"))
        assertEquals(RequestKind.BT_DISCOVERABLE, RequestModel.kindFor("android.bluetooth.adapter.action.REQUEST_DISCOVERABLE"))
        assertEquals(RequestKind.WIFI_ENABLE, RequestModel.kindFor("android.net.wifi.action.REQUEST_ENABLE"))
        assertEquals(RequestKind.UNINSTALL, RequestModel.kindFor("android.intent.action.DELETE"))
        assertEquals(RequestKind.UNINSTALL, RequestModel.kindFor("android.intent.action.UNINSTALL_PACKAGE"))
        assertNull(RequestModel.kindFor("android.intent.action.VIEW"))
    }

    @Test fun discoverableSeconds() {
        assertEquals(120, RequestModel.discoverableSeconds(null))
        assertEquals(120, RequestModel.discoverableSeconds(-5))
        assertEquals(300, RequestModel.discoverableSeconds(300))
        assertEquals(3600, RequestModel.discoverableSeconds(0))
        assertEquals(3600, RequestModel.discoverableSeconds(99999))
    }

    @Test fun titles() {
        assertEquals("Turn on Bluetooth?", RequestModel.title(RequestKind.BT_ENABLE))
        assertEquals("Turn off Wi-Fi?", RequestModel.title(RequestKind.WIFI_DISABLE))
        assertEquals("Make watch visible?", RequestModel.title(RequestKind.BT_DISCOVERABLE, 120))
        assertEquals("Nearby devices can find it for 2 minutes", RequestModel.detail(RequestKind.BT_DISCOVERABLE, 120))
        assertNull(RequestModel.detail(RequestKind.BT_ENABLE))
        assertEquals("Uninstall Foo?", RequestModel.title(RequestKind.UNINSTALL, target = "Foo"))
        assertEquals("Foo asks to", RequestModel.caption("Foo"))
        assertNull(RequestModel.caption(null))
    }

    @Test fun packageFromData() {
        assertEquals("com.foo", RequestModel.packageFromData("package:com.foo"))
        assertNull(RequestModel.packageFromData("content://x"))
        assertNull(RequestModel.packageFromData(null))
    }

    @Test fun refusals() {
        assertNull(RequestModel.uninstallRefusal("com.foo", false))
        assertTrue(RequestModel.uninstallRefusal("com.foo", true) != null)
        assertTrue(RequestModel.uninstallRefusal(AppsModel.SELF, false) != null)
    }

    /** RequestActivity declares a priority-100 filter for exactly the actions in the table. */
    @Test fun manifestClaimsTheRequestActions() {
        val full = File("src/main/AndroidManifest.xml").readText()
        val block = full.substring(full.indexOf("android:name=\".RequestActivity\"")).substringBefore("</activity>")
        val filters = Regex("""<intent-filter android:priority="100">\s*<action android:name="([^"]+)" />""")
            .findAll(block).map { it.groupValues[1] }.toSet()
        assertEquals(RequestModel.ACTIONS.keys, filters)
    }
}
