package org.circa.settings.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WifiModelTest {
    @Test fun securityFromCapabilities() {
        assertEquals(WifiSecurity.OPEN, WifiSecurity.fromCapabilities("[ESS]"))
        assertEquals(WifiSecurity.PSK, WifiSecurity.fromCapabilities("[WPA2-PSK-CCMP][RSN-PSK-CCMP][ESS]"))
        assertEquals(WifiSecurity.PSK_SAE, WifiSecurity.fromCapabilities("[RSN-PSK+SAE-CCMP][ESS][MFPC]"))
        assertEquals(WifiSecurity.SAE, WifiSecurity.fromCapabilities("[RSN-SAE-CCMP][ESS][MFPR]"))
        assertEquals(WifiSecurity.OWE, WifiSecurity.fromCapabilities("[RSN-OWE-CCMP][ESS]"))
        assertEquals(WifiSecurity.OPEN, WifiSecurity.fromCapabilities("[ESS][OWE_TRANSITION]"))
        assertEquals(WifiSecurity.EAP, WifiSecurity.fromCapabilities("[RSN-EAP/SHA1-CCMP][ESS]"))
        assertEquals(WifiSecurity.WEP, WifiSecurity.fromCapabilities("[WEP][ESS]"))
    }

    @Test fun securityFromConfigTypes() {
        assertEquals(WifiSecurity.PSK, WifiSecurity.fromSecurityTypes(listOf(2, 4)))
        assertEquals(WifiSecurity.PSK, WifiSecurity.fromSecurityTypes(listOf(2)))
        assertEquals(WifiSecurity.SAE, WifiSecurity.fromSecurityTypes(listOf(4)))
        assertEquals(WifiSecurity.OPEN, WifiSecurity.fromSecurityTypes(listOf(0, 6)))
        assertEquals(WifiSecurity.OPEN, WifiSecurity.fromSecurityTypes(listOf(0)))
        assertEquals(WifiSecurity.OWE, WifiSecurity.fromSecurityTypes(listOf(6)))
    }

    @Test fun unquote() {
        assertEquals("Home", WifiModel.unquote("\"Home\""))
        assertEquals("Home", WifiModel.unquote("Home"))
        assertNull(WifiModel.unquote("<unknown ssid>"))
        assertNull(WifiModel.unquote("\"\""))
        assertNull(WifiModel.unquote(null))
    }

    @Test fun levels() {
        assertEquals(3, WifiModel.level(-40))
        assertEquals(2, WifiModel.level(-60))
        assertEquals(1, WifiModel.level(-70))
        assertEquals(0, WifiModel.level(-90))
    }

    @Test fun entriesDedupeSortAndState() {
        val scans = listOf(
            WifiScan("Cafe", -80, WifiSecurity.OPEN),
            WifiScan("Home", -70, WifiSecurity.PSK),
            WifiScan("Home", -50, WifiSecurity.PSK),
            WifiScan("Neighbour", -45, WifiSecurity.SAE),
            WifiScan("", -30, WifiSecurity.OPEN),
            WifiScan("Office", -60, WifiSecurity.PSK),
        )
        val saved = listOf(WifiSaved(3, "Office", WifiSecurity.PSK), WifiSaved(4, "Away", WifiSecurity.PSK))
        val current = WifiCurrent(3, "Office", connected = true)
        val e = WifiModel.entries(scans, saved, current)
        assertEquals(listOf("Office", "Neighbour", "Home", "Cafe"), e.map { it.ssid })
        assertEquals(WifiRowState.CONNECTED, e[0].state)
        assertEquals(3, e[2].level) // strongest BSS of "Home" wins
        assertEquals(WifiRowState.SECURED, e[2].state)
        assertEquals(WifiRowState.OPEN, e[3].state)
        assertEquals(WifiModel.Tap.DETAILS, WifiModel.tap(e[0]))
        assertEquals(WifiModel.Tap.ASK_PASSWORD, WifiModel.tap(e[2]))
        assertEquals(WifiModel.Tap.CONNECT_OPEN, WifiModel.tap(e[3]))

        val s = WifiModel.savedEntries(scans, saved, current)
        assertEquals(listOf("Away", "Office"), s.map { it.ssid })
        assertEquals(-1, s[0].level)
        assertEquals(WifiRowState.SAVED, s[0].state)
    }

    @Test fun connectingShowsFirst() {
        val e = WifiModel.entries(
            listOf(WifiScan("A", -40, WifiSecurity.OPEN), WifiScan("B", -80, WifiSecurity.PSK)),
            listOf(WifiSaved(1, "B", WifiSecurity.PSK)),
            WifiCurrent(1, "B", connected = false),
        )
        assertEquals("B", e[0].ssid)
        assertEquals(WifiRowState.CONNECTING, e[0].state)
    }

    @Test fun passwordRules() {
        assertEquals("At least 8 characters", WifiModel.passwordProblem(WifiSecurity.PSK, "short"))
        assertNull(WifiModel.passwordProblem(WifiSecurity.PSK, "12345678"))
        assertNull(WifiModel.passwordProblem(WifiSecurity.SAE, "longenough"))
        assertNull(WifiModel.passwordProblem(WifiSecurity.OPEN, ""))
        assertEquals("At most 63 characters", WifiModel.passwordProblem(WifiSecurity.PSK, "x".repeat(64)))
    }

    @Test fun joinOutcome() {
        val t = "Home"
        assertEquals(JoinOutcome.CONNECTED, JoinTracker.outcome(t, WifiCurrent(1, t, true), 0, false, 100))
        assertEquals(JoinOutcome.CONNECTING, JoinTracker.outcome(t, WifiCurrent(1, t, false), 0, false, 100))
        assertEquals(JoinOutcome.WRONG_PASSWORD, JoinTracker.outcome(t, null, 8, false, 100))
        assertEquals(JoinOutcome.FAILED, JoinTracker.outcome(t, null, 1, false, 100))
        assertEquals(JoinOutcome.FAILED, JoinTracker.outcome(t, null, 0, true, 100))
        assertEquals(JoinOutcome.FAILED, JoinTracker.outcome(t, null, 0, false, 31_000))
        assertEquals(JoinOutcome.CONNECTING, JoinTracker.outcome(t, WifiCurrent(2, "Other", true), 0, false, 100))
    }
}
