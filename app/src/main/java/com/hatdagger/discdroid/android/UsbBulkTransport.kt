package com.hatdagger.discdroid.android

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import com.hatdagger.discdroid.core.BulkOnly
import com.hatdagger.discdroid.core.CommandResult
import com.hatdagger.discdroid.core.Direction
import com.hatdagger.discdroid.core.ScsiTransport
import java.io.IOException

/**
 * USB Mass Storage Bulk-Only Transport using Android's USB host API (no root needed).
 * Sends SCSI commands straight to the drive, bypassing Android's storage stack.
 */
class UsbBulkTransport private constructor(
    private val device: UsbDevice,
    private val connection: UsbDeviceConnection,
    private val iface: UsbInterface,
    private val epIn: UsbEndpoint,
    private val epOut: UsbEndpoint,
) : ScsiTransport {

    private var tag = 1
    private val cswBuf = ByteArray(BulkOnly.CSW_SIZE)

    override val description: String =
        "%s %s (USB %04X:%04X)".format(
            device.manufacturerName ?: "", device.productName ?: "", device.vendorId, device.productId,
        ).trim()

    @Synchronized
    override fun execute(cdb: ByteArray, direction: Direction, buffer: ByteArray, length: Int, timeoutMs: Int): CommandResult {
        val myTag = tag++
        val cbw = BulkOnly.buildCbw(myTag, length, direction, 0, cdb)
        if (connection.bulkTransfer(epOut, cbw, cbw.size, 5_000) != cbw.size) {
            resetRecovery()
            throw IOException("Drive did not accept command (USB write failed)")
        }

        var moved = 0
        when (direction) {
            Direction.IN -> {
                while (moved < length) {
                    val want = minOf(MAX_TRANSFER, length - moved)
                    val n = connection.bulkTransfer(epIn, buffer, moved, want, timeoutMs)
                    if (n < 0) { clearHalt(epIn); break }
                    moved += n
                    if (n < want) break // short packet = end of data
                }
            }
            Direction.OUT -> {
                while (moved < length) {
                    val want = minOf(MAX_TRANSFER, length - moved)
                    val n = connection.bulkTransfer(epOut, buffer, moved, want, timeoutMs)
                    if (n < 0) { clearHalt(epOut); break }
                    moved += n
                    if (n < want) break
                }
            }
            Direction.NONE -> {}
        }

        var csw = readCsw(timeoutMs)
        if (csw == null) {
            clearHalt(epIn)
            csw = readCsw(timeoutMs)
        }
        if (csw == null || csw.tag != myTag) {
            resetRecovery()
            throw IOException("Lost sync with drive (bad status packet)")
        }
        if (csw.status == 2) {
            resetRecovery()
            throw IOException("Drive reported a phase error")
        }
        return CommandResult(csw.status, moved)
    }

    private fun readCsw(timeoutMs: Int): BulkOnly.Csw? {
        val n = connection.bulkTransfer(epIn, cswBuf, BulkOnly.CSW_SIZE, timeoutMs)
        return if (n < 0) null else BulkOnly.parseCsw(cswBuf, n)
    }

    private fun clearHalt(ep: UsbEndpoint) {
        // CLEAR_FEATURE(ENDPOINT_HALT)
        connection.controlTransfer(0x02, 0x01, 0, ep.address, null, 0, 2_000)
    }

    private fun resetRecovery() {
        // Bulk-Only Mass Storage Reset, then clear both endpoints
        connection.controlTransfer(0x21, 0xFF, 0, iface.id, null, 0, 2_000)
        clearHalt(epIn)
        clearHalt(epOut)
    }

    override fun close() {
        try { connection.releaseInterface(iface) } catch (_: Exception) {}
        connection.close()
    }

    companion object {
        private const val MAX_TRANSFER = 16_384

        /** True if the device has a Bulk-Only mass storage interface (could be a disc drive or a card reader). */
        fun isMassStorage(device: UsbDevice): Boolean = findInterface(device) != null

        private fun findInterface(device: UsbDevice): UsbInterface? {
            for (i in 0 until device.interfaceCount) {
                val f = device.getInterface(i)
                if (f.interfaceClass == UsbConstants.USB_CLASS_MASS_STORAGE && f.interfaceProtocol == 0x50) return f
            }
            return null
        }

        fun open(manager: UsbManager, device: UsbDevice): UsbBulkTransport {
            val iface = findInterface(device) ?: throw IOException("Not a USB mass storage device")
            var epIn: UsbEndpoint? = null
            var epOut: UsbEndpoint? = null
            for (e in 0 until iface.endpointCount) {
                val ep = iface.getEndpoint(e)
                if (ep.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
                if (ep.direction == UsbConstants.USB_DIR_IN) epIn = ep else epOut = ep
            }
            if (epIn == null || epOut == null) throw IOException("Drive is missing bulk endpoints")
            val conn = manager.openDevice(device) ?: throw IOException("Could not open USB device (permission?)")
            if (!conn.claimInterface(iface, true)) {
                conn.close()
                throw IOException("Could not take control of the drive from Android")
            }
            return UsbBulkTransport(device, conn, iface, epIn, epOut)
        }
    }
}
