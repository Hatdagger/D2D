package com.hatdagger.discdroid.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException

/** A pretend CD/DVD writer that enforces the rules a real drive would. */
class FakeRecorder(
    var profile: Int = 0x09,
    var state: Int = 0,
    private val capacity: Long = 359_847, // ~80 min CD-R
    private val busyWrites: Int = 0,
) : ScsiTransport {
    override val description = "fake writer"
    var sense = Sense(0, 0, 0)
    val sectors = HashMap<Long, ByteArray>()
    var nwa = 0L
    var track = 1
    val closes = mutableListOf<Int>()
    val trackStarts = mutableListOf<Long>()
    var writeType = -1; var trackMode = -1; var dbt = -1; var testWrite = false
    var modeSelected = false
    var cue: ByteArray? = null
    private var busyLeft = busyWrites
    private var trackStarted = false

    private fun check(s: Sense): CommandResult { sense = s; return CommandResult(1, 0) }

    override fun execute(cdb: ByteArray, direction: Direction, buffer: ByteArray, length: Int, timeoutMs: Int): CommandResult {
        when (cdb.u8(0)) {
            0x00 -> return CommandResult(0, 0)
            0x03 -> {
                buffer.fill(0, 0, 18); buffer[0] = 0x70; buffer[2] = sense.key.toByte()
                buffer[12] = sense.asc.toByte(); buffer[13] = sense.ascq.toByte()
                return CommandResult(0, 18)
            }
            0x46 -> { buffer.fill(0, 0, 8); buffer[6] = (profile shr 8).toByte(); buffer[7] = profile.toByte(); return CommandResult(0, 8) }
            0x51 -> {
                buffer.fill(0, 0, 34)
                buffer[2] = (state or (if (profile == 0x0A || profile == 0x14) 0x10 else 0)).toByte()
                buffer[6] = track.toByte()
                return CommandResult(0, 34)
            }
            0x52 -> {
                buffer.fill(0, 0, 36)
                assertEquals(track.toLong(), cdb.be32(2))
                buffer[2] = track.toByte(); buffer[7] = 1
                buffer.putBe32(12, nwa); buffer.putBe32(16, capacity - nwa)
                return CommandResult(0, 36)
            }
            0x5A -> {
                assertEquals(0x05, cdb.u8(2))
                buffer.fill(0, 0, 8 + 52); buffer[1] = (6 + 52).toByte()
                buffer[8] = 0x85.toByte(); buffer[9] = 50 // PS bit set, page length
                return CommandResult(0, 60)
            }
            0x55 -> {
                assertEquals("mode data length must be zero", 0, buffer.be16(0))
                assertEquals(0x05, buffer.u8(8))
                assertEquals(60, length)
                writeType = buffer.u8(10) and 0x0F; testWrite = (buffer.u8(10) and 0x10) != 0
                trackMode = buffer.u8(11) and 0x0F; dbt = buffer.u8(12) and 0x0F
                modeSelected = true
                return CommandResult(0, length)
            }
            0x2A -> {
                if (busyLeft > 0) { busyLeft--; return check(Sense(2, 4, 8)) }
                val lba = cdb.be32(2).toInt().toLong(); val count = cdb.be16(7) // LBA is signed (SAO pregap is negative)
                if (writeType == 2 && cue == null) return check(Sense(5, 0x2C, 0)) // SAO needs a cue sheet first
                val size = if (profile in 0x09..0x0A) { if (dbt == 0) 2352 else 2048 } else 2048
                assertEquals(count * size, length)
                val sequential = profile !in listOf(0x12, 0x13, 0x1A)
                if (sequential) {
                    if (lba != nwa) return check(Sense(5, 0x21, 2))
                    if (state == 2) return check(Sense(5, 0x21, 2))
                    if (profile in 0x09..0x11 && !modeSelected) return check(Sense(5, 0x2C, 0))
                }
                if (!trackStarted) { trackStarts += lba; trackStarted = true }
                if (!testWrite) for (i in 0 until count) sectors[lba + i] = buffer.copyOfRange(i * size, (i + 1) * size)
                if (sequential) nwa += count
                if (!testWrite) state = 1
                return CommandResult(0, length)
            }
            0x35 -> { if (writeType == 2 && !testWrite && cue != null) state = 2; return CommandResult(0, 0) }
            0x5D -> {
                assertEquals("cue sheet needs SAO write type", 2, writeType)
                val n = (cdb.u8(6) shl 16) or (cdb.u8(7) shl 8) or cdb.u8(8)
                assertEquals(n, length); assertEquals(0, n % 8)
                cue = buffer.copyOf(n); nwa = -150
                return CommandResult(0, n)
            }
            0x5B -> {
                val fn = cdb.u8(2); closes += fn
                if (fn == 1) { nwa += 150 + 2; track++; trackStarted = false }
                if (fn == 2 || fn == 6) state = 2
                return CommandResult(0, 0)
            }
            0x25 -> { buffer.putBe32(0, capacity - 1); buffer.putBe32(4, 2048); return CommandResult(0, 8) }
            0x28 -> {
                val lba = cdb.be32(2); val count = cdb.be16(7)
                for (i in 0 until count) {
                    val s = sectors[lba + i] ?: return check(Sense(3, 0x11, 0))
                    s.copyInto(buffer, i * 2048)
                }
                return CommandResult(0, count * 2048)
            }
            else -> return check(Sense(5, 0x20, 0))
        }
    }

    override fun close() {}
}

class BurnerTest {

    private fun iso(bytes: Int) = ByteArray(bytes) { (it * 31 + 7).toByte() }
    private val opts = BurnOptions(simulate = false, verify = true)

    @Test fun burnIsoToCdrAndVerify() {
        val rec = FakeRecorder()
        val data = iso(2048 * 100 + 500) // not a whole number of sectors
        val b = Burner(Drive(rec), NoopListener) { false }
        val r = b.burnData(ByteArrayInputStream(data), data.size.toLong(), opts)
        assertEquals(101L, r.sectors)
        assertEquals(1, rec.writeType); assertEquals(4, rec.trackMode); assertEquals(8, rec.dbt)
        assertFalse(rec.testWrite)
        assertEquals(listOf(1, 2), rec.closes)
        assertEquals(2, rec.state)
        assertArrayEquals(data.copyOfRange(0, 2048), rec.sectors[0L])
        assertEquals(0, rec.sectors[100L]!![600].toInt()) // padded tail
        assertTrue(b.verifyData(r))
        rec.sectors[50L]!![0] = 99
        assertFalse(b.verifyData(r))
    }

    @Test fun simulateWritesNothing() {
        val rec = FakeRecorder()
        val data = iso(2048 * 10)
        Burner(Drive(rec), NoopListener) { false }.burnData(ByteArrayInputStream(data), data.size.toLong(), BurnOptions(true, false))
        assertTrue(rec.testWrite)
        assertTrue(rec.sectors.isEmpty())
        assertEquals(0, rec.state)
        assertTrue(rec.closes.isEmpty())
    }

    @Test fun refusesUsedDisc() {
        val rec = FakeRecorder(state = 2)
        try {
            Burner(Drive(rec), NoopListener) { false }.burnData(ByteArrayInputStream(iso(4096)), 4096, opts)
            fail()
        } catch (e: IOException) { assertTrue(e.message!!, e.message!!.contains("isn't blank")) }
    }

    @Test fun refusesTooBig() {
        val rec = FakeRecorder(capacity = 50)
        try {
            Burner(Drive(rec), NoopListener) { false }.burnData(ByteArrayInputStream(iso(2048 * 60)), 2048L * 60, opts)
            fail()
        } catch (e: IOException) { assertTrue(e.message!!, e.message!!.contains("free")) }
    }

    @Test fun retriesWhenDriveIsBusy() {
        val rec = FakeRecorder(busyWrites = 3)
        val data = iso(2048 * 40)
        Burner(Drive(rec), NoopListener) { false }.burnData(ByteArrayInputStream(data), data.size.toLong(), opts)
        assertArrayEquals(data.copyOfRange(2048 * 39, 2048 * 40), rec.sectors[39L])
    }

    @Test fun dvdPlusRFinalizes() {
        val rec = FakeRecorder(profile = 0x1B, capacity = 2_295_104)
        val data = iso(2048 * 64)
        Burner(Drive(rec), NoopListener) { false }.burnData(ByteArrayInputStream(data), data.size.toLong(), opts)
        assertFalse(rec.modeSelected)
        assertEquals(listOf(1, 6), rec.closes)
    }

    @Test fun dvdPlusRCannotSimulate() {
        val rec = FakeRecorder(profile = 0x1B)
        try {
            Burner(Drive(rec), NoopListener) { false }.burnData(ByteArrayInputStream(iso(2048)), 2048, BurnOptions(true, false))
            fail()
        } catch (e: IOException) { assertTrue(e.message!!.contains("Test burn")) }
    }

    @Test fun dvdMinusRUsesIncremental() {
        val rec = FakeRecorder(profile = 0x11, capacity = 2_298_496)
        Burner(Drive(rec), NoopListener) { false }.burnData(ByteArrayInputStream(iso(2048 * 3)), 2048L * 3, opts)
        assertEquals(0, rec.writeType); assertEquals(5, rec.trackMode)
    }

    private fun wav(samples: Int, channels: Int, gen: (Int) -> Int): ByteArray {
        val data = ByteArray(samples * channels * 2)
        for (i in 0 until samples * channels) { val v = gen(i); data[i * 2] = v.toByte(); data[i * 2 + 1] = (v shr 8).toByte() }
        val out = ByteArrayOutputStream()
        val h = Wav.header(data.size.toLong())
        if (channels == 1) { h.putLe16(22, 1); h.putLe32(28, 44_100L * 2); h.putLe16(32, 2) }
        out.write(h); out.write(data)
        return out.toByteArray()
    }

    @Test fun burnAudioCd() {
        val rec = FakeRecorder()
        val stereo = wav(588 * 400 + 10, 2) { it % 1000 }      // 401 sectors after padding
        val mono = wav(588 * 10, 1) { 1000 + it }               // short: padded to 300 sectors
        val tracks = listOf(WavSource("a.wav", ByteArrayInputStream(stereo)), WavSource("b.wav", ByteArrayInputStream(mono)))
        Burner(Drive(rec), NoopListener) { false }.burnAudio(tracks, opts)
        assertEquals(1, rec.writeType); assertEquals(0, rec.trackMode); assertEquals(0, rec.dbt)
        assertEquals(listOf(1, 1, 2), rec.closes)
        assertEquals(2, rec.trackStarts.size)
        val t2 = rec.trackStarts[1]
        assertEquals(401L + 152, t2)
        // first track's first sector equals the WAV data
        assertArrayEquals(stereo.copyOfRange(44, 44 + 2352), rec.sectors[0L])
        // mono sample duplicated into L and R
        val s = rec.sectors[t2]!!
        assertEquals(1000, (s[0].toInt() and 0xFF) or (s[1].toInt() shl 8))
        assertEquals(1000, (s[2].toInt() and 0xFF) or (s[3].toInt() shl 8))
        assertEquals(1001, (s[4].toInt() and 0xFF) or (s[5].toInt() shl 8))
        // padded to the 4-second minimum
        assertTrue(rec.sectors.containsKey(t2 + 299)); assertFalse(rec.sectors.containsKey(t2 + 300))
    }

    @Test fun burnAudioCdGapless() {
        val rec = FakeRecorder()
        val a = wav(588 * 400 + 10, 2) { it % 1000 }   // 400 sectors + 40 bytes
        val b = wav(588 * 320, 2) { 2000 + it % 7 }    // exactly 320 sectors
        val tracks = listOf(WavSource("a.wav", ByteArrayInputStream(a)), WavSource("b.wav", ByteArrayInputStream(b)))
        Burner(Drive(rec), NoopListener) { false }.burnAudio(tracks, BurnOptions(simulate = false, verify = false, gapless = true))
        assertEquals(2, rec.writeType)
        assertTrue("no close commands in disc-at-once", rec.closes.isEmpty())
        assertEquals(2, rec.state)
        // continuous write from the pregap
        assertEquals(-150L, rec.trackStarts[0])
        assertTrue(rec.sectors[-150L]!!.all { it.toInt() == 0 })
        assertArrayEquals(a.copyOfRange(44, 44 + 2352), rec.sectors[0L])
        // track 2 starts right after track 1's last byte, mid-sector: no silence in between
        val s400 = rec.sectors[400L]!!
        assertArrayEquals(a.copyOfRange(44 + 400 * 2352, a.size), s400.copyOfRange(0, 40))
        assertArrayEquals(b.copyOfRange(44, 44 + 2312), s400.copyOfRange(40, 2352))
        // 720 sectors + 40 bytes -> 721 sectors, nothing past that
        assertTrue(rec.sectors.containsKey(720L)); assertFalse(rec.sectors.containsKey(721L))
        // cue sheet: lead-in, T1 idx0 at 00:00:00, T1 idx1 at 00:02:00, T2 at 400+150, lead-out at 721+150
        val cue = rec.cue!!
        assertEquals(5 * 8, cue.size)
        assertEquals(0x01, cue.u8(3)); assertEquals(0x00, cue.u8(1))
        assertArrayEquals(byteArrayOf(0x01, 1, 0, 0, 0, 0, 0, 0), cue.copyOfRange(8, 16))
        assertArrayEquals(byteArrayOf(0x01, 1, 1, 0, 0, 0, 2, 0), cue.copyOfRange(16, 24))
        assertArrayEquals(byteArrayOf(0x01, 2, 1, 0, 0, 0, 7, 25), cue.copyOfRange(24, 32)) // 550 = 0:07:25
        assertArrayEquals(byteArrayOf(0x01, 0xAA.toByte(), 1, 1, 0, 0, 11, 46), cue.copyOfRange(32, 40)) // 871 = 0:11:46
    }

    @Test fun gaplessLayoutPadsShortTracksAndUsesBcd() {
        val l = GaplessLayout(List(12) { 2352L * 10 }) // 12 tiny tracks
        assertEquals(300L * 12, l.totalSectors)
        assertEquals(300L * 11, l.trackStarts[11])
        assertEquals(0x12, l.cueSheet().u8(8 * 13 + 1)) // track 12 written as BCD 0x12
        assertEquals(11, l.trackAt(300L * 11 + 5))
    }

    @Test fun gaplessTestBurnWritesNothing() {
        val rec = FakeRecorder()
        Burner(Drive(rec), NoopListener) { false }.burnAudio(listOf(WavSource("a", ByteArrayInputStream(wav(588 * 300, 2) { 1 }))),
            BurnOptions(simulate = true, verify = false, gapless = true))
        assertTrue(rec.testWrite); assertTrue(rec.sectors.isEmpty()); assertEquals(0, rec.state)
    }

    @Test fun earlyAudioCheckCatchesUsedDiscAndTooLong() {
        val b = { rec: FakeRecorder -> Burner(Drive(rec), NoopListener) { false } }
        try { b(FakeRecorder(state = 2)).checkAudioDisc(opts, 10, 2400); fail() }
        catch (e: IOException) { assertTrue(e.message!!, e.message!!.contains("isn't blank")) }
        try { b(FakeRecorder()).checkAudioDisc(opts, 20, 90 * 60); fail() }
        catch (e: IOException) { assertTrue(e.message!!, e.message!!.contains("Remove some songs")) }
        try { b(FakeRecorder(profile = 0x11)).checkAudioDisc(opts, 3, 600); fail() }
        catch (e: IOException) { assertTrue(e.message!!, e.message!!.contains("CD-R")) }
        assertTrue(b(FakeRecorder()).checkAudioDisc(opts, 15, 70 * 60).isBlank)
    }

    @Test fun audioNeedsCd() {
        val rec = FakeRecorder(profile = 0x11)
        try {
            Burner(Drive(rec), NoopListener) { false }.burnAudio(listOf(WavSource("a", ByteArrayInputStream(wav(588, 2) { 0 }))), opts)
            fail()
        } catch (e: IOException) { assertTrue(e.message!!.contains("CD-R")) }
    }

    @Test fun wavRejectsWrongFormat() {
        val w = wav(100, 2) { 0 }; w.putLe32(24, 48_000)
        try { WavSource("x.wav", ByteArrayInputStream(w)); fail() } catch (e: IOException) { assertTrue(e.message!!.contains("44.1")) }
    }
}
