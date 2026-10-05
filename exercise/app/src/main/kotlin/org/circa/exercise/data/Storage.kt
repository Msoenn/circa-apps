package org.circa.exercise.data

import android.content.Context
import org.circa.exercise.model.ActivityType
import org.circa.exercise.model.BangleCsv
import org.circa.exercise.model.Summary
import org.circa.exercise.model.Workout
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.ZoneId

/**
 * Files (app files dir, credential-encrypted storage):
 *
 * - `active/state.json`  snapshot of the running [Workout] (rewritten atomically every few seconds and on every
 *                        state change; this is what a restarted service restores)
 * - `active/track.csv`   the Bangle CSV of the running workout, append-only (header written at start)
 * - `active/summary.json` the summary of a finished workout waiting for "Done"
 * - `tracks/<id>.csv` + `tracks/<id>.json`  saved workouts (what the sync provider serves)
 * - `last_type`          id of the last *saved* activity (written by [commit], never by a discarded one)
 */
class Storage internal constructor(private val root: File) {
    constructor(context: Context) : this(context.applicationContext.filesDir)
    private val activeDir = File(root, "active")
    private val tracksDir = File(root, "tracks")
    private val stateFile = File(activeDir, "state.json")
    private val trackFile = File(activeDir, "track.csv")
    private val pendingFile = File(activeDir, "summary.json")
    private val lastFile = File(root, "last_type")

    fun loadActive(): Workout? = runCatching {
        if (!stateFile.exists()) null else Workout.fromJson(JSONObject(stateFile.readText()))
    }.getOrNull()

    fun loadPending(): Summary? = runCatching {
        if (!pendingFile.exists()) null else Summary.fromJson(JSONObject(pendingFile.readText()))
    }.getOrNull()

    @Synchronized fun begin(w: Workout) {
        activeDir.deleteRecursively()
        activeDir.mkdirs()
        trackFile.writeText(BangleCsv.HEADER + "\n")
        writeAtomic(stateFile, w.toJson().toString())
    }

    /** Append CSV rows and rewrite the snapshot (called every few seconds and on state changes). */
    @Synchronized fun flush(rows: List<String>, snapshot: String) {
        if (!activeDir.exists()) return
        if (rows.isNotEmpty()) trackFile.appendText(rows.joinToString("\n", postfix = "\n"))
        writeAtomic(stateFile, snapshot)
    }

    /** At End: blank the positions of fixes found to be outliers ([Workout.gpsDropSpans]) in the active CSV. */
    @Synchronized fun scrubGps(spans: List<LongArray>) {
        if (spans.isEmpty() || !trackFile.exists()) return
        val tmp = File(activeDir, "track.csv.tmp")
        tmp.writeText(BangleCsv.dropGps(trackFile.readText(), spans))
        if (!tmp.renameTo(trackFile)) { trackFile.writeText(tmp.readText()); tmp.delete() }
    }

    @Synchronized fun savePending(s: Summary) { if (activeDir.exists()) writeAtomic(pendingFile, s.toJson().toString()) }

    /** "Done": the finished workout becomes `tracks/<id>.{csv,json}`; returns the id. Only saving updates `last_type`. */
    @Synchronized fun commit(s: Summary, zone: ZoneId = ZoneId.systemDefault()): String {
        tracksDir.mkdirs()
        val date = Instant.ofEpochMilli(s.startMs).atZone(zone).toLocalDate()
        val id = BangleCsv.nextId(ids(), date)
        val csv = File(tracksDir, "$id.csv")
        if (trackFile.exists()) trackFile.copyTo(csv, overwrite = true) else csv.writeText(BangleCsv.HEADER + "\n")
        writeAtomic(File(tracksDir, "$id.json"), s.copy(id = id).toJson().toString())
        lastFile.writeText(s.type.id)
        activeDir.deleteRecursively()
        return id
    }

    @Synchronized fun discard() { activeDir.deleteRecursively() }

    fun lastType(): ActivityType? = runCatching { ActivityType.fromId(lastFile.readText().trim()) }.getOrNull()

    /** Saved ids (those with a CSV). */
    fun ids(): List<String> = tracksDir.list()?.filter { it.endsWith(".csv") }?.map { it.removeSuffix(".csv") }
        ?.filter { BangleCsv.isValidId(it) }?.sorted() ?: emptyList()

    fun csv(id: String): String? = if (!BangleCsv.isValidId(id)) null else File(tracksDir, "$id.csv").takeIf { it.exists() }?.readText()

    fun history(): List<Summary> = tracksDir.listFiles { f -> f.name.endsWith(".json") }?.mapNotNull {
        runCatching { Summary.fromJson(JSONObject(it.readText())) }.getOrNull()
    } ?: emptyList()

    private fun writeAtomic(f: File, text: String) {
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(f)) { f.writeText(text); tmp.delete() }
    }
}
