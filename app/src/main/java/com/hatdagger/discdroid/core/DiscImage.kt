package com.hatdagger.discdroid.core

import java.io.IOException
import java.io.OutputStream
import java.util.zip.CRC32

/** How one track is stored in a BIN/CUE image. */
enum class TrackMode(val cue: String) { AUDIO("AUDIO"), MODE1("MODE1/2352"), MODE2("MODE2/2352") }

/**
 * Raw "BIN/CUE" disc images: every sector copied in full (2352 bytes), all tracks in one .bin,
 * described by a .cue sheet. This is the standard format for PlayStation 1, Sega CD/Saturn and
 * PC Engine CD games, and for any CD that mixes data and music tracks.
 */
object DiscImage {

    /** Data mode of a raw sector (byte 15 after the 12-byte sync), or null if it doesn't look like data. */
    fun sectorMode(raw: ByteArray): TrackMode? {
        if (raw.size < 16) return null
        val sync = raw[0] == 0.toByte() && raw[11] == 0.toByte() && (1..10).all { raw[it] == 0xFF.toByte() }
        if (!sync) return null
        return when (raw.u8(15)) { 1 -> TrackMode.MODE1; 2 -> TrackMode.MODE2; else -> null }
    }

    /** True when a plain ISO can't hold this disc and a BIN/CUE copy is needed. */
    fun needsBinCue(toc: Toc, modes: Map<Int, TrackMode>): Boolean {
        val first = toc.tracks.first()
        if (first.isAudio) return false // audio CD / Enhanced CD: use the audio ripper (+ ISO for its data)
        return toc.tracks.size > 1 || modes[first.number] == TrackMode.MODE2
    }

    /** "mm:ss:ff" for a sector position counted from the start of the .bin file. */
    fun msf(sectors: Long): String = "%02d:%02d:%02d".format(sectors / (75 * 60), (sectors / 75) % 60, sectors % 75)

    /** The .cue sheet for a single-file image of [toc] named [binName]. */
    fun cueSheet(toc: Toc, modes: Map<Int, TrackMode>, binName: String): String {
        val base = toc.tracks.first().startLba
        val sb = StringBuilder()
        sb.append("FILE \"").append(binName.replace("\"", "'")).append("\" BINARY\r\n")
        for ((i, t) in toc.tracks.withIndex()) {
            val mode = if (t.isAudio) TrackMode.AUDIO else modes[t.number] ?: TrackMode.MODE1
            sb.append("  TRACK %02d %s\r\n".format(t.number, mode.cue))
            val pos = t.startLba - base
            // An audio track right after a data track always has a 2-second pregap on the disc.
            val prev = toc.tracks.getOrNull(i - 1)
            if (t.isAudio && prev != null && prev.isData && pos >= PREGAP) {
                sb.append("    INDEX 00 ").append(msf(pos - PREGAP)).append("\r\n")
            }
            sb.append("    INDEX 01 ").append(msf(pos)).append("\r\n")
        }
        return sb.toString()
    }

    const val PREGAP = 150L
}

class BinRipper(private val drive: Drive, private val listener: RipListener, private val isCancelled: () -> Boolean) {

    /** Works out each data track's mode by reading one raw sector from it. */
    fun detectModes(toc: Toc): Map<Int, TrackMode> {
        val modes = mutableMapOf<Int, TrackMode>()
        val buf = ByteArray(Drive.AUDIO_SECTOR)
        for (t in toc.tracks.filter { it.isData }) {
            // sector 16 is the file system header on PS1/ISO discs; fall back to the first sector
            for (off in listOf(16L, 0L)) {
                if (off >= t.lengthSectors) continue
                val m = try { drive.readRaw(t.startLba + off, 1, buf); DiscImage.sectorMode(buf) } catch (e: IOException) { null }
                if (m != null) { modes[t.number] = m; break }
            }
        }
        return modes
    }

    /** Copies every track of [toc] as raw 2352-byte sectors into one .bin stream. Does not close [out]. */
    fun rip(toc: Toc, out: OutputStream): RipStats {
        val start = System.currentTimeMillis()
        val tracks = toc.tracks
        val total = tracks.sumOf { it.lengthSectors }
        val crc = CRC32()
        var done = 0L
        var bad = 0L
        var retried = 0
        for ((i, t) in tracks.withIndex()) {
            // Data tracks: raw read of any sector type (also covers the pregap before a following
            // audio track); if the drive won't do raw on a sector, fall back to the audio read.
            val read: (Long, Int, ByteArray) -> Unit = if (t.isData) { lba, n, b ->
                try { drive.readRaw(lba, n, b) } catch (e: ScsiException) {
                    if (e.sense.isCopyProtected) throw e
                    drive.readAudio(lba, n, b)
                }
            } else { lba, n, b -> drive.readAudio(lba, n, b) }
            val reader = ChunkReader(Drive.AUDIO_SECTOR, CHUNK, 2, listener, isCancelled, read)
            val label = "Track ${t.number} (${i + 1}/${tracks.size})"
            var d = 0L
            while (d < t.lengthSectors) {
                val n = minOf(CHUNK.toLong(), t.lengthSectors - d).toInt()
                reader.readChunk(t.startLba + d, n)
                val bytes = n * Drive.AUDIO_SECTOR
                out.write(reader.buf, 0, bytes)
                crc.update(reader.buf, 0, bytes)
                d += n; done += n
                listener.progress(label, done, total)
            }
            bad += reader.bad; retried += reader.retried
        }
        out.flush()
        return RipStats(total, bad, retried, crc.value, System.currentTimeMillis() - start)
    }

    companion object {
        const val CHUNK = 26
    }
}
