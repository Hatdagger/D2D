package com.hatdagger.discdroid.android

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioTrack
import android.media.MediaPlayer
import android.net.Uri
import android.os.Handler
import android.os.Looper
import com.hatdagger.discdroid.core.CdPlayback
import com.hatdagger.discdroid.core.Drive
import com.hatdagger.discdroid.core.Song
import com.hatdagger.discdroid.core.Track
import android.media.AudioFormat as AndroidAudioFormat

/**
 * Plays either songs from the burn list (files, via MediaPlayer — music or the sound of a video)
 * or tracks straight off the CD in the drive (raw audio read over USB into an AudioTrack).
 * All public methods are called on the main thread; [onChange] and [onError] run there too.
 */
class Player(
    private val context: Context,
    private val onChange: () -> Unit,
    private val onError: (String) -> Unit,
) {
    enum class Source { FILES, DISC }

    private val main = Handler(Looper.getMainLooper())
    private val attrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        .build()

    var source = Source.FILES
        private set
    /** Index of the current song or disc track, or -1 when stopped. */
    var index = -1
        private set
    var isPaused = false
        private set
    val isActive get() = index >= 0
    val isDiscActive get() = isActive && source == Source.DISC

    // ---- files
    private var songs: List<Song> = emptyList()
    private var mp: MediaPlayer? = null
    private var prepared = false
    val currentSong: Song? get() = if (source == Source.FILES) songs.getOrNull(index) else null

    // ---- disc
    private var cd: CdPlayback? = null
    private var discTracks: List<Track> = emptyList()
    private var audio: AudioTrack? = null
    private var thread: Thread? = null
    @Volatile private var stopFlag = false
    @Volatile private var pauseFlag = false
    @Volatile private var discLba = 0L

    // ------------------------------------------------------------------ files

    fun playSongs(list: List<Song>, i: Int) {
        stop()
        source = Source.FILES
        songs = list
        startSong(i)
    }

    /** The burn list was re-ordered or edited: keep playing the same song. */
    fun updateSongs(list: List<Song>) {
        if (source != Source.FILES) { songs = list; return }
        val cur = currentSong
        songs = list
        if (cur != null) {
            val ni = list.indexOfFirst { it.key == cur.key }
            if (ni < 0) stop() else index = ni
        }
    }

    private fun startSong(i: Int) {
        releaseMediaPlayer()
        if (i !in songs.indices) { stop(); return }
        index = i
        isPaused = false
        val song = songs[i]
        val m = MediaPlayer()
        mp = m
        prepared = false
        m.setAudioAttributes(attrs)
        m.setOnPreparedListener { if (mp === it) { prepared = true; if (!isPaused) it.start(); onChange() } }
        m.setOnCompletionListener { if (mp === it) { startSong(index + 1); onChange() } }
        m.setOnErrorListener { p, what, extra ->
            if (mp === p) {
                onError("Can't play \"${song.fileName}\" (error $what/$extra). Skipping it.")
                main.post { startSong(index + 1); onChange() }
            }
            true
        }
        try {
            m.setDataSource(context, Uri.parse(song.key))
            m.prepareAsync()
        } catch (e: Exception) {
            onError("Can't open \"${song.fileName}\": ${e.message}")
            releaseMediaPlayer()
            index = -1
        }
        onChange()
    }

    private fun releaseMediaPlayer() {
        val m = mp ?: return
        mp = null
        prepared = false
        try { m.reset() } catch (_: Exception) {}
        m.release()
    }

    // ------------------------------------------------------------------ disc

    fun playDisc(drive: Drive, tracks: List<Track>, i: Int) {
        stop()
        if (i !in tracks.indices) return
        source = Source.DISC
        discTracks = tracks
        val p = CdPlayback(drive, tracks)
        cd = p
        index = i
        isPaused = false
        stopFlag = false
        pauseFlag = false
        discLba = tracks[i].startLba
        val minBuf = AudioTrack.getMinBufferSize(44_100, AndroidAudioFormat.CHANNEL_OUT_STEREO, AndroidAudioFormat.ENCODING_PCM_16BIT)
        val at = AudioTrack.Builder()
            .setAudioAttributes(attrs)
            .setAudioFormat(
                AndroidAudioFormat.Builder()
                    .setSampleRate(44_100)
                    .setChannelMask(AndroidAudioFormat.CHANNEL_OUT_STEREO)
                    .setEncoding(AndroidAudioFormat.ENCODING_PCM_16BIT)
                    .build(),
            )
            .setBufferSizeInBytes(maxOf(minBuf, 44_100 * 4 / 2)) // about half a second
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        audio = at
        at.play()
        val start = tracks[i].startLba
        thread = Thread({
            try {
                drive.setReadSpeed(4 * 176) // 4x: smooth and much quieter than full speed
                p.play(
                    start,
                    isStopped = { stopFlag },
                    isPaused = { pauseFlag },
                    out = { b, n -> var o = 0; while (o < n && !stopFlag) { val w = at.write(b, o, n - o); if (w <= 0) break; o += w } },
                    position = { lba ->
                        discLba = lba
                        val t = p.trackAt(lba - 1)
                        if (t != index) main.post { if (cd === p) { index = t; onChange() } }
                    },
                )
                if (!stopFlag) main.post { if (cd === p) { stop(); onChange() } } // reached the end of the disc
            } catch (e: Exception) {
                if (!stopFlag) main.post { if (cd === p) { stop(); onError("Disc playback stopped: ${e.message}"); onChange() } }
            }
        }, "cd-playback").apply { start() }
        onChange()
    }

    // ------------------------------------------------------------------ controls

    fun togglePause() {
        if (!isActive) return
        isPaused = !isPaused
        when (source) {
            Source.FILES -> mp?.let { if (prepared) { if (isPaused) it.pause() else it.start() } }
            Source.DISC -> { pauseFlag = isPaused; audio?.let { if (isPaused) it.pause() else it.play() } }
        }
        onChange()
    }

    fun next() {
        if (!isActive) return
        when (source) {
            Source.FILES -> startSong(index + 1)
            Source.DISC -> discTracks.getOrNull(index + 1)?.let { jumpDisc(index + 1, it.startLba) } ?: stop()
        }
        onChange()
    }

    /** Back to the start of this track, or to the previous one if we're near the start. */
    fun previous() {
        if (!isActive) return
        val target = if (positionMs() > 3000 || index == 0) index else index - 1
        when (source) {
            Source.FILES -> if (target == index) mp?.let { if (prepared) it.seekTo(0) } else startSong(target)
            Source.DISC -> jumpDisc(target, discTracks[target].startLba)
        }
        onChange()
    }

    fun seekTo(ms: Long) {
        if (!isActive) return
        when (source) {
            Source.FILES -> mp?.let { if (prepared) it.seekTo(ms.toInt()) }
            Source.DISC -> jumpDisc(index, discTracks[index].startLba + ms * 75 / 1000)
        }
    }

    private fun jumpDisc(trackIndex: Int, lba: Long) {
        val p = cd ?: return
        index = trackIndex
        discLba = lba
        p.seek(lba)
        audio?.let { at ->
            // drop what is still buffered so the jump is heard right away
            try { at.pause(); at.flush(); if (!isPaused) at.play() } catch (_: Exception) {}
        }
    }

    /** Position within the current track. */
    fun positionMs(): Long = when {
        !isActive -> 0
        source == Source.FILES -> mp?.let { if (prepared) it.currentPosition.toLong() else 0L } ?: 0L
        else -> {
            val t = discTracks.getOrNull(index) ?: return 0
            // the sound we hear lags the read position by about the AudioTrack buffer
            ((discLba - t.startLba) * 1000 / 75 - 500).coerceIn(0, t.lengthSectors * 1000 / 75)
        }
    }

    fun durationMs(): Long = when {
        !isActive -> 0
        source == Source.FILES -> mp?.let { if (prepared) it.duration.toLong().coerceAtLeast(0) else 0L } ?: (currentSong?.durationMs ?: 0)
        else -> discTracks.getOrNull(index)?.lengthSectors?.times(1000)?.div(75) ?: 0
    }

    fun stop() {
        releaseMediaPlayer()
        stopDiscThread()
        index = -1
        isPaused = false
    }

    /** Stops disc playback (needed before anything else uses the drive). Blocks briefly. */
    fun stopDisc() {
        if (source == Source.DISC && isActive) { stop(); onChange() }
    }

    private fun stopDiscThread() {
        stopFlag = true
        cd = null
        val at = audio
        audio = null
        try { at?.pause(); at?.flush() } catch (_: Exception) {}
        thread?.let { t -> try { t.join(3000) } catch (_: InterruptedException) {} }
        thread = null
        try { at?.stop() } catch (_: Exception) {}
        at?.release()
    }

    fun release() = stop()
}
