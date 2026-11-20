package com.hatdagger.discdroid.core

/**
 * USB Mass Storage Bulk-Only Transport wrappers (CBW / CSW), pure byte logic.
 */
object BulkOnly {
    const val CBW_SIZE = 31
    const val CSW_SIZE = 13
    private const val CBW_SIGNATURE = 0x43425355L
    private const val CSW_SIGNATURE = 0x53425355L

    fun buildCbw(tag: Int, dataLength: Int, direction: Direction, lun: Int, cdb: ByteArray): ByteArray {
        require(cdb.size in 1..16) { "CDB must be 1..16 bytes" }
        val b = ByteArray(CBW_SIZE)
        b.putLe32(0, CBW_SIGNATURE)
        b.putLe32(4, tag.toLong() and 0xFFFFFFFFL)
        b.putLe32(8, dataLength.toLong() and 0xFFFFFFFFL)
        b[12] = if (direction == Direction.IN) 0x80.toByte() else 0
        b[13] = (lun and 0x0F).toByte()
        b[14] = cdb.size.toByte()
        System.arraycopy(cdb, 0, b, 15, cdb.size)
        return b
    }

    class Csw(val tag: Int, val residue: Long, val status: Int)

    /** Returns null if the buffer is not a valid CSW. */
    fun parseCsw(b: ByteArray, len: Int): Csw? {
        if (len != CSW_SIZE) return null
        if (b.le32(0) != CSW_SIGNATURE) return null
        return Csw(b.le32(4).toInt(), b.le32(8), b.u8(12))
    }
}
