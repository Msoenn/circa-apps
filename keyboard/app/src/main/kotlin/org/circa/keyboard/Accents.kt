package org.circa.keyboard

/** Long-press alternatives per key (lower-case; upper-cased with shift). The key's own character comes first. */
object Accents {
    private val map = mapOf(
        "a" to "aàáâäãåāæ",
        "c" to "cçćč",
        "e" to "eèéêëēę",
        "i" to "iìíîïī",
        "n" to "nñń",
        "o" to "oòóôöõøōœ",
        "s" to "sßśš",
        "u" to "uùúûüū",
        "y" to "yýÿ",
        "z" to "zžźż",
        "l" to "lł",
        "d" to "dð",
        "g" to "gğ",
        "0" to "0°",
        "1" to "1¹½",
        "2" to "2²",
        "3" to "3³",
        "-" to "-–—_",
        // The letters page has only "," and "." - their long-press rows carry the rest of the common punctuation.
        "." to ".…!?-",
        "," to ",?!;:'",
        "?" to "?¿",
        "!" to "!¡",
        "$" to "$€£¥¢",
        "'" to "'‘’",
        "\"" to "\"“”«»",
        "@" to "@#",
        "/" to "/\\",
    )

    fun of(key: String): List<String> {
        val alts = map[key.lowercase()] ?: return emptyList()
        val upper = key.isNotEmpty() && key[0].isUpperCase()
        return alts.map { c -> if (upper) c.toString().uppercase().takeIf { it.length == 1 } ?: c.toString() else c.toString() }
            .distinct()
    }
}
