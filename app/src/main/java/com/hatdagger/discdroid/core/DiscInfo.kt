package com.hatdagger.discdroid.core

/** INQUIRY result. Peripheral type 5 = CD/DVD/BD drive. */
data class InquiryData(val peripheralType: Int, val vendor: String, val product: String, val revision: String) {
    val isOpticalDrive get() = peripheralType == 0x05
    val name get() = "$vendor $product".trim()

    companion object {
        fun parse(b: ByteArray, len: Int): InquiryData {
            require(len >= 36) { "INQUIRY response too short ($len bytes)" }
            return InquiryData(b.u8(0) and 0x1F, b.ascii(8, 8), b.ascii(16, 16), b.ascii(32, 4))
        }
    }
}

data class Track(val number: Int, val startLba: Long, val isData: Boolean, var lengthSectors: Long = 0) {
    val isAudio get() = !isData
    /** Length as m:ss (75 sectors per second on CD). */
    fun durationText(): String {
        val secs = lengthSectors / 75
        return "%d:%02d".format(secs / 60, secs % 60)
    }
}

data class Toc(val firstTrack: Int, val lastTrack: Int, val tracks: List<Track>, val leadOutLba: Long) {
    val audioTracks get() = tracks.filter { it.isAudio }
    val dataTracks get() = tracks.filter { it.isData }

    companion object {
        /** Gap between sessions on an Enhanced CD (audio session + data session). */
        const val SESSION_GAP_SECTORS = 11_400L

        /** Parse READ TOC format 0 (LBA addressing). */
        fun parse(b: ByteArray, len: Int): Toc {
            require(len >= 4) { "TOC too short" }
            val dataLen = minOf(b.be16(0) + 2, len)
            val first = b.u8(2)
            val last = b.u8(3)
            val raw = mutableListOf<Track>()
            var leadOut = -1L
            var i = 4
            while (i + 8 <= dataLen) {
                val control = b.u8(i + 1) and 0x0F
                val num = b.u8(i + 2)
                val lba = b.be32(i + 4).toInt().toLong() // signed: pregap can be negative
                if (num == 0xAA) leadOut = lba
                else raw += Track(num, lba, (control and 0x04) != 0)
                i += 8
            }
            require(leadOut >= 0) { "TOC has no lead-out" }
            require(raw.isNotEmpty()) { "TOC has no tracks" }
            for ((idx, t) in raw.withIndex()) {
                val next = raw.getOrNull(idx + 1)
                var end = next?.startLba ?: leadOut
                // Enhanced CD: last audio track is followed by a data track in a second session.
                if (t.isAudio && next != null && next.isData && idx + 1 == raw.lastIndex &&
                    end - t.startLba > SESSION_GAP_SECTORS
                ) end -= SESSION_GAP_SECTORS
                t.lengthSectors = end - t.startLba
            }
            return Toc(first, last, raw, leadOut)
        }
    }
}

/** Album / per-track text from CD-TEXT (block 0 only, ISO-8859-1). */
data class CdText(val albumTitle: String?, val albumArtist: String?, val trackTitles: Map<Int, String>, val trackArtists: Map<Int, String>) {
    companion object {
        private const val PACK_TITLE = 0x80
        private const val PACK_PERFORMER = 0x81

        /** Parse READ TOC format 5 response. Returns null if there is no usable text. */
        fun parse(b: ByteArray, len: Int): CdText? {
            if (len < 4 + 18) return null
            val dataLen = minOf(b.be16(0) + 2, len)
            val titles = HashMap<Int, String>()
            val artists = HashMap<Int, String>()
            for (type in intArrayOf(PACK_TITLE, PACK_PERFORMER)) {
                val target = if (type == PACK_TITLE) titles else artists
                var current = StringBuilder()
                var trackNo = -1
                var off = 4
                while (off + 18 <= dataLen) {
                    val packType = b.u8(off)
                    val block = (b.u8(off + 3) shr 4) and 0x07
                    val doubleByte = (b.u8(off + 3) and 0x80) != 0
                    if (packType == type && block == 0 && !doubleByte) {
                        if (trackNo < 0) trackNo = b.u8(off + 1) and 0x7F
                        for (k in 0 until 12) {
                            val c = b.u8(off + 4 + k)
                            if (c == 0) {
                                target[trackNo] = current.toString()
                                current = StringBuilder()
                                trackNo++
                            } else current.append(c.toChar())
                        }
                    }
                    off += 18
                }
            }
            // A TAB (0x09) means "same as previous track".
            for (map in listOf(titles, artists)) {
                val keys = map.keys.sorted()
                for (k in keys) if (map[k] == "\t") map[k] = map[k - 1] ?: ""
            }
            val album = titles.remove(0)?.takeIf { it.isNotBlank() }
            val albumArtist = artists.remove(0)?.takeIf { it.isNotBlank() }
            if (album == null && albumArtist == null && titles.isEmpty()) return null
            return CdText(album, albumArtist, titles.filterValues { it.isNotBlank() }, artists.filterValues { it.isNotBlank() })
        }
    }
}

/** MMC "current profile" from GET CONFIGURATION. */
object Profiles {
    fun name(p: Int): String = when (p) {
        0x0000 -> "No disc"
        0x0008 -> "CD-ROM"
        0x0009 -> "CD-R"
        0x000A -> "CD-RW"
        0x0010 -> "DVD-ROM"
        0x0011 -> "DVD-R"
        0x0012 -> "DVD-RAM"
        0x0013 -> "DVD-RW (restricted overwrite)"
        0x0014 -> "DVD-RW"
        0x0015 -> "DVD-R DL"
        0x0016 -> "DVD-R DL (layer jump)"
        0x001A -> "DVD+RW"
        0x001B -> "DVD+R"
        0x002A -> "DVD+RW DL"
        0x002B -> "DVD+R DL"
        0x0040 -> "BD-ROM"
        0x0041, 0x0042 -> "BD-R"
        0x0043 -> "BD-RE"
        else -> "Unknown disc (profile 0x%04X)".format(p)
    }

    fun isCd(p: Int) = p in 0x0008..0x000A
    fun isDvd(p: Int) = p in 0x0010..0x002B
}

/** Primary Volume Descriptor info from sector 16 of an ISO 9660 disc. */
data class IsoVolume(val label: String, val volumeSectors: Long) {
    companion object {
        fun parse(sector: ByteArray): IsoVolume? {
            if (sector.size < 2048) return null
            if (sector.u8(0) != 1 || String(sector, 1, 5, Charsets.US_ASCII) != "CD001") return null
            return IsoVolume(sector.ascii(40, 32), sector.le32(80))
        }
    }
}
