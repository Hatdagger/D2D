package com.hatdagger.discdroid.android

import android.app.Activity
import android.app.AlertDialog
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
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
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import com.hatdagger.discdroid.core.AlbumInfo
import com.hatdagger.discdroid.core.AudioFormat
import com.hatdagger.discdroid.core.AudioRipper
import com.hatdagger.discdroid.core.BurnOptions
import com.hatdagger.discdroid.core.Burner
import com.hatdagger.discdroid.core.CancelledException
import com.hatdagger.discdroid.core.Drive
import com.hatdagger.discdroid.core.FlacEncoder
import com.hatdagger.discdroid.core.IsoRipper
import com.hatdagger.discdroid.core.MusicBrainz
import com.hatdagger.discdroid.core.Names
import com.hatdagger.discdroid.core.PcmSource
import com.hatdagger.discdroid.core.Profiles
import com.hatdagger.discdroid.core.RipListener
import com.hatdagger.discdroid.core.Toc
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

    private lateinit var status: TextView
    private lateinit var folderText: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var progressText: TextView
    private lateinit var btnCancel: Button
    private lateinit var ripLog: LogPane
    private lateinit var burnLog: LogPane
    @Volatile private var activeLog: LogPane? = null
    private val buttons = mutableListOf<Button>()

    private lateinit var optFlac: CheckBox
    private lateinit var optOnline: CheckBox
    private lateinit var optSimulate: CheckBox
    private lateinit var optVerify: CheckBox

    private var lastProgressPost = 0L
    private var progressStart = 0L

    // ---------------------------------------------------------------- lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
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
        ripLog.add("Plug in your USB CD/DVD drive and tap Connect drive.")
        burnLog.add("Burn ISO images to CD/DVD, or make an audio CD from music files.")
        showFolder()
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
        unregisterReceiver(usbReceiver)
        worker.shutdown()
        try { drive?.close() } catch (_: Exception) {}
        super.onDestroy()
    }

    // ---------------------------------------------------------------- UI

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    /** A scrolling log area belonging to one half of the screen. */
    private inner class LogPane {
        val text = TextView(this@MainActivity).apply { typeface = Typeface.MONOSPACE; textSize = 12f; setTextIsSelectable(true) }
        val scroll = ScrollView(this@MainActivity).apply { addView(text) }
        fun add(msg: String) {
            ui.post {
                text.append(msg + "\n")
                scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
            }
        }
    }

    private fun panel(title: String, accent: Int): LinearLayout {
        val bg = GradientDrawable().apply {
            setColor(Color.argb(40, Color.red(accent), Color.green(accent), Color.blue(accent)))
            setStroke(dp(1), Color.argb(110, Color.red(accent), Color.green(accent), Color.blue(accent)))
            setCornerRadius(dp(14).toFloat())
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = bg
            setPadding(dp(12), dp(8), dp(12), dp(8))
            addView(TextView(this@MainActivity).apply {
                text = title; textSize = 18f; setTextColor(accent); setTypeface(typeface, Typeface.BOLD)
            })
        }
    }

    private fun row(vararg items: Pair<String, () -> Unit>): LinearLayout {
        val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for ((label, action) in items) {
            val b = Button(this).apply { text = label; isAllCaps = false; setOnClickListener { action() } }
            r.addView(b, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            buttons += b
        }
        return r
    }

    private fun option(label: String, key: String, default: Boolean): CheckBox = CheckBox(this).apply {
        text = label
        isChecked = prefs.getBoolean(key, default)
        setOnCheckedChangeListener { _, checked -> prefs.edit().putBoolean(key, checked).apply() }
    }

    private fun optionRow(vararg boxes: CheckBox) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        for (b in boxes) addView(b, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val side = dp(12)
        val extraTop = dp(16)
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
            v.setPadding(side, top + extraTop, side, bottom + side)
            insets
        }
        root.setPadding(side, dp(40), side, side)

        // Header: app name, drive, output folder, and drive-level buttons
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        header.addView(TextView(this).apply { text = "DiscDroid"; textSize = 22f; setTypeface(typeface, Typeface.BOLD) })
        status = TextView(this).apply { text = "   No drive connected"; textSize = 14f }
        header.addView(status)
        root.addView(header)
        folderText = TextView(this).apply { textSize = 12f }
        root.addView(folderText)
        root.addView(row("Connect drive" to ::connectDrive, "Output folder" to ::pickFolder, "Eject" to ::eject))

        // Top screen: ripping (blue)
        val blue = Color.rgb(66, 150, 255)
        ripLog = LogPane()
        optFlac = option("Save as FLAC (smaller, lossless)", KEY_FLAC, true)
        optOnline = option("Look up names online", KEY_ONLINE, true)
        val top = panel("RIP", blue).apply {
            addView(row("Read disc" to ::readDisc, "Rip audio CD" to ::ripAudio, "Copy data disc (ISO)" to ::ripIso))
            addView(optionRow(optFlac, optOnline))
            addView(ripLog.scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        root.addView(top, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dp(8) })

        // Hinge: progress + cancel, shared by both screens
        val hinge = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, dp(6))
        }
        val progressCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 1000 }
        progressText = TextView(this).apply { textSize = 12f; text = "Idle" }
        progressCol.addView(progressBar)
        progressCol.addView(progressText)
        hinge.addView(progressCol, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        btnCancel = Button(this).apply {
            text = "Cancel"; isAllCaps = false; isEnabled = false
            setOnClickListener { cancel.set(true); log("Cancelling…") }
        }
        hinge.addView(btnCancel)
        root.addView(hinge)

        // Bottom screen: burning (red)
        val red = Color.rgb(255, 90, 80)
        burnLog = LogPane()
        optSimulate = option("Test burn (laser off)", KEY_SIMULATE, false)
        optVerify = option("Verify after burning", KEY_VERIFY, true)
        val bottom = panel("BURN", red).apply {
            addView(row("Burn ISO image" to ::pickIso, "Burn audio CD" to ::pickAudio, "Erase disc" to ::confirmErase))
            addView(optionRow(optSimulate, optVerify))
            addView(burnLog.scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        root.addView(bottom, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        setContentView(root)
    }

    private fun setBusy(b: Boolean) {
        busy = b
        ui.post {
            buttons.forEach { it.isEnabled = !b }
            btnCancel.isEnabled = b
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
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(ok) { _, _ -> action() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---------------------------------------------------------------- files

    private fun showFolder() {
        val uri = prefs.getString(KEY_FOLDER, null)
        folderText.text = if (uri == null) "Output folder: not chosen yet" else "Output folder: ${Uri.parse(uri).lastPathSegment}"
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

    @Suppress("DEPRECATION")
    private fun pickAudio() {
        if (drive == null) { burnLog.add("Connect a drive first."); return }
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("audio/*")
            .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        burnLog.add("Select the tracks. They'll be burned in file-name order (e.g. 01 …, 02 …).")
        startActivityForResult(i, REQ_AUDIO)
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
            REQ_AUDIO -> {
                val uris = mutableListOf<Uri>()
                val clip = data.clipData
                if (clip != null) for (i in 0 until clip.itemCount) uris += clip.getItemAt(i).uri
                else data.data?.let { uris += it }
                if (uris.isNotEmpty()) confirmBurnAudio(uris)
            }
        }
    }

    private fun outputFolder(): OutputFolder? {
        val s = prefs.getString(KEY_FOLDER, null)
        if (s == null) {
            ripLog.add("Choose an output folder first (tap Output folder).")
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
                ui.post { status.text = "   ${inq.name} (fw ${inq.revision})" }
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
                        try { drive?.close() } catch (_: Exception) {}
                        drive = null; driveDeviceName = null; toc = null; album = null
                        status.text = "   No drive connected"
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
                ui.post { progressText.text = "Idle" }
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
        if (t.audioTracks.isNotEmpty()) album = lookUpNames(d, t)
        val a = album
        if (a != null) log("${a.source}: ${a.artist ?: "?"} — ${a.title ?: "?"}${a.date?.let { " ($it)" } ?: ""}")
        for (tr in t.tracks) {
            val kind = if (tr.isData) "data " else "audio"
            val title = a?.tracks?.get(tr.number)?.title?.let { "  $it" } ?: ""
            log("  %2d  %s  %s%s".format(tr.number, kind, if (tr.isData) "%,d sectors".format(tr.lengthSectors) else tr.durationText(), title))
        }
        if (t.audioTracks.isNotEmpty()) log("Ready: tap \"Rip audio CD\".")
        if (t.dataTracks.isNotEmpty()) log("Ready: tap \"Copy data disc\" for an ISO image.")
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

            val report = StringBuilder("DiscDroid rip log\nDrive: ${d.description}\nDisc: $albumName\n")
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
            if (!Profiles.isDvd(profile) && dataTrack != null) {
                start = dataTrack.startLba
                sectors = dataTrack.lengthSectors
            } else if (!Profiles.isDvd(profile) && t != null && t.dataTracks.isEmpty()) {
                log("This is an audio CD with no data track — use \"Rip audio CD\" instead.")
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

    // ---------------------------------------------------------------- burning

    private fun burnOptions() = BurnOptions(optSimulate.isChecked, optVerify.isChecked)

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

    private fun confirmBurnAudio(uris: List<Uri>) {
        val files = uris.map { it to Files.nameAndSize(this, it).first }.sortedBy { it.second.lowercase(Locale.ROOT) }
        val opts = burnOptions()
        val list = files.mapIndexed { i, f -> "%2d. %s".format(i + 1, f.second) }.joinToString("\n")
        val go = { burnAudio(files, opts) }
        if (opts.simulate) go()
        else confirm("Burn audio CD?", "Burn these ${files.size} tracks to the CD-R? This can't be undone.\n\n$list", "Burn", go)
    }

    private fun burnAudio(files: List<Pair<Uri, String>>, opts: BurnOptions) = job("Audio CD", burnLog) { d ->
        val sources = mutableListOf<PcmSource>()
        val cacheDir = File(cacheDir, "burn").apply { mkdirs() }
        try {
            // Prepare every track first so decoding can't slow the burn down.
            for ((i, f) in files.withIndex()) {
                val (uri, name) = f
                log("Preparing ${i + 1}/${files.size}: $name")
                progress("Preparing tracks", i.toLong(), files.size.toLong())
                sources += if (name.endsWith(".wav", ignoreCase = true)) {
                    WavSource(name, contentResolver.openInputStream(uri)!!.buffered(1 shl 20))
                } else {
                    AudioDecoder.decodeToCdPcm(this@MainActivity, uri, name, File(cacheDir, "track$i.pcm"), cancel::get)
                }
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
        private const val REQ_AUDIO = 3
        private const val KEY_FOLDER = "outputFolder"
        private const val KEY_LAST_DRIVE = "lastDrive"
        private const val KEY_FLAC = "flac"
        private const val KEY_ONLINE = "online"
        private const val KEY_SIMULATE = "simulate"
        private const val KEY_VERIFY = "verify"
    }
}
