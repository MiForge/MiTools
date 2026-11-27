package com.offici5l.mitools.miflash

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.EOFException
import java.io.File
import java.io.FileOutputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.util.zip.GZIPInputStream

object TarGz {

    private class Counting(input: InputStream) : FilterInputStream(input) {
        var count = 0L

        override fun read(): Int {
            val r = super.read()
            if (r >= 0) count++
            return r
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val r = super.read(b, off, len)
            if (r > 0) count += r
            return r
        }
    }

    private val SCRIPT = Regex(".+\\.sh", RegexOption.IGNORE_CASE)

    fun extract(
        input: InputStream,
        compressedSize: Long,
        outDir: File,
        check: () -> Unit,
        onProgress: (Int) -> Unit,
    ): Int {
        val counting = Counting(BufferedInputStream(input, 1 shl 20))
        val src = BufferedInputStream(counting, 1 shl 16)
        src.mark(2)
        val b0 = src.read()
        val b1 = src.read()
        src.reset()
        val stream: InputStream = if (b0 == 0x1F && b1 == 0x8B) GZIPInputStream(src, 1 shl 20) else src

        val header = ByteArray(512)
        val buf = ByteArray(1 shl 20)
        var longName: String? = null
        var paxPath: String? = null
        var paxSize = -1L
        var written = 0
        var lastPct = -1

        fun progress() {
            if (compressedSize > 0) {
                val pct = (counting.count * 100 / compressedSize).toInt().coerceIn(0, 99)
                if (pct != lastPct) {
                    lastPct = pct
                    onProgress(pct)
                }
            }
        }

        while (true) {
            check()
            if (!readBlock(stream, header)) break
            if (header.all { it == 0.toByte() }) break

            val type = header[156].toInt().toChar()
            var size = parseSize(header)
            var name = longName ?: parseName(header)
            if (paxPath != null) name = paxPath
            if (paxSize >= 0) size = paxSize
            val padding = (512 - (size % 512)) % 512

            when (type) {
                'L' -> {
                    longName = readString(stream, size)
                    skipFully(stream, padding, buf)
                    continue
                }
                'x' -> {
                    val text = readString(stream, size)
                    skipFully(stream, padding, buf)
                    for (line in text.split('\n')) {
                        val sp = line.indexOf(' ')
                        val eq = line.indexOf('=')
                        if (sp < 0 || eq < sp) continue
                        val key = line.substring(sp + 1, eq)
                        val value = line.substring(eq + 1)
                        if (key == "path") paxPath = value
                        if (key == "size") paxSize = value.toLongOrNull() ?: -1L
                    }
                    continue
                }
                'g' -> {
                    skipFully(stream, size + padding, buf)
                    continue
                }
            }

            longName = null
            paxPath = null
            paxSize = -1L

            val isFile = type == '0' || type == '\u0000' || type == '7'
            val target = if (isFile) targetFor(name, outDir) else null
            if (target == null) {
                skipFully(stream, size + padding, buf)
                continue
            }

            target.parentFile?.mkdirs()
            BufferedOutputStream(FileOutputStream(target), 1 shl 20).use { out ->
                var left = size
                while (left > 0) {
                    check()
                    val r = stream.read(buf, 0, minOf(left, buf.size.toLong()).toInt())
                    if (r < 0) throw EOFException("Truncated archive")
                    out.write(buf, 0, r)
                    left -= r
                    progress()
                }
            }
            skipFully(stream, padding, buf)
            written++
            progress()
        }
        return written
    }

    private fun targetFor(name: String, outDir: File): File? {
        val parts = name.replace('\\', '/').split('/').filter { it.isNotEmpty() && it != "." }
        if (parts.isEmpty() || parts.any { it == ".." }) return null
        val idx = parts.indexOf("images")
        if (idx >= 0 && idx < parts.size - 1) {
            return File(outDir, parts.subList(idx, parts.size).joinToString("/"))
        }
        val last = parts.last()
        if (parts.size <= 2 && SCRIPT.matches(last)) return File(outDir, last)
        return null
    }

    private fun parseName(h: ByteArray): String {
        val name = cString(h, 0, 100)

        val posix = h[257] == 'u'.code.toByte() && h[258] == 's'.code.toByte() && h[259] == 't'.code.toByte() &&
            h[260] == 'a'.code.toByte() && h[261] == 'r'.code.toByte() && h[262] == 0.toByte()
        if (posix) {
            val prefix = cString(h, 345, 155)
            if (prefix.isNotEmpty()) return "$prefix/$name"
        }
        return name
    }

    private fun cString(h: ByteArray, off: Int, len: Int): String {
        var end = off
        while (end < off + len && h[end] != 0.toByte()) end++
        return String(h, off, end - off, Charsets.UTF_8)
    }

    private fun parseSize(h: ByteArray): Long {
        if ((h[124].toInt() and 0x80) != 0) {

            var v = (h[124].toInt() and 0x7F).toLong()
            for (i in 125 until 136) v = (v shl 8) or (h[i].toLong() and 0xFF)
            return v
        }
        val s = String(h, 124, 12, Charsets.US_ASCII).trim { it == ' ' || it == '\u0000' }
        return if (s.isEmpty()) 0L else s.toLong(8)
    }

    private fun readBlock(s: InputStream, buf: ByteArray): Boolean {
        var got = 0
        while (got < buf.size) {
            val r = s.read(buf, got, buf.size - got)
            if (r < 0) {
                if (got == 0) return false
                throw EOFException("Truncated archive")
            }
            got += r
        }
        return true
    }

    private fun readString(s: InputStream, size: Long): String {
        if (size > 1 shl 20) throw IOException("Unexpected archive header size")
        val b = ByteArray(size.toInt())
        var got = 0
        while (got < b.size) {
            val r = s.read(b, got, b.size - got)
            if (r < 0) throw EOFException("Truncated archive")
            got += r
        }
        return String(b, Charsets.UTF_8).trimEnd('\u0000', '\n')
    }

    private fun skipFully(s: InputStream, n: Long, tmp: ByteArray) {
        var left = n
        while (left > 0) {
            val r = s.read(tmp, 0, minOf(left, tmp.size.toLong()).toInt())
            if (r < 0) throw EOFException("Truncated archive")
            left -= r
        }
    }
}
