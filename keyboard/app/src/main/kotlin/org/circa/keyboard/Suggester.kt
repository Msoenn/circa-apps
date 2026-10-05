package org.circa.keyboard

import kotlin.math.ln

/**
 * Word completion from a bundled frequency list plus words the user typed before. Pure Kotlin, unit-tested
 * in SuggesterTest. The service feeds it the word left of the cursor and shows the top three in the strip.
 *
 * Ranking: score = frequency (the LatinIME 0-255 unigram value, scaled to 0..1)
 *                + 0.25 * ln(1 + times the user committed this word) (capped, so learned words rise
 *                  quickly but a single typo never outranks a common word)
 *                + 0.6 if the candidate equals the typed word (it is a real word: keep it in front), except
 *                  when the typed word is lower case and the match is a capitalised name ("tom" -> "Tom").
 * Ties go to the shorter word, then alphabetical. Case follows the typed word: "Th" -> "The", "TH" -> "THE";
 * a word stored capitalised ("I", "Monday") keeps its capitals.
 */
class Suggester(words: List<Pair<String, Int>>) {
    private class Entry(val word: String, val lower: String, val freq: Float)

    /** Sorted by lower-case spelling so a prefix is a contiguous range (binary search). */
    private val entries: List<Entry>
    private val byLower = HashMap<String, Entry>()
    private val learned = HashMap<String, Int>()

    init {
        val list = ArrayList<Entry>(words.size)
        for ((w, f) in words) {
            val lower = w.lowercase()
            if (lower.isEmpty() || byLower.containsKey(lower)) continue
            val e = Entry(w, lower, (f.coerceIn(0, 255)) / 255f)
            byLower[lower] = e
            list += e
        }
        list.sortBy { it.lower }
        entries = list
    }

    val size: Int get() = entries.size

    fun isKnown(word: String): Boolean = byLower.containsKey(word.lowercase()) || learned.containsKey(word.lowercase())

    fun learnedCount(word: String): Int = learned[word.lowercase()] ?: 0

    /** Remember one more use of [word] (the caller decides whether learning is allowed). */
    fun learn(word: String) {
        val w = word.trim().trim('\'')
        if (w.length < 2 || w.length > 32 || !w.all { it.isLetter() || it == '\'' }) return
        val k = w.lowercase()
        learned[k] = (learned[k] ?: 0) + 1
        if (learned.size > MAX_LEARNED) {
            // Drop the least-used entry; ties broken arbitrarily. Rare (bounded size), so a scan is fine.
            learned.minByOrNull { it.value }?.key?.let { learned.remove(it) }
        }
    }

    fun learnedSnapshot(): Map<String, Int> = HashMap(learned)

    fun restoreLearned(m: Map<String, Int>) {
        learned.clear()
        m.forEach { (k, v) -> if (k.isNotBlank() && v > 0) learned[k.lowercase()] = v }
    }

    fun forget() = learned.clear()

    /** Up to [limit] completions of [typed] (the word left of the cursor), best first. */
    fun suggest(typed: String, limit: Int = 3): List<String> {
        if (typed.isEmpty() || !typed.any { it.isLetter() }) return emptyList()
        val prefix = typed.lowercase()
        val scored = HashMap<String, Float>()
        val display = HashMap<String, String>()

        // Dictionary range [lo, hi) of entries starting with prefix.
        var lo = lowerBound(prefix)
        while (lo < entries.size && entries[lo].lower.startsWith(prefix)) {
            val e = entries[lo]
            scored[e.lower] = e.freq
            display[e.lower] = e.word
            lo++
        }
        for ((k, n) in learned) {
            if (!k.startsWith(prefix)) continue
            val base = scored[k] ?: 0f
            scored[k] = base + 0.25f * ln(1f + n.coerceAtMost(20))
            if (k !in display) display[k] = k
        }
        // The typed word itself stays in front when it is a real word - unless it only matches a capitalised
        // name ("tom" vs "Tom"): then it competes on frequency like any other completion.
        scored[prefix]?.let { sc ->
            val shown = display[prefix] ?: prefix
            // ("i" -> "I" keeps the bonus: a one-letter word is never a name.)
            if (!shown.first().isUpperCase() || typed.first().isUpperCase() || shown.length == 1) scored[prefix] = sc + 0.6f
        }

        return scored.entries
            .sortedWith(compareByDescending<Map.Entry<String, Float>> { it.value }.thenBy { it.key.length }.thenBy { it.key })
            .take(limit)
            .map { matchCase(typed, display[it.key] ?: it.key) }
    }

    private fun lowerBound(prefix: String): Int {
        var lo = 0
        var hi = entries.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (entries[mid].lower < prefix) lo = mid + 1 else hi = mid
        }
        return lo
    }

    companion object {
        const val MAX_LEARNED = 2000

        /** Parse the bundled list: "word<TAB>freq" lines, '#' comments. */
        fun parse(lines: Sequence<String>): List<Pair<String, Int>> = lines
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .mapNotNull { line ->
                val tab = line.indexOf('\t')
                if (tab <= 0) null else line.substring(0, tab) to (line.substring(tab + 1).trim().toIntOrNull() ?: 0)
            }
            .toList()

        fun matchCase(typed: String, word: String): String = when {
            typed.length > 1 && typed.all { !it.isLetter() || it.isUpperCase() } -> word.uppercase()
            typed.first().isUpperCase() -> word.replaceFirstChar { it.uppercaseChar() }
            else -> word
        }

        /** The word immediately left of the cursor in [before] (letters and inner apostrophes), or "". */
        fun currentWord(before: CharSequence?): String {
            if (before.isNullOrEmpty()) return ""
            var i = before.length
            while (i > 0 && (before[i - 1].isLetter() || before[i - 1] == '\'')) i--
            return before.subSequence(i, before.length).toString().trimStart('\'')
        }
    }
}
