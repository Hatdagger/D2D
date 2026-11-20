package com.hatdagger.discdroid.core

/** Decoded fixed-format SCSI sense data (REQUEST SENSE response). */
data class Sense(val key: Int, val asc: Int, val ascq: Int) {

    val isNoMedium get() = key == 0x02 && asc == 0x3A
    val isBecomingReady get() = key == 0x02 && asc == 0x04 && (ascq == 0x01 || ascq == 0x07 || ascq == 0x08)
    val isMediumChanged get() = key == 0x06 && asc == 0x28
    val isReset get() = key == 0x06 && asc == 0x29
    /** Copy-protection key exchange / scrambled sector (e.g. CSS on DVD-Video). */
    val isCopyProtected get() = key == 0x05 && asc == 0x6F
    val isMediumError get() = key == 0x03
    val isLbaOutOfRange get() = key == 0x05 && asc == 0x21

    fun describe(): String {
        val keyName = KEY_NAMES.getOrElse(key) { "Unknown" }
        val detail = when {
            isNoMedium -> "no disc in drive"
            isBecomingReady -> "drive is spinning up"
            isMediumChanged -> "disc was changed"
            isReset -> "drive was reset"
            isCopyProtected -> "copy-protected sector"
            isLbaOutOfRange -> "address past end of disc"
            key == 0x03 && asc == 0x11 -> "unreadable sector"
            key == 0x03 && asc == 0x02 -> "no seek complete"
            key == 0x05 && asc == 0x20 -> "command not supported by this drive"
            key == 0x05 && asc == 0x24 -> "invalid field in command"
            key == 0x05 && asc == 0x64 -> "wrong sector type for this track"
            key == 0x05 && asc == 0x30 -> "incompatible disc format"
            else -> null
        }
        val codes = "%s (ASC %02X/%02X)".format(keyName, asc, ascq)
        return if (detail != null) "$detail - $codes" else codes
    }

    companion object {
        private val KEY_NAMES = listOf(
            "No sense", "Recovered error", "Not ready", "Medium error",
            "Hardware error", "Illegal request", "Unit attention", "Data protect",
            "Blank check", "Vendor specific", "Copy aborted", "Aborted command",
            "Equal", "Volume overflow", "Miscompare", "Reserved",
        )

        fun parse(buf: ByteArray, len: Int): Sense {
            if (len < 14) return Sense(buf.u8(2).and(0x0F), 0, 0)
            val code = buf.u8(0) and 0x7F
            return if (code == 0x72 || code == 0x73) {
                // Descriptor format
                Sense(buf.u8(1) and 0x0F, buf.u8(2), buf.u8(3))
            } else {
                Sense(buf.u8(2) and 0x0F, buf.u8(12), buf.u8(13))
            }
        }
    }
}

internal fun ByteArray.u8(i: Int): Int = this[i].toInt() and 0xFF
internal fun ByteArray.be16(i: Int): Int = (u8(i) shl 8) or u8(i + 1)
internal fun ByteArray.be32(i: Int): Long =
    (u8(i).toLong() shl 24) or (u8(i + 1).toLong() shl 16) or (u8(i + 2).toLong() shl 8) or u8(i + 3).toLong()
internal fun ByteArray.le32(i: Int): Long =
    (u8(i + 3).toLong() shl 24) or (u8(i + 2).toLong() shl 16) or (u8(i + 1).toLong() shl 8) or u8(i).toLong()
internal fun ByteArray.putBe32(i: Int, v: Long) {
    this[i] = (v ushr 24).toByte(); this[i + 1] = (v ushr 16).toByte()
    this[i + 2] = (v ushr 8).toByte(); this[i + 3] = v.toByte()
}
internal fun ByteArray.putLe32(i: Int, v: Long) {
    this[i] = v.toByte(); this[i + 1] = (v ushr 8).toByte()
    this[i + 2] = (v ushr 16).toByte(); this[i + 3] = (v ushr 24).toByte()
}
internal fun ByteArray.putLe16(i: Int, v: Int) {
    this[i] = v.toByte(); this[i + 1] = (v ushr 8).toByte()
}
internal fun ByteArray.ascii(from: Int, len: Int): String =
    String(this, from, len, Charsets.ISO_8859_1).trim { it <= ' ' }
