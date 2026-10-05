package org.circa.watchlink

/** Classifies one phone-to-watch line (spec §2.4, §2.5). Pure Kotlin, host-tested. */
object Proto {
    const val KIND_EMPTY = 0
    const val KIND_GB = 1
    const val KIND_TIME = 2
    const val KIND_RAW = 3
    const val KIND_BAD_GB = 4

    class In {
        @JvmField var kind = 0
        @JvmField var msg: Map<String, Any?>? = null   // KIND_GB
        @JvmField var unixSec = 0L                     // KIND_TIME
        @JvmField var tzHours = Double.NaN
        @JvmField var error: String? = null            // KIND_BAD_GB
        fun type(): String? = msg?.let { str(it, "t") }
    }

    private val SET_TIME = Regex("""setTime\((\d+)\)""")
    private val SET_TZ = Regex("""E\.setTimeZone\((-?[0-9.]+(?:E-?\d+)?)\)""")

    @JvmStatic
    fun classify(line: String): In {
        val input = In()
        val l = line.trim { it <= ' ' }   // Java String.trim() semantics (Kotlin trim() also strips U+00A0)
        if (l.isEmpty()) { input.kind = KIND_EMPTY; return input }
        if (l.startsWith("GB(") && l.endsWith(")")) {
            try {
                val v = JsParser.parse(l.substring(3, l.length - 1))
                if (v !is Map<*, *>) throw JsParser.ParseException("GB() arg is not an object", 3)
                input.kind = KIND_GB
                @Suppress("UNCHECKED_CAST")
                input.msg = v as Map<String, Any?>
            } catch (e: JsParser.ParseException) {
                input.kind = KIND_BAD_GB
                input.error = e.message
            }
            return input
        }
        val m = SET_TIME.find(l)
        if (m != null) {
            input.kind = KIND_TIME
            input.unixSec = java.lang.Long.parseLong(m.groupValues[1])
            val z = SET_TZ.find(l)
            if (z != null) {
                try { input.tzHours = java.lang.Double.parseDouble(z.groupValues[1]) } catch (ignored: NumberFormatException) {}
            }
            return input
        }
        input.kind = KIND_RAW
        return input
    }

    fun str(m: Map<String, Any?>, k: String): String? = m[k]?.toString()

    fun num(m: Map<String, Any?>, k: String, def: Long): Long {
        val v = m[k]
        if (v is Number) return v.toLong()
        if (v is Boolean) return if (v) 1 else 0
        if (v is String) {
            try { return java.lang.Long.parseLong(v) } catch (ignored: NumberFormatException) {}
        }
        return def
    }

    fun bool(m: Map<String, Any?>, k: String, def: Boolean): Boolean {
        val v = m[k]
        if (v is Boolean) return v
        if (v is Number) return v.toDouble() != 0.0
        return def
    }
}
