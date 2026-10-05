package org.circa.settings.data

import android.app.ActivityManager
import android.app.PendingIntent
import android.app.usage.StorageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.content.pm.PermissionInfo
import android.graphics.Bitmap
import android.os.Process
import android.os.UserHandle
import android.os.storage.StorageManager
import android.text.format.Formatter
import android.util.Log
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import org.circa.settings.model.AppEntry
import org.circa.settings.model.NotifModel
import org.circa.settings.model.PermGroup
import org.circa.settings.model.PermGroups
import org.circa.settings.model.RuntimePerm

/** App info's Storage row: sizes as text, or why they cannot be read. */
data class AppStorage(val app: Long, val data: Long, val cache: Long)

/** An app's notification switch: the real state and whether the platform lets it be changed. */
data class NotifState(val on: Boolean, val requestsPost: Boolean, val fixed: Boolean) {
    val changeable: Boolean get() = NotifModel.changeable(requestsPost, fixed)
    val secondary: String get() = NotifModel.secondary(on, requestsPost, fixed)
}

/**
 * Everything the Apps pages read from and write to the platform, in one place. Circa Settings is a
 * platform-signed priv-app, so the signature permissions in the manifest are granted (the
 * signature|privileged ones also need device/circa's privapp allowlist). The @SystemApi / hidden
 * calls (runtime permission grants and flags, force stop, the notification service) are reached by
 * reflection because the compile classpath is the public SDK; each one catches and logs, and a
 * caller sees `false`/null rather than a crash. All of it is blocking binder work: call it off the
 * main thread.
 */
class AppsData(private val context: Context) {

    private val pm: PackageManager get() = context.packageManager
    private val user: UserHandle get() = Process.myUserHandle()

    // ---- the list -------------------------------------------------------------------------------

    /** Every installed package (disabled ones included), unsorted; `AppsModel.visible` filters. */
    fun listApps(): List<AppEntry> {
        val launchable = pm.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),
            PackageManager.ResolveInfoFlags.of(MATCH_ALL_STATES.toLong()),
        ).mapTo(HashSet()) { it.activityInfo.packageName }
        return pm.getInstalledPackages(PackageManager.PackageInfoFlags.of(MATCH_ALL_STATES.toLong()))
            .mapNotNull { pi -> pi.applicationInfo?.let { entry(pi, it, it.packageName in launchable) } }
    }

    fun app(pkg: String): AppEntry? = runCatching {
        val pi = pm.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(MATCH_ALL_STATES.toLong()))
        val ai = pi.applicationInfo ?: return null
        entry(pi, ai, pm.getLaunchIntentForPackage(pkg) != null || hasLauncherActivity(pkg))
    }.getOrNull()

    private fun hasLauncherActivity(pkg: String): Boolean = pm.queryIntentActivities(
        Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(pkg),
        PackageManager.ResolveInfoFlags.of(MATCH_ALL_STATES.toLong()),
    ).isNotEmpty()

    private fun entry(pi: PackageInfo, ai: ApplicationInfo, launchable: Boolean) = AppEntry(
        pkg = pi.packageName,
        label = runCatching { ai.loadLabel(pm).toString() }.getOrDefault(pi.packageName).ifBlank { pi.packageName },
        versionName = pi.versionName,
        versionCode = pi.longVersionCode,
        system = ai.flags and ApplicationInfo.FLAG_SYSTEM != 0,
        enabled = ai.enabled && enabledSetting(pi.packageName).let {
            it == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT || it == PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        },
        launchable = launchable,
        uid = ai.uid,
    )

    private fun enabledSetting(pkg: String): Int =
        runCatching { pm.getApplicationEnabledSetting(pkg) }.getOrDefault(PackageManager.COMPONENT_ENABLED_STATE_DEFAULT)

    /** The app's icon as a small bitmap, cached (an icon list scrolls past ~100 packages). */
    fun icon(pkg: String, sizePx: Int): ImageBitmap? {
        val key = "$pkg@$sizePx"
        iconCache.get(key)?.let { return it }
        return runCatching {
            pm.getApplicationIcon(pkg).toBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888).asImageBitmap()
        }.onFailure { Log.w(TAG, "icon $pkg", it) }.getOrNull()?.also { iconCache.put(key, it) }
    }

    // ---- app info ---------------------------------------------------------------------------------

    /**
     * Code, data and cache sizes (StorageStatsManager; needs PACKAGE_USAGE_STATS). null when the
     * platform refuses (permission not granted) or the package has no stats.
     */
    fun storage(pkg: String): AppStorage? = runCatching {
        val ssm = context.getSystemService(StorageStatsManager::class.java)
        val s = ssm.queryStatsForPackage(StorageManager.UUID_DEFAULT, pkg, user)
        AppStorage(s.appBytes, s.dataBytes, s.cacheBytes)
    }.onFailure { Log.w(TAG, "storage $pkg", it) }.getOrNull()

    fun formatSize(bytes: Long): String = Formatter.formatShortFileSize(context, bytes)

    fun launchIntent(pkg: String): Intent? = pm.getLaunchIntentForPackage(pkg)

    fun open(pkg: String): Boolean = runCatching {
        val i = launchIntent(pkg) ?: return false
        context.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    }.onFailure { Log.w(TAG, "open $pkg", it) }.getOrDefault(false)

    /** ActivityManager.forceStopPackage (hidden; FORCE_STOP_PACKAGES). */
    fun forceStop(pkg: String): Boolean = runCatching {
        val am = context.getSystemService(ActivityManager::class.java)
        ActivityManager::class.java.getMethod("forceStopPackage", String::class.java).invoke(am, pkg)
        true
    }.onFailure { Log.w(TAG, "forceStop $pkg", it) }.getOrDefault(false)

    /** Disable (DISABLED_USER, what stock's Disable does) or back to DEFAULT; CHANGE_COMPONENT_ENABLED_STATE. */
    fun setEnabled(pkg: String, on: Boolean): Boolean = runCatching {
        pm.setApplicationEnabledSetting(
            pkg,
            if (on) PackageManager.COMPONENT_ENABLED_STATE_DEFAULT else PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER,
            0,
        )
        true
    }.onFailure { Log.w(TAG, "setEnabled $pkg $on", it) }.getOrDefault(false)

    /**
     * Uninstall [pkg]; true when the platform reports success. Android 16 deletes silently only for
     * the app's installer, the verifier / uninstaller / storage manager, or a holder of
     * MANAGE_PROFILE_AND_DEVICE_OWNERS (DeletePackageHelper.isCallerAllowedToSilentlyUninstall):
     * DELETE_PACKAGES alone gets STATUS_PENDING_USER_ACTION. Then the platform's own confirmation
     * (the intent it hands back) is started, and its final result arrives on the same receiver.
     * Bounded: gives up (false) after [timeoutMs].
     */
    suspend fun uninstall(pkg: String, timeoutMs: Long = 120_000): Boolean {
        val result = kotlinx.coroutines.CompletableDeferred<Boolean>()
        val action = "$ACTION_UNINSTALL_RESULT.$pkg"
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(ctx: Context, i: Intent) {
                val status = i.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
                Log.i(TAG, "uninstall $pkg status=$status ${i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)}")
                when (status) {
                    PackageInstaller.STATUS_PENDING_USER_ACTION -> runCatching {
                        @Suppress("DEPRECATION")
                        val confirm = i.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
                        // Circa Settings now answers ACTION_UNINSTALL_PACKAGE itself (RequestActivity): send the
                        // platform's own dialog to the activity that is not this app, or it would loop back here.
                        val other = pm.queryIntentActivities(confirm, PackageManager.ResolveInfoFlags.of(0))
                            .firstOrNull { it.activityInfo.packageName != context.packageName }
                        if (other != null) {
                            confirm.setClassName(other.activityInfo.packageName, other.activityInfo.name)
                        }
                        context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }.onFailure { Log.w(TAG, "uninstall confirm $pkg", it); result.complete(false) }
                    PackageInstaller.STATUS_SUCCESS -> result.complete(true)
                    else -> result.complete(false)
                }
            }
        }
        context.registerReceiver(receiver, android.content.IntentFilter(action), Context.RECEIVER_NOT_EXPORTED)
        try {
            val sender = PendingIntent.getBroadcast(
                context, pkg.hashCode(), Intent(action).setPackage(context.packageName),
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ).intentSender
            pm.packageInstaller.uninstall(pkg, sender)
            return kotlinx.coroutines.withTimeoutOrNull(timeoutMs) { result.await() } ?: false
        } catch (e: Exception) {
            Log.w(TAG, "uninstall $pkg", e)
            return false
        } finally {
            runCatching { context.unregisterReceiver(receiver) }
        }
    }

    fun isInstalled(pkg: String): Boolean =
        runCatching { pm.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(MATCH_ALL_STATES.toLong())); true }
            .getOrDefault(false)

    // ---- runtime permissions -------------------------------------------------------------------

    /** The app's runtime (dangerous) permissions with grant state and fixed flags, grouped like stock. */
    fun permGroups(pkg: String): List<PermGroup> {
        val pi = runCatching {
            pm.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of((PackageManager.GET_PERMISSIONS or MATCH_ALL_STATES).toLong()))
        }.getOrNull() ?: return emptyList()
        val names = pi.requestedPermissions ?: return emptyList()
        val flagsArr = pi.requestedPermissionsFlags
        val perms = names.indices.mapNotNull { i ->
            val name = names[i]
            val info = permInfo(name) ?: return@mapNotNull null
            if (info.protection != PermissionInfo.PROTECTION_DANGEROUS) return@mapNotNull null
            // Hard-restricted (SMS / call log) ones the installer did not allowlist cannot be granted at all.
            val granted = flagsArr != null && flagsArr[i] and PackageInfo.REQUESTED_PERMISSION_GRANTED != 0
            val f = permissionFlags(name, pkg)
            RuntimePerm(name, granted && f and FLAG_REVOKED_COMPAT == 0, fixed = f and FIXED_FLAGS != 0)
        }
        return PermGroups.group(perms) { name -> platformGroupLabel(name) }
    }

    private fun permInfo(name: String): PermissionInfo? =
        runCatching { pm.getPermissionInfo(name, 0) }.getOrNull()

    private fun platformGroupLabel(name: String): String? = runCatching {
        val g = permInfo(name)?.group ?: return null
        if (g == "android.permission-group.UNDEFINED") null
        else pm.getPermissionGroupInfo(g, 0).loadLabel(pm).toString()
    }.getOrNull()

    /** PackageManager.getPermissionFlags (@SystemApi; GRANT/REVOKE_RUNTIME_PERMISSIONS). 0 if unreadable. */
    fun permissionFlags(perm: String, pkg: String): Int = runCatching {
        PackageManager::class.java
            .getMethod("getPermissionFlags", String::class.java, String::class.java, UserHandle::class.java)
            .invoke(pm, perm, pkg, user) as Int
    }.onFailure { Log.w(TAG, "getPermissionFlags $perm $pkg", it) }.getOrDefault(0)

    /**
     * Grant or revoke [perm] (@SystemApi grant/revokeRuntimePermission) and mark it user-set, as
     * stock's permission controller does, so a later default-grant pass leaves the choice alone.
     * Revoking kills the app's process (platform behaviour).
     */
    fun setPermission(pkg: String, perm: String, grant: Boolean): Boolean = runCatching {
        val m = PackageManager::class.java.getMethod(
            if (grant) "grantRuntimePermission" else "revokeRuntimePermission",
            String::class.java, String::class.java, UserHandle::class.java,
        )
        m.invoke(pm, pkg, perm, user)
        runCatching {
            PackageManager::class.java.getMethod(
                "updatePermissionFlags", String::class.java, String::class.java,
                Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, UserHandle::class.java,
            ).invoke(pm, perm, pkg, FLAG_USER_SET or FLAG_USER_FIXED, FLAG_USER_SET, user)
        }.onFailure { Log.w(TAG, "updatePermissionFlags $perm $pkg", it) }
        true
    }.onFailure { Log.w(TAG, "setPermission $pkg $perm $grant", it) }.getOrDefault(false)

    /** Every changeable permission of [group] to [grant]; true when all calls went through. */
    fun setGroup(pkg: String, group: PermGroup, grant: Boolean): Boolean =
        group.toChange(grant).map { setPermission(pkg, it.name, grant) }.all { it }

    // ---- notifications --------------------------------------------------------------------------

    /** NotificationManager.getService(): the INotificationManager binder proxy (hidden). */
    private val notificationService: Any? by lazy {
        runCatching {
            Class.forName("android.app.NotificationManager").getMethod("getService").invoke(null)
        }.onFailure { Log.w(TAG, "INotificationManager", it) }.getOrNull()
    }

    fun requestsPostNotifications(pkg: String): Boolean = runCatching {
        pm.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of((PackageManager.GET_PERMISSIONS or MATCH_ALL_STATES).toLong()))
            .requestedPermissions?.contains(NotifModel.POST_NOTIFICATIONS) == true
    }.getOrDefault(false)

    /**
     * The real state (INotificationManager.areNotificationsEnabledForPackage, which the shade's
     * blocking honours) and whether the switch can change it (POST_NOTIFICATIONS requested and not
     * system/policy fixed: NotificationManagerService ignores the call otherwise).
     */
    fun notifState(pkg: String, uid: Int, requestsPost: Boolean = requestsPostNotifications(pkg)): NotifState {
        val on = runCatching {
            val svc = notificationService!!
            svc.javaClass.getMethod("areNotificationsEnabledForPackage", String::class.java, Int::class.javaPrimitiveType)
                .invoke(svc, pkg, uid) as Boolean
        }.onFailure { Log.w(TAG, "areNotificationsEnabledForPackage $pkg", it) }
            .getOrElse { pm.checkPermission(NotifModel.POST_NOTIFICATIONS, pkg) == PackageManager.PERMISSION_GRANTED }
        val fixed = requestsPost && permissionFlags(NotifModel.POST_NOTIFICATIONS, pkg) and FIXED_FLAGS != 0
        return NotifState(on, requestsPost, fixed)
    }

    /** Whether [pkg] has ever posted a notification (INotificationManager.getNumNotificationChannelsForPackage > 0). */
    fun hasPosted(pkg: String, uid: Int): Boolean = runCatching {
        val svc = notificationService!!
        (svc.javaClass.getMethod(
            "getNumNotificationChannelsForPackage", String::class.java, Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType,
        ).invoke(svc, pkg, uid, false) as Int) > 0
    }.getOrDefault(true)

    /** INotificationManager.setNotificationsEnabledForPackage (STATUS_BAR_SERVICE): flips POST_NOTIFICATIONS. */
    fun setNotifications(pkg: String, uid: Int, on: Boolean): Boolean = runCatching {
        val svc = notificationService!!
        svc.javaClass.getMethod(
            "setNotificationsEnabledForPackage", String::class.java, Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType,
        ).invoke(svc, pkg, uid, on)
        true
    }.onFailure { Log.w(TAG, "setNotificationsEnabledForPackage $pkg $on", it) }.getOrDefault(false)

    companion object {
        private const val TAG = "CircaApps"
        const val ACTION_UNINSTALL_RESULT = "org.circa.settings.action.UNINSTALL_RESULT"

        /** MATCH_DISABLED_COMPONENTS | MATCH_DISABLED_UNTIL_USED_COMPONENTS: disabled apps stay listed. */
        private const val MATCH_ALL_STATES =
            PackageManager.MATCH_DISABLED_COMPONENTS or PackageManager.MATCH_DISABLED_UNTIL_USED_COMPONENTS

        // PackageManager.FLAG_PERMISSION_* (@SystemApi constants, values from the platform source).
        private const val FLAG_USER_SET = 1 shl 0
        private const val FLAG_USER_FIXED = 1 shl 1
        private const val FLAG_POLICY_FIXED = 1 shl 2
        private const val FLAG_REVOKED_COMPAT = 1 shl 3
        private const val FLAG_SYSTEM_FIXED = 1 shl 4
        private const val FIXED_FLAGS = FLAG_POLICY_FIXED or FLAG_SYSTEM_FIXED

        private val iconCache = LruCache<String, ImageBitmap>(256)
    }
}
