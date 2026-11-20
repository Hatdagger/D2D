package com.hatdagger.discdroid.core

import java.io.OutputStream

/**
 * Lossless FLAC encoder for CD audio (16-bit, stereo, 44.1 kHz), pure Kotlin.
 *
 * Uses fixed predictors (orders 0-4), stereo decorrelation (left/side, right/side,
 * mid/side) and partitioned Rice coding. Compression is typically within a few
 * percent of `flac -5`. The STREAMINFO MD5 is left as zero ("unknown"), which the
 * format allows, because output is streamed and cannot be rewound.
 */
class FlacEncoder(
    private val out: OutputStream,
    private val totalSamples: Long,
    private val tags: List<Pair<String, String>> = emptyList(),
) {
    private val left = IntArray(BLOCK)
    private val right = IntArray(BLOCK)
    private var fill = 0
    private var frameNumber = 0L
    private var samplesWritten = 0L
    private val bw = BitWriter(BLOCK * 6)
    private val residual = IntArray(BLOCK)
    private val mid = IntArray(BLOCK)
    private val side = IntArray(BLOCK)

    init {
        writeHeader()
    }

    private fun writeHeader() {
        out.write("fLaC".toByteArray(Charsets.US_ASCII))
        // STREAMINFO
        val si = BitWriter(64)
        si.bits(0, 1); si.bits(0, 7); si.bits(34, 24)
        si.bits(BLOCK.toLong(), 16); si.bits(BLOCK.toLong(), 16)
        si.bits(0, 24); si.bits(0, 24)
        si.bits(44_100, 20); si.bits(1, 3); si.bits(15, 5)
        si.bits(totalSamples, 36)
        repeat(4) { si.bits(0, 32) } // MD5 unknown
        out.write(si.bytes(), 0, si.byteLength())
        // VORBIS_COMMENT (last metadata block)
        val vendor = "D2D Disc2Android".toByteArray()
        val entries = tags.filter { it.second.isNotBlank() }.map { "${it.first}=${it.second}".toByteArray(Charsets.UTF_8) }
        val len = 4 + vendor.size + 4 + entries.sumOf { 4 + it.size }
        val vc = ByteArray(4 + len)
        vc[0] = 0x84.toByte(); vc[1] = (len shr 16).toByte(); vc[2] = (len shr 8).toByte(); vc[3] = len.toByte()
        var o = 4
        vc.putLe32(o, vendor.size.toLong()); o += 4
        vendor.copyInto(vc, o); o += vendor.size
        vc.putLe32(o, entries.size.toLong()); o += 4
        for (e in entries) { vc.putLe32(o, e.size.toLong()); o += 4; e.copyInto(vc, o); o += e.size }
        out.write(vc)
    }

    /** Feed little-endian interleaved 16-bit stereo PCM (as read from an audio CD). */
    fun write(pcm: ByteArray, off: Int, len: Int) {
        var i = off
        val end = off + len
        // Simple path: callers always pass whole 4-byte frames (CD sectors are 2352 bytes).
        require(len % 4 == 0) { "PCM must be whole stereo frames" }
        while (i < end) {
            left[fill] = ((pcm[i + 1].toInt() shl 8) or (pcm[i].toInt() and 0xFF))
            right[fill] = ((pcm[i + 3].toInt() shl 8) or (pcm[i + 2].toInt() and 0xFF))
            fill++
            i += 4
            if (fill == BLOCK) encodeBlock()
        }
    }

    fun finish() {
        if (fill > 0) encodeBlock()
        out.flush()
        check(samplesWritten == totalSamples) { "FLAC: wrote $samplesWritten samples, expected $totalSamples" }
    }

    // ------------------------------------------------------------------ frame

    private fun encodeBlock() {
        val n = fill
        for (i in 0 until n) {
            mid[i] = (left[i] + right[i]) shr 1
            side[i] = left[i] - right[i]
        }
        val cl = SubframePlan.choose(left, n, 16, residual)
        val cr = SubframePlan.choose(right, n, 16, residual)
        val cm = SubframePlan.choose(mid, n, 16, residual)
        val cs = SubframePlan.choose(side, n, 17, residual)
        val options = listOf(
            Triple(1, cl to cr, cl.bits + cr.bits),
            Triple(8, cl to cs, cl.bits + cs.bits),
            Triple(9, cs to cr, cs.bits + cr.bits),
            Triple(10, cm to cs, cm.bits + cs.bits),
        )
        val (assign, plans, _) = options.minByOrNull { it.third }!!
        val (a, b) = when (assign) {
            1 -> left to right
            8 -> left to side
            9 -> side to right
            else -> mid to side
        }
        val bpsA = if (assign == 9) 17 else 16
        val bpsB = if (assign == 8 || assign == 10) 17 else 16

        bw.reset()
        bw.bits(0xFFF8, 16)
        val bsCode = if (n == BLOCK) 12 else 7
        bw.bits(bsCode.toLong(), 4); bw.bits(9, 4)          // block size, 44.1 kHz
        bw.bits(assign.toLong(), 4); bw.bits(4, 3); bw.bits(0, 1) // channels, 16-bit
        bw.utf8(frameNumber)
        if (bsCode == 7) bw.bits((n - 1).toLong(), 16)
        bw.bits(Crc.crc8(bw.bytes(), bw.byteLength()).toLong(), 8)

        writeSubframe(a, n, bpsA, plans.first)
        writeSubframe(b, n, bpsB, plans.second)
        bw.alignByte()
        val crc = Crc.crc16(bw.bytes(), bw.byteLength())
        bw.bits(crc.toLong(), 16)
        out.write(bw.bytes(), 0, bw.byteLength())

        frameNumber++
        samplesWritten += n
        fill = 0
    }

    private fun writeSubframe(x: IntArray, n: Int, bps: Int, plan: SubframePlan) {
        when (plan.kind) {
            SubframePlan.CONSTANT -> {
                bw.bits(0, 8)
                bw.signed(x[0], bps)
            }
            SubframePlan.VERBATIM -> {
                bw.bits(0x02, 8) // 0 000001 0
                for (i in 0 until n) bw.signed(x[i], bps)
            }
            else -> {
                val order = plan.order
                bw.bits(((0x08 or order) shl 1).toLong(), 8)
                for (i in 0 until order) bw.signed(x[i], bps)
                SubframePlan.fixedResidual(x, n, order, residual)
                writeResidual(residual, n, order, plan.partitionOrder)
            }
        }
    }

    private fun writeResidual(res: IntArray, n: Int, order: Int, p: Int) {
        bw.bits(0, 2)             // Rice, 4-bit parameters
        bw.bits(p.toLong(), 4)
        val parts = 1 shl p
        val size = n shr p
        var idx = 0
        for (part in 0 until parts) {
            val count = if (part == 0) size - order else size
            var sum = 0L
            for (j in idx until idx + count) sum += zigzag(res[j]).toLong()
            val k = SubframePlan.riceParam(sum, count)
            bw.bits(k.toLong(), 4)
            for (j in idx until idx + count) bw.rice(zigzag(res[j]), k)
            idx += count
        }
    }

    companion object {
        const val BLOCK = 4096
        fun zigzag(v: Int): Int = (v shl 1) xor (v shr 31)

        fun tagsFor(title: String?, artist: String?, album: String?, albumArtist: String?, track: Int, total: Int) =
            listOfNotNull(
                title?.let { "TITLE" to it },
                (artist ?: albumArtist)?.let { "ARTIST" to it },
                album?.let { "ALBUM" to it },
                albumArtist?.let { "ALBUMARTIST" to it },
                "TRACKNUMBER" to track.toString(),
                "TRACKTOTAL" to total.toString(),
            )
    }
}

/** Chosen encoding for one channel of one block, with its estimated size in bits. */
internal class SubframePlan(val kind: Int, val order: Int, val partitionOrder: Int, val bits: Long) {
    companion object {
        const val CONSTANT = 0
        const val VERBATIM = 1
        const val FIXED = 2
        private const val MAX_PARTITION_ORDER = 8

        fun fixedResidual(x: IntArray, n: Int, order: Int, r: IntArray) {
            // r[i - order] holds the residual for sample i
            when (order) {
                0 -> for (i in 0 until n) r[i] = x[i]
                1 -> for (i in 1 until n) r[i - 1] = x[i] - x[i - 1]
                2 -> for (i in 2 until n) r[i - 2] = x[i] - 2 * x[i - 1] + x[i - 2]
                3 -> for (i in 3 until n) r[i - 3] = x[i] - 3 * x[i - 1] + 3 * x[i - 2] - x[i - 3]
                4 -> for (i in 4 until n) r[i - 4] = x[i] - 4 * x[i - 1] + 6 * x[i - 2] - 4 * x[i - 3] + x[i - 4]
            }
        }

        /** Rice parameter minimising count*(k+1) + sum>>k, capped at 14 (15 is the escape code). */
        fun riceParam(sum: Long, count: Int): Int {
            if (count <= 0) return 0
            var best = 0
            var bestBits = Long.MAX_VALUE
            for (k in 0..14) {
                val bits = count.toLong() * (k + 1) + (sum shr k)
                if (bits < bestBits) { bestBits = bits; best = k }
            }
            return best
        }

        fun choose(x: IntArray, n: Int, bps: Int, scratch: IntArray): SubframePlan {
            var constant = true
            for (i in 1 until n) if (x[i] != x[0]) { constant = false; break }
            if (constant) return SubframePlan(CONSTANT, 0, 0, 8L + bps)

            var best = SubframePlan(VERBATIM, 0, 0, 8L + n.toLong() * bps)
            val maxOrder = minOf(4, n - 1)
            for (order in 0..maxOrder) {
                fixedResidual(x, n, order, scratch)
                // Highest partition order allowed: n divisible by 2^p and first partition non-empty.
                var maxP = 0
                while (maxP < MAX_PARTITION_ORDER && (n and ((1 shl (maxP + 1)) - 1)) == 0 && (n shr (maxP + 1)) > order) maxP++
                // Sums at the finest level, then merge upwards.
                val parts = 1 shl maxP
                val size = n shr maxP
                val sums = LongArray(parts)
                var idx = 0
                for (pt in 0 until parts) {
                    val count = if (pt == 0) size - order else size
                    var s = 0L
                    for (j in idx until idx + count) s += FlacEncoder.zigzag(scratch[j]).toLong()
                    sums[pt] = s
                    idx += count
                }
                var level = sums
                var bestP = 0
                var bestBits = Long.MAX_VALUE
                for (p in maxP downTo 0) {
                    val partSize = n shr p
                    var bits = 6L
                    for (pt in level.indices) {
                        val count = if (pt == 0) partSize - order else partSize
                        val k = riceParam(level[pt], count)
                        bits += 4 + count.toLong() * (k + 1) + (level[pt] shr k)
                    }
                    if (bits < bestBits) { bestBits = bits; bestP = p }
                    if (p > 0) level = LongArray(level.size / 2) { level[2 * it] + level[2 * it + 1] }
                }
                val total = 8L + order.toLong() * bps + bestBits
                if (total < best.bits) best = SubframePlan(FIXED, order, bestP, total)
            }
            return best
        }
    }
}

internal class BitWriter(initial: Int) {
    private var buf = ByteArray(initial)
    private var pos = 0       // bytes completed
    private var acc = 0L      // pending bits
    private var accBits = 0

    fun reset() { pos = 0; acc = 0; accBits = 0 }

    private fun ensure(extra: Int) {
        if (pos + extra > buf.size) buf = buf.copyOf(maxOf(buf.size * 2, pos + extra))
    }

    fun bits(value: Long, n: Int) {
        if (n == 0) return
        if (n > 32) { bits(value ushr 32, n - 32); bits(value and 0xFFFFFFFFL, 32); return }
        acc = (acc shl n) or (value and ((1L shl n) - 1))
        accBits += n
        ensure(8)
        while (accBits >= 8) {
            accBits -= 8
            buf[pos++] = (acc ushr accBits).toByte()
        }
        acc = acc and ((1L shl accBits) - 1)
    }

    fun signed(v: Int, n: Int) = bits(v.toLong(), n)

    fun unaryZeros(q: Int) {
        var left = q
        while (left >= 32) { bits(0, 32); left -= 32 }
        bits(1, left + 1) // q zeros followed by a one
    }

    fun rice(u: Int, k: Int) {
        unaryZeros(u ushr k)
        if (k > 0) bits((u and ((1 shl k) - 1)).toLong(), k)
    }

    fun utf8(v: Long) {
        when {
            v < 0x80 -> bits(v, 8)
            v < 0x800 -> { bits(0xC0L or (v shr 6), 8); cont(v, 0) }
            v < 0x10000 -> { bits(0xE0L or (v shr 12), 8); cont(v, 6); cont(v, 0) }
            v < 0x200000 -> { bits(0xF0L or (v shr 18), 8); cont(v, 12); cont(v, 6); cont(v, 0) }
            v < 0x4000000 -> { bits(0xF8L or (v shr 24), 8); cont(v, 18); cont(v, 12); cont(v, 6); cont(v, 0) }
            else -> { bits(0xFCL or (v shr 30), 8); cont(v, 24); cont(v, 18); cont(v, 12); cont(v, 6); cont(v, 0) }
        }
    }

    private fun cont(v: Long, shift: Int) = bits(0x80L or ((v shr shift) and 0x3F), 8)

    fun alignByte() { if (accBits > 0) bits(0, 8 - accBits) }

    /** Valid only when byte-aligned. */
    fun bytes(): ByteArray = buf
    fun byteLength(): Int = pos
}

internal object Crc {
    private val t8 = IntArray(256) { i ->
        var c = i
        repeat(8) { c = if (c and 0x80 != 0) ((c shl 1) xor 0x07) and 0xFF else (c shl 1) and 0xFF }
        c
    }
    private val t16 = IntArray(256) { i ->
        var c = i shl 8
        repeat(8) { c = if (c and 0x8000 != 0) ((c shl 1) xor 0x8005) and 0xFFFF else (c shl 1) and 0xFFFF }
        c
    }

    fun crc8(b: ByteArray, len: Int): Int {
        var c = 0
        for (i in 0 until len) c = t8[c xor (b[i].toInt() and 0xFF)]
        return c
    }

    fun crc16(b: ByteArray, len: Int): Int {
        var c = 0
        for (i in 0 until len) c = ((c shl 8) and 0xFFFF) xor t16[(c ushr 8) xor (b[i].toInt() and 0xFF)]
        return c
    }
}
