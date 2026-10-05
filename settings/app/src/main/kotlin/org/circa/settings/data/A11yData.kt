package org.circa.settings.data

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import android.util.Log
import org.circa.settings.model.FontScale

/** What the Accessibility pages draw. */
data class A11yState(
    val fontScale: Float,
    val bold: Boolean,
    val inversion: Boolean,
    val correction: Boolean,
    /** null = no TalkBack installed (row hidden). */
    val talkBack: Boolean?,
)

/**
 * Accessibility settings, written to the same keys stock Settings uses; the framework's observers
 * (ActivityTaskManager for font_scale / font_weight_adjustment, the colour display manager for
 * inversion / daltonizer, AccessibilityManagerService for services) apply them system wide.
 * Needs WRITE_SETTINGS (System) and WRITE_SECURE_SETTINGS (Secure).
 */
class A11yData(context: Context) {
    private val app = context.applicationContext
    private val resolver = app.contentResolver

    fun read(): A11yState = A11yState(
        fontScale = Settings.System.getFloat(resolver, Settings.System.FONT_SCALE, 1f),
        bold = FontScale.isBold(Settings.Secure.getString(resolver, FONT_WEIGHT_ADJUSTMENT)),
        inversion = secureInt(INVERSION) != 0,
        correction = secureInt(DALTONIZER_ENABLED) != 0,
        talkBack = talkBackComponent()?.let { enabledServices().contains(it.flattenToString()) },
    )

    fun setFontScale(scale: Float): Boolean = write { Settings.System.putFloat(resolver, Settings.System.FONT_SCALE, scale) }

    fun setBold(on: Boolean): Boolean =
        write { Settings.Secure.putInt(resolver, FONT_WEIGHT_ADJUSTMENT, if (on) FontScale.BOLD_WEIGHT_ADJUSTMENT else 0) }

    fun setInversion(on: Boolean): Boolean = write { Settings.Secure.putInt(resolver, INVERSION, if (on) 1 else 0) }

    /** Colour correction on in stock's default mode (deuteranomaly, 12) unless a mode is already set. */
    fun setCorrection(on: Boolean): Boolean = write {
        if (on && Settings.Secure.getString(resolver, DALTONIZER_MODE) == null) {
            Settings.Secure.putInt(resolver, DALTONIZER_MODE, 12)
        }
        Settings.Secure.putInt(resolver, DALTONIZER_ENABLED, if (on) 1 else 0)
    }

    /** Adds / removes TalkBack in enabled_accessibility_services and keeps accessibility_enabled in step. */
    fun setTalkBack(on: Boolean): Boolean {
        val tb = talkBackComponent()?.flattenToString() ?: return false
        val services = enabledServices().toMutableList().apply { remove(tb); if (on) add(tb) }
        return write {
            Settings.Secure.putString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, services.joinToString(":"))
            Settings.Secure.putInt(resolver, Settings.Secure.ACCESSIBILITY_ENABLED, if (services.isEmpty()) 0 else 1)
        }
    }

    private fun enabledServices(): List<String> =
        Settings.Secure.getString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?.split(':')?.filter { it.isNotBlank() }.orEmpty()

    /** TalkBack's accessibility service, if either package that ships it is installed. */
    private fun talkBackComponent(): ComponentName? = TALKBACK_PACKAGES.firstNotNullOfOrNull { pkg ->
        runCatching {
            app.packageManager.getPackageInfo(pkg, PackageManager.GET_SERVICES).services
                ?.firstOrNull { it.permission == "android.permission.BIND_ACCESSIBILITY_SERVICE" }
                ?.let { ComponentName(pkg, it.name) }
        }.getOrNull()
    }

    private fun secureInt(key: String): Int = runCatching { Settings.Secure.getInt(resolver, key, 0) }.getOrDefault(0)

    private inline fun write(block: () -> Boolean): Boolean =
        runCatching { block() }.onFailure { Log.w("CircaA11y", "write", it) }.getOrDefault(false)

    private companion object {
        // Settings.Secure keys that are @hide or @SystemApi (stable names).
        const val FONT_WEIGHT_ADJUSTMENT = "font_weight_adjustment"
        const val INVERSION = "accessibility_display_inversion_enabled"
        const val DALTONIZER_ENABLED = "accessibility_display_daltonizer_enabled"
        const val DALTONIZER_MODE = "accessibility_display_daltonizer"
        val TALKBACK_PACKAGES = listOf("com.google.android.marvin.talkback", "com.android.talkback")
    }
}
