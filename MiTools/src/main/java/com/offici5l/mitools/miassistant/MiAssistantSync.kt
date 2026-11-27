package com.offici5l.mitools.miassistant

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale

class RemoteEntry(val name: String, val mode: Long, val size: Long, val mtime: Long) {
    val isDir: Boolean get() = (mode and 0xF000L) == 0x4000L
    val isLink: Boolean get() = (mode and 0xF000L) == 0xA000L
}

class ByteQueue {
    private var a = ByteArray(64 * 1024)
    private var s = 0
    private var e = 0

    val size: Int get() = e - s

    fun append(b: ByteArray) {
        if (b.isEmpty()) return
        if (e + b.size > a.size) {
            val n = e - s
            if (n + b.size <= a.size) {
                System.arraycopy(a, s, a, 0, n)
            } else {
                val na = ByteArray(maxOf(a.size * 2, n + b.size))
                System.arraycopy(a, s, na, 0, n)
                a = na
            }
            s = 0
            e = n
        }
        System.arraycopy(b, 0, a, e, b.size)
        e += b.size
    }

    fun u32(i: Int): Long {
        var v = 0L
        for (k in 3 downTo 0) v = (v shl 8) or (a[s + i + k].toLong() and 0xFF)
        return v
    }

    fun u64(i: Int): Long {
        var v = 0L
        for (k in 7 downTo 0) v = (v shl 8) or (a[s + i + k].toLong() and 0xFF)
        return v
    }

    fun bytes(from: Int, to: Int): ByteArray = a.copyOfRange(s + from, s + to)

    fun drop(n: Int) {
        s += n
        if (s == e) {
            s = 0
            e = 0
        }
    }

    fun takeAll(): ByteArray {
        val r = a.copyOfRange(s, e)
        s = 0
        e = 0
        return r
    }
}

fun u32le(v: Int): ByteArray = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array()

fun humanSize(n: Long): String {
    if (n < 1024) return "$n B"
    val units = arrayOf("KB", "MB", "GB", "TB")
    var v = n / 1024.0
    var i = 0
    while (v >= 1024 && i < units.size - 1) {
        v /= 1024
        i++
    }
    return String.format(Locale.US, "%.1f %s", v, units[i])
}

fun fileModeString(mode: Long): String {
    val type = when (mode and 0xF000L) {
        0x4000L -> 'd'
        0xA000L -> 'l'
        0x2000L -> 'c'
        0x6000L -> 'b'
        0x1000L -> 'p'
        0xC000L -> 's'
        else -> '-'
    }
    val sb = StringBuilder().append(type)
    val bits = longArrayOf(0x100, 0x80, 0x40, 0x20, 0x10, 0x8, 0x4, 0x2, 0x1)
    val chars = charArrayOf('r', 'w', 'x', 'r', 'w', 'x', 'r', 'w', 'x')
    for (i in 0 until 9) {
        val set = (mode and bits[i]) != 0L
        var c = if (set) chars[i] else '-'
        if (i == 2 && (mode and 0x800L) != 0L) c = if (set) 's' else 'S'
        if (i == 5 && (mode and 0x400L) != 0L) c = if (set) 's' else 'S'
        if (i == 8 && (mode and 0x200L) != 0L) c = if (set) 't' else 'T'
        sb.append(c)
    }
    return sb.toString()
}
