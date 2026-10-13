package org.circa.settings

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import org.circa.settings.data.QuickSettings
import org.circa.settings.data.SystemSettings

/**
 * Puts a "charging only" USB port back at boot: the USB HAL forgets the data-signalling state on
 * every reboot, so the stored `circa_usb_data` (the USB data row in ui/SettingsScreen.kt) is applied
 * again here. LOCKED_BOOT_COMPLETED runs before the user unlocks - the receiver is direct-boot
 * aware, so the port is locked down as soon as it is live - and BOOT_COMPLETED covers the rest of
 * the boot. With the key unset or on, [SystemSettings.applyUsbData] calls nothing: the platform
 * default is already what was chosen.
 */
class UsbDataReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_LOCKED_BOOT_COMPLETED -> Unit
            else -> return
        }
        val app = context.applicationContext
        SystemSettings(app, QuickSettings(app)).applyUsbData()
    }
}
