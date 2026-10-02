package com.chris.sharkhub.util

/**
 * A small JSON reader/writer over plain Kotlin values, for code that must run identically on the
 * JVM (unit tests, where Android's org.json is a stub) and on the device: the Kanzi decoder's GLB
 * JSON, the bake rig and meta files. Objects are `Map<String, Any?>` (insertion-ordered), arrays
 * `List<Any?>`, numbers `Long` when written without a fraction or exponent and `Double` otherwise
 * (so a meta's integer pixel counts stay integers through a round trip, the way Python's json does).
 */
object Json {
    fun parse(text: String): Any? = Parser(text).parseDocument()

    /** Compact (`indent == null`, like Python's `separators=(",", ":")`) or pretty-printed with `indent` spaces per level. */
    fun write(value: Any?, indent: Int? = null): String = StringBuilder().also { writeValue(it, value, indent, 0) }.toString()

    fun obj(vararg pairs: Pair<String, Any?>): MutableMap<String, Any?> = linkedMapOf(*pairs)

    @Suppress("UNCHECKED_CAST")
    fun Any?.asObject(): MutableMap<String, Any?>? = this as? MutableMap<String, Any?>
    @Suppress("UNCHECKED_CAST")
    fun Any?.asList(): List<Any?>? = this as? List<Any?>
    fun Any?.asDouble(): Double? = (this as? Number)?.toDouble()
    fun Any?.asInt(): Int? = (this as? Number)?.toInt()
    fun Any?.asString(): String? = this as? String
    fun Any?.asDoubleList(): DoubleArray? = asList()?.map { (it as? Number)?.toDouble() ?: return null }?.toDoubleArray()

    private fun writeValue(sb: StringBuilder, v: Any?, indent: Int?, level: Int) {
        when (v) {
            null -> sb.append("null")
            is Boolean -> sb.append(if (v) "true" else "false")
            is Int, is Long, is Short, is Byte -> sb.append(v.toString())
            is Float -> writeDouble(sb, v.toDouble())
            is Double -> writeDouble(sb, v)
            is Number -> writeDouble(sb, v.toDouble())
            is String -> writeString(sb, v)
            is Map<*, *> -> {
                if (v.isEmpty()) { sb.append("{}"); return }
                sb.append('{')
                var first = true
                for ((k, value) in v) {
                    if (!first) sb.append(',')
                    first = false
                    newline(sb, indent, level + 1)
                    writeString(sb, k.toString())
                    sb.append(if (indent == null) ":" else ": ")
                    writeValue(sb, value, indent, level + 1)
                }
                newline(sb, indent, level)
                sb.append('}')
            }
            is Iterable<*> -> {
                val items = v.toList()
                if (items.isEmpty()) { sb.append("[]"); return }
                sb.append('[')
                items.forEachIndexed { i, item ->
                    if (i > 0) sb.append(',')
                    newline(sb, indent, level + 1)
                    writeValue(sb, item, indent, level + 1)
                }
                newline(sb, indent, level)
                sb.append(']')
            }
            is DoubleArray -> writeValue(sb, v.toList(), indent, level)
            is FloatArray -> writeValue(sb, v.map { it.toDouble() }, indent, level)
            is IntArray -> writeValue(sb, v.toList(), indent, level)
            is LongArray -> writeValue(sb, v.toList(), indent, level)
            is Array<*> -> writeValue(sb, v.toList(), indent, level)
            else -> writeString(sb, v.toString())
        }
    }

    private fun newline(sb: StringBuilder, indent: Int?, level: Int) {
        if (indent == null) return
        sb.append('\n')
        repeat(indent * level) { sb.append(' ') }
    }

    private fun writeDouble(sb: StringBuilder, d: Double) {
        when {
            d.isNaN() || d.isInfinite() -> sb.append("null")
            d == Math.rint(d) && kotlin.math.abs(d) < 1e15 -> sb.append(d.toLong().toString()).append(".0")
            else -> sb.append(d.toString())
        }
    }

    private fun writeString(sb: StringBuilder, s: String) {
        sb.append('"')
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                else -> if (c < ' ') sb.append(String.format("\\u%04x", c.code)) else sb.append(c)
            }
        }
        sb.append('"')
    }

    private class Parser(private val s: String) {
        private var i = 0

        fun parseDocument(): Any? {
            val v = parseValue()
            skipWs()
            if (i != s.length) fail("trailing data")
            return v
        }

        private fun fail(msg: String): Nothing = throw IllegalArgumentException("JSON: $msg at $i")

        private fun skipWs() { while (i < s.length && s[i].isWhitespace()) i++ }

        private fun parseValue(): Any? {
            skipWs()
            if (i >= s.length) fail("unexpected end")
            return when (val c = s[i]) {
                '{' -> parseObject()
                '[' -> parseArray()
                '"' -> parseString()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> if (c == '-' || c.isDigit()) parseNumber() else fail("unexpected '$c'")
            }
        }

        private fun literal(word: String, value: Any?): Any? {
            if (!s.startsWith(word, i)) fail("bad literal")
            i += word.length
            return value
        }

        private fun parseObject(): MutableMap<String, Any?> {
            val out = LinkedHashMap<String, Any?>()
            i++ // {
            skipWs()
            if (i < s.length && s[i] == '}') { i++; return out }
            while (true) {
                skipWs()
                if (i >= s.length || s[i] != '"') fail("expected key")
                val k = parseString()
                skipWs()
                if (i >= s.length || s[i] != ':') fail("expected ':'")
                i++
                out[k] = parseValue()
                skipWs()
                if (i >= s.length) fail("unterminated object")
                when (s[i]) {
                    ',' -> i++
                    '}' -> { i++; return out }
                    else -> fail("expected ',' or '}'")
                }
            }
        }

        private fun parseArray(): MutableList<Any?> {
            val out = ArrayList<Any?>()
            i++ // [
            skipWs()
            if (i < s.length && s[i] == ']') { i++; return out }
            while (true) {
                out.add(parseValue())
                skipWs()
                if (i >= s.length) fail("unterminated array")
                when (s[i]) {
                    ',' -> i++
                    ']' -> { i++; return out }
                    else -> fail("expected ',' or ']'")
                }
            }
        }

        private fun parseString(): String {
            i++ // opening quote
            val sb = StringBuilder()
            while (true) {
                if (i >= s.length) fail("unterminated string")
                val c = s[i++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        if (i >= s.length) fail("bad escape")
                        when (val e = s[i++]) {
                            '"' -> sb.append('"'); '\\' -> sb.append('\\'); '/' -> sb.append('/')
                            'b' -> sb.append('\b'); 'f' -> sb.append('\u000C'); 'n' -> sb.append('\n')
                            'r' -> sb.append('\r'); 't' -> sb.append('\t')
                            'u' -> {
                                if (i + 4 > s.length) fail("bad unicode escape")
                                sb.append(s.substring(i, i + 4).toInt(16).toChar())
                                i += 4
                            }
                            else -> fail("bad escape '\\$e'")
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }

        private fun parseNumber(): Any {
            val start = i
            if (s[i] == '-') i++
            while (i < s.length && s[i].isDigit()) i++
            var isDouble = false
            if (i < s.length && s[i] == '.') { isDouble = true; i++; while (i < s.length && s[i].isDigit()) i++ }
            if (i < s.length && (s[i] == 'e' || s[i] == 'E')) {
                isDouble = true; i++
                if (i < s.length && (s[i] == '+' || s[i] == '-')) i++
                while (i < s.length && s[i].isDigit()) i++
            }
            val text = s.substring(start, i)
            return if (isDouble) text.toDouble() else (text.toLongOrNull() ?: text.toDouble())
        }
    }
}
