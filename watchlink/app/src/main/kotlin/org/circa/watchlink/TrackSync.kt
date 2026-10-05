package org.circa.watchlink

/**
 * Gadgetbridge's Bangle.js recorder-track protocol (watchlink/README.md), pure so the host tests
 * cover it. Mirrors BangleApps `apps/android/lib.js` (`listRecs` / `fetchRec`) and GB's `BangleJSActivityTrack`:
 *
 *  - `{"t":"listRecs","id":<last synced>}` -> `{"t":"actTrksList","list":[ids after it]}`
 *  - `{"t":"fetchRec","id":<id>,"last":"true"|"false"}` -> `actTrk` packets, `cnt` 0, 1, 2, ... (GB aborts on a gap):
 *    cnt 0 = `"lines":"erase"`, then the CSV, the end packet has NO `lines` key.
 *
 * GB appends each packet's `lines` to its file verbatim (FileUtils.copyStringToFile, no separator added), so every
 * line in a packet carries its own trailing "\n" - exactly what Espruino's `readLine()` returns on the Bangle.
 */
object TrackSync {
    /** GB's default `lastSportsActivityIdBangleJS`. */
    const val DEFAULT_ID = "19700101a"

    /** `id` of a fetchRec that aborts the transfer. */
    const val STOP_ID = "stop"

    /** CSV lines per packet (the Bangle sends 4: one readLine plus three more). */
    const val LINES_PER_PACKET = 4

    /** Pause between two packets (the Bangle's setInterval(sendlines, 50)); GB times out after 5 s of silence. */
    const val PACKET_INTERVAL_MS = 50L

    /** `listRecs` / `fetchRec` id: missing or blank means the default. */
    @JvmStatic
    fun idOf(m: Map<String, Any?>): String {
        val v = m["id"]
        val s = if (v is String) v.trim() else v?.toString()?.trim()
        return if (s.isNullOrEmpty()) DEFAULT_ID else s
    }

    /** `last` arrives as the string "true"/"false" (GB's String.valueOf); a real boolean is accepted too. */
    @JvmStatic
    fun lastOf(m: Map<String, Any?>): Boolean = when (val v = m["last"]) {
        is Boolean -> v
        is String -> v.trim().equals("true", ignoreCase = true)
        else -> false
    }

    @JvmStatic
    fun listReply(ids: List<String>): LinkedHashMap<String, Any?> {
        val m = LinkedHashMap<String, Any?>()
        m["t"] = "actTrksList"
        m["list"] = ArrayList<Any?>(ids)
        return m
    }

    /**
     * The CSV split into lines for the wire: "\r\n" and "\r" normalised to "\n", blank lines dropped (GB drops rows
     * whose cell count differs from the header anyway), every line ending in "\n".
     */
    @JvmStatic
    fun csvLines(csv: String): List<String> =
        csv.replace("\r\n", "\n").replace('\r', '\n').split('\n')
            .filter { it.isNotBlank() }
            .map { it + "\n" }

    /** Every packet of one track, in order: erase (cnt 0), data packets, the end packet (no `lines`). */
    @JvmStatic
    @JvmOverloads
    fun packets(id: String, csv: String, linesPerPacket: Int = LINES_PER_PACKET): List<LinkedHashMap<String, Any?>> {
        require(linesPerPacket > 0)
        val out = ArrayList<LinkedHashMap<String, Any?>>()
        var cnt = 0
        out.add(packet(id, "erase", cnt++))
        for (chunk in csvLines(csv).chunked(linesPerPacket)) out.add(packet(id, chunk.joinToString(""), cnt++))
        out.add(packet(id, null, cnt))
        return out
    }

    private fun packet(id: String, lines: String?, cnt: Int): LinkedHashMap<String, Any?> {
        val m = LinkedHashMap<String, Any?>()
        m["t"] = "actTrk"
        m["log"] = id
        if (lines != null) m["lines"] = lines
        m["cnt"] = cnt
        return m
    }
}
