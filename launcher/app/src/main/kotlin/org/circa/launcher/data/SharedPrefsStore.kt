package org.circa.launcher.data

import android.content.SharedPreferences
import org.circa.launcher.model.KeyValueStore

/** [KeyValueStore] over `SharedPreferences` (the launcher's own `launcher` file). */
class SharedPrefsStore(private val prefs: SharedPreferences) : KeyValueStore {
    override fun getString(key: String): String? = prefs.getString(key, null)
    override fun putString(key: String, value: String) = prefs.edit().putString(key, value).apply()
    override fun getLong(key: String): Long? = if (prefs.contains(key)) prefs.getLong(key, 0L) else null
    override fun putLong(key: String, value: Long) = prefs.edit().putLong(key, value).apply()
}
