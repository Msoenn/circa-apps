package org.circa.launcher.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PhoneStatusTest {
    @Test fun phoneStatusLabels() {
        assertEquals("Phone connected", PhoneStatus.label(true))
        assertEquals("Phone disconnected", PhoneStatus.label(false))
        assertEquals("Connected", PhoneStatus.short(true))
        assertEquals("Disconnected", PhoneStatus.short(false))
    }

    /** `Settings.Secure circa_accent_color` (shared with Circa Settings and the shade) -> accent. */
    @Test fun accentFromArgb() {
        Accent.entries.forEach { assertEquals(it, Accent.fromArgb(it.argb.toInt())) }
        assertEquals(Accent.BLUE, Accent.fromArgb(-7686920))
        assertNull(Accent.fromArgb(0x12345678))
        assertNull(Accent.fromArgb(null))
    }
}
