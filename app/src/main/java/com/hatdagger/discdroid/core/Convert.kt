package com.hatdagger.discdroid.core

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Turns decoded audio of any sample rate and channel count (mono, stereo, 5.1, 7.1…) into
 * CD audio: 44,100 Hz, 16-bit, stereo, little-endian. Used for MP3/M4A/Opus files and for the
 * soundtrack of video files (MP4, WebM, MKV), which are usually 48 kHz and sometimes surround.
 *
 * Input that is already 16-bit 44.1 kHz mono/stereo is passed through bit-for-bit.
 * Other rates are resampled with a windowed-sinc filter.
 *
 * Feed decoder output with [feed], then call [finish] once; CD PCM goes to [sink].
 */
class PcmConverter(
    private val inRate: Int,
    private val channels: Int,
    private val floatInput: Boolean,
    private val sink: (ByteArray, Int) -> Unit,
) {
    init {
        require(inRate in 4_000..768_000) { "Unsupported sample rate $inRate Hz" }
        require(channels in 1..16) { "Unsupported channel count $channels" }
    }

    private val passThrough = inRate == CD_RATE && !floatInput && channels <= 2
    private val out = ByteArray(OUT_CHUNK * 4)
    private var outFill = 0
    private val resampler = if (inRate != CD_RATE) Resampler(inRate) else null

    // Downmix weights (ITU-style) for FL FR FC LFE BL BR SL SR…; LFE is dropped.
    private val wl = FloatArray(channels)
    private val wr = FloatArray(channels)
    init {
        when (channels) {
            1 -> { wl[0] = 1f; wr[0] = 1f }
            2 -> { wl[0] = 1f; wr[1] = 1f }
            else -> {
                val c = 0.7071f
                wl[0] = 1f; wr[1] = 1f
                wl[2] = c; wr[2] = c                         // centre
                for (ch in 4 until channels) {               // surrounds alternate left/right
                    if (ch % 2 == 0) wl[ch] = c else wr[ch] = c
                }
                val norm = wl.sum()                          // keep peaks below full scale
                for (i in 0 until channels) { wl[i] /= norm; wr[i] /= norm }
            }
        }
    }

    private val bps = if (floatInput) 4 else 2
    private val frameBytes = bps * channels
    private val carry = ByteArray(frameBytes)
    private var carryLen = 0

    /** Accepts any number of bytes; a frame split across calls is joined up. */
    fun feed(src: ByteArray, len: Int) {
        var off = 0
        if (carryLen > 0) {
            val n = minOf(frameBytes - carryLen, len)
            System.arraycopy(src, 0, carry, carryLen, n)
            carryLen += n; off = n
            if (carryLen < frameBytes) return
            feedFrames(carry, 0, 1)
            carryLen = 0
        }
        val frames = (len - off) / frameBytes
        feedFrames(src, off, frames)
        val rest = len - off - frames * frameBytes
        if (rest > 0) { System.arraycopy(src, off + frames * frameBytes, carry, 0, rest); carryLen = rest }
    }

    private fun feedFrames(src: ByteArray, start: Int, frames: Int) {
        if (passThrough) {
            for (f in 0 until frames) {
                val b = start + f * frameBytes
                val r = if (channels == 2) b + 2 else b
                emitRaw(src[b], src[b + 1], src[r], src[r + 1])
            }
            return
        }
        for (f in 0 until frames) {
            val b = start + f * frameBytes
            var l = 0f; var r = 0f
            for (ch in 0 until channels) {
                val wL = wl[ch]; val wR = wr[ch]
                if (wL == 0f && wR == 0f) continue
                val s = sample(src, b + ch * bps)
                l += s * wL; r += s * wR
            }
            if (resampler != null) resampler.push(l, r, ::emit) else emit(l, r)
        }
    }

    fun finish() {
        resampler?.finish(::emit)
        if (outFill > 0) { sink(out, outFill); outFill = 0 }
    }

    private fun sample(b: ByteArray, i: Int): Float =
        if (floatInput) {
            val bits = (b[i].toInt() and 0xFF) or ((b[i + 1].toInt() and 0xFF) shl 8) or
                ((b[i + 2].toInt() and 0xFF) shl 16) or (b[i + 3].toInt() shl 24)
            java.lang.Float.intBitsToFloat(bits)
        } else {
            ((b[i].toInt() and 0xFF) or (b[i + 1].toInt() shl 8)) / 32768f
        }

    private fun emit(l: Float, r: Float) {
        val li = (l * 32768f).roundToInt().coerceIn(-32768, 32767)
        val ri = (r * 32768f).roundToInt().coerceIn(-32768, 32767)
        emitRaw(li.toByte(), (li shr 8).toByte(), ri.toByte(), (ri shr 8).toByte())
    }

    private fun emitRaw(l0: Byte, l1: Byte, r0: Byte, r1: Byte) {
        out[outFill] = l0; out[outFill + 1] = l1; out[outFill + 2] = r0; out[outFill + 3] = r1
        outFill += 4
        if (outFill == out.size) { sink(out, outFill); outFill = 0 }
    }

    companion object {
        const val CD_RATE = 44_100
        private const val OUT_CHUNK = 4096
    }
}

/**
 * Streaming stereo resampler to 44.1 kHz: windowed-sinc (Blackman, 32 taps each side) with a
 * precomputed, linearly interpolated phase table. Output length is round(input × 44100 / rate).
 */
class Resampler(private val inRate: Int) {
    private val outRate = PcmConverter.CD_RATE
    private val half = 32
    private val phases = 512
    private val table: Array<FloatArray>

    init {
        val fc = minOf(1.0, outRate.toDouble() / inRate) * 0.96
        table = Array(phases + 1) { p ->
            val frac = p.toDouble() / phases
            FloatArray(2 * half) { k ->
                val x = (k - half + 1) - frac // tap position relative to the output time
                val sinc = if (x == 0.0) 1.0 else sin(PI * fc * x) / (PI * fc * x)
                val n = (x + half) / (2.0 * half)
                val w = if (n <= 0.0 || n >= 1.0) 0.0 else 0.42 - 0.5 * cos(2 * PI * n) + 0.08 * cos(4 * PI * n)
                (fc * sinc * w).toFloat()
            }
        }
    }

    private var bufL = FloatArray(8192)
    private var bufR = FloatArray(8192)
    private var bufStart = 0L        // absolute input index of buf[0]
    private var count = half.toLong() // absolute input frames held (starts with `half` zeros of history)
    private var realInput = 0L
    private var outIndex = 0L

    fun push(l: Float, r: Float, emit: (Float, Float) -> Unit) {
        val i = (count - bufStart).toInt()
        if (i == bufL.size) compact()
        val j = (count - bufStart).toInt()
        bufL[j] = l; bufR[j] = r
        count++; realInput++
        drain(emit, count)
    }

    fun finish(emit: (Float, Float) -> Unit) {
        val target = (realInput * outRate + inRate / 2) / inRate
        repeat(half + 1) {
            val j = (count - bufStart).toInt()
            if (j == bufL.size) compact()
            val k = (count - bufStart).toInt()
            bufL[k] = 0f; bufR[k] = 0f; count++
        }
        while (outIndex < target) { if (!produce(emit, count)) break }
    }

    private fun drain(emit: (Float, Float) -> Unit, available: Long) {
        while (produce(emit, available)) {}
    }

    /** Makes one output sample if enough input is buffered. */
    private fun produce(emit: (Float, Float) -> Unit, available: Long): Boolean {
        val num = outIndex * inRate
        val ip = half + num / outRate              // absolute input index at or before the output time
        val frac = (num % outRate).toDouble() / outRate
        val first = ip - half + 1
        val last = ip + half
        if (last >= available) return false
        val pos = frac * phases
        val p = pos.toInt()
        val t = (pos - p).toFloat()
        val a = table[p]; val b = table[p + 1]
        var l = 0f; var r = 0f
        var idx = (first - bufStart).toInt()
        for (k in 0 until 2 * half) {
            val h = a[k] + (b[k] - a[k]) * t
            l += bufL[idx] * h; r += bufR[idx] * h
            idx++
        }
        emit(l, r)
        outIndex++
        return true
    }

    /** Drops input that no future output needs, or grows the buffer. */
    private fun compact() {
        val nextFirst = half + (outIndex * inRate) / outRate - half + 1
        val keepFrom = minOf(nextFirst, count).coerceAtLeast(bufStart)
        val drop = (keepFrom - bufStart).toInt()
        val used = (count - bufStart).toInt()
        if (drop > bufL.size / 2) {
            System.arraycopy(bufL, drop, bufL, 0, used - drop)
            System.arraycopy(bufR, drop, bufR, 0, used - drop)
            bufStart = keepFrom
        } else {
            bufL = bufL.copyOf(bufL.size * 2)
            bufR = bufR.copyOf(bufR.size * 2)
        }
    }
}
