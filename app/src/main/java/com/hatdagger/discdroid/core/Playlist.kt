package com.hatdagger.discdroid.core

import java.io.IOException

/** One song in the burn list. [key] identifies the file (a content URI on Android). */
class Song(
    val key: String,
    val fileName: String,
    var title: String? = null,
    var artist: String? = null,
    var trackNumber: Int? = null,
    var durationMs: Long = 0,
    /** The file is a video (MP4/WebM/MKV…); only its sound is used. */
    var isVideo: Boolean = false,
) {
    val displayTitle get() = title?.takeIf { it.isNotBlank() } ?: fileName.substringBeforeLast('.')

    /** Track number from the tags, else from a leading number in the file name ("03 - Song.flac"). */
    val effectiveTrackNumber: Int?
        get() = trackNumber ?: Regex("^\\s*(\\d{1,3})").find(fileName)?.groupValues?.get(1)?.toIntOrNull()
}

/** Ways to put the burn list in order. */
enum class SongSort(val label: String) {
    TRACK_NUMBER("Track #"), FILE_NAME("File name"), TITLE("Title"), ARTIST("Artist"), REVERSE("Reverse");

    fun apply(songs: List<Song>): List<Song> = when (this) {
        TRACK_NUMBER -> songs.sortedWith(
            compareBy<Song, Int?>(nullsLast()) { it.effectiveTrackNumber }.thenComparator { a, b -> natural(a.fileName, b.fileName) },
        )
        FILE_NAME -> songs.sortedWith { a, b -> natural(a.fileName, b.fileName) }
        TITLE -> songs.sortedWith { a, b -> natural(a.displayTitle, b.displayTitle) }
        ARTIST -> songs.sortedWith(
            compareBy<Song, String?>(nullsLast()) { it.artist?.lowercase() }.thenComparator { a, b -> TRACK_NUMBER.compareOne(a, b) },
        )
        REVERSE -> songs.reversed()
    }

    private fun compareOne(a: Song, b: Song): Int {
        val ta = a.effectiveTrackNumber; val tb = b.effectiveTrackNumber
        if (ta != null && tb != null && ta != tb) return ta.compareTo(tb)
        return natural(a.fileName, b.fileName)
    }

    companion object {
        /** Case-insensitive compare where "2 Song" comes before "10 Song". */
        fun natural(a: String, b: String): Int {
            var i = 0; var j = 0
            while (i < a.length && j < b.length) {
                val ca = a[i]; val cb = b[j]
                if (ca.isDigit() && cb.isDigit()) {
                    var ei = i; while (ei < a.length && a[ei].isDigit()) ei++
                    var ej = j; while (ej < b.length && b[ej].isDigit()) ej++
                    val na = a.substring(i, ei).trimStart('0'); val nb = b.substring(j, ej).trimStart('0')
                    if (na.length != nb.length) return na.length - nb.length
                    val c = na.compareTo(nb)
                    if (c != 0) return c
                    i = ei; j = ej
                } else {
                    val c = ca.lowercaseChar().compareTo(cb.lowercaseChar())
                    if (c != 0) return c
                    i++; j++
                }
            }
            return (a.length - i) - (b.length - j)
        }
    }
}

object Playlists {
    /** Moves the item at [from] to [to] (both 0-based). */
    fun <T> move(list: MutableList<T>, from: Int, to: Int) {
        if (from !in list.indices || to !in list.indices || from == to) return
        list.add(to, list.removeAt(from))
    }

    /** "12 songs · 48:21" */
    fun summary(songs: List<Song>): String {
        val s = songs.sumOf { it.durationMs } / 1000
        return "${songs.size} song${if (songs.size == 1) "" else "s"} · ${clock(s)}"
    }

    fun clock(seconds: Long): String =
        if (seconds >= 3600) "%d:%02d:%02d".format(seconds / 3600, seconds / 60 % 60, seconds % 60)
        else "%d:%02d".format(seconds / 60, seconds % 60)

    /** An 80-minute CD-R holds this many seconds of audio (with gaps it's a little less). */
    const val CD_SECONDS = 79 * 60 + 57L
}

/**
 * Plays audio straight off a CD: reads raw audio sectors from the drive and hands them to [out]
 * (an AudioTrack on Android). Runs until the last audio track ends, [isStopped] returns true,
 * or the drive disappears. Plays continuously across tracks, like a CD player.
 */
class CdPlayback(private val drive: Drive, val tracks: List<Track>) {
    /** End of the playable audio area (exclusive). */
    val endLba: Long = tracks.lastOrNull()?.let { it.startLba + it.lengthSectors } ?: 0L

    @Volatile private var seekTarget = -1L

    /** Jump to [lba] as soon as the current chunk is done. */
    fun seek(lba: Long) { seekTarget = lba.coerceIn(tracks.firstOrNull()?.startLba ?: 0L, endLba) }

    /** Index into [tracks] for a sector position. */
    fun trackAt(lba: Long): Int = tracks.indexOfLast { it.startLba <= lba }.coerceAtLeast(0)

    fun play(
        fromLba: Long,
        isStopped: () -> Boolean,
        isPaused: () -> Boolean,
        out: (ByteArray, Int) -> Unit,
        position: (Long) -> Unit,
        onReadError: (Long) -> Unit = {},
    ) {
        val buf = ByteArray(CHUNK * Drive.AUDIO_SECTOR)
        var lba = fromLba
        while (!isStopped()) {
            val s = seekTarget
            if (s >= 0) { lba = s; seekTarget = -1 }
            if (lba >= endLba) return
            if (isPaused()) { Thread.sleep(40); continue }
            val n = minOf(CHUNK.toLong(), endLba - lba).toInt()
            try {
                drive.readAudio(lba, n, buf)
            } catch (e: ScsiException) {
                if (e.sense.isNoMedium) throw IOException("The disc was removed.")
                buf.fill(0, 0, n * Drive.AUDIO_SECTOR) // scratched spot: play silence and move on
                onReadError(lba)
            }
            out(buf, n * Drive.AUDIO_SECTOR)
            lba += n
            position(lba)
        }
    }

    companion object {
        /** 15 sectors = 0.2 s per read: small enough that pause and seek feel instant. */
        const val CHUNK = 15
    }
}
