package org.circa.launcher.model

import org.junit.Assert.assertEquals
import org.junit.Test

class AppListModelTest {

    @Test
    fun sortOrdersByLabelCaseInsensitively() {
        val entries = listOf(
            AppEntry("watchlink", "a.b", "A"),
            AppEntry("Settings", "a.c", "A"),
            AppEntry("clock", "a.d", "A"),
            AppEntry("AGENDA", "a.e", "A"),
        )
        val sorted = AppListModel.sort(entries).map { it.label }
        assertEquals(listOf("AGENDA", "clock", "Settings", "watchlink"), sorted)
    }

    @Test
    fun sortBreaksTiesByPackageThenClass() {
        val entries = listOf(
            AppEntry("Same", "z.pkg", "Activity"),
            AppEntry("Same", "a.pkg", "Later"),
            AppEntry("Same", "a.pkg", "Earlier"),
        )
        val sorted = AppListModel.sort(entries)
        assertEquals("a.pkg", sorted[0].packageName)
        assertEquals("Earlier", sorted[0].className)
        assertEquals("a.pkg", sorted[1].packageName)
        assertEquals("Later", sorted[1].className)
        assertEquals("z.pkg", sorted[2].packageName)
    }

    @Test
    fun filterSelfRemovesOnlyOwnPackage() {
        val entries = listOf(
            AppEntry("Mine", "org.circa.launcher", "MainActivity"),
            AppEntry("Other", "com.example", "MainActivity"),
        )
        val filtered = AppListModel.filterSelf(entries, "org.circa.launcher")
        assertEquals(1, filtered.size)
        assertEquals("com.example", filtered[0].packageName)
    }
}
