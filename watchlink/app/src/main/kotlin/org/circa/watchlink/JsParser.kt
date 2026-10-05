package org.circa.watchlink

import java.util.Base64
import java.util.LinkedHashMap

/**
 * Parser for the JavaScript-literal subset Gadgetbridge sends inside `GB(...)` (spec §2.4).
 *
 * Input is a String whose chars are the raw Latin-1 bytes of the line (one byte = one char, U+0000-U+00FF).
 * Output: LinkedHashMap (objects, insertion order), ArrayList (arrays), String, Long (integers),
 * Double (other numbers), Boolean, null. String results are UTF-16 code-unit sequences, so escaped
 * surrogate pairs (`😀`) become the right chars.
 *
 * Pure Kotlin (no android.*) so it is unit-tested on the host JVM against Gadgetbridge's own encoder.
 */
class JsParser private constructor(private val s: String) {

    class ParseException(msg: String, pos: Int) : Exception("$msg at $pos")

    private var p = 0

    fun ws() {
        while (p < s.length) {
            val c = s[p]
            if (c == ' ' || c == '\t' || c == '\r' || c == '\n') p++ else break
        }
    }

    private fun peek(): Char {
        if (p >= s.length) throw ParseException("unexpected end", p)
        return s[p]
    }

    private fun expect(c: Char) {
        if (peek() != c) throw ParseException("expected '$c' got '${s[p]}'", p)
        p++
    }

    private fun startsWith(w: String) = s.startsWith(w, p)

    private fun value(): Any? {
        val c = peek()
        if (c == '{') return obj()
        if (c == '[') return array()
        if (c == '"' || c == '\'') return string()
        if (c == '-' || c == '+' || c == '.' || (c >= '0' && c <= '9')) return number()
        if (startsWith("true")) { p += 4; return true }
        if (startsWith("false")) { p += 5; return false }
        if (startsWith("null")) { p += 4; return null }
        if (startsWith("undefined")) { p += 9; return null }
        if (startsWith("NaN")) { p += 3; return Double.NaN }
        if (startsWith("Infinity")) { p += 8; return Double.POSITIVE_INFINITY }
        if (startsWith("atob")) return atob()
        throw ParseException("unexpected char '$c'", p)
    }

    private fun obj(): LinkedHashMap<String, Any?> {
        expect('{')
        val m = LinkedHashMap<String, Any?>()
        ws()
        if (peek() == '}') { p++; return m }
        while (true) {
            ws()
            val key: String
            var c = peek()
            key = if (c == '"' || c == '\'') string() else ident()
            ws()
            expect(':')
            ws()
            m[key] = value()
            ws()
            c = peek()
            p++
            if (c == '}') return m
            if (c != ',') throw ParseException("expected ',' or '}'", p - 1)
        }
    }

    private fun ident(): String {
        val st = p
        while (p < s.length) {
            val c = s[p]
            if (Character.isLetterOrDigit(c) || c == '_' || c == '$') p++ else break
        }
        if (st == p) throw ParseException("expected key", p)
        return s.substring(st, p)
    }

    private fun array(): ArrayList<Any?> {
        expect('[')
        val a = ArrayList<Any?>()
        ws()
        if (peek() == ']') { p++; return a }
        while (true) {
            ws()
            a.add(value())
            ws()
            val c = peek()
            p++
            if (c == ']') return a
            if (c != ',') throw ParseException("expected ',' or ']'", p - 1)
        }
    }

    private fun number(): Any {
        val st = p
        if (peek() == '-' || peek() == '+') p++
        var frac = false
        while (p < s.length) {
            val c = s[p]
            if (c in '0'..'9') p++
            else if (c == '.' || c == 'e' || c == 'E') { frac = true; p++ }
            else if ((c == '-' || c == '+') && (s[p - 1] == 'e' || s[p - 1] == 'E')) p++
            else break
        }
        var t = s.substring(st, p)
        try {
            if (!frac) {
                if (t.startsWith("+")) t = t.substring(1)
                return java.lang.Long.parseLong(t)
            }
            return java.lang.Double.parseDouble(t)
        } catch (e: NumberFormatException) {
            throw ParseException("bad number '$t'", st)
        }
    }

    private fun atob(): String {
        p += 4
        ws()
        expect('(')
        ws()
        val b64 = string()
        ws()
        expect(')')
        val bytes: ByteArray
        try {
            bytes = Base64.getMimeDecoder().decode(b64)
        } catch (e: IllegalArgumentException) {
            throw ParseException("bad base64", p)
        }
        return latin1(bytes, bytes.size)
    }

    private fun hexN(n: Int): Int {
        if (p + n > s.length) throw ParseException("short hex escape", p)
        var v = 0
        for (i in 0 until n) {
            val h = hex(s[p + i])
            if (h < 0) throw ParseException("bad hex escape", p + i)
            v = v * 16 + h
        }
        p += n
        return v
    }

    private fun string(): String {
        val q = peek()
        p++
        val sb = StringBuilder()
        while (true) {
            if (p >= s.length) throw ParseException("unterminated string", p)
            val c = s[p++]
            if (c == q) return sb.toString()
            if (c != '\\') { sb.append(c); continue }
            if (p >= s.length) throw ParseException("dangling backslash", p)
            when (val e = s[p++]) {
                'b' -> sb.append('\b')
                't' -> sb.append('\t')
                'n' -> sb.append('\n')
                'v' -> sb.append('\u000b')
                'f' -> sb.append('\u000c')
                'r' -> sb.append('\r')
                'x' -> sb.append(hexN(2).toChar())
                'u' -> {
                    if (p < s.length && s[p] == '{') {
                        val end = s.indexOf('}', p)
                        if (end < 0) throw ParseException("bad \\u{}", p)
                        val cp: Int
                        try {
                            cp = Integer.parseInt(s.substring(p + 1, end), 16)
                        } catch (ex: NumberFormatException) {
                            throw ParseException("bad \\u{}", p)
                        }
                        sb.appendCodePoint(cp)
                        p = end + 1
                    } else {
                        sb.append(hexN(4).toChar())
                    }
                }
                else -> {
                    if (e in '0'..'7') {
                        // Legacy octal. JS reads \[0-3][0-7]{0,2} or \[4-7][0-7]?, but we cap at 2 digits on purpose:
                        // GB never emits a 3-digit octal. Its only multi-digit one is \20 (DLE, BJS:425), and when a
                        // digit 0-7 follows the DLE, JS (and a real Bangle) would misread "\20" + "7" as \207 = U+0087.
                        // With the cap we recover what the phone meant: DLE then '7'. Host-tested (HostTest P5c, fuzz).
                        var v = e - '0'
                        val maxMore = 1
                        var i = 0
                        while (i < maxMore && p < s.length && s[p] in '0'..'7') {
                            v = v * 8 + (s[p++] - '0')
                            i++
                        }
                        sb.append(v.toChar())
                    } else {
                        sb.append(e) // \" \\ \' \/ and any other \c -> c
                    }
                }
            }
        }
    }

    companion object {
        /** Parse exactly one value; trailing non-whitespace is an error. */
        @JvmStatic
        @Throws(ParseException::class)
        fun parse(s: String): Any? {
            val jp = JsParser(s)
            jp.ws()
            val v = jp.value()
            jp.ws()
            if (jp.p != s.length) throw ParseException("trailing data", jp.p)
            return v
        }

        /** Bytes to a String with one char per byte (ISO-8859-1). */
        @JvmStatic
        fun latin1(b: ByteArray, len: Int): String {
            val c = CharArray(len)
            for (i in 0 until len) c[i] = (b[i].toInt() and 0xff).toChar()
            return String(c)
        }

        private fun hex(c: Char): Int = when (c) {
            in '0'..'9' -> c - '0'
            in 'a'..'f' -> c - 'a' + 10
            in 'A'..'F' -> c - 'A' + 10
            else -> -1
        }
    }
}
