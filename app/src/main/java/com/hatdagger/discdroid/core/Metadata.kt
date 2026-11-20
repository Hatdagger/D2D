package com.hatdagger.discdroid.core

import java.security.MessageDigest
import java.util.Base64

/** Album and track names, from MusicBrainz or CD-TEXT. */
data class AlbumInfo(
    val title: String?,
    val artist: String?,
    val date: String?,
    val tracks: Map<Int, TrackInfo>,
    val releaseId: String? = null,
    val source: String,
) {
    data class TrackInfo(val title: String?, val artist: String?)

    companion object {
        fun fromCdText(t: CdText) = AlbumInfo(
            t.albumTitle, t.albumArtist, null,
            (t.trackTitles.keys + t.trackArtists.keys).associateWith { AlbumInfo.TrackInfo(t.trackTitles[it], t.trackArtists[it]) },
            source = "CD-TEXT",
        )
    }
}

object MusicBrainz {
    const val USER_AGENT = "D2D-Disc2Android/0.3 ( https://github.com/Hatdagger/d2d )"

    /** The audio session only: Enhanced CDs end at the last audio track. */
    private fun audioLayout(toc: Toc): Triple<List<Track>, Long, Int>? {
        val audio = toc.audioTracks
        if (audio.isEmpty()) return null
        // MusicBrainz only counts a contiguous run of audio tracks starting at the first track.
        val run = toc.tracks.takeWhile { it.isAudio }.ifEmpty { return null }
        val last = run.last()
        return Triple(run, last.startLba + last.lengthSectors, run.first().number)
    }

    /** MusicBrainz disc ID (SHA-1 of the TOC, MusicBrainz base64 alphabet). */
    fun discId(toc: Toc): String? {
        val (run, leadOut, first) = audioLayout(toc) ?: return null
        val sb = StringBuilder()
        sb.append("%02X".format(first))
        sb.append("%02X".format(run.last().number))
        sb.append("%08X".format(leadOut + 150))
        for (i in 1..99) {
            val t = run.firstOrNull { it.number == i }
            sb.append("%08X".format(if (t != null) t.startLba + 150 else 0))
        }
        val sha = MessageDigest.getInstance("SHA-1").digest(sb.toString().toByteArray(Charsets.US_ASCII))
        return Base64.getEncoder().encodeToString(sha).replace('+', '.').replace('/', '_').replace('=', '-')
    }

    /** "toc" query parameter for fuzzy lookup when the exact disc ID is unknown. */
    fun tocParam(toc: Toc): String? {
        val (run, leadOut, first) = audioLayout(toc) ?: return null
        return (listOf(first.toLong(), run.last().number.toLong(), leadOut + 150) + run.map { it.startLba + 150 }).joinToString("+")
    }

    fun lookupUrl(toc: Toc): String? {
        val id = discId(toc) ?: return null
        return "https://musicbrainz.org/ws/2/discid/$id?toc=${tocParam(toc)}&inc=recordings+artist-credits&fmt=json"
    }

    fun coverUrl(releaseId: String) = "https://coverartarchive.org/release/$releaseId/front-500"

    private fun credit(o: Any?): String? {
        val list = o as? List<*> ?: return null
        val s = list.joinToString("") { c ->
            val m = c as? Map<*, *> ?: return@joinToString ""
            (m["name"] as? String ?: "") + (m["joinphrase"] as? String ?: "")
        }
        return s.ifBlank { null }
    }

    /**
     * Picks the release that contains this exact disc (or, for fuzzy matches, the first medium
     * with the right number of tracks) and returns its names.
     */
    fun parse(json: String, discId: String, trackCount: Int): AlbumInfo? {
        val root = Json.parse(json) as? Map<*, *> ?: return null
        val releases = root["releases"] as? List<*> ?: return null
        var chosen: Pair<Map<*, *>, Map<*, *>>? = null
        for (pass in 0..1) {
            for (r in releases) {
                val rel = r as? Map<*, *> ?: continue
                for (m in rel["media"] as? List<*> ?: emptyList<Any>()) {
                    val med = m as? Map<*, *> ?: continue
                    val discs = (med["discs"] as? List<*>)?.mapNotNull { (it as? Map<*, *>)?.get("id") } ?: emptyList()
                    val tracks = med["tracks"] as? List<*> ?: continue
                    val match = if (pass == 0) discId in discs else tracks.size == trackCount
                    if (match) { chosen = rel to med; break }
                }
                if (chosen != null) break
            }
            if (chosen != null) break
        }
        val (rel, med) = chosen ?: return null
        val albumArtist = credit(rel["artist-credit"])
        val tracks = HashMap<Int, AlbumInfo.TrackInfo>()
        for (t in med["tracks"] as List<*>) {
            val tm = t as? Map<*, *> ?: continue
            val pos = (tm["position"] as? Number)?.toInt() ?: continue
            val rec = tm["recording"] as? Map<*, *>
            val title = tm["title"] as? String ?: rec?.get("title") as? String
            val artist = credit(tm["artist-credit"]) ?: credit(rec?.get("artist-credit"))
            tracks[pos] = AlbumInfo.TrackInfo(title, artist)
        }
        return AlbumInfo(
            rel["title"] as? String, albumArtist, (rel["date"] as? String)?.ifBlank { null },
            tracks, rel["id"] as? String, "MusicBrainz",
        )
    }
}

/** Minimal JSON parser: objects -> Map, arrays -> List, numbers -> Double, plus String/Boolean/null. */
object Json {
    fun parse(s: String): Any? {
        val p = P(s)
        val v = p.value()
        p.ws()
        require(p.i == s.length) { "Trailing data in JSON" }
        return v
    }

    private class P(val s: String) {
        var i = 0
        fun ws() { while (i < s.length && s[i].isWhitespace()) i++ }
        fun value(): Any? {
            ws()
            require(i < s.length) { "Unexpected end of JSON" }
            return when (val c = s[i]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> str()
                't' -> lit("true", true)
                'f' -> lit("false", false)
                'n' -> lit("null", null)
                else -> if (c == '-' || c.isDigit()) num() else throw IllegalArgumentException("Bad JSON at $i")
            }
        }
        fun lit(w: String, v: Any?): Any? {
            require(s.startsWith(w, i)) { "Bad JSON literal at $i" }
            i += w.length; return v
        }
        fun num(): Double {
            val st = i
            while (i < s.length && (s[i].isDigit() || s[i] in "+-.eE")) i++
            return s.substring(st, i).toDouble()
        }
        fun str(): String {
            i++
            val sb = StringBuilder()
            while (true) {
                require(i < s.length) { "Unterminated JSON string" }
                val c = s[i++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        when (val e = s[i++]) {
                            'n' -> sb.append('\n'); 't' -> sb.append('\t'); 'r' -> sb.append('\r')
                            'b' -> sb.append('\b'); 'f' -> sb.append('\u000C')
                            'u' -> { sb.append(s.substring(i, i + 4).toInt(16).toChar()); i += 4 }
                            else -> sb.append(e)
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }
        fun arr(): List<Any?> {
            i++; val l = ArrayList<Any?>(); ws()
            if (s[i] == ']') { i++; return l }
            while (true) {
                l += value(); ws()
                when (s[i++]) { ',' -> continue; ']' -> return l; else -> throw IllegalArgumentException("Bad JSON array at $i") }
            }
        }
        fun obj(): Map<String, Any?> {
            i++; val m = LinkedHashMap<String, Any?>(); ws()
            if (s[i] == '}') { i++; return m }
            while (true) {
                ws(); val k = str(); ws()
                require(s[i++] == ':') { "Bad JSON object at $i" }
                m[k] = value(); ws()
                when (s[i++]) { ',' -> continue; '}' -> return m; else -> throw IllegalArgumentException("Bad JSON object at $i") }
            }
        }
    }
}
