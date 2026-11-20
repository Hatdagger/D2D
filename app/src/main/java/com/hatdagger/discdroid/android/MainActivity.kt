package com.hatdagger.discdroid.android

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Typeface
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import com.hatdagger.discdroid.core.AudioRipper
import com.hatdagger.discdroid.core.CancelledException
import com.hatdagger.discdroid.core.CdText
import com.hatdagger.discdroid.core.Drive
import com.hatdagger.discdroid.core.IsoRipper
import com.hatdagger.discdroid.core.Names
import com.hatdagger.discdroid.core.Profiles
import com.hatdagger.discdroid.core.RipListener
import com.hatdagger.discdroid.core.Toc
import java.io.BufferedOutputStream
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
    @Volatile private var cdText: CdText? = null

    // Devices waiting for a permission answer / to be probed
    private val candidates = ArrayDeque<UsbDevice>()

    private lateinit var status: TextView
    private lateinit var folderText: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var progressText: TextView
    private lateinit var logView: TextView
    private lateinit var logScroll: ScrollView
    private val buttons = mutableListOf<Button>()
    private lateinit var btnCancel: Button

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

        log("DiscDroid ready. Plug in your USB CD/DVD drive and tap Connect drive.")
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

    private fun buildUi() {
        val pad = (12 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad) }

        root.addView(TextView(this).apply { text = "DiscDroid"; textSize = 24f; setTypeface(typeface, Typeface.BOLD) })
        status = TextView(this).apply { text = "No drive connected"; textSize = 16f }
        root.addView(status)
        folderText = TextView(this).apply { textSize = 13f }
        root.addView(folderText)

        fun row(vararg items: Pair<String, () -> Unit>): LinearLayout {
            val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for ((label, action) in items) {
                val b = Button(this).apply { text = label; isAllCaps = false; setOnClickListener { action() } }
                r.addView(b, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                buttons += b
            }
            return r
        }
        root.addView(row("Connect drive" to ::connectDrive, "Output folder" to ::pickFolder))
        root.addView(row("Read disc" to ::readDisc, "Eject" to ::eject))
        root.addView(row("Rip audio CD (WAV)" to ::ripAudio, "Copy data disc (ISO)" to ::ripIso))

        btnCancel = Button(this).apply {
            text = "Cancel"; isAllCaps = false; isEnabled = false
            setOnClickListener { cancel.set(true); log("Cancelling…") }
        }
        root.addView(btnCancel)

        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 1000 }
        root.addView(progressBar)
        progressText = TextView(this).apply { textSize = 13f }
        root.addView(progressText)

        logView = TextView(this).apply { typeface = Typeface.MONOSPACE; textSize = 12f; setTextIsSelectable(true) }
        logScroll = ScrollView(this).apply { addView(logView) }
        root.addView(logScroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        setContentView(root)
    }

    private fun setBusy(b: Boolean) {
        busy = b
        ui.post { applyBusy(b) }
    }

    private fun applyBusy(b: Boolean) {
        buttons.forEach { it.isEnabled = !b }
        btnCancel.isEnabled = b
        if (b) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    override fun log(message: String) {
        ui.post {
            logView.append(message + "\n")
            logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
        }
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

    // ---------------------------------------------------------------- output folder

    private fun showFolder() {
        val uri = prefs.getString(KEY_FOLDER, null)
        folderText.text = if (uri == null) "Output folder: not chosen yet" else "Output folder: ${Uri.parse(uri).lastPathSegment}"
    }

    private fun pickFolder() {
        @Suppress("DEPRECATION")
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), REQ_FOLDER)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_FOLDER || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        contentResolver.takePersistableUriPermission(
            uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )
        prefs.edit().putString(KEY_FOLDER, uri.toString()).apply()
        showFolder()
        log("Output folder set.")
    }

    private fun outputFolder(): OutputFolder? {
        val s = prefs.getString(KEY_FOLDER, null)
        if (s == null) {
            log("Choose an output folder first (tap Output folder).")
            return null
        }
        return OutputFolder(contentResolver, Uri.parse(s))
    }

    // ---------------------------------------------------------------- USB connection

    private fun connectDrive() {
        if (busy) return
        if (drive != null) { log("Already connected to ${status.text}"); return }
        val all = usb.deviceList.values.filter { UsbBulkTransport.isMassStorage(it) }
        if (all.isEmpty()) {
            log("No USB storage devices found. Check the cable, and that the drive has enough power.")
            return
        }
        // Try the drive we used last time first, then ATAPI-style interfaces (subclass 2/5),
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
        log("Found ${all.size} USB storage device(s); looking for the disc drive…")
        nextCandidate()
    }

    private fun nextCandidate() {
        val d = candidates.removeFirstOrNull()
        if (d == null) {
            log("No CD/DVD drive found. If you have a card reader in the same hub, that's normal — it was skipped.")
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
        worker.execute {
            try {
                val t = UsbBulkTransport.open(usb, device)
                val d = Drive(t)
                val inq = try { d.inquiry() } catch (e: Exception) { d.close(); throw e }
                if (!inq.isOpticalDrive) {
                    d.close()
                    log("Skipped ${device.productName ?: device.deviceName}: not a disc drive (type ${inq.peripheralType}).")
                    setBusy(false)
                    ui.post { nextCandidate() }
                    return@execute
                }
                drive = d
                driveDeviceName = device.deviceName
                ui.post { candidates.clear() }
                prefs.edit().putString(KEY_LAST_DRIVE, "%04X:%04X".format(device.vendorId, device.productId)).apply()
                ui.post { status.text = "Drive: ${inq.name} (firmware ${inq.revision})" }
                log("Connected: ${inq.name}, firmware ${inq.revision}")
                setBusy(false)
                readDisc()
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
                    else { log("USB permission denied for ${device.productName ?: device.deviceName}."); nextCandidate() }
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    if (device != null && device.deviceName == driveDeviceName) {
                        cancel.set(true)
                        try { drive?.close() } catch (_: Exception) {}
                        drive = null; driveDeviceName = null; toc = null; cdText = null
                        status.text = "No drive connected"
                        log("Drive disconnected. If you didn't unplug it, it may be short on power.")
                    }
                }
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> if (drive == null && !busy) connectDrive()
            }
        }
    }

    // ---------------------------------------------------------------- jobs

    /** Runs [block] on the worker thread with the connected drive, handling busy state and errors. */
    private fun job(name: String, block: (Drive) -> Unit) {
        val d = drive
        if (d == null) { log("Connect a drive first."); return }
        if (busy) return
        cancel.set(false)
        setBusy(true)
        worker.execute {
            try {
                block(d)
            } catch (e: CancelledException) {
                log("$name cancelled.")
            } catch (e: Exception) {
                log("$name failed: ${e.message}")
            } finally {
                setBusy(false)
            }
        }
    }

    private fun readDisc() = job("Read disc") { d ->
        log("Waiting for disc…")
        if (!d.waitReady(isCancelled = cancel::get)) {
            profile = 0; toc = null; cdText = null
            log("No disc in the drive.")
            return@job
        }
        d.setMaxSpeed()
        profile = try { d.currentProfile() } catch (e: Exception) { 0 }
        log("Disc: ${Profiles.name(profile)}")
        val t = try { d.readToc() } catch (e: Exception) { log("Could not read track list: ${e.message}"); null }
        toc = t
        cdText = if (t != null && t.audioTracks.isNotEmpty()) d.readCdText() else null
        if (t != null) {
            val text = cdText
            if (text != null) log("CD-TEXT: ${text.albumArtist ?: "?"} — ${text.albumTitle ?: "?"}")
            for (tr in t.tracks) {
                val kind = if (tr.isData) "data " else "audio"
                val title = text?.trackTitles?.get(tr.number)?.let { "  $it" } ?: ""
                log("  %2d  %s  %s%s".format(tr.number, kind, if (tr.isData) "%,d sectors".format(tr.lengthSectors) else tr.durationText(), title))
            }
            if (t.audioTracks.isNotEmpty()) log("Ready: tap \"Rip audio CD\".")
            if (t.dataTracks.isNotEmpty()) log("Ready: tap \"Copy data disc\" for an ISO image.")
        }
    }

    private fun eject() = job("Eject") { d ->
        d.eject()
        toc = null; cdText = null
        log("Ejected.")
    }

    private fun ripAudio() {
        val out = outputFolder() ?: return
        job("Audio rip") { d ->
            if (!d.waitReady(isCancelled = cancel::get)) { log("No disc in the drive."); return@job }
            val t = toc ?: d.readToc().also { toc = it }
            val tracks = t.audioTracks
            if (tracks.isEmpty()) { log("This disc has no audio tracks."); return@job }
            val text = cdText
            val stamp = SimpleDateFormat("yyyy-MM-dd HHmm", Locale.US).format(Date())
            val albumName = if (text?.albumTitle != null)
                Names.safe(listOfNotNull(text.albumArtist, text.albumTitle).joinToString(" - "), "Audio CD $stamp")
            else "Audio CD $stamp"
            val folder = out.createFolder(out.root(), albumName)
            log("Ripping ${tracks.size} tracks to \"$albumName\"…")
            d.setMaxSpeed()

            val report = StringBuilder("DiscDroid rip log\nDrive: ${d.description}\nDisc: $albumName\n\n")
            val ripper = AudioRipper(d, this@MainActivity, cancel::get)
            var totalBad = 0L
            for ((i, tr) in tracks.withIndex()) {
                val title = text?.trackTitles?.get(tr.number)
                val fileName = Names.safe("%02d%s".format(tr.number, if (title != null) " - $title" else ""), "%02d".format(tr.number)) + ".wav"
                val doc = out.createFile(folder, "audio/x-wav", fileName)
                resetProgress()
                try {
                    val stats = BufferedOutputStream(out.open(doc), 1 shl 20).use { s ->
                        ripper.ripTrack(tr, "Track ${tr.number} (${i + 1}/${tracks.size})", s)
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
        job("Disc copy") { d ->
            if (!d.waitReady(isCancelled = cancel::get)) { log("No disc in the drive."); return@job }
            if (profile == 0) profile = try { d.currentProfile() } catch (e: Exception) { 0 }
            val t = toc ?: try { d.readToc().also { toc = it } } catch (e: Exception) { null }
            val ripper = IsoRipper(d, this@MainActivity, cancel::get)

            // CDs: copy the first data track (from the track list). DVDs: copy the whole disc.
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

    companion object {
        private const val ACTION_USB_PERMISSION = "com.hatdagger.discdroid.USB_PERMISSION"
        private const val REQ_FOLDER = 1
        private const val KEY_FOLDER = "outputFolder"
        private const val KEY_LAST_DRIVE = "lastDrive"
    }
}
