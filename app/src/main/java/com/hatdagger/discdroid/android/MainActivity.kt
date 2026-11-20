package com.hatdagger.discdroid.android

import android.app.Activity
import android.app.AlertDialog
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import com.hatdagger.discdroid.core.AlbumInfo
import com.hatdagger.discdroid.core.AudioFormat
import com.hatdagger.discdroid.core.AudioRipper
import com.hatdagger.discdroid.core.TrackMode
import com.hatdagger.discdroid.core.DiscImage
import com.hatdagger.discdroid.core.BinRipper
import com.hatdagger.discdroid.core.BurnOptions
import com.hatdagger.discdroid.core.Burner
import com.hatdagger.discdroid.core.CancelledException
import com.hatdagger.discdroid.core.Drive
import com.hatdagger.discdroid.core.FlacEncoder
import com.hatdagger.discdroid.core.IsoRipper
import com.hatdagger.discdroid.core.MusicBrainz
import com.hatdagger.discdroid.core.Names
import com.hatdagger.discdroid.core.PcmSource
import com.hatdagger.discdroid.core.Playlists
import com.hatdagger.discdroid.core.Profiles
import com.hatdagger.discdroid.core.RipListener
import com.hatdagger.discdroid.core.Song
import com.hatdagger.discdroid.core.SongSort
import com.hatdagger.discdroid.core.Toc
import com.hatdagger.discdroid.core.Track
import com.hatdagger.discdroid.core.WavSource
import java.io.BufferedOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : Activity(), RipListener {

    private val ui = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val cancel = AtomicBoolean(false)
    private val usb by lazy { getSystemService(Context.USB_SERVICE) as UsbManager }
    private val prefs by lazy { getSharedPreferences("discdroid", MODE_PRIVATE) }

    @Volatile private var drive: Drive? = null
    @Volatile private var driveDeviceName: String? = null
    @Volatile private var busy = false

    // Disc state from the last "Read disc"
    @Volatile private var profile = 0
    @Volatile private var toc: Toc? = null
    @Volatile private var album: AlbumInfo? = null

    private val candidates = ArrayDeque<UsbDevice>()

    private val ui2 by lazy { Ui(this) }
    private lateinit var player: Player
    private val meta = Executors.newSingleThreadExecutor()

    /** Songs to burn, in burn order. Also what the Player plays in "Burn list" mode. */
    private val burnList = mutableListOf<Song>()

    private lateinit var status: TextView
    private lateinit var statusDot: View
    private lateinit var folderText: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var progressText: TextView
    private lateinit var btnCancel: Button
    private lateinit var ripLog: LogPane
    private lateinit var burnLog: LogPane
    @Volatile private var activeLog: LogPane? = null
    private val buttons = mutableListOf<Button>()

    private lateinit var optFlac: Switch
    private lateinit var optOnline: Switch
    private lateinit var optSimulate: Switch
    private lateinit var optVerify: Switch
    private lateinit var optGapless: Switch

    // bottom screen
    private lateinit var bottomCard: LinearLayout
    private lateinit var tabs: Ui.Segments
    private lateinit var playerPage: View
    private lateinit var burnPage: View
    private lateinit var burnSummary: TextView

    // player
    private var showDisc = false
    private lateinit var listSource: Ui.Segments
    private lateinit var listTools: View
    private lateinit var trackList: ListView
    private lateinit var emptyText: TextView
    private lateinit var listSummary: TextView
    private lateinit var nowTitle: TextView
    private lateinit var nowSub: TextView
    private lateinit var seek: SeekBar
    private lateinit var timeText: TextView
    private lateinit var btnPlay: Button
    private var userSeeking = false
    private val adapter = TrackAdapter()
    private val ticker = object : Runnable {
        override fun run() { if (player.isActive) updateNowPlaying(); ui.postDelayed(this, 500) }
    }

    private var lastProgressPost = 0L
    private var progressStart = 0L

    // ---------------------------------------------------------------- lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        player = Player(this, onChange = { refreshPlayer() }, onError = { msg -> burnLog.add(msg); toastLine(msg) })
        buildUi()
        loadBurnList()
        val filter = IntentFilter().apply {
            addAction(ACTION_USB_PERMISSION)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(usbReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(usbReceiver, filter)
        }
        ripLog.add("Plug in your USB CD/DVD drive and tap Connect.")
        burnLog.add("Burn ISO images, or make an audio CD from your burn list (music or video files) in the Player tab.")
        showFolder()
        refreshPlayer()
        ui.post(ticker)
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.action == UsbManager.ACTION_USB_DEVICE_ATTACHED && drive == null) connectDrive()
    }

    override fun onDestroy() {
        cancel.set(true)
        ui.removeCallbacks(ticker)
        player.release()
        unregisterReceiver(usbReceiver)
        worker.shutdown()
        meta.shutdown()
        try { drive?.close() } catch (_: Exception) {}
        super.onDestroy()
    }

    // ---------------------------------------------------------------- UI

    private fun dp(v: Int) = ui2.dp(v)

    /** Puts both logs on the clipboard so they can be pasted into a message. */
    private fun copyLogs() {
        val text = "D2D Disc2Android log\n\n=== RIP ===\n${ripLog.text.text}\n=== BURN ===\n${burnLog.text.text}"
        val cm = getSystemService(android.content.ClipboardManager::class.java)
        cm.setPrimaryClip(android.content.ClipData.newPlainText("D2D log", text))
        toastLine("Log copied. Paste it anywhere.")
    }

    /** A scrolling log area belonging to one half of the screen. */
    private inner class LogPane {
        val text = TextView(this@MainActivity).apply {
            typeface = Typeface.MONOSPACE; textSize = 12f; setTextIsSelectable(true)
            setTextColor(Palette.LOG); setLineSpacing(0f, 1.15f)
        }
        val scroll = ScrollView(this@MainActivity).apply {
            addView(text)
            background = ui2.rounded(Palette.alpha(0xFF000000.toInt(), 70), 14)
            setPadding(dp(10), dp(8), dp(10), dp(8))
            isVerticalScrollBarEnabled = false
        }
        fun add(msg: String) {
            ui.post {
                text.append(msg + "\n")
                scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
            }
        }
    }

    private fun btn(label: String, accent: Int, style: Ui.Style = Ui.Style.TONAL, track: Boolean = true, action: () -> Unit): Button =
        ui2.button(label, accent, style, action).also { if (track) buttons += it }

    private fun option(label: String, key: String, default: Boolean, accent: Int): Switch =
        ui2.toggle(label, accent, prefs.getBoolean(key, default)) { prefs.edit().putBoolean(key, it).apply() }

    private fun flow(vararg views: View) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        for (v in views) addView(v, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
    }

    private fun gap(h: Int) = View(this).apply { layoutParams = LinearLayout.LayoutParams(1, dp(h)) }

    private fun buildUi() {
        @Suppress("DEPRECATION")
        if (Build.VERSION.SDK_INT < 35) { window.statusBarColor = Palette.BG_TOP; window.navigationBarColor = Palette.BG_BOTTOM }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Palette.BG_TOP, Palette.BG_BOTTOM))
        }
        val side = dp(14)
        // Keep clear of the status bar and navigation bar (Android 15 draws edge-to-edge).
        root.setOnApplyWindowInsetsListener { v, insets ->
            val top: Int
            val bottom: Int
            if (Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars())
                top = bars.top; bottom = bars.bottom
            } else {
                @Suppress("DEPRECATION")
                top = insets.systemWindowInsetTop
                @Suppress("DEPRECATION")
                bottom = insets.systemWindowInsetBottom
            }
            v.setPadding(side, top + dp(10), side, bottom + dp(10))
            insets
        }
        root.setPadding(side, dp(36), side, side)

        // ---- Header: drive status and output folder, with the drive buttons
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = ui2.rounded(Palette.SURFACE, 22)
            setPadding(dp(14), dp(8), dp(8), dp(8))
        }
        statusDot = View(this).apply { background = ui2.rounded(0xFF5B6678.toInt(), 6) }
        header.addView(statusDot, LinearLayout.LayoutParams(dp(10), dp(10)).apply { marginEnd = dp(10) })
        val info = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        status = ui2.text("No drive connected", 15f, Palette.TEXT, bold = true).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
        folderText = ui2.text("", 12f, Palette.TEXT_DIM).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.MIDDLE }
        info.addView(status); info.addView(folderText)
        header.addView(info, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(btn("Connect", Palette.BLUE, Ui.Style.FILLED, action = ::connectDrive))
        header.addView(btn("Folder", Palette.BLUE, action = ::pickFolder), LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(6) })
        header.addView(btn("Eject", Palette.BLUE, action = ::eject), LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(6) })
        root.addView(header)

        // ---- Top screen: ripping (blue)
        ripLog = LogPane()
        optFlac = option("Save as FLAC", KEY_FLAC, true, Palette.BLUE)
        optOnline = option("Look up names online", KEY_ONLINE, true, Palette.BLUE)
        val top = ui2.card(Palette.BLUE).apply {
            addView(ui2.sectionTitle("RIP", Palette.BLUE, "Disc → files"))
            addView(gap(10))
            addView(ui2.row(
                btn("Read disc", Palette.BLUE, action = ::readDisc),
                btn("Rip audio CD", Palette.BLUE, Ui.Style.FILLED, action = ::ripAudio),
                btn("Copy disc", Palette.BLUE, action = ::ripIso),
            ))
            addView(flow(optFlac, optOnline))
            addView(ripLog.scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dp(4) })
        }
        root.addView(top, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dp(10) })

        // ---- Hinge: progress + cancel, shared by both screens
        val hinge = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), dp(8), 0, dp(8))
        }
        val progressCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 1000; ui2.tint(this, Palette.AMBER) }
        progressText = ui2.text("Idle", 12f, Palette.TEXT_DIM)
        progressCol.addView(progressBar)
        progressCol.addView(progressText)
        hinge.addView(progressCol, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        btnCancel = ui2.button("Cancel", Palette.AMBER, Ui.Style.OUTLINE) { cancel.set(true); log("Cancelling…") }.apply { isEnabled = false; alpha = 0.4f }
        hinge.addView(ui2.button("Copy log", Palette.AMBER, Ui.Style.OUTLINE) { copyLogs() },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(12) })
        hinge.addView(btnCancel, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(12) })
        root.addView(hinge)

        // ---- Bottom screen: Player (green) and Burn (red) tabs
        bottomCard = ui2.card(Palette.GREEN)
        tabs = ui2.Segments(listOf("♪  Player", "●  Burn"), listOf(Palette.GREEN, Palette.RED)) { showTab(it) }
        bottomCard.addView(tabs.view)
        val pages = FrameLayout(this)
        playerPage = buildPlayerPage()
        burnPage = buildBurnPage()
        pages.addView(playerPage, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        pages.addView(burnPage, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        bottomCard.addView(pages, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dp(10) })
        root.addView(bottomCard, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1.3f))
        showTab(prefs.getInt(KEY_TAB, 0))

        setContentView(root)
    }

    private fun showTab(i: Int) {
        tabs.select(i)
        prefs.edit().putInt(KEY_TAB, i).apply()
        playerPage.visibility = if (i == 0) View.VISIBLE else View.GONE
        burnPage.visibility = if (i == 1) View.VISIBLE else View.GONE
        bottomCard.background = ui2.cardBackground(if (i == 0) Palette.GREEN else Palette.RED)
    }

    private fun buildPlayerPage(): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL

        // What to show: the burn list, or the CD in the drive
        listSource = ui2.Segments(listOf("Burn list", "Disc"), listOf(Palette.GREEN, Palette.GREEN)) { i ->
            showDisc = i == 1
            if (showDisc && toc == null && drive != null && !busy) readDisc()
            refreshPlayer()
        }
        val sourceRow = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        sourceRow.addView(listSource.view, LinearLayout.LayoutParams(dp(220), LinearLayout.LayoutParams.WRAP_CONTENT))
        listSummary = ui2.text("", 12f, Palette.TEXT_DIM).apply { gravity = Gravity.END }
        sourceRow.addView(listSummary, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(10) })
        addView(sourceRow)

        listTools = ui2.row(
            btn("+ Add files", Palette.GREEN, Ui.Style.FILLED, track = false, action = ::pickSongs),
            btn("Sort", Palette.GREEN, track = false, action = ::chooseSort),
            btn("Clear", Palette.GREEN, Ui.Style.GHOST, track = false, action = ::confirmClear),
        ).apply { setPadding(0, dp(8), 0, 0) }
        addView(listTools)

        val listBox = FrameLayout(this@MainActivity).apply { background = ui2.rounded(Palette.alpha(0xFF000000.toInt(), 70), 14) }
        trackList = ListView(this@MainActivity).apply {
            adapter = this@MainActivity.adapter
            divider = null; dividerHeight = 0
            selector = android.graphics.drawable.ColorDrawable(0)
            setPadding(dp(4), dp(4), dp(4), dp(4)); clipToPadding = false
        }
        emptyText = ui2.text("", 13f, Palette.TEXT_DIM).apply { gravity = Gravity.CENTER; setPadding(dp(24), dp(12), dp(24), dp(12)) }
        listBox.addView(trackList, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        listBox.addView(emptyText, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        trackList.emptyView = emptyText
        addView(listBox, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dp(8) })

        // Now playing + transport controls
        val now = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL
            background = ui2.rounded(Palette.SURFACE_HI, 16)
            setPadding(dp(14), dp(10), dp(14), dp(8))
        }
        nowTitle = ui2.text("Nothing playing", 15f, Palette.TEXT, bold = true).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
        nowSub = ui2.text("Tap a song to play it", 12f, Palette.TEXT_DIM).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
        now.addView(nowTitle); now.addView(nowSub)
        val seekRow = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        seek = SeekBar(this@MainActivity).apply {
            max = 1000; ui2.tint(this, Palette.GREEN)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {
                    if (fromUser) timeText.text = timeLabel(player.durationMs() * p / 1000, player.durationMs())
                }
                override fun onStartTrackingTouch(s: SeekBar) { userSeeking = true }
                override fun onStopTrackingTouch(s: SeekBar) {
                    userSeeking = false
                    player.seekTo(player.durationMs() * s.progress / 1000)
                }
            })
        }
        timeText = ui2.text("0:00 / 0:00", 12f, Palette.TEXT_DIM)
        seekRow.addView(seek, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        seekRow.addView(timeText)
        now.addView(seekRow)
        btnPlay = ui2.button("▶  Play", Palette.GREEN, Ui.Style.FILLED) { playPause() }
        now.addView(ui2.row(
            ui2.button("⏮", Palette.GREEN) { player.previous() },
            btnPlay,
            ui2.button("⏭", Palette.GREEN) { player.next() },
            ui2.button("■  Stop", Palette.GREEN, Ui.Style.GHOST) { player.stop(); refreshPlayer() },
        ))
        addView(now, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
    }

    private fun buildBurnPage(): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        burnLog = LogPane()
        optSimulate = option("Test burn (laser off)", KEY_SIMULATE, false, Palette.RED)
        optVerify = option("Verify after burning", KEY_VERIFY, true, Palette.RED)
        optGapless = option("No gaps between tracks", KEY_GAPLESS, false, Palette.RED)
        burnSummary = ui2.text("", 13f, Palette.TEXT_DIM).apply { setPadding(dp(2), 0, 0, dp(8)) }
        addView(burnSummary)
        addView(ui2.row(
            btn("Burn audio CD", Palette.RED, Ui.Style.FILLED, action = ::burnAudioFromList),
            btn("Burn ISO image", Palette.RED, action = ::pickIso),
            btn("Erase disc", Palette.RED, action = ::confirmErase),
        ))
        addView(flow(optSimulate, optVerify))
        addView(optGapless)
        addView(burnLog.scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dp(4) })
    }

    // ---------------------------------------------------------------- player

    /** One row of the track list. */
    private inner class TrackAdapter : BaseAdapter() {
        override fun getCount() = if (showDisc) discTracks().size else burnList.size
        override fun getItem(position: Int): Any = position
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val playingHere = player.isActive && player.index == position &&
                (player.source == Player.Source.DISC) == showDisc
            val row = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(10), dp(6), dp(4), dp(6))
                background = ui2.ripple(ui2.rounded(if (playingHere) Palette.alpha(Palette.GREEN, 40) else 0, 12), Palette.GREEN)
                setOnClickListener { playItem(position) }
            }
            val title: String; val sub: String; val length: String
            if (showDisc) {
                val t = discTracks()[position]
                val a = album
                title = a?.tracks?.get(t.number)?.title ?: "Track ${t.number}"
                sub = a?.tracks?.get(t.number)?.artist ?: a?.artist ?: "Audio CD"
                length = t.durationText()
            } else {
                val s = burnList[position]
                title = s.displayTitle
                sub = listOfNotNull(if (s.isVideo) "Video sound" else null, s.artist, s.fileName).joinToString(" · ")
                length = if (s.durationMs > 0) Playlists.clock(s.durationMs / 1000) else ""
            }
            row.addView(ui2.text(if (playingHere) (if (player.isPaused) "❚❚" else "▶") else "${position + 1}", 13f,
                if (playingHere) Palette.GREEN else Palette.TEXT_DIM, bold = true).apply { gravity = Gravity.CENTER },
                LinearLayout.LayoutParams(dp(30), LinearLayout.LayoutParams.WRAP_CONTENT))
            val col = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(8), 0, dp(8), 0) }
            col.addView(ui2.text(title, 14f, Palette.TEXT, bold = playingHere).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
            col.addView(ui2.text(sub, 11f, Palette.TEXT_DIM).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
            row.addView(col, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(ui2.text(length, 12f, Palette.TEXT_DIM))
            if (!showDisc) {
                row.addView(iconButton("▲") { moveSong(position, position - 1) })
                row.addView(iconButton("▼") { moveSong(position, position + 1) })
                row.addView(iconButton("✕") { removeSong(position) })
            }
            return row
        }
    }

    private fun iconButton(glyph: String, action: () -> Unit) = ui2.text(glyph, 14f, Palette.TEXT_DIM).apply {
        gravity = Gravity.CENTER
        background = ui2.ripple(ui2.rounded(0, 14), Palette.GREEN)
        setOnClickListener { action() }
        layoutParams = LinearLayout.LayoutParams(dp(38), dp(38))
    }

    private fun discTracks(): List<Track> = toc?.audioTracks ?: emptyList()

    private fun playItem(position: Int) {
        if (showDisc) {
            val d = drive
            if (d == null) { toastLine("Connect the drive first."); return }
            if (busy) { toastLine("Wait for the current job to finish."); return }
            player.playDisc(d, discTracks(), position)
        } else {
            player.playSongs(burnList.toList(), position)
        }
        refreshPlayer()
    }

    private fun playPause() {
        when {
            player.isActive -> player.togglePause()
            showDisc && discTracks().isNotEmpty() -> playItem(0)
            !showDisc && burnList.isNotEmpty() -> playItem(0)
            else -> toastLine(if (showDisc) "No audio CD read yet — insert one and tap Read disc." else "Add some songs first.")
        }
    }

    private fun refreshPlayer() {
        if (!::trackList.isInitialized) return
        listSource.select(if (showDisc) 1 else 0)
        listTools.visibility = if (showDisc) View.GONE else View.VISIBLE
        emptyText.text = if (showDisc) {
            if (drive == null) "Connect the drive, insert an audio CD, then tap Read disc."
            else if (toc == null) "No disc read yet. Insert an audio CD and tap Read disc."
            else "This disc has no audio tracks."
        } else "Your burn list is empty.\nTap + Add files to pick music or videos (MP3, FLAC, M4A, WAV, MP4, WebM, MKV…)."
        listSummary.text = if (showDisc) {
            val t = discTracks()
            if (t.isEmpty()) "" else "${t.size} tracks · ${Playlists.clock(t.sumOf { it.lengthSectors } / 75)}"
        } else if (burnList.isEmpty()) "" else Playlists.summary(burnList)
        adapter.notifyDataSetChanged()
        updateBurnSummary()
        updateNowPlaying()
    }

    private fun updateNowPlaying() {
        if (!::nowTitle.isInitialized) return
        if (!player.isActive) {
            nowTitle.text = "Nothing playing"
            nowSub.text = if (showDisc) "Tap a track to play it from the disc" else "Tap a song to play it"
            seek.progress = 0
            timeText.text = "0:00 / 0:00"
            btnPlay.text = "▶  Play"
            return
        }
        if (player.source == Player.Source.FILES) {
            val s = player.currentSong
            nowTitle.text = s?.displayTitle ?: ""
            nowSub.text = listOfNotNull(s?.artist, "Burn list ${player.index + 1}/${burnList.size}").joinToString(" · ")
        } else {
            val t = discTracks().getOrNull(player.index)
            val a = album
            nowTitle.text = t?.let { a?.tracks?.get(it.number)?.title ?: "Track ${it.number}" } ?: ""
            nowSub.text = listOfNotNull(a?.artist, a?.title, "Disc track ${player.index + 1}/${discTracks().size}").joinToString(" · ")
        }
        val pos = player.positionMs(); val dur = player.durationMs()
        if (!userSeeking) {
            seek.progress = if (dur > 0) (pos * 1000 / dur).toInt() else 0
            timeText.text = timeLabel(pos, dur)
        }
        btnPlay.text = if (player.isPaused) "▶  Play" else "❚❚  Pause"
    }

    private fun timeLabel(pos: Long, dur: Long) = "${Playlists.clock(pos / 1000)} / ${Playlists.clock(dur / 1000)}"

    private fun toastLine(msg: String) {
        ui.post { android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show() }
    }

    // ---------------------------------------------------------------- burn list

    private fun burnListChanged() {
        player.updateSongs(burnList.toList())
        saveBurnList()
        refreshPlayer()
    }

    private fun moveSong(from: Int, to: Int) {
        Playlists.move(burnList, from, to)
        burnListChanged()
    }

    private fun removeSong(i: Int) {
        if (i !in burnList.indices) return
        burnList.removeAt(i)
        burnListChanged()
    }

    private fun confirmClear() {
        if (burnList.isEmpty()) return
        confirm("Clear the burn list?", "This removes all ${burnList.size} songs from the list. The files themselves are not deleted.", "Clear") {
            burnList.clear()
            burnListChanged()
        }
    }

    private fun chooseSort() {
        if (burnList.size < 2) return
        val options = SongSort.values()
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Sort burn list by")
            .setItems(options.map { it.label }.toTypedArray()) { _, which ->
                val sorted = options[which].apply(burnList)
                burnList.clear(); burnList.addAll(sorted)
                burnListChanged()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    @Suppress("DEPRECATION")
    private fun pickSongs() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
            .putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("audio/*", "video/*"))
            .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        startActivityForResult(i, REQ_SONGS)
    }

    private fun addSongs(uris: List<Uri>) {
        val added = mutableListOf<Song>()
        for (u in uris) {
            try { contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {}
            if (burnList.any { it.key == u.toString() }) continue
            added += Song(u.toString(), Files.nameAndSize(this, u).first)
        }
        // new picks go to the end, in file-name order
        burnList.addAll(SongSort.FILE_NAME.apply(added))
        burnListChanged()
        if (added.isNotEmpty()) toastLine("Added ${added.size} file${if (added.size == 1) "" else "s"}.")
        loadTags(added)
    }

    /** Reads titles and lengths in the background, then redraws. */
    private fun loadTags(songs: List<Song>) {
        if (songs.isEmpty()) return
        meta.execute {
            for (s in songs) Files.readTags(this, s)
            ui.post { refreshPlayer() }
        }
    }

    private fun saveBurnList() {
        prefs.edit().putString(KEY_BURN_LIST, burnList.joinToString("\n") { it.key + "\t" + it.fileName }).apply()
    }

    private fun loadBurnList() {
        val s = prefs.getString(KEY_BURN_LIST, null) ?: return
        burnList.clear()
        for (line in s.split('\n')) {
            if (line.isBlank()) continue
            val key = line.substringBefore('\t')
            burnList += Song(key, line.substringAfter('\t', Uri.parse(key).lastPathSegment ?: "file"))
        }
        loadTags(burnList.toList())
    }

    private fun updateBurnSummary() {
        if (!::burnSummary.isInitialized) return
        burnSummary.text = if (burnList.isEmpty()) "Burn list: empty — add songs or videos in the Player tab."
        else {
            val secs = burnList.sumOf { it.durationMs } / 1000
            val over = secs > Playlists.CD_SECONDS
            "Burn list: ${Playlists.summary(burnList)} of 80:00" + if (over) "  — too long for one CD!" else ""
        }
    }

    private fun setBusy(b: Boolean) {
        busy = b
        ui.post {
            buttons.forEach { it.isEnabled = !b; it.alpha = if (b) 0.4f else 1f }
            btnCancel.isEnabled = b
            btnCancel.alpha = if (b) 1f else 0.4f
            if (b) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    /** Messages go to whichever half started the current job (top by default). */
    override fun log(message: String) {
        (activeLog ?: ripLog).add(message)
    }

    override fun progress(label: String, done: Long, total: Long) {
        val now = System.currentTimeMillis()
        if (done < total && now - lastProgressPost < 250) return
        lastProgressPost = now
        val elapsed = (now - progressStart).coerceAtLeast(1)
        val sectorsPerSec = done * 1000.0 / elapsed
        // 1x = 75 sectors/s on CD, ~676 sectors/s (1,385 KB/s) on DVD
        val speed = if (Profiles.isDvd(profile)) sectorsPerSec / 676.0 else sectorsPerSec / 75.0
        val pct = if (total > 0) done * 1000 / total else 0
        ui.post {
            progressBar.progress = pct.toInt()
            progressText.text = "%s — %.1f%% — %.1fx".format(label, pct / 10.0, speed)
        }
    }

    private fun resetProgress() {
        progressStart = System.currentTimeMillis()
        lastProgressPost = 0
    }

    private fun confirm(title: String, message: String, ok: String, action: () -> Unit) {
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(ok) { _, _ -> action() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---------------------------------------------------------------- files

    private fun showFolder() {
        val uri = prefs.getString(KEY_FOLDER, null)
        folderText.text = if (uri == null) "Rips go to: no folder chosen yet (tap Folder)" else "Rips go to: ${Uri.parse(uri).lastPathSegment?.substringAfter(':')}"
    }

    @Suppress("DEPRECATION")
    private fun pickFolder() {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), REQ_FOLDER)
    }

    @Suppress("DEPRECATION")
    private fun pickIso() {
        if (drive == null) { burnLog.add("Connect a drive first."); return }
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
        startActivityForResult(i, REQ_ISO)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data == null) return
        when (requestCode) {
            REQ_FOLDER -> {
                val uri = data.data ?: return
                contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
                prefs.edit().putString(KEY_FOLDER, uri.toString()).apply()
                showFolder()
                ripLog.add("Output folder set.")
            }
            REQ_ISO -> data.data?.let { confirmBurnIso(it) }
            REQ_SONGS -> {
                val uris = mutableListOf<Uri>()
                val clip = data.clipData
                if (clip != null) for (i in 0 until clip.itemCount) uris += clip.getItemAt(i).uri
                else data.data?.let { uris += it }
                if (uris.isNotEmpty()) addSongs(uris)
            }
        }
    }

    private fun outputFolder(): OutputFolder? {
        val s = prefs.getString(KEY_FOLDER, null)
        if (s == null) {
            ripLog.add("Choose where rips go first (tap Folder).")
            return null
        }
        return OutputFolder(contentResolver, Uri.parse(s))
    }

    // ---------------------------------------------------------------- USB connection

    private fun connectDrive() {
        if (busy) return
        if (drive != null) { ripLog.add("Already connected."); return }
        val all = usb.deviceList.values.filter { UsbBulkTransport.isMassStorage(it) }
        if (all.isEmpty()) {
            ripLog.add("No USB storage devices found. Check the cable, and that the drive has enough power.")
            return
        }
        // Try the drive used last time first, then ATAPI-style interfaces (subclass 2/5),
        // which card readers never use, then everything else.
        val known = prefs.getString(KEY_LAST_DRIVE, null)
        candidates.clear()
        candidates.addAll(all.sortedBy { d ->
            when {
                "%04X:%04X".format(d.vendorId, d.productId) == known -> 0
                (0 until d.interfaceCount).any { d.getInterface(it).interfaceSubclass in intArrayOf(2, 5) } -> 1
                else -> 2
            }
        })
        ripLog.add("Found ${all.size} USB storage device(s); looking for the disc drive…")
        nextCandidate()
    }

    private fun nextCandidate() {
        val d = candidates.removeFirstOrNull()
        if (d == null) {
            ripLog.add("No CD/DVD drive found. (A card reader in the same hub is skipped on purpose.)")
            return
        }
        if (usb.hasPermission(d)) probe(d)
        else {
            val flags = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
            val pi = PendingIntent.getBroadcast(this, 0, Intent(ACTION_USB_PERMISSION).setPackage(packageName), flags)
            usb.requestPermission(d, pi)
        }
    }

    private fun probe(device: UsbDevice) {
        setBusy(true)
        activeLog = ripLog
        worker.execute {
            try {
                val t = UsbBulkTransport.open(usb, device)
                val d = Drive(t)
                val inq = try { d.inquiry() } catch (e: Exception) { d.close(); throw e }
                if (!inq.isOpticalDrive) {
                    d.close()
                    log("Skipped ${device.productName ?: device.deviceName}: not a disc drive.")
                    setBusy(false)
                    ui.post { nextCandidate() }
                    return@execute
                }
                drive = d
                driveDeviceName = device.deviceName
                ui.post { candidates.clear() }
                prefs.edit().putString(KEY_LAST_DRIVE, "%04X:%04X".format(device.vendorId, device.productId)).apply()
                ui.post { status.text = inq.name; statusDot.background = ui2.rounded(Palette.GREEN, 6) }
                log("Connected: ${inq.name}, firmware ${inq.revision}")
                setBusy(false)
                ui.post { readDisc() }
            } catch (e: Exception) {
                log("Could not use ${device.productName ?: device.deviceName}: ${e.message}")
                setBusy(false)
                ui.post { nextCandidate() }
            }
        }
    }

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            @Suppress("DEPRECATION")
            val device: UsbDevice? = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
            when (intent.action) {
                ACTION_USB_PERMISSION -> {
                    if (device == null) return
                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) probe(device)
                    else { ripLog.add("USB permission denied for ${device.productName ?: device.deviceName}."); nextCandidate() }
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    if (device != null && device.deviceName == driveDeviceName) {
                        cancel.set(true)
                        player.stopDisc()
                        try { drive?.close() } catch (_: Exception) {}
                        drive = null; driveDeviceName = null; toc = null; album = null
                        status.text = "No drive connected"
                        statusDot.background = ui2.rounded(0xFF5B6678.toInt(), 6)
                        refreshPlayer()
                        log("Drive disconnected. If you didn't unplug it, it may be short on power.")
                    }
                }
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> if (drive == null && !busy) connectDrive()
            }
        }
    }

    // ---------------------------------------------------------------- jobs

    /** Runs [block] on the worker thread, logging into [pane]. */
    private fun job(name: String, pane: LogPane, block: (Drive) -> Unit) {
        val d = drive
        if (d == null) { pane.add("Connect a drive first."); return }
        if (busy) return
        player.stopDisc() // the drive can only do one thing at a time
        cancel.set(false)
        activeLog = pane
        setBusy(true)
        ui.post { progressBar.progress = 0; progressText.text = name }
        worker.execute {
            try {
                block(d)
            } catch (e: CancelledException) {
                log("$name cancelled.")
            } catch (e: Exception) {
                log("$name failed: ${e.message}")
            } finally {
                setBusy(false)
                ui.post { progressText.text = "Idle"; refreshPlayer() }
            }
        }
    }

    private fun readDisc() = job("Read disc", ripLog) { d ->
        log("Waiting for disc…")
        album = null
        if (!d.waitReady(isCancelled = cancel::get)) {
            profile = 0; toc = null
            log("No disc in the drive.")
            return@job
        }
        d.setMaxSpeed()
        profile = try { d.currentProfile() } catch (e: Exception) { 0 }
        log("Disc: ${Profiles.name(profile)}")
        val t = try { d.readToc() } catch (e: Exception) { log("No track list (blank disc?): ${e.message}"); null }
        toc = t
        if (t == null) return@job
        // game discs (data first, music after) aren't music albums: skip the name lookup
        if (t.tracks.first().isAudio) album = lookUpNames(d, t)
        val a = album
        if (a != null) log("${a.source}: ${a.artist ?: "?"} — ${a.title ?: "?"}${a.date?.let { " ($it)" } ?: ""}")
        for (tr in t.tracks) {
            val kind = if (tr.isData) "data " else "audio"
            val title = a?.tracks?.get(tr.number)?.title?.let { "  $it" } ?: ""
            log("  %2d  %s  %s%s".format(tr.number, kind, if (tr.isData) "%,d sectors".format(tr.lengthSectors) else tr.durationText(), title))
        }
        if (t.audioTracks.isNotEmpty()) log("Ready: tap \"Rip audio CD\", or play it in the Player tab (Disc).")
        if (t.dataTracks.isNotEmpty()) log("Ready: tap \"Copy disc\" to save it as an image file (ISO, or BIN/CUE for game discs).")
    }

    /** MusicBrainz first (if enabled), then CD-TEXT. */
    private fun lookUpNames(d: Drive, t: Toc): AlbumInfo? {
        if (optOnline.isChecked) {
            val url = MusicBrainz.lookupUrl(t)
            val id = MusicBrainz.discId(t)
            if (url != null && id != null) {
                try {
                    log("Looking up disc online (MusicBrainz)…")
                    val body = Http.get(url)
                    val audioCount = t.tracks.takeWhile { it.isAudio }.size
                    val info = body?.let { MusicBrainz.parse(String(it, Charsets.UTF_8), id, audioCount) }
                    if (info != null) return info
                    log("Not found on MusicBrainz.")
                } catch (e: Exception) {
                    log("Online lookup failed (${e.message}); using disc info only.")
                }
            }
        }
        return d.readCdText()?.let { AlbumInfo.fromCdText(it) }
    }

    private fun eject() = job("Eject", ripLog) { d ->
        d.eject()
        toc = null; album = null
        log("Ejected.")
    }

    private fun ripAudio() {
        val out = outputFolder() ?: return
        val format = if (optFlac.isChecked) AudioFormat.FLAC else AudioFormat.WAV
        job("Audio rip", ripLog) { d ->
            if (!d.waitReady(isCancelled = cancel::get)) { log("No disc in the drive."); return@job }
            val t = toc ?: d.readToc().also { toc = it }
            val tracks = t.audioTracks
            if (tracks.isEmpty()) { log("This disc has no audio tracks."); return@job }
            val a = album ?: lookUpNames(d, t).also { album = it }
            val stamp = SimpleDateFormat("yyyy-MM-dd HHmm", Locale.US).format(Date())
            val albumName = if (a?.title != null)
                Names.safe(listOfNotNull(a.artist, a.title).joinToString(" - "), "Audio CD $stamp")
            else "Audio CD $stamp"
            val folder = out.createFolder(out.root(), albumName)
            log("Ripping ${tracks.size} tracks as ${format.name} to \"$albumName\"…")
            d.setMaxSpeed()

            // Cover art (MusicBrainz releases only)
            a?.releaseId?.let { rid ->
                try {
                    Http.get(MusicBrainz.coverUrl(rid), "image/*")?.let { img ->
                        out.open(out.createFile(folder, "image/jpeg", "cover.jpg")).use { it.write(img) }
                        log("Saved cover art.")
                    }
                } catch (e: Exception) { log("No cover art (${e.message}).") }
            }

            val report = StringBuilder("D2D Disc2Android rip log\nDrive: ${d.description}\nDisc: $albumName\n")
            a?.let { report.append("Names from: ${it.source}\n") }
            MusicBrainz.discId(t)?.let { report.append("MusicBrainz disc ID: $it\n") }
            report.append("\n")
            val ripper = AudioRipper(d, this@MainActivity, cancel::get)
            var totalBad = 0L
            for ((i, tr) in tracks.withIndex()) {
                val info = a?.tracks?.get(tr.number)
                val title = info?.title
                val base = Names.safe("%02d%s".format(tr.number, if (title != null) " - $title" else ""), "%02d".format(tr.number))
                val fileName = "$base.${format.extension}"
                val tags = FlacEncoder.tagsFor(title, info?.artist, a?.title, a?.artist, tr.number, tracks.size) +
                    listOfNotNull(a?.date?.let { "DATE" to it })
                val doc = out.createFile(folder, format.mime, fileName)
                resetProgress()
                try {
                    val stats = BufferedOutputStream(out.open(doc), 1 shl 20).use { s ->
                        ripper.ripTrack(tr, "Track ${tr.number} (${i + 1}/${tracks.size})", s, format, tags)
                    }
                    totalBad += stats.badSectors
                    log("Track %02d done: %s".format(tr.number, stats.summary()))
                    report.append("Track %02d  %s  %s\n".format(tr.number, fileName, stats.summary()))
                } catch (e: Exception) {
                    out.delete(doc)
                    throw e
                }
            }
            report.append(if (totalBad == 0L) "\nAll tracks read without errors.\n" else "\n$totalBad sectors could not be read.\n")
            out.open(out.createFile(folder, "text/plain", "rip-log.txt")).use { it.write(report.toString().toByteArray()) }
            log(if (totalBad == 0L) "Audio rip finished — all tracks clean." else "Audio rip finished with $totalBad unreadable sectors (see rip-log.txt).")
        }
    }

    private fun ripIso() {
        val out = outputFolder() ?: return
        job("Disc copy", ripLog) { d ->
            if (!d.waitReady(isCancelled = cancel::get)) { log("No disc in the drive."); return@job }
            if (profile == 0) profile = try { d.currentProfile() } catch (e: Exception) { 0 }
            val t = toc ?: try { d.readToc().also { toc = it } } catch (e: Exception) { null }
            val ripper = IsoRipper(d, this@MainActivity, cancel::get)

            // CDs: copy the first data track. DVDs: copy the whole disc.
            val start: Long
            val sectors: Long
            val dataTrack = t?.dataTracks?.firstOrNull()
            if (!Profiles.isDvd(profile) && t != null && dataTrack != null) {
                val bin = BinRipper(d, this@MainActivity, cancel::get)
                val modes = bin.detectModes(t)
                if (DiscImage.needsBinCue(t, modes)) {
                    copyBinCue(d, t, modes, bin, ripper, out)
                    return@job
                }
            }
            if (!Profiles.isDvd(profile) && dataTrack != null) {
                start = dataTrack.startLba
                sectors = dataTrack.lengthSectors
            } else if (!Profiles.isDvd(profile) && t != null && t.dataTracks.isEmpty()) {
                log("This is an audio CD with no data track. Use \"Rip audio CD\" instead.")
                return@job
            } else {
                start = 0
                sectors = d.readCapacity().first + 1
            }
            val vol = ripper.readVolume(start)
            if (vol != null) log("Volume \"${vol.label}\", ${"%,d".format(vol.volumeSectors)} sectors in file system")
            val stamp = SimpleDateFormat("yyyy-MM-dd HHmm", Locale.US).format(Date())
            val name = Names.safe(vol?.label ?: "", "Disc $stamp") + ".iso"
            log("Copying %,d sectors (%.1f MB) to \"%s\"…".format(sectors, sectors * 2048 / 1_048_576.0, name))
            val doc = out.createFile(out.root(), "application/x-iso9660-image", name)
            resetProgress()
            try {
                val stats = BufferedOutputStream(out.open(doc), 1 shl 20).use { s -> ripper.rip(start, sectors, name, s) }
                log("Disc copy finished: ${stats.summary()}")
                if (stats.badSectors in 1..2) log("(A couple of unreadable sectors at the very end of a burned CD is normal.)")
            } catch (e: Exception) {
                out.delete(doc)
                throw e
            }
        }
    }

    /** Game discs (PS1, Saturn, Sega CD, Neo Geo CD…) and other mixed CDs: raw BIN + CUE in a folder. */
    private fun copyBinCue(d: Drive, t: Toc, modes: Map<Int, TrackMode>, bin: BinRipper, iso: IsoRipper, out: OutputFolder) {
        val first = t.tracks.first()
        val vol = if (modes[first.number] != null) iso.readVolume(first.startLba) else null
        val stamp = SimpleDateFormat("yyyy-MM-dd HHmm", Locale.US).format(Date())
        val name = Names.safe(vol?.label ?: "", "Game disc $stamp")
        val audio = t.audioTracks.size
        val kind = t.dataTracks.joinToString { (modes[it.number] ?: TrackMode.MODE1).cue }
        log("Game/mixed disc: data track $kind${if (audio > 0) " + $audio music track${if (audio == 1) "" else "s"}" else ""}.")
        val sectors = t.tracks.sumOf { it.lengthSectors }
        log("Copying all %d tracks (%,d sectors, %.1f MB) as BIN/CUE to folder \"%s\"…".format(t.tracks.size, sectors, sectors * 2352 / 1_048_576.0, name))
        val folder = out.createFolder(out.root(), name)
        val binName = "$name.bin"
        val binDoc = out.createFile(folder, "application/octet-stream", binName)
        resetProgress()
        val stats = try {
            BufferedOutputStream(out.open(binDoc), 1 shl 20).use { s -> bin.rip(t, s) }
        } catch (e: Exception) {
            out.delete(binDoc); out.delete(folder)
            throw e
        }
        out.open(out.createFile(folder, "application/octet-stream", "$name.cue")).use {
            it.write(DiscImage.cueSheet(t, modes, binName).toByteArray())
        }
        val report = "D2D Disc2Android disc copy log\nDrive: ${d.description}\nDisc: $name\nTracks:\n" +
            t.tracks.joinToString("") { "  %02d  %-10s  start %d, %,d sectors\n".format(it.number, if (it.isAudio) "AUDIO" else (modes[it.number] ?: TrackMode.MODE1).cue, it.startLba, it.lengthSectors) } +
            "Result: ${stats.summary()}\n"
        out.open(out.createFile(folder, "text/plain", "copy-log.txt")).use { it.write(report.toByteArray()) }
        log("Disc copy finished: ${stats.summary()}")
        log("Saved $name.bin + $name.cue. Open the .cue file in an emulator (DuckStation, RetroArch…).")
    }

    // ---------------------------------------------------------------- burning

    private fun burnOptions() = BurnOptions(optSimulate.isChecked, optVerify.isChecked, optGapless.isChecked)

    private fun confirmBurnIso(uri: Uri) {
        val (name, size) = Files.nameAndSize(this, uri)
        if (size <= 0) { burnLog.add("Couldn't read the size of $name."); return }
        val opts = burnOptions()
        val what = "%s (%.1f MB)".format(name, size / 1_048_576.0)
        val go = { burnIso(uri, name, size, opts) }
        if (opts.simulate) go()
        else confirm("Burn disc?", "Burn $what to the disc in the drive? This can't be undone.", "Burn", go)
    }

    private fun burnIso(uri: Uri, name: String, size: Long, opts: BurnOptions) = job("Burn", burnLog) { d ->
        val b = Burner(d, this@MainActivity, cancel::get)
        log("Image: $name")
        resetProgress()
        val result = contentResolver.openInputStream(uri)!!.buffered(1 shl 20).use { b.burnData(it, size, opts) }
        toc = null; album = null
        if (opts.verify && !opts.simulate) {
            log("Verifying…")
            resetProgress()
            log(if (b.verifyData(result)) "Verify OK — the disc matches the image." else "VERIFY FAILED — the disc doesn't match the image.")
        }
        log(if (opts.simulate) "Test burn OK. Untick \"Test burn\" to burn for real." else "Done!")
    }

    private fun burnAudioFromList() {
        if (drive == null) { burnLog.add("Connect a drive first."); return }
        if (burnList.isEmpty()) {
            burnLog.add("Your burn list is empty. Add music or video files in the Player tab, put them in order, then come back here.")
            showTab(0); showDisc = false; refreshPlayer()
            return
        }
        val opts = burnOptions()
        val files = burnList.map { Uri.parse(it.key) to it.fileName }
        val list = burnList.mapIndexed { i, s -> "%2d. %s".format(i + 1, s.displayTitle) }.joinToString("\n")
        val secs = burnList.sumOf { it.durationMs } / 1000
        val mode = if (opts.gapless) "with no gaps between tracks" else "with 2-second gaps between tracks"
        val warn = if (secs > Playlists.CD_SECONDS) "\n\nWarning: this is ${Playlists.clock(secs)}, longer than an 80-minute CD." else ""
        val go = { burnAudio(files, opts, secs) }
        if (opts.simulate) go()
        else confirm("Burn audio CD?", "Burn these ${files.size} tracks $mode? This can't be undone.$warn\n\n$list", "Burn", go)
    }

    private fun burnAudio(files: List<Pair<Uri, String>>, opts: BurnOptions, estimatedSeconds: Long) = job("Audio CD", burnLog) { d ->
        // Check the disc first, so a wrong or used disc is caught before minutes of track preparation.
        log("Checking the disc…")
        Burner(d, this@MainActivity, cancel::get).checkAudioDisc(opts, files.size, estimatedSeconds)
        val sources = mutableListOf<PcmSource>()
        val cacheDir = File(cacheDir, "burn").apply { mkdirs() }
        try {
            // Prepare every track first so decoding can't slow the burn down.
            for ((i, f) in files.withIndex()) {
                val (uri, name) = f
                log("Preparing ${i + 1}/${files.size}: $name")
                progress("Preparing tracks", i.toLong(), files.size.toLong())
                val wav = if (name.endsWith(".wav", ignoreCase = true)) {
                    try { WavSource(name, contentResolver.openInputStream(uri)!!.buffered(1 shl 20)) } catch (e: java.io.IOException) { null }
                } else null
                // anything else (or a WAV that isn't CD format) is decoded, resampled and mixed to stereo
                sources += wav ?: AudioDecoder.decodeToCdPcm(this@MainActivity, uri, name, File(cacheDir, "track$i.pcm"), cancel::get)
            }
            val totalSec = sources.sumOf { it.totalBytes } / (44_100L * 4)
            log("Total length %d:%02d".format(totalSec / 60, totalSec % 60))
            resetProgress()
            profile = 0x09
            Burner(d, this@MainActivity, cancel::get).burnAudio(sources, opts)
            toc = null; album = null
            log(if (opts.simulate) "Test burn OK. Untick \"Test burn\" to burn for real." else "Done! The CD should play in any CD player.")
        } finally {
            sources.forEach { try { it.close() } catch (_: Exception) {} }
            cacheDir.listFiles()?.forEach { it.delete() }
        }
    }

    private fun confirmErase() {
        if (drive == null) { burnLog.add("Connect a drive first."); return }
        confirm("Erase disc?", "Erase the rewritable disc in the drive? Everything on it will be lost.", "Erase") {
            job("Erase", burnLog) { d ->
                if (!d.waitReady(isCancelled = cancel::get)) throw java.io.IOException("No disc in the drive.")
                val b = Burner(d, this@MainActivity, cancel::get)
                val st = b.discStatus()
                log("Disc: ${st.describe()}")
                b.erase(st)
                toc = null; album = null
                log("Erased. The disc is ready to burn.")
            }
        }
    }

    companion object {
        private const val ACTION_USB_PERMISSION = "com.hatdagger.discdroid.USB_PERMISSION"
        private const val REQ_FOLDER = 1
        private const val REQ_ISO = 2
        private const val REQ_SONGS = 3
        private const val KEY_FOLDER = "outputFolder"
        private const val KEY_LAST_DRIVE = "lastDrive"
        private const val KEY_FLAC = "flac"
        private const val KEY_ONLINE = "online"
        private const val KEY_SIMULATE = "simulate"
        private const val KEY_VERIFY = "verify"
        private const val KEY_GAPLESS = "gapless"
        private const val KEY_TAB = "tab"
        private const val KEY_BURN_LIST = "burnList"
    }
}
