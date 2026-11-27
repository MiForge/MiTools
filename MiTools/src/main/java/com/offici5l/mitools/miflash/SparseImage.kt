package com.offici5l.mitools.miflash

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

sealed class Segment {
    abstract val length: Long
}

class BytesSegment(val bytes: ByteArray) : Segment() {
    override val length: Long get() = bytes.size.toLong()
}

class FileSegment(val offset: Long, override val length: Long) : Segment()

class Piece(val segments: List<Segment>) {
    val size: Long = segments.sumOf { it.length }
}

object ImagePlanner {

    private val SPARSE_MAGIC = 0xED26FF3A.toInt()
    private const val CHUNK_RAW = 0xCAC1
    private const val CHUNK_FILL = 0xCAC2
    private const val CHUNK_DONT_CARE = 0xCAC3
    private const val CHUNK_CRC32 = 0xCAC4
    private const val HEADER_SIZE = 28L
    private const val CHUNK_HEADER_SIZE = 12L

    private class Src(val type: Int, val blocks: Long, val dataOffset: Long, val fill: ByteArray?)

    fun plan(file: File, maxDownload: Long): List<Piece> {
        val len = file.length()
        if (len <= maxDownload) return listOf(Piece(listOf(FileSegment(0, len))))

        RandomAccessFile(file, "r").use { raf ->
            val head = ByteArray(HEADER_SIZE.toInt())
            raf.seek(0)
            raf.readFully(head)
            val bb = ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN)
            if (bb.getInt(0) == SPARSE_MAGIC) return resparse(raf, bb, len, maxDownload)

            val blk = 4096
            val blocks = (len + blk - 1) / blk
            return build(listOf(Src(CHUNK_RAW, blocks, 0, null)), blk, blocks, len, maxDownload)
        }
    }

    private fun resparse(raf: RandomAccessFile, bb: ByteBuffer, fileLen: Long, limit: Long): List<Piece> {
        val fileHdr = bb.getShort(8).toInt() and 0xFFFF
        val chunkHdr = bb.getShort(10).toInt() and 0xFFFF
        val blk = bb.getInt(12)
        val totalBlocks = bb.getInt(16).toLong() and 0xFFFFFFFFL
        val totalChunks = bb.getInt(20).toLong() and 0xFFFFFFFFL
        if (fileHdr < HEADER_SIZE || chunkHdr < CHUNK_HEADER_SIZE || blk <= 0) {
            throw FastbootException("Unsupported sparse image header.")
        }

        val chunks = ArrayList<Src>()
        val ch = ByteArray(chunkHdr)
        var pos = fileHdr.toLong()
        var i = 0L
        while (i < totalChunks) {
            raf.seek(pos)
            raf.readFully(ch)
            val cb = ByteBuffer.wrap(ch).order(ByteOrder.LITTLE_ENDIAN)
            val type = cb.getShort(0).toInt() and 0xFFFF
            val sz = cb.getInt(4).toLong() and 0xFFFFFFFFL
            val total = cb.getInt(8).toLong() and 0xFFFFFFFFL
            val dataOff = pos + chunkHdr
            when (type) {
                CHUNK_RAW -> chunks.add(Src(CHUNK_RAW, sz, dataOff, null))
                CHUNK_FILL -> {
                    val fill = ByteArray(4)
                    raf.seek(dataOff)
                    raf.readFully(fill)
                    chunks.add(Src(CHUNK_FILL, sz, dataOff, fill))
                }
                CHUNK_DONT_CARE -> chunks.add(Src(CHUNK_DONT_CARE, sz, 0, null))
                CHUNK_CRC32 -> {}
                else -> throw FastbootException("Unknown sparse chunk type 0x" + Integer.toHexString(type))
            }
            pos += total
            i++
        }
        return build(chunks, blk, totalBlocks, fileLen, limit)
    }

    private fun chunkHeader(type: Int, blocks: Long, totalSize: Long): ByteArray =
        ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
            .putShort(type.toShort()).putShort(0)
            .putInt(blocks.toInt()).putInt(totalSize.toInt())
            .array()

    private class Builder(val blk: Int, val totalBlocks: Long, val limit: Long, startBlock: Long) {
        val body = ArrayList<Segment>()
        var size = HEADER_SIZE
        var endBlock = 0L
        var chunkCount = 0
        var dataChunks = 0

        init {
            if (startBlock > 0) addSkip(startBlock)
        }

        private fun addSkip(blocks: Long) {
            body.add(BytesSegment(chunkHeader(CHUNK_DONT_CARE, blocks, CHUNK_HEADER_SIZE)))
            size += CHUNK_HEADER_SIZE
            chunkCount++
            endBlock += blocks
        }

        fun room(): Long = limit - size - CHUNK_HEADER_SIZE

        fun gapTo(pos: Long) {
            if (pos > endBlock) addSkip(pos - endBlock)
        }

        fun addRaw(blocks: Long, srcOffset: Long, fileLen: Long) {
            val want = blocks * blk
            val avail = maxOf(0L, minOf(want, fileLen - srcOffset))
            body.add(BytesSegment(chunkHeader(CHUNK_RAW, blocks, CHUNK_HEADER_SIZE + want)))
            if (avail > 0) body.add(FileSegment(srcOffset, avail))
            if (avail < want) body.add(BytesSegment(ByteArray((want - avail).toInt())))
            size += CHUNK_HEADER_SIZE + want
            chunkCount++
            dataChunks++
            endBlock += blocks
        }

        fun addFill(blocks: Long, fill: ByteArray) {
            body.add(BytesSegment(chunkHeader(CHUNK_FILL, blocks, CHUNK_HEADER_SIZE + 4) + fill))
            size += CHUNK_HEADER_SIZE + 4
            chunkCount++
            dataChunks++
            endBlock += blocks
        }

        fun finish(): Piece {
            if (totalBlocks > endBlock) addSkip(totalBlocks - endBlock)
            val header = ByteBuffer.allocate(HEADER_SIZE.toInt()).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(SPARSE_MAGIC)
                .putShort(1).putShort(0)
                .putShort(HEADER_SIZE.toShort()).putShort(CHUNK_HEADER_SIZE.toShort())
                .putInt(blk).putInt(totalBlocks.toInt()).putInt(chunkCount).putInt(0)
                .array()
            return Piece(listOf<Segment>(BytesSegment(header)) + body)
        }
    }

    private fun build(chunks: List<Src>, blk: Int, totalBlocks: Long, fileLen: Long, limit: Long): List<Piece> {
        val pieces = ArrayList<Piece>()
        var cur = Builder(blk, totalBlocks, limit, 0)
        var pos = 0L

        fun rotate() {
            if (cur.dataChunks == 0) throw FastbootException("max-download-size is too small for this image.")
            pieces.add(cur.finish())
            cur = Builder(blk, totalBlocks, limit, pos)
        }

        for (c in chunks) {
            when (c.type) {
                CHUNK_DONT_CARE -> pos += c.blocks
                CHUNK_FILL -> {
                    while (true) {
                        val gap = if (pos > cur.endBlock) CHUNK_HEADER_SIZE else 0L
                        if (cur.room() >= gap + CHUNK_HEADER_SIZE + 4) break
                        rotate()
                    }
                    cur.gapTo(pos)
                    cur.addFill(c.blocks, c.fill!!)
                    pos += c.blocks
                }
                CHUNK_RAW -> {
                    var remaining = c.blocks
                    var src = c.dataOffset
                    while (remaining > 0) {
                        val gap = if (pos > cur.endBlock) CHUNK_HEADER_SIZE else 0L
                        val avail = cur.room() - CHUNK_HEADER_SIZE - gap
                        val n = if (avail <= 0) 0L else minOf(remaining, avail / blk)
                        if (n <= 0) {
                            rotate()
                            continue
                        }
                        cur.gapTo(pos)
                        cur.addRaw(n, src, fileLen)
                        pos += n
                        src += n * blk
                        remaining -= n
                    }
                }
            }
        }
        if (cur.dataChunks > 0) pieces.add(cur.finish())
        return pieces
    }
}
