package com.hatdagger.discdroid.android

import android.content.Context
import android.media.AudioFormat as AndroidAudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.media.MediaMetadataRetriever
import android.provider.OpenableColumns
import android.util.Log
import com.hatdagger.discdroid.core.CancelledException
import com.hatdagger.discdroid.core.MusicBrainz
import com.hatdagger.discdroid.core.PcmConverter
import com.hatdagger.discdroid.core.PcmSource
import com.hatdagger.discdroid.core.Song
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection

object Http {
    /** GET a URL; returns the body for HTTP 200, null for 404, throws on other failures. */
    fun get(url: String, accept: String = "application/json"): ByteArray? {
        val c = java.net.URI(url).toURL().openConnection() as HttpURLConnection
        try {
            c.setRequestProperty("User-Agent", MusicBrainz.USER_AGENT)
            c.setRequestProperty("Accept", accept)
            c.connectTimeout = 10_000
            c.readTimeout = 20_000
            c.instanceFollowRedirects = true
            return when (val code = c.responseCode) {
                200 -> c.inputStream.use { it.readBytes() }
                404 -> null
                else -> throw IOException("HTTP $code")
            }
        } finally {
            c.disconnect()
        }
    }
}

object Files {
    fun nameAndSize(context: Context, uri: Uri): Pair<String, Long> {
        var name = uri.lastPathSegment ?: "file"
        var size = -1L
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                c.getString(0)?.let { name = it }
                if (!c.isNull(1)) size = c.getLong(1)
            }
        }
        if (size < 0) {
            context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { size = it.length }
        }
        return name to size
    }

    /** Fills in title, artist, track number and length from the file's tags (music or video). */
    fun readTags(context: Context, song: Song) {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(context, Uri.parse(song.key))
            r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)?.let { song.title = it }
            (r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                ?: r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST))?.let { song.artist = it }
            r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER)
                ?.substringBefore('/')?.trim()?.toIntOrNull()?.let { song.trackNumber = it }
            r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?.let { song.durationMs = it }
            song.isVideo = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) == "yes"
        } catch (_: Exception) {
            // unreadable tags: keep the file name
        } finally {
            try { r.release() } catch (_: Exception) {}
        }
    }
}

/** Raw 16-bit stereo 44.1 kHz PCM stored in a temp file. */
class RawPcmFile(override val name: String, private val file: File) : PcmSource {
    private val input = FileInputStream(file).buffered(1 shl 20)
    override val totalBytes: Long = file.length()
    override fun read(buf: ByteArray, off: Int, len: Int): Int = input.read(buf, off, len)
    override fun close() { input.close(); file.delete() }
}

/**
 * Decodes the audio of any file Android understands to CD-format PCM in a temp file, so burning
 * is never slowed down by decoding. Works for music (FLAC, MP3, AAC/M4A, OGG, Opus, WAV…) and for
 * the soundtrack of videos (MP4, WebM, MKV, 3GP). Other sample rates are resampled to 44.1 kHz and
 * surround sound is mixed down to stereo.
 */
object AudioDecoder {
    fun decodeToCdPcm(context: Context, uri: Uri, name: String, out: File, isCancelled: () -> Boolean): RawPcmFile {
        val ex = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            ex.setDataSource(context, uri, null)
            val track = (0 until ex.trackCount).firstOrNull {
                ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: throw IOException("$name has no sound track")
            ex.selectTrack(track)
            val fmt = ex.getTrackFormat(track)
            val mime = fmt.getString(MediaFormat.KEY_MIME)!!
            var rate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var encoding = AndroidAudioFormat.ENCODING_PCM_16BIT
            val c = try { MediaCodec.createDecoderByType(mime) } catch (e: Exception) {
                throw IOException("$name uses a sound format this tablet can't decode ($mime)")
            }
            codec = c
            c.configure(fmt, null, null, 0)
            c.start()
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var scratch = ByteArray(0)
            BufferedOutputStream(FileOutputStream(out), 1 shl 20).use { os ->
                var conv: PcmConverter? = null
                fun converter(): PcmConverter {
                    val cur = conv
                    if (cur != null) return cur
                    if (rate != 44_100 || channels > 2) {
                        val what = if (channels > 2) "$channels-channel sound" else "sound"
                        Log.i("D2D", "$name: converting $what at $rate Hz to CD audio")
                    }
                    return PcmConverter(rate, channels, encoding == AndroidAudioFormat.ENCODING_PCM_FLOAT) { b, n -> os.write(b, 0, n) }
                        .also { conv = it }
                }
                while (!outputDone) {
                    if (isCancelled()) throw CancelledException()
                    if (!inputDone) {
                        val i = c.dequeueInputBuffer(10_000)
                        if (i >= 0) {
                            val buf = c.getInputBuffer(i)!!
                            val n = ex.readSampleData(buf, 0)
                            if (n < 0) {
                                c.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                c.queueInputBuffer(i, 0, n, ex.sampleTime, 0)
                                ex.advance()
                            }
                        }
                    }
                    val o = c.dequeueOutputBuffer(info, 10_000)
                    if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        val f = c.outputFormat
                        val newRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        val newChannels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        val newEnc = if (f.containsKey(MediaFormat.KEY_PCM_ENCODING)) f.getInteger(MediaFormat.KEY_PCM_ENCODING) else AndroidAudioFormat.ENCODING_PCM_16BIT
                        if (newRate != rate || newChannels != channels || newEnc != encoding) {
                            conv?.finish(); conv = null
                            rate = newRate; channels = newChannels; encoding = newEnc
                        }
                    } else if (o >= 0) {
                        if (info.size > 0) {
                            val buf = c.getOutputBuffer(o)!!
                            buf.position(info.offset)
                            buf.limit(info.offset + info.size)
                            if (scratch.size < info.size) scratch = ByteArray(info.size)
                            buf.get(scratch, 0, info.size)
                            converter().feed(scratch, info.size)
                        }
                        c.releaseOutputBuffer(o, false)
                        if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outputDone = true
                    }
                }
                conv?.finish()
            }
            if (out.length() == 0L) throw IOException("$name has no sound to burn")
            return RawPcmFile(name, out)
        } catch (e: Exception) {
            out.delete()
            throw e
        } finally {
            try { codec?.stop() } catch (_: Exception) {}
            try { codec?.release() } catch (_: Exception) {}
            ex.release()
        }
    }
}
