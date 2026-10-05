package org.circa.watchlink

/**
 * Reassembles Gadgetbridge's chunked GATT writes into lines (spec §2.3, §6a).
 * Splits only on 0x0A, strips one trailing 0x0D and any leading 0x10 (DLE, "don't echo").
 * Returned lines are Latin-1 Strings (one char per byte). Pure Kotlin, host-tested.
 */
class LineAssembler {
    private var buf = ByteArray(256)
    private var len = 0
    private var overflow = false
    var overflows = 0
        private set

    fun reset() {
        len = 0
        overflow = false
    }

    fun pending(): Int = len

    fun feed(data: ByteArray?): List<String> {
        val out = ArrayList<String>()
        if (data == null) return out
        for (b in data) {
            if (b == LF) {
                if (!overflow) out.add(finish())
                len = 0
                overflow = false
                continue
            }
            if (overflow) continue
            if (len == MAX_LINE) { overflow = true; overflows++; continue }
            if (len == buf.size) {
                val n = ByteArray(minOf(MAX_LINE, buf.size * 2))
                System.arraycopy(buf, 0, n, 0, len)
                buf = n
            }
            buf[len++] = b
        }
        return out
    }

    private fun finish(): String {
        var st = 0
        var end = len
        while (st < end && buf[st] == DLE) st++
        if (end > st && buf[end - 1] == CR) end--
        val c = CharArray(end - st)
        for (i in st until end) c[i - st] = (buf[i].toInt() and 0xff).toChar()
        return String(c)
    }

    companion object {
        const val MAX_LINE = 64 * 1024
        private const val LF: Byte = 0x0A
        private const val CR: Byte = 0x0D
        private const val DLE: Byte = 0x10
    }
}
