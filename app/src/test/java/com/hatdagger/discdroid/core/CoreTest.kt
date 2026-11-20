package com.hatdagger.discdroid.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException

/** A pretend CD/DVD drive that answers SCSI commands from memory. */
class FakeDrive(
    private val tocBytes: ByteArray,
    private val cdText: ByteArray? = null,
    private val badSectors: MutableMap<Long, Int> = mutableMapOf(), // lba -> failures left (-1 = forever)
    private val protectedFrom: Long = Long.MAX_VALUE,
    private val lastLba: Long = 999,
) : ScsiTransport {
    override val description = "fake"
    var sense = Sense(0, 0, 0)
    var commands = 0

    private fun check(s: Sense): CommandResult { sense = s; return CommandResult(1, 0) }

    override fun execute(cdb: ByteArray, direction: Direction, buffer: ByteArray, length: Int, timeoutMs: Int): CommandResult {
        commands++
        when (cdb.u8(0)) {
            0x03 -> {
                buffer.fill(0, 0, 18); buffer[0] = 0x70; buffer[2] = sense.key.toByte()
                buffer[7] = 10; buffer[12] = sense.asc.toByte(); buffer[13] = sense.ascq.toByte()
                return CommandResult(0, 18)
            }
            0x00 -> return CommandResult(0, 0)
            0x12 -> {
                buffer.fill(0x20, 0, 36); buffer[0] = 5
                "HL-DT-ST".toByteArray().copyInto(buffer, 8)
                "DVDRAM GP65NB60 ".toByteArray().copyInto(buffer, 16)
                "PF00".toByteArray().copyInto(buffer, 32)
                return CommandResult(0, 36)
            }
            0x43 -> {
                val src = if (cdb.u8(2) == 5) cdText ?: return check(Sense(5, 0x24, 0)) else tocBytes
                val n = minOf(src.size, length); src.copyInto(buffer, 0, 0, n)
                return CommandResult(0, n)
            }
            0x25 -> { buffer.putBe32(0, lastLba); buffer.putBe32(4, 2048); return CommandResult(0, 8) }
            0xBE, 0x28 -> {
                val lba = cdb.be32(2)
                val count = if (cdb.u8(0) == 0xBE) (cdb.u8(6) shl 16) or (cdb.u8(7) shl 8) or cdb.u8(8) else cdb.be16(7)
                val size = if (cdb.u8(0) == 0xBE) 2352 else 2048
                for (s in lba until lba + count) {
                    if (s >= protectedFrom) return check(Sense(5, 0x6F, 0x03))
                    val left = badSectors[s]
                    if (left != null && left != 0) {
                        if (left > 0) badSectors[s] = left - 1
                        return check(Sense(3, 0x11, 0))
                    }
                }
                for (i in 0 until count) buffer.fill((lba + i).toByte(), i * size, (i + 1) * size)
                return CommandResult(0, count * size)
            }
            else -> return check(Sense(5, 0x20, 0))
        }
    }

    override fun close() {}
}

fun toc(vararg entries: Triple<Int, Long, Boolean>, leadOut: Long): ByteArray {
    val n = entries.size + 1
    val b = ByteArray(4 + n * 8)
    val len = 2 + n * 8
    b[0] = (len shr 8).toByte(); b[1] = len.toByte()
    b[2] = entries.first().first.toByte(); b[3] = entries.last().first.toByte()
    var o = 4
    for ((num, lba, data) in entries.toList() + Triple(0xAA, leadOut, false)) {
        b[o + 1] = (0x10 or if (data) 0x04 else 0).toByte()
        b[o + 2] = num.toByte()
        b.putBe32(o + 4, lba)
        o += 8
    }
    return b
}

object NoopListener : RipListener {
    val logs = mutableListOf<String>()
    override fun log(message: String) { logs += message }
    override fun progress(label: String, done: Long, total: Long) {}
}

class CoreTest {

    @Test fun cbwLayout() {
        val cbw = BulkOnly.buildCbw(0x01020304, 2352, Direction.IN, 0, byteArrayOf(0xBE.toByte(), 4))
        assertEquals(31, cbw.size)
        assertArrayEquals(byteArrayOf(0x55, 0x53, 0x42, 0x43), cbw.copyOfRange(0, 4))
        assertArrayEquals(byteArrayOf(4, 3, 2, 1), cbw.copyOfRange(4, 8))
        assertEquals(2352L, cbw.le32(8))
        assertEquals(0x80, cbw.u8(12))
        assertEquals(2, cbw.u8(14))
        assertEquals(0xBE, cbw.u8(15))
    }

    @Test fun cswParse() {
        val b = byteArrayOf(0x55, 0x53, 0x42, 0x53, 7, 0, 0, 0, 0, 0, 0, 0, 1)
        val csw = BulkOnly.parseCsw(b, 13)!!
        assertEquals(7, csw.tag); assertEquals(1, csw.status)
        assertNull(BulkOnly.parseCsw(b, 12))
    }

    @Test fun senseFixedFormat() {
        val b = ByteArray(18); b[0] = 0x70; b[2] = 0x02; b[12] = 0x3A
        assertTrue(Sense.parse(b, 18).isNoMedium)
    }

    @Test fun tocAudioCd() {
        val t = Toc.parse(toc(Triple(1, 0L, false), Triple(2, 15000L, false), leadOut = 30000), 100)
        assertEquals(2, t.tracks.size)
        assertEquals(15000L, t.tracks[0].lengthSectors)
        assertEquals(15000L, t.tracks[1].lengthSectors)
        assertEquals("3:20", t.tracks[0].durationText())
    }

    @Test fun tocEnhancedCdSubtractsSessionGap() {
        val t = Toc.parse(toc(Triple(1, 0L, false), Triple(2, 50000L, true), leadOut = 60000), 100)
        assertEquals(50000L - 11400, t.tracks[0].lengthSectors)
        assertTrue(t.tracks[1].isData)
    }

    @Test fun tocMixedModeKeepsFullAudioLength() {
        val t = Toc.parse(toc(Triple(1, 0L, true), Triple(2, 20000L, false), leadOut = 30000), 100)
        assertEquals(20000L, t.tracks[0].lengthSectors)
        assertEquals(10000L, t.tracks[1].lengthSectors)
    }

    @Test fun cdTextParse() {
        // Titles: album "Album", track1 "One", track2 "Two" ; performer album "Band"
        fun packs(type: Int, text: String): List<ByteArray> {
            val bytes = text.toByteArray(Charsets.ISO_8859_1)
            val out = mutableListOf<ByteArray>()
            var i = 0; var trackNo = 0
            while (i < bytes.size) {
                val p = ByteArray(18); p[0] = type.toByte(); p[1] = trackNo.toByte()
                for (k in 0 until 12) {
                    if (i < bytes.size) { p[4 + k] = bytes[i]; if (bytes[i].toInt() == 0) trackNo++; i++ }
                }
                out += p
            }
            return out
        }
        val all = packs(0x80, "Album\u0000One\u0000Two\u0000") + packs(0x81, "Band\u0000\u0000\u0000")
        val b = ByteArray(4 + all.size * 18)
        val len = 2 + all.size * 18; b[0] = (len shr 8).toByte(); b[1] = len.toByte()
        all.forEachIndexed { i, p -> p.copyInto(b, 4 + i * 18) }
        val t = CdText.parse(b, b.size)!!
        assertEquals("Album", t.albumTitle)
        assertEquals("Band", t.albumArtist)
        assertEquals("One", t.trackTitles[1])
        assertEquals("Two", t.trackTitles[2])
    }

    @Test fun wavHeader() {
        val h = Wav.header(2352L * 10)
        assertEquals("RIFF", String(h, 0, 4)); assertEquals("WAVE", String(h, 8, 4))
        assertEquals(36L + 23520, h.le32(4)); assertEquals(23520L, h.le32(40))
        assertEquals(44100L, h.le32(24))
    }

    @Test fun driveInquiryAndToc() {
        val d = Drive(FakeDrive(toc(Triple(1, 0L, false), leadOut = 100)))
        val inq = d.inquiry()
        assertTrue(inq.isOpticalDrive); assertEquals("HL-DT-ST DVDRAM GP65NB60", inq.name)
        assertTrue(d.waitReady())
        assertEquals(100L, d.readToc().tracks[0].lengthSectors)
        assertNull(d.readCdText())
    }

    @Test fun ripCleanTrack() {
        val drive = Drive(FakeDrive(toc(Triple(1, 0L, false), leadOut = 60)))
        val track = drive.readToc().tracks[0]
        val out = ByteArrayOutputStream()
        val stats = AudioRipper(drive, NoopListener) { false }.ripTrack(track, "t1", out)
        val bytes = out.toByteArray()
        assertEquals(44 + 60 * 2352, bytes.size)
        assertEquals(0L, stats.badSectors)
        // sector 30 starts at 44 + 30*2352 and is filled with byte 30
        assertEquals(30, bytes.u8(44 + 30 * 2352))
        assertEquals(59, bytes.u8(bytes.size - 1))
    }

    @Test fun ripRecoversFromTransientError() {
        val fake = FakeDrive(toc(Triple(1, 0L, false), leadOut = 60), badSectors = mutableMapOf(40L to 2))
        val drive = Drive(fake)
        val out = ByteArrayOutputStream()
        val stats = AudioRipper(drive, NoopListener) { false }.ripTrack(drive.readToc().tracks[0], "t", out)
        assertEquals(0L, stats.badSectors)
        assertTrue(stats.retriedChunks >= 1)
        assertEquals(40, out.toByteArray().u8(44 + 40 * 2352))
    }

    @Test fun ripZeroFillsPermanentError() {
        val fake = FakeDrive(toc(Triple(1, 0L, false), leadOut = 60), badSectors = mutableMapOf(41L to -1))
        val drive = Drive(fake)
        val out = ByteArrayOutputStream()
        val stats = AudioRipper(drive, NoopListener) { false }.ripTrack(drive.readToc().tracks[0], "t", out)
        val b = out.toByteArray()
        assertEquals(1L, stats.badSectors)
        assertEquals(44 + 60 * 2352, b.size)
        assertEquals(0, b.u8(44 + 41 * 2352 + 100))
        assertEquals(42, b.u8(44 + 42 * 2352))
    }

    @Test fun isoRipAndCopyProtection() {
        val drive = Drive(FakeDrive(toc(Triple(1, 0L, true), leadOut = 100), lastLba = 99))
        val out = ByteArrayOutputStream()
        val (last, block) = drive.readCapacity()
        assertEquals(99L, last); assertEquals(2048, block)
        IsoRipper(drive, NoopListener) { false }.rip(0, 100, "iso", out)
        assertEquals(100 * 2048, out.size())

        val prot = Drive(FakeDrive(toc(Triple(1, 0L, true), leadOut = 100), protectedFrom = 50))
        try {
            IsoRipper(prot, NoopListener) { false }.rip(0, 100, "iso", ByteArrayOutputStream())
            fail("expected copy-protection error")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("copy-protected"))
        }
    }

    @Test fun cancelStopsRip() {
        val drive = Drive(FakeDrive(toc(Triple(1, 0L, false), leadOut = 600)))
        var calls = 0
        try {
            AudioRipper(drive, NoopListener) { ++calls > 3 }.ripTrack(drive.readToc().tracks[0], "t", ByteArrayOutputStream())
            fail("expected cancel")
        } catch (_: CancelledException) {
        }
    }

    @Test fun isoVolumeParse() {
        val s = ByteArray(2048); s[0] = 1; "CD001".toByteArray().copyInto(s, 1)
        "MY_DISC".padEnd(32).toByteArray().copyInto(s, 40); s.putLe32(80, 12345)
        val v = IsoVolume.parse(s)
        assertNotNull(v); assertEquals("MY_DISC", v!!.label); assertEquals(12345L, v.volumeSectors)
        assertFalse(IsoVolume.parse(ByteArray(2048)) != null)
    }

    @Test fun safeNames() {
        assertEquals("AC_DC - Back_In Black", Names.safe("AC/DC - Back\\In Black", "x"))
        assertEquals("x", Names.safe("  ...  ", "x"))
    }
}
