package org.circa.settings.data

import android.content.Context

/**
 * The screen-lock PIN, through the platform's own LockPatternUtils / LockSettingsService (hidden API,
 * reached by reflection like the rest of this app's platform access). Circa Settings is platform
 * signed, which is what ACCESS_KEYGUARD_SECURE_STORAGE (checked by LockSettingsService) needs. This is
 * the same call chain AOSP Settings' ChooseLockPassword ends in (setLockCredential), without its form:
 * the PIN is entered on Circa's own keypad (ui/PinPages.kt).
 *
 * The stored PIN length (what makes the bouncer auto-confirm without an Enter key) is written by the
 * platform itself in setLockCredential when `config_circaPinAutoConfirm` is on (settings/README.md),
 * so nothing extra is done here.
 *
 * All calls block on binder and LockSettingsService's throttling: call them off the main thread.
 */
class PinLock(context: Context) {

    private val userId: Int = android.os.Process.myUserHandle().hashCode()
    private val lpuClass = Class.forName("com.android.internal.widget.LockPatternUtils")
    private val credClass = Class.forName("com.android.internal.widget.LockscreenCredential")
    private val lpu: Any = lpuClass.getConstructor(Context::class.java).newInstance(context)

    private fun none(): Any = credClass.getMethod("createNone").invoke(null)!!
    private fun pin(p: String): Any = credClass.getMethod("createPin", CharSequence::class.java).invoke(null, p)!!
    private fun close(c: Any) { runCatching { credClass.getMethod("zeroize").invoke(c) } }

    /** Is a credential set at all (PIN, pattern or password)? */
    fun isSecure(): Boolean = runCatching {
        lpuClass.getMethod("isSecure", Int::class.javaPrimitiveType).invoke(lpu, userId) as Boolean
    }.getOrDefault(false)

    /** Length of the stored PIN when the platform knows it (it does for PINs set with auto-confirm on), else null. */
    fun storedLength(): Int? = runCatching {
        (lpuClass.getMethod("getPinLength", Int::class.javaPrimitiveType).invoke(lpu, userId) as Int)
            .takeIf { it > 0 }
    }.getOrNull()

    sealed interface Check {
        data object Ok : Check
        data object Wrong : Check
        data class Throttled(val seconds: Int) : Check
        data object Error : Check
    }

    /** Check [candidate] against the stored credential the way the bouncer does (counts a failure, throttles). */
    fun check(candidate: String): Check {
        val c = pin(candidate)
        try {
            val r = lpuClass.getMethod("verifyCredential", credClass, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                .invoke(lpu, c, userId, 0)!!
            val rc = r.javaClass
            return when {
                rc.getMethod("isMatched").invoke(r) as Boolean -> Check.Ok
                rc.getMethod("hasTimeout").invoke(r) as Boolean ->
                    Check.Throttled(((rc.getMethod("getTimeout").invoke(r) as Int) + 999) / 1000)
                else -> Check.Wrong
            }
        } catch (e: Exception) {
            return Check.Error
        } finally {
            close(c)
        }
    }

    /**
     * Store [new] as the PIN, or remove the credential when it is null. [old] is the current PIN (null when
     * none is set); a wrong [old] makes the platform refuse. Returns whether the platform accepted it.
     */
    fun set(new: String?, old: String?): Boolean {
        val n = if (new == null) none() else pin(new)
        val o = if (old == null) none() else pin(old)
        return try {
            lpuClass.getMethod("setLockCredential", credClass, credClass, Int::class.javaPrimitiveType)
                .invoke(lpu, n, o, userId) as Boolean
        } catch (e: Exception) {
            false
        } finally {
            close(n); close(o)
        }
    }
}
