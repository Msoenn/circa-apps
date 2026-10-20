package org.circa.settings.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class BedtimeModelTest {
    @Test fun defaultIs2300To0700EveryDayOff() {
        val d = BedtimeSchedule.decode(null)
        assertFalse(d.enabled)
        assertEquals(23 * 60, d.startMin)
        assertEquals(7 * 60, d.endMin)
        assertEquals(BedtimeSchedule.ALL_DAYS, d.days)
    }

    @Test fun codecMatchesTheShadeFormat() {
        val s = BedtimeSchedule(true, 22 * 60 + 30, 6 * 60 + 45, BedtimeSchedule.WEEKDAYS)
        assertEquals("1;1350;405;31", s.encode())
        assertEquals(s, BedtimeSchedule.decode(s.encode()))
        assertEquals(BedtimeSchedule.DEFAULT, BedtimeSchedule.decode("1;9999;0;127"))
        assertEquals(BedtimeSchedule.DEFAULT, BedtimeSchedule.decode("junk"))
    }

    @Test fun adjustSnapsAndWraps() {
        assertEquals(23 * 60 + 15, BedtimeModel.adjust(23 * 60, 1))
        assertEquals(0, BedtimeModel.adjust(23 * 60 + 45, 1))
        assertEquals(23 * 60 + 45, BedtimeModel.adjust(0, -1))
        assertEquals(7 * 60 + 15, BedtimeModel.adjust(7 * 60 + 7, 1))   // snaps to the 15 min grid first
        assertEquals(7 * 60, BedtimeModel.adjust(7 * 60 + 7, -1))
    }

    @Test fun labels() {
        assertEquals("23:00", BedtimeModel.time(23 * 60, true))
        assertEquals("11:00 PM", BedtimeModel.time(23 * 60, false))
        assertEquals("12:05 AM", BedtimeModel.time(5, false))
        assertEquals("Mon - Fri", BedtimeModel.days(BedtimeSchedule.WEEKDAYS))
        assertEquals("Mon, Wed", BedtimeModel.days(0b101))
        assertEquals("Off", BedtimeModel.scheduleSummary(BedtimeSchedule.DEFAULT, true))
        assertEquals("23:00 - 07:00", BedtimeModel.scheduleSummary(BedtimeSchedule.DEFAULT.copy(enabled = true), true))
    }

    @Test fun pagesSitUnderSound() {
        assertEquals(SettingsPage.SOUND, SettingsPage.BEDTIME.parent)
        assertEquals(SettingsPage.BEDTIME, SettingsNav.pageFor(null, "bedtime"))
    }
}
