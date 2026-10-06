package org.circa.settings.data

import android.app.KeyguardManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.ConnectivityManager
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import org.circa.settings.model.Accent
import org.circa.settings.model.LongPressAction
import org.circa.settings.model.Profile
import org.circa.settings.model.ProfileField
import org.circa.settings.model.ProfileKeys
import org.circa.settings.model.ProfileModel
import org.circa.settings.model.Sex
import org.circa.settings.model.GestureModel
import org.circa.settings.model.GestureRow
import org.circa.settings.model.AodBrightness
import org.circa.settings.model.AodBrightnessModel
import org.circa.settings.model.TiltWake
import org.circa.settings.model.TiltWakeModel
import org.circa.settings.model.Ringer
import org.circa.settings.model.ScreenTimeout
import org.circa.settings.model.SettingKey
import org.circa.settings.model.SettingsTable
import org.circa.settings.model.SharedKeys

/** Read-only facts for the About screen. */
data class AboutInfo(
    val androidVersion: String,
    val lineageVersion: String?,
    val securityPatch: String,
    val model: String,
    val settingsVersion: String,
    val launcherVersion: String?,
    val watchLinkVersion: String?,
)

/** Everything the Settings screens draw, read in one pass (and re-read after each change). */
data class SettingsSnapshot(
    val qs: QuickSettingsState,
    val airplane: Boolean,
    val screenTimeoutMs: Int,
    val alwaysOn: Boolean,
    /** Settings.Secure circa_aod_brightness: the always-on face's doze brightness. */
    val aodBrightness: AodBrightness = AodBrightness.NORMAL,
    val gestures: List<GestureRow>,
    val tiltWake: TiltWake,
    val ringer: Ringer,
    val touchVibration: Boolean,
    val deviceSecure: Boolean,
    val lockAfterMs: Long,
    /** Shared with the launcher and the shade through Settings.Secure (see [SharedKeys]). */
    val accent: Accent,
    val lockWhenTakenOff: Boolean,
    val batteryPercent: Int?,
    /** The Exercise profile (Settings.Secure circa_profile_*). */
    val profile: Profile = Profile(),
    /** Settings.Global circa_exercise_long_press. */
    val longPress: LongPressAction = LongPressAction.DEFAULT,
    /** Settings.Secure circa_auto_detect: the Exercise app's walk/run auto-detection (default on). */
    val autoDetect: Boolean = true,
)

/**
 * The Settings screens' writes, against the real platform. Circa Settings is platform-signed, so
 * WRITE_SECURE_SETTINGS / WRITE_SETTINGS / NETWORK_AIRPLANE_MODE / REBOOT are granted; every write
 * is read back by the next [read], so a toggle always shows what the platform really holds.
 * Bluetooth, Wi-Fi, battery saver and brightness go through [QuickSettings] (the tray's code).
 */
class SystemSettings(private val context: Context, private val quick: QuickSettings) {

    private val resolver get() = context.contentResolver

    companion object {
        const val ALWAYS_ON_KEY = "doze_always_on"
        const val LOCK_AFTER_KEY = "lock_screen_lock_after_timeout"
        const val WATCHLINK = "org.circa.watchlink"
        const val LAUNCHER = "org.circa.launcher"

        /** AOSP Settings: the backend for every screen this app does not replace. */
        const val AOSP_SETTINGS = "com.android.settings"

        /** The launcher's face picker (handled by its MainActivity; settings/README.md). */
        const val ACTION_PICK_FACE = "org.circa.intent.action.PICK_FACE"
    }

    fun read(): SettingsSnapshot {
        migrateTiltWake()
        migrateScreenTimeout()
        return SettingsSnapshot(
            qs = quick.read(),
            airplane = Settings.Global.getInt(resolver, Settings.Global.AIRPLANE_MODE_ON, 0) != 0,
            screenTimeoutMs = Settings.System.getInt(resolver, Settings.System.SCREEN_OFF_TIMEOUT, ScreenTimeout.DEFAULT_MS),
            alwaysOn = GestureModel.parse(getRaw(SettingKey(SettingsTable.SECURE, ALWAYS_ON_KEY))),
            aodBrightness = AodBrightnessModel.resolve(
                getRaw(SettingKey(SettingsTable.SECURE, AodBrightnessModel.SECURE_KEY)),
            ),
            gestures = GestureModel.rows(::exists, ::getRaw),
            tiltWake = TiltWakeModel.resolve(
                getRaw(SettingKey(SettingsTable.SECURE, TiltWakeModel.SECURE_KEY)),
                getRaw(SettingKey(SettingsTable.GLOBAL, TiltWakeModel.LEGACY_GLOBAL_KEY)),
                getRaw(SettingKey(SettingsTable.SECURE, TiltWakeModel.MIGRATED_KEY)),
            ),
            ringer = Ringer.fromMode(
                context.getSystemService(AudioManager::class.java)?.ringerMode ?: Ringer.SOUND.mode,
            ),
            touchVibration = Settings.System.getInt(resolver, Settings.System.HAPTIC_FEEDBACK_ENABLED, 1) != 0,
            deviceSecure = context.getSystemService(KeyguardManager::class.java)?.isDeviceSecure == true,
            lockAfterMs = getRaw(SettingKey(SettingsTable.SECURE, LOCK_AFTER_KEY))?.toLongOrNull() ?: 0L,
            accent = Accent.fromArgb(
                getRaw(SettingKey(SettingsTable.SECURE, SharedKeys.ACCENT))?.trim()?.toIntOrNull(),
            ) ?: Accent.DEFAULT,
            lockWhenTakenOff = SharedKeys.parseLockWhenTakenOff(
                getRaw(SettingKey(SettingsTable.SECURE, SharedKeys.LOCK_WHEN_TAKEN_OFF)),
            ),
            batteryPercent = context.getSystemService(BatteryManager::class.java)
                ?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
                ?.takeIf { it in 0..100 },
            profile = readProfile(),
            longPress = LongPressAction.fromRaw(getRaw(SettingKey(SettingsTable.GLOBAL, ProfileKeys.LONG_PRESS))),
            autoDetect = ProfileModel.autoDetectOn(getRaw(SettingKey(SettingsTable.SECURE, ProfileKeys.AUTO_DETECT))),
        )
    }

    /**
     * The one-time `Low -> Medium` migration: Low was the old default and was never an explicit
     * choice, so it becomes Medium once (recorded in `circa_tilt_wake_migrated`). The launcher
     * applies the same migration from its own read path.
     */
    private fun migrateTiltWake() {
        val writes = TiltWakeModel.migrationWrites(
            getRaw(SettingKey(SettingsTable.SECURE, TiltWakeModel.SECURE_KEY)),
            getRaw(SettingKey(SettingsTable.SECURE, TiltWakeModel.MIGRATED_KEY)),
        )
        for ((name, value) in writes) putRaw(SettingKey(SettingsTable.SECURE, name), value)
    }

    /**
     * The one-time screen-timeout migration: Android's 30 s default and the 10 s set by hand while
     * the framework minimum was 10 s both become the new 5 s default, recorded in
     * `circa_timeout_migrated`. Any other value is a real choice and is left alone.
     */
    private fun migrateScreenTimeout() {
        val flag = SettingKey(SettingsTable.SECURE, ScreenTimeout.MIGRATED_KEY)
        val migrated = getRaw(flag)
        val current = Settings.System.getInt(resolver, ScreenTimeout.SYSTEM_KEY, ScreenTimeout.DEFAULT_MS)
        ScreenTimeout.migrationValue(current, migrated)?.let {
            Settings.System.putInt(resolver, ScreenTimeout.SYSTEM_KEY, it)
        }
        if (migrated == null) putRaw(flag, "1")
    }

    private fun secure(name: String) = getRaw(SettingKey(SettingsTable.SECURE, name))

    fun readProfile(): Profile = ProfileModel.parse(
        birthYear = secure(ProfileKeys.BIRTH_YEAR),
        weightKg = secure(ProfileKeys.WEIGHT_KG),
        heightCm = secure(ProfileKeys.HEIGHT_CM),
        sex = secure(ProfileKeys.SEX),
        maxHr = secure(ProfileKeys.MAX_HR),
    )

    // ---- generic key access --------------------------------------------------------------------

    fun getRaw(key: SettingKey): String? = runCatching {
        when (key.table) {
            SettingsTable.GLOBAL -> Settings.Global.getString(resolver, key.name)
            SettingsTable.SECURE -> Settings.Secure.getString(resolver, key.name)
            SettingsTable.SYSTEM -> Settings.System.getString(resolver, key.name)
        }
    }.getOrNull()

    fun exists(key: SettingKey): Boolean = getRaw(key) != null

    fun putRaw(key: SettingKey, value: String): Boolean = runCatching {
        when (key.table) {
            SettingsTable.GLOBAL -> Settings.Global.putString(resolver, key.name, value)
            SettingsTable.SECURE -> Settings.Secure.putString(resolver, key.name, value)
            SettingsTable.SYSTEM -> Settings.System.putString(resolver, key.name, value)
        }
    }.getOrDefault(false)

    // ---- Profile and buttons (Exercise) --------------------------------------------------------------

    /** Writes one numeric field: ints via putInt, the weight as a decimal string (the Exercise app's contract). */
    fun setProfileValue(field: ProfileField, value: Double): Boolean = runCatching {
        val v = ProfileModel.encode(field, value)
        when (field) {
            ProfileField.BIRTH_YEAR -> Settings.Secure.putInt(resolver, ProfileKeys.BIRTH_YEAR, v.toInt())
            ProfileField.WEIGHT -> Settings.Secure.putString(resolver, ProfileKeys.WEIGHT_KG, v)
            ProfileField.HEIGHT -> Settings.Secure.putInt(resolver, ProfileKeys.HEIGHT_CM, v.toInt())
            ProfileField.MAX_HR -> Settings.Secure.putInt(resolver, ProfileKeys.MAX_HR, v.toInt())
        }
    }.getOrDefault(false)

    /** 0 = automatic (the Exercise app estimates it from the age). */
    fun setMaxHrAuto(): Boolean =
        runCatching { Settings.Secure.putInt(resolver, ProfileKeys.MAX_HR, 0) }.getOrDefault(false)

    /** null = not set (the key's value is cleared). */
    fun setSex(sex: Sex?): Boolean =
        runCatching { Settings.Secure.putString(resolver, ProfileKeys.SEX, sex?.value) }.getOrDefault(false)

    fun setAutoDetect(on: Boolean): Boolean =
        runCatching { Settings.Secure.putInt(resolver, ProfileKeys.AUTO_DETECT, if (on) 1 else 0) }.getOrDefault(false)

    fun setLongPress(action: LongPressAction): Boolean =
        runCatching { Settings.Global.putString(resolver, ProfileKeys.LONG_PRESS, action.value) }.getOrDefault(false)

    // ---- Connectivity ---------------------------------------------------------------------------

    fun setBluetooth(on: Boolean) = quick.setBluetooth(on)
    fun setWifi(on: Boolean) = quick.setWifi(on)

    /**
     * Airplane mode. `ConnectivityManager.setAirplaneMode` (@SystemApi, NETWORK_AIRPLANE_MODE) flips the
     * radios and the setting together; where it is missing the setting is written and the protected
     * broadcast attempted, which only lands for a system caller.
     */
    fun setAirplane(on: Boolean): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val viaApi = runCatching {
            ConnectivityManager::class.java.getMethod("setAirplaneMode", Boolean::class.javaPrimitiveType)
                .invoke(cm, on)
            true
        }.getOrDefault(false)
        if (viaApi) return true
        val wrote = runCatching {
            Settings.Global.putInt(resolver, Settings.Global.AIRPLANE_MODE_ON, if (on) 1 else 0)
        }.getOrDefault(false)
        runCatching {
            context.sendBroadcast(
                Intent(Intent.ACTION_AIRPLANE_MODE_CHANGED).putExtra("state", on),
            )
        }
        return wrote
    }

    // ---- Display --------------------------------------------------------------------------------

    fun setBrightness(level: Int) = quick.setBrightness(level)

    fun setScreenTimeout(ms: Int): Boolean =
        runCatching { Settings.System.putInt(resolver, Settings.System.SCREEN_OFF_TIMEOUT, ms) }
            .getOrDefault(false)

    fun setAlwaysOn(on: Boolean): Boolean =
        putRaw(SettingKey(SettingsTable.SECURE, ALWAYS_ON_KEY), GestureModel.encode(on))

    /** The system-wide accent: the launcher re-reads it when it comes back to the front, the shade at once. */
    fun setAccent(accent: Accent): Boolean =
        putRaw(SettingKey(SettingsTable.SECURE, SharedKeys.ACCENT), accent.argb.toInt().toString())

    /** The launcher's face picker (the face itself is the launcher's own preference). */
    fun openFacePicker() = start(Intent(ACTION_PICK_FACE).setPackage(LAUNCHER))

    // ---- Gestures -------------------------------------------------------------------------------

    fun setGesture(row: GestureRow, on: Boolean): Boolean = putRaw(row.key, GestureModel.encode(on))

    fun setAodBrightness(level: AodBrightness): Boolean =
        putRaw(SettingKey(SettingsTable.SECURE, AodBrightnessModel.SECURE_KEY), level.value.toString())

    fun setTiltWake(level: TiltWake): Boolean {
        val a = putRaw(SettingKey(SettingsTable.SECURE, TiltWakeModel.SECURE_KEY), level.value.toString())
        val b = putRaw(SettingKey(SettingsTable.GLOBAL, TiltWakeModel.LEGACY_GLOBAL_KEY), TiltWakeModel.legacyValue(level))
        return a && b
    }

    // ---- Sound & vibration ----------------------------------------------------------------------

    /** Ringer mode; silent needs notification-policy access (granted to the launcher for DND). */
    fun setRinger(ringer: Ringer): Boolean = runCatching {
        context.getSystemService(AudioManager::class.java)?.ringerMode = ringer.mode
        true
    }.getOrDefault(false)

    fun setTouchVibration(on: Boolean): Boolean =
        runCatching { Settings.System.putInt(resolver, Settings.System.HAPTIC_FEEDBACK_ENABLED, if (on) 1 else 0) }
            .getOrDefault(false)

    // ---- Security -------------------------------------------------------------------------------

    fun setLockAfter(ms: Long): Boolean =
        putRaw(SettingKey(SettingsTable.SECURE, LOCK_AFTER_KEY), ms.toString())

    /** "Lock when taken off": the launcher's off-body lock observes this key. */
    fun setLockWhenTakenOff(on: Boolean): Boolean =
        putRaw(SettingKey(SettingsTable.SECURE, SharedKeys.LOCK_WHEN_TAKEN_OFF), GestureModel.encode(on))

    /** Android's own set/change-PIN flow (ChooseLockGeneric); a candidate for a round PIN entry later. */
    fun openSetPin() = start(aosp(DevicePolicyManager.ACTION_SET_NEW_PASSWORD))

    // ---- AOSP Settings' own screens (phone UI, for what Circa Settings does not replace yet) -----
    // Pinned to com.android.settings: this app claims several of these actions itself (at a higher
    // priority), so an implicit start would land back here.

    fun openAndroidSettings() = start(aosp(Settings.ACTION_SETTINGS))
    fun openBluetoothSettings() = start(aosp(Settings.ACTION_BLUETOOTH_SETTINGS))
    fun openAppList() = start(aosp(Settings.ACTION_MANAGE_ALL_APPLICATIONS_SETTINGS))
    fun openNotificationSettings() = start(aosp("android.settings.ALL_APPS_NOTIFICATION_SETTINGS"))
    fun openBatteryUsage() = start(aosp(Intent.ACTION_POWER_USAGE_SUMMARY))

    private fun aosp(action: String) = Intent(action).setPackage(AOSP_SETTINGS)

    private fun start(intent: Intent) {
        runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    // ---- System ---------------------------------------------------------------------------------

    private fun prop(name: String): String? = runCatching {
        Class.forName("android.os.SystemProperties")
            .getMethod("get", String::class.java, String::class.java)
            .invoke(null, name, "") as String
    }.getOrNull()?.takeIf { it.isNotBlank() }

    private fun versionName(pkg: String): String? = runCatching {
        context.packageManager.getPackageInfo(pkg, 0).versionName
    }.getOrNull()

    fun about(): AboutInfo = AboutInfo(
        androidVersion = Build.VERSION.RELEASE,
        lineageVersion = prop("ro.lineage.version") ?: prop("ro.lineage.build.version"),
        securityPatch = Build.VERSION.SECURITY_PATCH,
        model = Build.MODEL,
        settingsVersion = versionName(context.packageName) ?: "?",
        launcherVersion = versionName(LAUNCHER),
        watchLinkVersion = versionName(WATCHLINK),
    )

    /** Reboot (PowerManager.reboot, REBOOT permission). Only ever called after the confirmation screen. */
    fun reboot(): Boolean = runCatching {
        context.getSystemService(PowerManager::class.java).reboot(null)
        true
    }.getOrDefault(false)

    /** Power off (`PowerManager.shutdown(confirm, reason, wait)`, hidden; REBOOT permission). */
    fun shutdown(): Boolean = runCatching {
        PowerManager::class.java
            .getMethod("shutdown", Boolean::class.javaPrimitiveType, String::class.java, Boolean::class.javaPrimitiveType)
            .invoke(context.getSystemService(PowerManager::class.java), false, null, false)
        true
    }.getOrDefault(false)
}
