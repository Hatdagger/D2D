package com.hatdagger.discdroid.core

import java.io.IOException

/**
 * MMC (SCSI Multimedia Commands) for a CD/DVD drive, on top of any [ScsiTransport].
 */
class Drive(private val transport: ScsiTransport) : AutoCloseable {

    private val senseBuf = ByteArray(32)

    val description get() = transport.description

    private fun run(
        name: String, cdb: ByteArray, dir: Direction, buf: ByteArray, len: Int, timeoutMs: Int = 20_000,
    ): Int {
        val r = transport.execute(cdb, dir, buf, len, timeoutMs)
        if (r.status == 0) return r.transferred
        throw ScsiException(requestSense(), name)
    }

    fun requestSense(): Sense {
        val cdb = byteArrayOf(0x03, 0, 0, 0, senseBuf.size.toByte(), 0)
        val r = transport.execute(cdb, Direction.IN, senseBuf, senseBuf.size, 5_000)
        if (r.status != 0) throw IOException("REQUEST SENSE failed")
        return Sense.parse(senseBuf, r.transferred)
    }

    fun inquiry(): InquiryData {
        val buf = ByteArray(96)
        val n = run("INQUIRY", byteArrayOf(0x12, 0, 0, 0, 96, 0), Direction.IN, buf, 96, 5_000)
        return InquiryData.parse(buf, n)
    }

    fun testUnitReady() {
        run("TEST UNIT READY", ByteArray(6), Direction.NONE, ByteArray(0), 0, 10_000)
    }

    /**
     * Wait for the drive to be ready with a disc. Returns false if there is no disc.
     * Clears "disc changed" / "reset" unit attentions along the way.
     */
    fun waitReady(maxWaitMs: Long = 45_000, isCancelled: () -> Boolean = { false }): Boolean {
        val deadline = System.currentTimeMillis() + maxWaitMs
        while (true) {
            try {
                testUnitReady(); return true
            } catch (e: ScsiException) {
                val s = e.sense
                when {
                    s.isNoMedium -> return false
                    s.isMediumChanged || s.isReset || s.isBecomingReady || s.key == 0x02 -> {
                        if (System.currentTimeMillis() > deadline) throw e
                        if (isCancelled()) throw CancelledException()
                        Thread.sleep(500)
                    }
                    else -> throw e
                }
            }
        }
    }

    fun currentProfile(): Int {
        val buf = ByteArray(8)
        // GET CONFIGURATION, RT=2 (only feature 0), allocation 8 bytes = header only
        val cdb = byteArrayOf(0x46, 0x02, 0, 0, 0, 0, 0, 0, 8, 0)
        val n = run("GET CONFIGURATION", cdb, Direction.IN, buf, 8, 10_000)
        return if (n >= 8) buf.be16(6) else 0
    }

    fun readToc(): Toc {
        val buf = ByteArray(804) // 4 header + 100 descriptors * 8
        val cdb = byteArrayOf(0x43, 0, 0, 0, 0, 0, 1, (804 shr 8).toByte(), (804 and 0xFF).toByte(), 0)
        val n = run("READ TOC", cdb, Direction.IN, buf, buf.size)
        return Toc.parse(buf, n)
    }

    /** CD-TEXT, or null if the disc or drive doesn't have it. */
    fun readCdText(): CdText? = try {
        val size = 0x4000 // up to ~900 packs
        val buf = ByteArray(size)
        val cdb = byteArrayOf(0x43, 0, 0x05, 0, 0, 0, 0, (size shr 8).toByte(), (size and 0xFF).toByte(), 0)
        val n = run("READ TOC (CD-TEXT)", cdb, Direction.IN, buf, size)
        CdText.parse(buf, n)
    } catch (e: ScsiException) {
        null
    }

    /** Returns (lastLba, blockSize). */
    fun readCapacity(): Pair<Long, Int> {
        val buf = ByteArray(8)
        run("READ CAPACITY", byteArrayOf(0x25, 0, 0, 0, 0, 0, 0, 0, 0, 0), Direction.IN, buf, 8)
        return buf.be32(0) to buf.be32(4).toInt()
    }

    /** READ CD for CD-DA: [count] raw 2352-byte audio sectors into [buf]. */
    fun readAudio(lba: Long, count: Int, buf: ByteArray) {
        val len = count * AUDIO_SECTOR
        require(buf.size >= len)
        val cdb = ByteArray(12)
        cdb[0] = 0xBE.toByte()
        cdb[1] = 0x04 // expected sector type: CD-DA
        cdb.putBe32(2, lba)
        cdb[6] = (count shr 16).toByte(); cdb[7] = (count shr 8).toByte(); cdb[8] = count.toByte()
        cdb[9] = 0x10 // user data only
        val n = run("READ CD", cdb, Direction.IN, buf, len, 30_000)
        if (n != len) throw IOException("READ CD returned $n of $len bytes")
    }

    /** READ(10): [count] 2048-byte data sectors into [buf]. */
    fun readData(lba: Long, count: Int, buf: ByteArray) {
        val len = count * DATA_SECTOR
        require(buf.size >= len)
        val cdb = ByteArray(10)
        cdb[0] = 0x28
        cdb.putBe32(2, lba)
        cdb[7] = (count shr 8).toByte(); cdb[8] = count.toByte()
        val n = run("READ(10)", cdb, Direction.IN, buf, len, 30_000)
        if (n != len) throw IOException("READ(10) returned $n of $len bytes")
    }

    /** Ask for maximum read speed. Ignored by drives that don't support it. */
    fun setMaxSpeed() {
        try {
            val cdb = byteArrayOf(0xBB.toByte(), 0, 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0, 0, 0, 0, 0, 0)
            run("SET CD SPEED", cdb, Direction.NONE, ByteArray(0), 0, 10_000)
        } catch (_: ScsiException) {
        }
    }

    fun eject() {
        try {
            // PREVENT ALLOW MEDIUM REMOVAL: allow
            run("ALLOW REMOVAL", byteArrayOf(0x1E, 0, 0, 0, 0, 0), Direction.NONE, ByteArray(0), 0, 10_000)
        } catch (_: ScsiException) {
        }
        run("START STOP UNIT", byteArrayOf(0x1B, 0, 0, 0, 0x02, 0), Direction.NONE, ByteArray(0), 0, 30_000)
    }

    override fun close() = transport.close()

    companion object {
        const val AUDIO_SECTOR = 2352
        const val DATA_SECTOR = 2048
    }
}

class CancelledException : IOException("Cancelled")
