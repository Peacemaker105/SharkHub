package com.chris.sharkhub.car.kanzi

import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.util.zip.ZipFile

/** Thrown when a record does not fit the layout we expect — the parsers catch it the way the Python tools catch ValueError/struct.error. */
class ParseError(msg: String) : RuntimeException(msg)

/**
 * Random access over a large file (the kzb files are 70–85 MB): nothing is ever read whole, each
 * mesh blob or image payload is fetched on demand. A [base] offset lets a stored zip entry be read
 * in place inside its APK.
 */
class ByteSource(private val file: File, private val base: Long = 0, size: Long? = null) : Closeable {
    private val raf = RandomAccessFile(file, "r")
    val size: Long = size ?: (raf.length() - base)

    fun read(offset: Long, length: Int): ByteArray {
        if (offset < 0 || offset + length > size) throw ParseError("read past end: $offset+$length of $size")
        val out = ByteArray(length)
        raf.seek(base + offset)
        raf.readFully(out)
        return out
    }

    fun u32(offset: Long): Long = Bytes.u32(read(offset, 4), 0)

    /** Copies [length] bytes starting at [offset] into [dest] in chunks (image payloads of tens of MB). */
    fun copyTo(offset: Long, length: Long, dest: java.io.OutputStream, chunk: Int = 1 shl 20) {
        var left = length
        var at = offset
        val buf = ByteArray(chunk)
        raf.seek(base + at)
        while (left > 0) {
            val n = minOf(left, buf.size.toLong()).toInt()
            raf.readFully(buf, 0, n)
            dest.write(buf, 0, n)
            left -= n; at += n
        }
    }

    override fun close() = raf.close()
}

/** Little-endian readers over a byte array with bounds checks that raise [ParseError]. */
object Bytes {
    fun u32(b: ByteArray, p: Int): Long {
        if (p < 0 || p + 4 > b.size) throw ParseError("u32 at $p past ${b.size}")
        return ((b[p].toLong() and 0xFF) or ((b[p + 1].toLong() and 0xFF) shl 8) or ((b[p + 2].toLong() and 0xFF) shl 16) or ((b[p + 3].toLong() and 0xFF) shl 24))
    }

    fun i32(b: ByteArray, p: Int): Int {
        if (p < 0 || p + 4 > b.size) throw ParseError("i32 at $p past ${b.size}")
        return (b[p].toInt() and 0xFF) or ((b[p + 1].toInt() and 0xFF) shl 8) or ((b[p + 2].toInt() and 0xFF) shl 16) or ((b[p + 3].toInt() and 0xFF) shl 24)
    }

    fun u16(b: ByteArray, p: Int): Int {
        if (p < 0 || p + 2 > b.size) throw ParseError("u16 at $p past ${b.size}")
        return (b[p].toInt() and 0xFF) or ((b[p + 1].toInt() and 0xFF) shl 8)
    }

    fun f32(b: ByteArray, p: Int): Float = Float.fromBits(i32(b, p))

    /** IEEE 754 half → float, exactly (the mesh attributes are halves). */
    fun half(b: ByteArray, p: Int): Float = halfToFloat(u16(b, p))

    fun halfToFloat(h: Int): Float {
        val s = (h shr 15) and 1
        val e = (h shr 10) and 0x1F
        val m = h and 0x3FF
        val bits = when (e) {
            0 -> if (m == 0) s shl 31 else {
                // subnormal: normalise
                var mm = m; var ee = -1
                do { mm = mm shl 1; ee++ } while (mm and 0x400 == 0)
                (s shl 31) or ((127 - 15 - ee) shl 23) or ((mm and 0x3FF) shl 13)
            }
            0x1F -> (s shl 31) or 0x7F800000 or (m shl 13)
            else -> (s shl 31) or ((e - 15 + 127) shl 23) or (m shl 13)
        }
        return Float.fromBits(bits)
    }

    /** Null-terminated UTF-8 string at [p]; returns the text and the position after the terminator. */
    fun cstr(b: ByteArray, p: Int): Pair<String, Int> {
        var e = p
        while (e < b.size && b[e] != 0.toByte()) e++
        if (e >= b.size) throw ParseError("unterminated string at $p")
        return String(b, p, e - p, Charsets.UTF_8) to e + 1
    }

    fun putU32(b: ByteArray, p: Int, v: Long) {
        b[p] = v.toByte(); b[p + 1] = (v shr 8).toByte(); b[p + 2] = (v shr 16).toByte(); b[p + 3] = (v shr 24).toByte()
    }
}

/**
 * Finds a kzb inside an APK. BYD's My Car APK deflates its scene files (75 MB → 41 MB), so the entry
 * is inflated once into [cacheDir] and then read at random; a stored entry would be read in place.
 */
object ZipEntries {
    fun open(zip: File, entryName: String, cacheDir: File, onProgress: ((Long, Long) -> Unit)? = null): ByteSource {
        ZipFile(zip).use { zf ->
            val entry = zf.getEntry(entryName) ?: throw ParseError("$entryName not in ${zip.name}")
            if (entry.method == java.util.zip.ZipEntry.STORED) {
                storedOffset(zip, entryName)?.let { off -> return ByteSource(zip, off, entry.size) }
            }
            cacheDir.mkdirs()
            val cached = File(cacheDir, entryName.replace('/', '_') + ".raw")
            if (cached.length() != entry.size || cached.lastModified() < zip.lastModified()) {
                val tmp = File(cached.path + ".part")
                zf.getInputStream(entry).use { input ->
                    tmp.outputStream().buffered(1 shl 20).use { out ->
                        val buf = ByteArray(1 shl 20)
                        var done = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            onProgress?.invoke(done, entry.size)
                        }
                    }
                }
                if (cached.exists()) cached.delete()
                if (!tmp.renameTo(cached)) throw ParseError("could not place ${cached.name}")
            }
            return ByteSource(cached)
        }
    }

    /**
     * The data offset of a stored entry: the end-of-central-directory record → central directory →
     * the entry's local header. Null when the archive uses zip64 or the entry can't be located.
     */
    private fun storedOffset(zip: File, entryName: String): Long? = runCatching {
        RandomAccessFile(zip, "r").use { raf ->
            val len = raf.length()
            val tailLen = minOf(len, 66_000L).toInt()
            val tail = ByteArray(tailLen)
            raf.seek(len - tailLen); raf.readFully(tail)
            var eocd = -1
            for (i in tailLen - 22 downTo 0) if (Bytes.u32(tail, i) == 0x06054b50L) { eocd = i; break }
            if (eocd < 0) return null
            val cdSize = Bytes.u32(tail, eocd + 12)
            val cdOffset = Bytes.u32(tail, eocd + 16)
            if (cdOffset == 0xFFFFFFFFL || cdSize == 0xFFFFFFFFL) return null
            val cd = ByteArray(cdSize.toInt())
            raf.seek(cdOffset); raf.readFully(cd)
            var p = 0
            val want = entryName.toByteArray(Charsets.UTF_8)
            while (p + 46 <= cd.size && Bytes.u32(cd, p) == 0x02014b50L) {
                val nameLen = Bytes.u16(cd, p + 28); val extraLen = Bytes.u16(cd, p + 30); val commentLen = Bytes.u16(cd, p + 32)
                val localOff = Bytes.u32(cd, p + 42)
                val name = cd.copyOfRange(p + 46, p + 46 + nameLen)
                if (name.contentEquals(want)) {
                    val lh = ByteArray(30)
                    raf.seek(localOff); raf.readFully(lh)
                    if (Bytes.u32(lh, 0) != 0x04034b50L) return null
                    return localOff + 30 + Bytes.u16(lh, 26) + Bytes.u16(lh, 28)
                }
                p += 46 + nameLen + extraLen + commentLen
            }
            null
        }
    }.getOrNull()
}
