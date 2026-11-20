package com.hatdagger.discdroid.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaylistTest {

    private fun names(l: List<Song>) = l.map { it.fileName }

    @Test fun naturalOrder() {
        assertTrue(SongSort.natural("2 b.flac", "10 a.flac") < 0)
        assertTrue(SongSort.natural("Track 09", "track 10") < 0)
        assertTrue(SongSort.natural("abc", "ABD") < 0)
        assertEquals(0, SongSort.natural("Song", "song"))
        assertTrue(SongSort.natural("a", "a1") < 0)
    }

    @Test fun sortByTrackNumberUsesTagsThenFileName() {
        val songs = listOf(
            Song("1", "zzz.mp3", trackNumber = 1),
            Song("2", "10 - Ten.flac"),
            Song("3", "2 - Two.flac"),
            Song("4", "bonus.wav"),
        )
        assertEquals(listOf("zzz.mp3", "2 - Two.flac", "10 - Ten.flac", "bonus.wav"), names(SongSort.TRACK_NUMBER.apply(songs)))
        assertEquals(listOf("2 - Two.flac", "10 - Ten.flac", "bonus.wav", "zzz.mp3"), names(SongSort.FILE_NAME.apply(songs)))
        assertEquals(listOf("zzz.mp3", "bonus.wav", "10 - Ten.flac", "2 - Two.flac"), names(SongSort.REVERSE.apply(SongSort.FILE_NAME.apply(songs))))
    }

    @Test fun sortByTitleAndArtist() {
        val songs = listOf(
            Song("1", "a.flac", title = "Zebra", artist = "B"),
            Song("2", "b.flac", title = "apple", artist = "a"),
            Song("3", "c.flac"),
        )
        assertEquals(listOf("b.flac", "c.flac", "a.flac"), names(SongSort.TITLE.apply(songs)))
        assertEquals(listOf("b.flac", "a.flac", "c.flac"), names(SongSort.ARTIST.apply(songs)))
    }

    @Test fun moveAndSummary() {
        val l = mutableListOf("a", "b", "c", "d")
        Playlists.move(l, 0, 2); assertEquals(listOf("b", "c", "a", "d"), l)
        Playlists.move(l, 3, 0); assertEquals(listOf("d", "b", "c", "a"), l)
        Playlists.move(l, 5, 0); assertEquals(listOf("d", "b", "c", "a"), l)
        val s = listOf(Song("1", "a", durationMs = 61_000), Song("2", "b", durationMs = 3_600_000))
        assertEquals("2 songs · 1:01:01", Playlists.summary(s))
    }

    @Test fun cdPlaybackStreamsSeeksAndSkipsBadSectors() {
        val t = Toc.parse(toc(Triple(1, 0L, false), Triple(2, 40L, false), leadOut = 100), 4 + 3 * 8)
        val drive = Drive(FakeDrive(toc(Triple(1, 0L, false), Triple(2, 40L, false), leadOut = 100), badSectors = mutableMapOf(50L to -1)))
        val p = CdPlayback(drive, t.audioTracks)
        assertEquals(100L, p.endLba)
        assertEquals(1, p.trackAt(40)); assertEquals(0, p.trackAt(39))

        var bytes = 0L
        val firstByteOfChunk = mutableListOf<Int>()
        val errors = mutableListOf<Long>()
        p.play(0, { false }, { false }, { b, n -> bytes += n; firstByteOfChunk += b[0].toInt() }, {}, { errors += it })
        assertEquals(100L * 2352, bytes)
        assertEquals(0, firstByteOfChunk[0])          // fake drive fills each sector with its LBA
        assertEquals(listOf(45L), errors)             // chunk 45..59 has the bad sector -> silence
        assertEquals(0, firstByteOfChunk[3])

        // seek before playing: starts from track 2
        bytes = 0
        p.seek(40)
        p.play(0, { false }, { false }, { _, n -> bytes += n }, {})
        assertEquals(60L * 2352, bytes)

        // stop right away
        bytes = 0
        p.play(0, { true }, { false }, { _, n -> bytes += n }, {})
        assertEquals(0L, bytes)
    }
}
