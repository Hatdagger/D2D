package com.hatdagger.discdroid.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt

class ConvertTest {

    private fun pcm16(frames: Int, channels: Int, gen: (frame: Int, ch: Int) -> Int): ByteArray {
        val b = ByteArray(frames * channels * 2)
        for (f in 0 until frames) for (c in 0 until channels) {
            val v = gen(f, c); val i = (f * channels + c) * 2
            b[i] = v.toByte(); b[i + 1] = (v shr 8).toByte()
        }
        return b
    }

    private fun convert(input: ByteArray, rate: Int, channels: Int, float: Boolean = false, chunk: Int = 1000): ShortArray {
        val out = ByteArrayOutputStream()
        val c = PcmConverter(rate, channels, float) { b, n -> out.write(b, 0, n) }
        var o = 0
        while (o < input.size) { val n = minOf(chunk, input.size - o); c.feed(input.copyOfRange(o, o + n), n); o += n }
        c.finish()
        val bytes = out.toByteArray()
        return ShortArray(bytes.size / 2) { ((bytes[it * 2].toInt() and 0xFF) or (bytes[it * 2 + 1].toInt() shl 8)).toShort() }
    }

    private fun left(s: ShortArray) = DoubleArray(s.size / 2) { s[it * 2] / 32768.0 }
    private fun right(s: ShortArray) = DoubleArray(s.size / 2) { s[it * 2 + 1] / 32768.0 }

    /** Amplitude of a sine at [hz] (correlation), ignoring the edges. */
    private fun amplitudeAt(x: DoubleArray, hz: Double, rate: Int = 44_100): Double {
        var s = 0.0; var c = 0.0
        val from = 2000; val to = x.size - 2000
        for (i in from until to) { val a = 2 * PI * hz * i / rate; s += x[i] * sin(a); c += x[i] * kotlin.math.cos(a) }
        return 2 * sqrt(s * s + c * c) / (to - from)
    }

    @Test fun cdAudioPassesThroughExactly() {
        // odd-sized chunks split frames across feed() calls
        val input = pcm16(5000, 2) { f, c -> (f * 37 + c * 1000) % 65536 - 32768 }
        val out = convert(input, 44_100, 2, chunk = 1000)
        val back = ByteArray(out.size * 2).also { for (i in out.indices) { it[i * 2] = out[i].toByte(); it[i * 2 + 1] = (out[i].toInt() shr 8).toByte() } }
        assertArrayEquals(input, back)
    }

    @Test fun monoIsCopiedToBothSides() {
        val out = convert(pcm16(100, 1) { f, _ -> f * 10 }, 44_100, 1)
        assertEquals(200, out.size)
        assertEquals(50 * 10, out[100].toInt()); assertEquals(50 * 10, out[101].toInt())
    }

    @Test fun resamples48kTo44k() {
        val frames = 48_000 // one second
        val input = pcm16(frames, 2) { f, c -> (0.5 * 32767 * sin(2 * PI * (if (c == 0) 1000.0 else 3000.0) * f / 48_000)).toInt() }
        val out = convert(input, 48_000, 2, chunk = 4096 + 6)
        assertEquals(44_100 * 2, out.size)
        val l = left(out); val r = right(out)
        assertEquals(0.5, amplitudeAt(l, 1000.0), 0.01)
        assertEquals(0.5, amplitudeAt(r, 3000.0), 0.01)
        assertTrue("no leakage between channels", amplitudeAt(l, 3000.0) < 0.002)
        // a tone above the CD limit is filtered out instead of folding back as a false tone
        val hi = pcm16(frames, 2) { f, _ -> (0.5 * 32767 * sin(2 * PI * 23_000.0 * f / 48_000)).toInt() }
        val hiOut = left(convert(hi, 48_000, 2))
        assertTrue(amplitudeAt(hiOut, 44_100 - 23_000.0) < 0.01)
    }

    @Test fun upsamples22kAndHandlesFloat() {
        val frames = 22_050
        val f = ByteArray(frames * 4)
        for (i in 0 until frames) {
            val bits = java.lang.Float.floatToIntBits((0.25 * sin(2 * PI * 440.0 * i / 22_050)).toFloat())
            f[i * 4] = bits.toByte(); f[i * 4 + 1] = (bits shr 8).toByte(); f[i * 4 + 2] = (bits shr 16).toByte(); f[i * 4 + 3] = (bits shr 24).toByte()
        }
        val out = convert(f, 22_050, 1, float = true)
        assertEquals(44_100 * 2, out.size)
        assertEquals(0.25, amplitudeAt(left(out), 440.0), 0.01)
        assertEquals(0.25, amplitudeAt(right(out), 440.0), 0.01)
    }

    @Test fun surroundIsDownmixedWithoutClipping() {
        // 5.1: FL FR FC LFE BL BR, all at full scale
        val input = pcm16(1000, 6) { _, c -> if (c == 3) 0 else 32767 }
        val out = convert(input, 44_100, 6)
        assertEquals(2000, out.size)
        assertTrue(out.all { abs(it.toInt()) <= 32767 && it > 30000 })
        // only the left front channel -> left only
        val fl = convert(pcm16(1000, 6) { _, c -> if (c == 0) 20000 else 0 }, 44_100, 6)
        assertTrue(fl[1].toInt() == 0 && fl[0] > 0)
    }
}
