package org.circa.launcher

import android.content.Context
import android.util.Log
import org.circa.launcher.model.TiltLog
import java.io.File
import java.util.concurrent.Executors

/**
 * The on-watch tilt log: one [TiltLog] line per wrist-tilt event (accepted or not, with what happened
 * afterwards), so real wear tunes the gate instead of a separate recording session.
 *
 * Written to the app's *external* files dir, `/sdcard/Android/data/org.circa.launcher/files/
 * tilt-log.csv`, because a platform-signed, non-debuggable app's private dir cannot be read over adb
 * without root (`run-as` refuses it), while the shell user can read the external app dir:
 * `adb pull` reads it. No permission is involved (an app's own external dir).
 * Rolling: trimmed back to the newest [TiltLog.MAX_LINES] lines once it is [TiltLog.TRIM_SLACK] over.
 * Disk work runs on a single background thread, in order.
 */
class TiltTelemetry(private val context: Context) {

    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "tilt-log").apply { isDaemon = true } }

    /** Data lines in the file, counted on first use; -1 = not yet known. */
    private var lines = -1

    fun file(): File {
        val dir = runCatching { context.getExternalFilesDir(null) }.getOrNull() ?: context.filesDir
        return File(dir, FILE_NAME)
    }

    fun append(line: String) {
        Log.i(TAG, line)
        io.execute {
            try {
                val f = file()
                if (!f.exists() || f.length() == 0L) {
                    f.parentFile?.mkdirs()
                    f.writeText(TiltLog.HEADER + "\n")
                    lines = 0
                }
                if (lines < 0) lines = f.useLines { seq -> seq.count { it.isNotBlank() } } - 1
                f.appendText(line + "\n")
                lines++
                if (lines > TiltLog.MAX_LINES + TiltLog.TRIM_SLACK) {
                    TiltLog.trim(f.readLines())?.let { kept ->
                        val tmp = File(f.parentFile, "$FILE_NAME.tmp")
                        tmp.writeText(kept.joinToString("\n", postfix = "\n"))
                        if (!tmp.renameTo(f)) f.writeText(tmp.readText())
                        lines = kept.size - 1
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "tilt log write failed", e)
            }
        }
    }

    /** Log the whole file to logcat (debug broadcast), in order with pending writes. */
    fun dumpToLogcat() {
        io.execute {
            val f = file()
            Log.i(TAG, "tilt log ${f.absolutePath} (${if (f.exists()) f.length() else 0} bytes)")
            if (f.exists()) f.forEachLine { Log.i(TAG, "| $it") }
        }
    }

    companion object {
        private const val TAG = "TiltLog"
        const val FILE_NAME = "tilt-log.csv"
    }
}
