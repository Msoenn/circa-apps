package org.circa.settings.data

import android.content.Context
import android.location.LocationManager
import android.os.Process

/**
 * The master location switch (Settings > Location). `LocationManager.setLocationEnabledForUser` is a
 * @SystemApi guarded by WRITE_SECURE_SETTINGS, which this platform-signed app holds; it is reached by
 * reflection like the rest of this app's system API use. Reading is public (`isLocationEnabled`).
 */
class LocationData(private val context: Context) {
    private val lm get() = context.getSystemService(LocationManager::class.java)

    fun isEnabled(): Boolean = runCatching { lm.isLocationEnabled }.getOrDefault(false)

    fun setEnabled(on: Boolean): Boolean = runCatching {
        LocationManager::class.java
            .getMethod("setLocationEnabledForUser", Boolean::class.javaPrimitiveType, android.os.UserHandle::class.java)
            .invoke(lm, on, Process.myUserHandle())
        true
    }.getOrDefault(false)
}
