package org.circa.launcher.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationStreamTest {

    private fun item(key: String, postTime: Long) = NotificationItem(
        key = key,
        packageName = "com.android.shell",
        appName = "Shell",
        title = key,
        text = null,
        postTimeMillis = postTime,
        ongoing = false,
    )

    @Test
    fun ordersNewestFirst() {
        val list = NotificationStream.upsert(
            NotificationStream.upsert(emptyList(), item("a", 100)),
            item("b", 200),
        )
        assertEquals(listOf("b", "a"), list.map { it.key })
    }

    @Test
    fun repostingAKeyReplacesInsteadOfDuplicating() {
        val first = NotificationStream.upsert(emptyList(), item("a", 100))
        val updated = NotificationStream.upsert(first, item("a", 300))
        assertEquals(listOf("a"), updated.map { it.key })
        assertEquals(300L, updated.single().postTimeMillis)
    }

    @Test
    fun capsAtTheStreamLimitKeepingTheNewest() {
        var list = emptyList<NotificationItem>()
        for (i in 1..NotificationStream.CAP + 5) {
            list = NotificationStream.upsert(list, item("n$i", i.toLong()))
        }
        assertEquals(NotificationStream.CAP, list.size)
        assertEquals("n25", list.first().key)
        assertEquals("n6", list.last().key)
    }

    @Test
    fun removeDropsOnlyThatKey() {
        val list = listOf(item("a", 100), item("b", 200))
        assertEquals(listOf("b"), NotificationStream.remove(list, "a").map { it.key })
        assertEquals(list.map { it.key }, NotificationStream.remove(list, "gone").map { it.key })
        assertTrue(NotificationStream.remove(emptyList(), "a").isEmpty())
    }
}
