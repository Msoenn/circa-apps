package org.circa.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CircaButtonsTest {

    @Test
    fun stemKeyBelongsToTheFrameworkOnlyAtTheCircaConfigValues() {
        // The Circa product overlay (CircaLauncherOverlay): PhoneWindowManager owns the side button.
        assertTrue(CircaButtons.stemHandledByPlatform(CircaButtons.SHORT_PRESS_PRIMARY_CIRCA_NOTIFICATIONS))
        // The older Circa mapping (stem key = app list).
        assertTrue(CircaButtons.stemHandledByPlatform(CircaButtons.SHORT_PRESS_PRIMARY_CIRCA))
        // The stock GSI the watch ran before: the launcher answers the raw key itself.
        assertFalse(CircaButtons.stemHandledByPlatform(2))
        // Stock AOSP default: no stem key rule at all.
        assertFalse(CircaButtons.stemHandledByPlatform(0))
    }

    @Test
    fun showRecentsOpensRecentsAndDismissesFromRecents() {
        assertEquals(Screen.RECENTS, CircaButtons.recentsIntentTarget(Screen.HOME))
        assertEquals(Screen.RECENTS, CircaButtons.recentsIntentTarget(Screen.ALL_APPS))
        assertEquals(Screen.RECENTS, CircaButtons.recentsIntentTarget(Screen.FACE_PICKER))
        assertEquals(Screen.HOME, CircaButtons.recentsIntentTarget(Screen.RECENTS))
    }

    @Test
    fun lockedCrownOnlyUnlocksAndStaysOnTheFace() {
        // Crown on the locked face: the bouncer, then the face (never Recents after the PIN).
        for (screen in Screen.values()) {
            assertEquals(
                CircaButtons.CrownAction.UNLOCK_THEN_FACE,
                CircaButtons.crownAction(locked = true, trayOpen = false, current = screen),
            )
            assertEquals(
                CircaButtons.CrownAction.UNLOCK_THEN_FACE,
                CircaButtons.crownAction(locked = true, trayOpen = true, current = screen),
            )
        }
    }

    @Test
    fun unlockedCrownTogglesFaceAndAppList() {
        assertEquals(CircaButtons.CrownAction.OPEN_RECENTS, CircaButtons.crownAction(false, false, Screen.HOME))
        assertEquals(CircaButtons.CrownAction.SHOW_FACE, CircaButtons.crownAction(false, false, Screen.RECENTS))
        assertEquals(CircaButtons.CrownAction.SHOW_FACE, CircaButtons.crownAction(false, false, Screen.ALL_APPS))
        assertEquals(CircaButtons.CrownAction.SHOW_FACE, CircaButtons.crownAction(false, false, Screen.FACE_PICKER))
        assertEquals(CircaButtons.CrownAction.CLOSE_TRAY, CircaButtons.crownAction(false, true, Screen.HOME))
    }
}
