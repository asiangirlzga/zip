package com.example.kbz

import org.tukaani.xz.LZMA2Options
import org.tukaani.xz.XZInputStream
import org.tukaani.xz.XZOutputStream
import java.io.*
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream

/**
 * .kbz archive (version 2) - holds one or many files (folders are stored as relative paths).
 *
 *   "KBZ2"   magic
 *   int      entry count
 *   long     total original size
 *   per entry:
 *     byte   type   0 = stored, 1 = periodic pattern, 2 = deflate, 3 = xz (LZMA2)
 *     UTF    relative path
 *     long   original size
 *     ...    payload
 *               stored   : raw bytes
 *               periodic : int period + pattern bytes   (whole file is this pattern repeated)
 *               deflate/xz: chunked stream  [int len][bytes] ... [int 0]
 *
 * Everything is lossless. Random or already-compressed data (MP4, JPG, ZIP...) cannot be
 * made meaningfully smaller by any compressor.
 */
object KbzCodec {
    private val MAGIC = "KBZ2".toByteArray(Charsets.US_ASCII)
    private const val T_STORED = 0
    private const val T_PERIODIC = 1
    private const val T_DEFLATE = 2
    private const val T_XZ = 3

    private const val PROBE = 64 * 1024
    private const val BUF = 256 * 1024

    private val INCOMPRESSIBLE = setOf(
        "jpg", "jpeg", "png", "gif", "webp", "heic", "mp4", "mkv", "avi", "mov", "webm", "3gp",
        "mp3", "aac", "m4a", "ogg", "opus", "flac", "zip", "rar", "7z", "gz", "xz", "bz2", "zst",
        "apk", "docx", "xlsx", "pptx", "jar"
    )

    enum class Level { FAST, SMALL }

    class Entry(val path: String, var size: Long, val open: () -> InputStream)
    class FileResult(val path: String, val original: Long, val compressed: Long, val method: String)
    class Result(val files: List<FileResult>, val archiveSize: Long) {
        val originalTotal: Long get() = files.sumOf { it.original }
    }

    // ---------------------------------------------------------------- compress

    fun compress(
        entries: List<Entry>,
        out: OutputStream,
        level: Level,
        onProgress: (done: Long, total: Long) -> Unit
    ): Result {
        for (e in entries) if (e.size <= 0) e.size = e.open().use { countBytes(it) }
        val total = entries.sumOf { it.size }

        val counting = CountingOutputStream(BufferedOutputStream(out, BUF))
        val dos = DataOutputStream(counting)
        dos.write(MAGIC)
        dos.writeInt(entries.size)
        dos.writeLong(total)

        var done = 0L
        val results = ArrayList<FileResult>()
        for (e in entries) {
            val start = counting.count
            val base = done
            val method = writeEntry(e, dos, level) { d -> onProgress(base + d, total) }
            done += e.size
            results += FileResult(e.path, e.size, counting.count - start, method)
            onProgress(done, total)
        }
        dos.flush()
        return Result(results, counting.count)
    }

    private fun writeEntry(e: Entry, dos: DataOutputStream, level: Level, progress: (Long) -> Unit): String {
        val pattern = e.open().use { detectPeriodic(BufferedInputStream(it, BUF), e.size) }
        if (pattern != null) {
            dos.writeByte(T_PERIODIC)
            dos.writeUTF(e.path)
            dos.writeLong(e.size)
            dos.writeInt(pattern.size)
            dos.write(pattern)
            return "pattern"
        }

        val ext = e.path.substringAfterLast('.', "").lowercase()
        val type = when {
            ext in INCOMPRESSIBLE -> T_STORED
            level == Level.FAST -> T_DEFLATE
            else -> T_XZ
        }
        dos.writeByte(type)
        dos.writeUTF(e.path)
        dos.writeLong(e.size)

        e.open().use { ins ->
            when (type) {
                T_STORED -> {
                    var left = e.size
                    var done = 0L
                    val buf = ByteArray(BUF)
                    while (left > 0) {
                        val r = ins.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                        if (r < 0) throw EOFException("File ended early: ${e.path}")
                        dos.write(buf, 0, r)
                        left -= r; done += r
                        progress(done)
                    }
                }
                T_DEFLATE -> {
                    val chunked = ChunkedOutputStream(dos)
                    val deflater = Deflater(Deflater.BEST_COMPRESSION)
                    val dout = DeflaterOutputStream(chunked, deflater, BUF)
                    pump(ins, dout, progress)
                    dout.finish()
                    deflater.end()
                    chunked.finish()
                }
                else -> {
                    val chunked = ChunkedOutputStream(dos)
                    val xz = XZOutputStream(chunked, LZMA2Options(6))
                    pump(ins, xz, progress)
                    xz.finish()
                    chunked.finish()
                }
            }
        }
        return when (type) {
            T_STORED -> "stored"
            T_DEFLATE -> "deflate"
            else -> "xz"
        }
    }

    private fun pump(ins: InputStream, out: OutputStream, progress: (Long) -> Unit) {
        val buf = ByteArray(BUF)
        var done = 0L
        while (true) {
            val r = ins.read(buf)
            if (r < 0) break
            out.write(buf, 0, r)
            done += r
            progress(done)
        }
    }

    private fun countBytes(ins: InputStream): Long {
        val buf = ByteArray(BUF)
        var n = 0L
        while (true) {
            val r = ins.read(buf)
            if (r < 0) return n
            n += r
        }
    }

    /** Smallest period of buf[0 until n] (KMP failure function). */
    private fun smallestPeriod(buf: ByteArray, n: Int): Int {
        val fail = IntArray(n)
        var k = 0
        for (i in 1 until n) {
            while (k > 0 && buf[i] != buf[k]) k = fail[k - 1]
            if (buf[i] == buf[k]) k++
            fail[i] = k
        }
        return n - fail[n - 1]
    }

    /** The repeating pattern if the whole stream is that pattern repeated, else null. */
    private fun detectPeriodic(input: InputStream, length: Long): ByteArray? {
        if (length < 4096) return null
        val probe = ByteArray(minOf(PROBE.toLong(), length).toInt())
        DataInputStream(input).readFully(probe)
        val p = smallestPeriod(probe, probe.size)
        if (p * 2 > probe.size) return null
        var pos = probe.size.toLong()
        var idx = probe.size % p
        val buf = ByteArray(BUF)
        while (pos < length) {
            val r = input.read(buf)
            if (r < 0) return null
            for (i in 0 until r) {
                if (buf[i] != probe[idx]) return null
                idx++
                if (idx == p) idx = 0
            }
            pos += r
        }
        return probe.copyOf(p)
    }

    // ---------------------------------------------------------------- extract

    class Restored(val path: String, val size: Long)

    /** [openSink] is called once per file with its relative path and must return where to write it. */
    fun extract(
        ins: InputStream,
        openSink: (String) -> OutputStream,
        onProgress: (done: Long, total: Long) -> Unit
    ): List<Restored> {
        val din = DataInputStream(BufferedInputStream(ins, BUF))
        val m = ByteArray(4)
        din.readFully(m)
        if (!m.contentEquals(MAGIC)) throw IOException("Not a .kbz (v2) file")
        val count = din.readInt()
        val total = din.readLong()

        var done = 0L
        val restored = ArrayList<Restored>()
        repeat(count) {
            val type = din.readUnsignedByte()
            val path = safePath(din.readUTF())
            val len = din.readLong()
            val base = done
            openSink(path).use { sink ->
                val out = BufferedOutputStream(sink, BUF)
                when (type) {
                    T_STORED -> {
                        var left = len
                        var d = 0L
                        val buf = ByteArray(BUF)
                        while (left > 0) {
                            val r = din.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                            if (r < 0) throw EOFException("Archive is truncated")
                            out.write(buf, 0, r)
                            left -= r; d += r
                            onProgress(base + d, total)
                        }
                    }
                    T_PERIODIC -> {
                        val p = din.readInt()
                        val pattern = ByteArray(p)
                        din.readFully(pattern)
                        var blockLen = p
                        while (blockLen < BUF) blockLen += p
                        val block = ByteArray(blockLen) { pattern[it % p] }
                        var left = len
                        while (left > 0) {
                            val n = minOf(left, blockLen.toLong()).toInt()
                            out.write(block, 0, n)
                            left -= n
                            onProgress(base + (len - left), total)
                        }
                    }
                    T_DEFLATE, T_XZ -> {
                        val chunked = ChunkedInputStream(din)
                        val dec: InputStream =
                            if (type == T_DEFLATE) InflaterInputStream(chunked, Inflater(), BUF)
                            else XZInputStream(chunked)
                        val buf = ByteArray(BUF)
                        var d = 0L
                        while (true) {
                            val r = dec.read(buf)
                            if (r < 0) break
                            out.write(buf, 0, r)
                            d += r
                            onProgress(base + d, total)
                        }
                        while (chunked.read(buf) >= 0) { /* consume terminator */ }
                    }
                    else -> throw IOException("Unknown entry type $type")
                }
                out.flush()
            }
            done += len
            restored += Restored(path, len)
            onProgress(done, total)
        }
        return restored
    }

    private fun safePath(p: String): String {
        val parts = p.split('/').filter { it.isNotEmpty() }
        if (parts.isEmpty() || parts.any { it == "." || it == ".." }) throw IOException("Unsafe path in archive")
        return parts.joinToString("/")
    }

    // ---------------------------------------------------------------- stream helpers

    private class CountingOutputStream(private val o: OutputStream) : OutputStream() {
        var count = 0L
        override fun write(b: Int) { o.write(b); count++ }
        override fun write(b: ByteArray, off: Int, len: Int) { o.write(b, off, len); count += len }
        override fun flush() = o.flush()
    }

    /** Splits a stream into [int len][bytes] chunks and ends with [int 0], so it is self-delimiting. */
    private class ChunkedOutputStream(private val out: DataOutputStream) : OutputStream() {
        private val buf = ByteArray(64 * 1024)
        private var n = 0
        override fun write(b: Int) {
            buf[n++] = b.toByte()
            if (n == buf.size) flushChunk()
        }
        override fun write(b: ByteArray, off: Int, len: Int) {
            var o = off
            var l = len
            while (l > 0) {
                val c = minOf(l, buf.size - n)
                System.arraycopy(b, o, buf, n, c)
                n += c; o += c; l -= c
                if (n == buf.size) flushChunk()
            }
        }
        private fun flushChunk() {
            if (n > 0) { out.writeInt(n); out.write(buf, 0, n); n = 0 }
        }
        fun finish() { flushChunk(); out.writeInt(0) }
    }

    private class ChunkedInputStream(private val din: DataInputStream) : InputStream() {
        private var left = 0
        private var eof = false
        override fun read(): Int {
            val b = ByteArray(1)
            return if (read(b, 0, 1) < 0) -1 else b[0].toInt() and 0xFF
        }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            if (eof) return -1
            if (left == 0) {
                left = din.readInt()
                if (left == 0) { eof = true; return -1 }
            }
            val r = din.read(b, off, minOf(len, left))
            if (r < 0) throw EOFException("Archive is truncated")
            left -= r
            return r
        }
    }
}
