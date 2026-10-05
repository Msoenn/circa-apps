package org.circa.launcher.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private class MapStore(val map: MutableMap<String, Any> = mutableMapOf()) : KeyValueStore {
    override fun getString(key: String) = map[key] as? String
    override fun putString(key: String, value: String) { map[key] = value }
    override fun getLong(key: String) = map[key] as? Long
    override fun putLong(key: String, value: Long) { map[key] = value }
}

class LauncherSettingsTest {

    @Test
    fun defaultsAreTheBigDigitalFaceAndTheBlueAccent() {
        val s = LauncherSettings(MapStore())
        assertEquals(FaceStyle.DIGITAL, s.face)
        assertEquals(Accent.BLUE, s.accent)
        assertEquals(0xFF8AB4F8, s.accent.argb)
        assertNull(s.stepState)
    }

    @Test
    fun choicesSurviveANewInstanceOverTheSameStore() {
        val store = MapStore()
        LauncherSettings(store).apply {
            face = FaceStyle.ANALOG
            accent = Accent.AMBER
        }
        val reloaded = LauncherSettings(store)
        assertEquals(FaceStyle.ANALOG, reloaded.face)
        assertEquals(Accent.AMBER, reloaded.accent)
    }

    @Test
    fun unknownIdsFallBackToTheDefaults() {
        val store = MapStore(mutableMapOf("face" to "hologram", "accent" to "plaid"))
        val s = LauncherSettings(store)
        assertEquals(FaceStyle.DEFAULT, s.face)
        assertEquals(Accent.DEFAULT, s.accent)
    }

    @Test
    fun stepStateRoundTrips() {
        val store = MapStore()
        LauncherSettings(store).stepState = StepState(20_000, 123, 456)
        assertEquals(StepState(20_000, 123, 456), LauncherSettings(store).stepState)
    }

    @Test
    fun lockWhenTakenOffDefaultsOnAndRoundTrips() {
        val store = MapStore()
        assertTrue(LauncherSettings(store).lockWhenTakenOff)
        LauncherSettings(store).lockWhenTakenOff = false
        assertFalse(LauncherSettings(store).lockWhenTakenOff)
        LauncherSettings(store).lockWhenTakenOff = true
        assertTrue(LauncherSettings(store).lockWhenTakenOff)
    }

    @Test
    fun accentChoicesAreTheFiveSpecifiedColours() {
        assertEquals(
            listOf(0xFFC5CBFF, 0xFF8AB4F8, 0xFF81C995, 0xFFFDD663, 0xFFE8EAED),
            Accent.entries.map { it.argb },
        )
        assertEquals(Accent.GREEN, Accent.fromId("green"))
        assertEquals(Accent.BLUE, Accent.fromId(null))
    }

    @Test
    fun faceIdsAreUniqueAndDigitalComesFirst() {
        assertEquals(FaceStyle.entries.size, FaceStyle.entries.map { it.id }.toSet().size)
        assertEquals(FaceStyle.DIGITAL, FaceStyle.entries.first())
    }
}
