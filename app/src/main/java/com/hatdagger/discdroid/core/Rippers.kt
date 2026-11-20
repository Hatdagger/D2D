package com.hatdagger.discdroid.core

import java.io.IOException
import java.io.OutputStream
import java.util.zip.CRC32

enum class AudioFormat(val extension: String, val mime: String) {
    WAV("wav", "audio/x-wav"), FLAC("flac", "audio/flac")
}

interface RipListener {
    fun log(message: String)
    /** [done] and [total] are in sectors for the current item. */
    fun progress(label: String, done: Long, total: Long)
}

object Wav {
    /** 44-byte header for 16-bit stereo 44.1 kHz PCM with a known data size. */
    fun header(dataBytes: Long): ByteArray {
        require(dataBytes in 0..0xFFFF_FFDBL) { "Track too large for WAV" }
        val h = ByteArray(44)
        "RIFF".toByteArray().copyInto(h, 0)
        h.putLe32(4, 36 + dataBytes)
        "WAVE".toByteArray().copyInto(h, 8)
        "fmt ".toByteArray().copyInto(h, 12)
        h.putLe32(16, 16)
        h.putLe16(20, 1)          // PCM
        h.putLe16(22, 2)          // channels
        h.putLe32(24, 44_100)     // sample rate
        h.putLe32(28, 44_100L * 4) // byte rate
        h.putLe16(32, 4)          // block align
        h.putLe16(34, 16)         // bits per sample
        "data".toByteArray().copyInto(h, 36)
        h.putLe32(40, dataBytes)
        return h
    }
}

class RipStats(val sectors: Long, val badSectors: Long, val retriedChunks: Int, val crc32: Long, val millis: Long) {
    fun summary(): String {
        val secs = millis / 1000.0
        val quality = if (badSectors == 0L) "no errors" else "$badSectors unreadable sectors filled with silence/zeros"
        return "%s, %d re-reads, CRC32 %08X, %.0f s".format(quality, retriedChunks, crc32, secs)
    }
}

/**
 * Shared chunked reader with retries. On a failed chunk it re-reads sector by sector;
 * a sector that still fails after [sectorRetries] tries is replaced with zeros.
 */
internal class ChunkReader(
    private val sectorSize: Int,
    private val chunkSectors: Int,
    private val sectorRetries: Int,
    private val listener: RipListener,
    private val isCancelled: () -> Boolean,
    private val read: (lba: Long, count: Int, buf: ByteArray) -> Unit,
) {
    val buf = ByteArray(sectorSize * chunkSectors)
    private val one = ByteArray(sectorSize)
    var bad = 0L; private set
    var retried = 0; private set

    /** Fills buf with [count] sectors starting at [lba]. */
    fun readChunk(lba: Long, count: Int) {
        if (isCancelled()) throw CancelledException()
        try {
            read(lba, count, buf); return
        } catch (e: ScsiException) {
            if (e.sense.isCopyProtected) throw copyProtected()
            retried++
        } catch (e: CancelledException) {
            throw e
        } catch (e: IOException) {
            // Transport hiccup; the transport has already tried to resync.
            retried++
        }
        for (i in 0 until count) {
            if (isCancelled()) throw CancelledException()
            var ok = false
            var lastError: Exception? = null
            for (attempt in 0..sectorRetries) {
                try {
                    read(lba + i, 1, one); ok = true; break
                } catch (e: ScsiException) {
                    if (e.sense.isCopyProtected) throw copyProtected()
                    lastError = e
                } catch (e: CancelledException) {
                    throw e
                } catch (e: IOException) {
                    lastError = e
                }
            }
            if (ok) one.copyInto(buf, i * sectorSize)
            else {
                bad++
                buf.fill(0, i * sectorSize, (i + 1) * sectorSize)
                listener.log("  Sector ${lba + i} unreadable (${lastError?.message}); filled with zeros")
            }
        }
    }

    private fun copyProtected() = IOException(
        "This disc is copy-protected (encrypted sectors). D2D only copies discs without copy protection.",
    )
}

class AudioRipper(private val drive: Drive, private val listener: RipListener, private val isCancelled: () -> Boolean) {

    /** Writes one track as a complete WAV or FLAC file to [out]. Does not close [out]. */
    fun ripTrack(
        track: Track, label: String, out: OutputStream,
        format: AudioFormat = AudioFormat.WAV, tags: List<Pair<String, String>> = emptyList(),
    ): RipStats {
        require(track.isAudio) { "Track ${track.number} is a data track" }
        val total = track.lengthSectors
        val start = System.currentTimeMillis()
        val flac = if (format == AudioFormat.FLAC) FlacEncoder(out, total * SAMPLES_PER_SECTOR, tags) else null
        if (flac == null) out.write(Wav.header(total * Drive.AUDIO_SECTOR))
        val reader = ChunkReader(Drive.AUDIO_SECTOR, CHUNK, 3, listener, isCancelled) { lba, n, b -> drive.readAudio(lba, n, b) }
        val crc = CRC32()
        var done = 0L
        while (done < total) {
            val n = minOf(CHUNK.toLong(), total - done).toInt()
            reader.readChunk(track.startLba + done, n)
            val bytes = n * Drive.AUDIO_SECTOR
            if (flac != null) flac.write(reader.buf, 0, bytes) else out.write(reader.buf, 0, bytes)
            crc.update(reader.buf, 0, bytes)
            done += n
            listener.progress(label, done, total)
        }
        flac?.finish()
        out.flush()
        return RipStats(total, reader.bad, reader.retried, crc.value, System.currentTimeMillis() - start)
    }

    companion object {
        const val SAMPLES_PER_SECTOR = 588
        const val CHUNK = 26 // 26 * 2352 = 61,152 bytes per command
    }
}

class IsoRipper(private val drive: Drive, private val listener: RipListener, private val isCancelled: () -> Boolean) {

    /** Copies [sectors] 2048-byte sectors starting at [startLba] to [out]. Does not close [out]. */
    fun rip(startLba: Long, sectors: Long, label: String, out: OutputStream): RipStats {
        val start = System.currentTimeMillis()
        val reader = ChunkReader(Drive.DATA_SECTOR, CHUNK, 2, listener, isCancelled) { lba, n, b -> drive.readData(lba, n, b) }
        val crc = CRC32()
        var done = 0L
        while (done < sectors) {
            val n = minOf(CHUNK.toLong(), sectors - done).toInt()
            reader.readChunk(startLba + done, n)
            val bytes = n * Drive.DATA_SECTOR
            out.write(reader.buf, 0, bytes)
            crc.update(reader.buf, 0, bytes)
            done += n
            listener.progress(label, done, sectors)
        }
        out.flush()
        return RipStats(sectors, reader.bad, reader.retried, crc.value, System.currentTimeMillis() - start)
    }

    /** Volume label and size from the ISO 9660 header, if the disc has one. */
    fun readVolume(startLba: Long): IsoVolume? = try {
        val b = ByteArray(Drive.DATA_SECTOR)
        drive.readData(startLba + 16, 1, b)
        IsoVolume.parse(b)
    } catch (e: IOException) {
        null
    }

    companion object {
        const val CHUNK = 32 // 64 KiB per command
    }
}

object Names {
    private val bad = Regex("[\\\\/:*?\"<>|\\x00-\\x1F]")
    fun safe(s: String, fallback: String): String {
        val t = s.replace(bad, "_").trim().trim('.').take(120)
        return t.ifEmpty { fallback }
    }
}
