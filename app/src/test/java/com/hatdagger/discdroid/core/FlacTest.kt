package com.hatdagger.discdroid.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

/**
 * Encodes test audio and decodes it with ffmpeg (an independent FLAC decoder with CRC
 * checking) to prove the output is bit-exact. Skipped if ffmpeg isn't installed.
 */
class FlacTest {

    private fun pcm(samples: Int, gen: (Int) -> Pair<Int, Int>): ByteArray {
        val b = ByteArray(samples * 4)
        for (i in 0 until samples) {
            val (l, r) = gen(i)
            b[i * 4] = l.toByte(); b[i * 4 + 1] = (l shr 8).toByte()
            b[i * 4 + 2] = r.toByte(); b[i * 4 + 3] = (r shr 8).toByte()
        }
        return b
    }

    private fun clamp(v: Double) = v.toInt().coerceIn(-32768, 32767)

    private fun encode(raw: ByteArray, chunk: Int = 2352 * 26): ByteArray {
        val out = ByteArrayOutputStream()
        val enc = FlacEncoder(out, raw.size / 4L, FlacEncoder.tagsFor("Täst Title", null, "Album", "Artist", 1, 9))
        var o = 0
        while (o < raw.size) { val n = minOf(chunk, raw.size - o); enc.write(raw, o, n); o += n }
        enc.finish()
        return out.toByteArray()
    }

    private fun ffmpeg(): String? =
        listOf("/usr/bin/ffmpeg", "/usr/local/bin/ffmpeg").firstOrNull { File(it).canExecute() }

    private fun decode(flac: ByteArray): ByteArray {
        val f = File.createTempFile("discdroid", ".flac"); f.writeBytes(flac)
        val raw = File.createTempFile("discdroid", ".raw")
        val p = ProcessBuilder(
            ffmpeg()!!, "-v", "error", "-err_detect", "crccheck+bitstream+explode", "-y",
            "-i", f.path, "-f", "s16le", "-acodec", "pcm_s16le", raw.path,
        ).redirectErrorStream(true).start()
        val msg = p.inputStream.bufferedReader().readText()
        assertEquals("ffmpeg said: $msg", 0, p.waitFor())
        assertTrue("ffmpeg reported problems: $msg", msg.isBlank())
        return raw.readBytes().also { f.delete(); raw.delete() }
    }

    private fun roundTrip(raw: ByteArray) {
        assumeTrue("ffmpeg not installed", ffmpeg() != null)
        val flac = encode(raw)
        assertArrayEquals(raw, decode(flac))
    }

    @Test fun musicLike() {
        val rnd = Random(1)
        // 10 seconds + an odd tail so the last block is short
        roundTrip(pcm(441_000 + 1234) { i ->
            val t = i / 44_100.0
            val l = 9000 * sin(2 * PI * 440 * t) + 4000 * sin(2 * PI * 1250 * t) + rnd.nextInt(-200, 200)
            val r = 8000 * sin(2 * PI * 440 * t + 0.3) + 3000 * sin(2 * PI * 660 * t) + rnd.nextInt(-200, 200)
            clamp(l) to clamp(r)
        })
    }

    @Test fun silenceAndConstant() = roundTrip(pcm(20_000) { i -> if (i < 10_000) 0 to 0 else 1234 to -1234 })

    @Test fun fullScaleExtremes() {
        val rnd = Random(2)
        roundTrip(pcm(30_000) { i ->
            when ((i / 3000) % 3) {
                0 -> (if (i % 2 == 0) 32767 else -32768) to (if (i % 2 == 0) -32768 else 32767) // worst-case side channel
                1 -> rnd.nextInt(-32768, 32768) to rnd.nextInt(-32768, 32768)             // white noise -> verbatim
                else -> 32767 to 32767
            }
        })
    }

    @Test fun monoInStereo() = roundTrip(pcm(50_000) { i -> val v = clamp(12000 * sin(i / 30.0)); v to v })

    @Test fun tinyFile() = roundTrip(pcm(3) { i -> i to -i })

    @Test fun compresses() {
        val raw = pcm(441_000) { i -> val v = clamp(10000 * sin(2 * PI * 440 * i / 44_100.0)); v to (v / 2) }
        val flac = encode(raw)
        assertTrue("expected real compression, got ${flac.size} of ${raw.size}", flac.size < raw.size / 3)
    }

    @Test fun headerFields() {
        val flac = encode(pcm(5000) { 0 to 0 })
        assertEquals("fLaC", String(flac, 0, 4))
        // STREAMINFO total samples (36 bits) ends at byte 4+4+18
        val total = ((flac[21].toLong() and 0x0F) shl 32) or (flac.be32(22))
        assertEquals(5000L, total)
    }
}
