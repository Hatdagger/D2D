package com.hatdagger.discdroid.android

import android.content.Context
import android.media.AudioFormat as AndroidAudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.provider.OpenableColumns
import com.hatdagger.discdroid.core.CancelledException
import com.hatdagger.discdroid.core.MusicBrainz
import com.hatdagger.discdroid.core.PcmSource
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
}

/** Raw 16-bit stereo 44.1 kHz PCM stored in a temp file. */
class RawPcmFile(override val name: String, private val file: File) : PcmSource {
    private val input = FileInputStream(file).buffered(1 shl 20)
    override val totalBytes: Long = file.length()
    override fun read(buf: ByteArray, off: Int, len: Int): Int = input.read(buf, off, len)
    override fun close() { input.close(); file.delete() }
}

/**
 * Decodes any audio file Android understands (FLAC, MP3, AAC/M4A, OGG, WAV…) to CD-format PCM
 * in a temp file, so burning is never slowed down by decoding.
 */
object AudioDecoder {
    fun decodeToCdPcm(context: Context, uri: Uri, name: String, out: File, isCancelled: () -> Boolean): RawPcmFile {
        val ex = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            ex.setDataSource(context, uri, null)
            val track = (0 until ex.trackCount).firstOrNull {
                ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: throw IOException("$name has no audio")
            ex.selectTrack(track)
            val fmt = ex.getTrackFormat(track)
            val mime = fmt.getString(MediaFormat.KEY_MIME)!!
            var rate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var encoding = AndroidAudioFormat.ENCODING_PCM_16BIT
            val c = MediaCodec.createDecoderByType(mime)
            codec = c
            c.configure(fmt, null, null, 0)
            c.start()
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var scratch = ByteArray(0)
            val pcmOut = ByteArray(1 shl 16)
            BufferedOutputStream(FileOutputStream(out), 1 shl 20).use { os ->
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
                        rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        if (f.containsKey(MediaFormat.KEY_PCM_ENCODING)) encoding = f.getInteger(MediaFormat.KEY_PCM_ENCODING)
                    } else if (o >= 0) {
                        if (rate != 44_100) {
                            throw IOException("$name is $rate Hz. Audio CDs need 44,100 Hz files; resampling isn't supported yet.")
                        }
                        val buf = c.getOutputBuffer(o)!!
                        buf.position(info.offset)
                        buf.limit(info.offset + info.size)
                        if (scratch.size < info.size) scratch = ByteArray(info.size)
                        buf.get(scratch, 0, info.size)
                        writeCdPcm(scratch, info.size, channels, encoding, pcmOut, os)
                        c.releaseOutputBuffer(o, false)
                        if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outputDone = true
                    }
                }
            }
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

    /** Converts decoder output (16-bit or float, any channel count) to 16-bit stereo. */
    private fun writeCdPcm(src: ByteArray, len: Int, channels: Int, encoding: Int, tmp: ByteArray, os: java.io.OutputStream) {
        val bytesPerSample = if (encoding == AndroidAudioFormat.ENCODING_PCM_FLOAT) 4 else 2
        val frameBytes = bytesPerSample * channels
        val frames = len / frameBytes
        var o = 0
        for (f in 0 until frames) {
            val base = f * frameBytes
            val l = sample(src, base, encoding)
            val r = if (channels >= 2) sample(src, base + bytesPerSample, encoding) else l
            tmp[o++] = l.toByte(); tmp[o++] = (l shr 8).toByte()
            tmp[o++] = r.toByte(); tmp[o++] = (r shr 8).toByte()
            if (o == tmp.size) { os.write(tmp, 0, o); o = 0 }
        }
        if (o > 0) os.write(tmp, 0, o)
    }

    private fun sample(b: ByteArray, i: Int, encoding: Int): Int =
        if (encoding == AndroidAudioFormat.ENCODING_PCM_FLOAT) {
            val bits = (b[i].toInt() and 0xFF) or ((b[i + 1].toInt() and 0xFF) shl 8) or
                ((b[i + 2].toInt() and 0xFF) shl 16) or (b[i + 3].toInt() shl 24)
            (java.lang.Float.intBitsToFloat(bits) * 32767f).toInt().coerceIn(-32768, 32767)
        } else {
            (b[i].toInt() and 0xFF) or (b[i + 1].toInt() shl 8)
        }
}
