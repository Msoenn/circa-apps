package org.circa.settings.data

import android.app.LocaleManager
import android.content.Context
import android.content.res.Resources
import android.os.LocaleList
import android.util.Log
import java.util.Locale
import org.circa.settings.model.LocaleModel

/**
 * The system language: the choices are the locales the framework itself ships translations for
 * (Resources.getSystem().assets.locales, what AOSP's LocalePicker marks as translated), so the list
 * follows whatever the image is built with. Applying needs CHANGE_CONFIGURATION
 * (signature|privileged|development).
 */
class LocaleData(context: Context) {
    private val app = context.applicationContext

    fun choices(): List<Locale> = LocaleModel.choices(Resources.getSystem().assets.locales.toList())

    /** The first system locale (what the UI language is). */
    fun current(): Locale? = systemLocale()

    /**
     * Makes [locale] the only system locale. LocaleManager.setSystemLocales (@SystemApi) first,
     * then the older internal LocalePicker.updateLocales; both end in
     * ActivityManager.updatePersistentConfiguration, which also persists persist.sys.locale.
     */
    fun apply(locale: Locale): Boolean {
        val list = LocaleList(locale)
        val viaManager = runCatching {
            val lm = app.getSystemService(LocaleManager::class.java) ?: error("no LocaleManager")
            LocaleManager::class.java.getMethod("setSystemLocales", LocaleList::class.java).invoke(lm, list)
            true
        }.onFailure { Log.w(TAG, "LocaleManager.setSystemLocales", it) }.getOrDefault(false)
        if (viaManager) return true
        return runCatching {
            Class.forName("com.android.internal.app.LocalePicker")
                .getMethod("updateLocales", LocaleList::class.java).invoke(null, list)
            true
        }.onFailure { Log.w(TAG, "LocalePicker.updateLocales", it) }.getOrDefault(false)
    }

    companion object {
        private const val TAG = "CircaLocale"

        fun systemLocale(): Locale? = runCatching {
            Resources.getSystem().configuration.locales.takeIf { !it.isEmpty }?.get(0)
        }.getOrNull()
    }
}
