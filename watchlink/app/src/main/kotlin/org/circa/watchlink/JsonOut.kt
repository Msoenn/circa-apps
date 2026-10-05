package org.circa.watchlink

/**
 * ASCII-only JSON encoder for watch-to-phone lines (spec §2.6): every char outside 0x20-0x7E is a
 * `\uXXXX` escape, so no raw 0x11/0x13 (XON/XOFF) or non-Latin-1 byte ever reaches GB.
 * Output matches Python's `json.dumps(v, ensure_ascii=True, separators=(',',':'))` for
 * str/int/bool/None/list/dict, which the host tests and the harness rely on.
 */
object JsonOut {
    @JvmStatic
    fun encode(v: Any?): String {
        val sb = StringBuilder()
        write(sb, v)
        return sb.toString()
    }

    private fun write(sb: StringBuilder, v: Any?) {
        when (v) {
            null -> { sb.append("null"); return }
            is String -> { str(sb, v); return }
            is Boolean -> { sb.append(if (v) "true" else "false"); return }
            is Double, is Float -> {
                val d = (v as Number).toDouble()
                if (d.isNaN() || d.isInfinite()) { sb.append("null"); return }
                sb.append(java.lang.Double.toString(d))
                return
            }
            is Number -> { sb.append(v.toLong()); return }
            is Map<*, *> -> {
                sb.append('{')
                var first = true
                for ((k, value) in v) {
                    if (!first) sb.append(',')
                    first = false
                    str(sb, k as String)
                    sb.append(':')
                    write(sb, value)
                }
                sb.append('}')
                return
            }
            is List<*> -> {
                sb.append('[')
                var first = true
                for (o in v) {
                    if (!first) sb.append(',')
                    first = false
                    write(sb, o)
                }
                sb.append(']')
                return
            }
            else -> str(sb, v.toString())
        }
    }

    private val HEX = "0123456789abcdef".toCharArray()

    private fun str(sb: StringBuilder, s: String) {
        sb.append('"')
        for (i in 0 until s.length) {
            when (val c = s[i]) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000c' -> sb.append("\\f")
                else ->
                    if (c < ' ' || c > '~') {
                        sb.append("\\u").append(HEX[(c.code shr 12) and 15]).append(HEX[(c.code shr 8) and 15])
                            .append(HEX[(c.code shr 4) and 15]).append(HEX[c.code and 15])
                    } else sb.append(c)
            }
        }
        sb.append('"')
    }

    /** Printable, log-safe rendering of a Latin-1 line: non-printables become \xHH. */
    @JvmStatic
    fun escapeRaw(s: String): String {
        val sb = StringBuilder(s.length + 16)
        for (i in 0 until s.length) {
            val c = s[i]
            if (c >= ' ' && c <= '~' && c != '\\') sb.append(c)
            else if (c == '\\') sb.append("\\\\")
            else sb.append("\\x").append(HEX[(c.code shr 4) and 15]).append(HEX[c.code and 15])
        }
        return sb.toString()
    }
}
