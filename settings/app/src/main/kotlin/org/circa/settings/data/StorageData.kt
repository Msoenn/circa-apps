package org.circa.settings.data

import android.app.usage.StorageStatsManager
import android.content.Context
import android.os.Environment
import android.os.Process
import android.os.StatFs
import android.os.storage.StorageManager

/** Internal storage: [total]/[free] are the /data filesystem (what `df /data` shows). */
data class StorageInfo(
    val total: Long,
    val free: Long,
    /** Installed apps: code + data + cache of this user (StorageStatsManager), or null if refused. */
    val apps: Long?,
) {
    val used: Long get() = (total - free).coerceAtLeast(0)

    /** Everything used that is not an app: the OS's own files on /data, dex caches, logs, ... */
    val other: Long? get() = apps?.let { (used - it).coerceAtLeast(0) }
}

object StorageData {
    /**
     * StatFs on /data rather than StorageStatsManager.getTotalBytes: the latter rounds the device up to
     * a marketing size (e.g. 8 GB) and includes the system partitions, so it would not match `df`.
     * App sizes need PACKAGE_USAGE_STATS (signature|privileged|appop): granted to the platform-signed app.
     */
    fun read(context: Context): StorageInfo {
        val fs = StatFs(Environment.getDataDirectory().path)
        val apps = runCatching {
            val ssm = context.getSystemService(StorageStatsManager::class.java)
            val s = ssm.queryStatsForUser(StorageManager.UUID_DEFAULT, Process.myUserHandle())
            s.appBytes + s.dataBytes + s.cacheBytes
        }.getOrNull()
        return StorageInfo(total = fs.totalBytes, free = fs.availableBytes, apps = apps)
    }
}
