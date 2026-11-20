package com.hatdagger.discdroid.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

class DiscImageTest {

    private fun rawSector(mode: Int) = ByteArray(2352).also { s ->
        for (i in 1..10) s[i] = 0xFF.toByte()
        s[15] = mode.toByte()
    }

    @Test fun sectorModeFromHeader() {
        assertEquals(TrackMode.MODE2, DiscImage.sectorMode(rawSector(2)))
        assertEquals(TrackMode.MODE1, DiscImage.sectorMode(rawSector(1)))
        assertNull(DiscImage.sectorMode(ByteArray(2352) { 7 })) // audio: no sync pattern
    }

    @Test fun msf() {
        assertEquals("00:00:00", DiscImage.msf(0))
        assertEquals("00:02:00", DiscImage.msf(150))
        assertEquals("01:00:74", DiscImage.msf(60 * 75 + 74))
    }

    /** A typical PS1 game: Mode 2 data track, then two CD-audio music tracks. */
    @Test fun ps1CueSheet() {
        val t = Toc.parse(toc(Triple(1, 0L, true), Triple(2, 20_000L, false), Triple(3, 30_000L, false), leadOut = 40_000).let { it }, 4 + 4 * 8)
        val cue = DiscImage.cueSheet(t, mapOf(1 to TrackMode.MODE2), "Game.bin")
        val expected = "FILE \"Game.bin\" BINARY\r\n" +
            "  TRACK 01 MODE2/2352\r\n    INDEX 01 00:00:00\r\n" +
            "  TRACK 02 AUDIO\r\n    INDEX 00 04:24:50\r\n    INDEX 01 04:26:50\r\n" +
            "  TRACK 03 AUDIO\r\n    INDEX 01 06:40:00\r\n"
        assertEquals(expected, cue)
    }

    @Test fun whenToUseBinCue() {
        val single = Toc.parse(toc(Triple(1, 0L, true), leadOut = 1000), 4 + 2 * 8)
        assertTrue(DiscImage.needsBinCue(single, mapOf(1 to TrackMode.MODE2)))   // PS1 game, no music tracks
        assertFalse(DiscImage.needsBinCue(single, mapOf(1 to TrackMode.MODE1)))  // ordinary data CD -> ISO
        val mixed = Toc.parse(toc(Triple(1, 0L, true), Triple(2, 500L, false), leadOut = 1000), 4 + 3 * 8)
        assertTrue(DiscImage.needsBinCue(mixed, mapOf(1 to TrackMode.MODE1)))
        val audio = Toc.parse(toc(Triple(1, 0L, false), Triple(2, 500L, false), leadOut = 1000), 4 + 3 * 8)
        assertFalse(DiscImage.needsBinCue(audio, emptyMap()))
    }

    @Test fun binRipCopiesEveryTrackRaw() {
        val drive = Drive(FakeDrive(toc(Triple(1, 0L, true), Triple(2, 40L, false), leadOut = 60)))
        val t = drive.readToc()
        val out = ByteArrayOutputStream()
        val stats = BinRipper(drive, NoopListener) { false }.rip(t, out)
        val b = out.toByteArray()
        assertEquals(60 * 2352, b.size)
        assertEquals(0L, stats.badSectors)
        assertEquals(5, b.u8(5 * 2352 + 2000))   // data track sector 5
        assertEquals(50, b.u8(50 * 2352 + 2351)) // audio track sector 50
    }

    @Test fun binRipZeroFillsBadSector() {
        val drive = Drive(FakeDrive(toc(Triple(1, 0L, true), leadOut = 30), badSectors = mutableMapOf(12L to -1)))
        val out = ByteArrayOutputStream()
        val stats = BinRipper(drive, NoopListener) { false }.rip(drive.readToc(), out)
        assertEquals(1L, stats.badSectors)
        assertEquals(0, out.toByteArray().u8(12 * 2352 + 100))
        assertEquals(13, out.toByteArray().u8(13 * 2352))
    }
}
