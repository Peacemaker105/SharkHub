package com.chris.sharkhub.car.kanzi

import java.io.Closeable

/**
 * A Kanzi KZBF v3 container (Rightware's engine, which BYD's My Car and Rage Mode apps are built
 * on), read the way KZB-Explorer and our Python tools read it: `"KZBF"`, u32, u32 name length,
 * project name, u32 count, that many null-terminated resource paths, u32 count, that many
 * `{u32 id, u32 type, u32 offset, u32 size, u32 0, u32 size}` entries with absolute offsets. The
 * `/$strings`, `/$property_dictionary`, `/$metaclass_dictionary` and `/$property_types` entries
 * are the dictionaries every other record indexes into.
 */
class KzbFile(val source: ByteSource) : Closeable {
    class Entry(val id: Long, val type: Long, val offset: Long, val size: Long)

    val project: String
    val names: List<String>
    val dir: Map<String, Entry>
    val strings: List<String>
    val props: List<String>
    val metas: List<String>
    /** Index of the empty string in [strings] (object headers carry it), -1 when absent. */
    val emptyId: Int
    /** The project's own property types: name → Kanzi data type code. */
    val propertyTypes: Map<String, Int>

    init {
        var head = source.read(0, minOf(source.size, 4L shl 20).toInt())
        if (head.size < 16 || String(head, 0, 4, Charsets.US_ASCII) != "KZBF") throw ParseError("not a KZBF file")
        val nameLen = Bytes.u32(head, 8).toInt()
        project = String(head, 12, nameLen, Charsets.UTF_8)
        var pos = 12 + nameLen
        val count = Bytes.u32(head, pos).toInt(); pos += 4
        val names = ArrayList<String>(count)
        // the directory is small (≈150 KB) but its size is only known once the names are read: grow the head as needed
        fun ensure(bytes: Long) { if (bytes > head.size && bytes <= source.size) head = source.read(0, bytes.toInt()) }
        for (i in 0 until count) {
            ensure(pos + 4096L)
            val (n, next) = Bytes.cstr(head, pos)
            names.add(n); pos = next
        }
        val count2 = Bytes.u32(head, pos).toInt()
        val base = pos + 4
        ensure(base + count2 * 24L)
        val dir = LinkedHashMap<String, Entry>(count2)
        for (i in 0 until count2) {
            val p = base + i * 24
            dir[names[i]] = Entry(Bytes.u32(head, p), Bytes.u32(head, p + 4), Bytes.u32(head, p + 8), Bytes.u32(head, p + 12))
        }
        this.names = names
        this.dir = dir
        strings = table("/\$strings", cap = 50000)
        props = tableOrEmpty("/\$property_dictionary")
        metas = tableOrEmpty("/\$metaclass_dictionary")
        emptyId = strings.indexOf("")
        propertyTypes = readPropertyTypes()
    }

    fun has(name: String): Boolean = dir.containsKey(name)

    fun blob(name: String): ByteArray {
        val e = dir[name] ?: throw ParseError("no entry $name")
        return source.read(e.offset, e.size.toInt())
    }

    /** `u32 count` then null-terminated strings. */
    private fun table(name: String, cap: Int = Int.MAX_VALUE): List<String> {
        val b = blob(name)
        val n = minOf(Bytes.u32(b, 0), cap.toLong()).toInt()
        val out = ArrayList<String>(n)
        var p = 4
        for (i in 0 until n) {
            var e = p
            while (e < b.size && b[e] != 0.toByte()) e++
            if (e >= b.size) break
            out.add(String(b, p, e - p, Charsets.UTF_8))
            p = e + 1
        }
        return out
    }

    private fun tableOrEmpty(name: String): List<String> = if (has(name)) table(name) else emptyList()

    /** `/$property_types`: u32 count, records {u32 name string-id, u32 datatype, u32 flags, u32 x, default value}. */
    private fun readPropertyTypes(): Map<String, Int> {
        if (!has("/\$property_types")) return emptyMap()
        val b = blob("/\$property_types")
        val n = Bytes.u32(b, 0).toInt()
        val out = LinkedHashMap<String, Int>()
        var p = 4
        for (i in 0 until n) {
            if (p + 16 > b.size) break
            val nm = Bytes.u32(b, p).toInt(); val dt = Bytes.u32(b, p + 4).toInt()
            p += 16
            val sz = KanziTypes.SIZE[dt] ?: (4 + Bytes.u32(b, p).toInt())
            p += sz
            if (nm < strings.size) out[strings[nm]] = dt
        }
        return out
    }

    fun label(id: Long): String = if (id >= 0 && id < strings.size) strings[id.toInt()] else "#$id"

    val meshNames: List<String> get() = names.filter { it.startsWith("/Mesh Data/") }

    /** Resource-file entries whose name ends with [shortName] (the image files live under `/Resource Files/Images/`). */
    fun findResource(shortName: String): String? = names.firstOrNull { it.startsWith("/Resource Files/") && it.endsWith("/$shortName") }

    override fun close() = source.close()
}
