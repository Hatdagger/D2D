package com.hatdagger.discdroid.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MetadataTest {

    private fun tocFromOffsets(offsets: List<Long>, leadOut: Long, dataLast: Boolean = false): Toc {
        val entries = offsets.mapIndexed { i, o -> Triple(i + 1, o - 150, dataLast && i == offsets.lastIndex) }
        return Toc.parse(toc(*entries.toTypedArray(), leadOut = leadOut - 150), 1000)
    }

    @Test fun discIdMatchesPublishedExample() {
        // Test vector from the cdtoc crate: CDTOC 4+96+2D2B+6256+B327+D84A
        val t = tocFromOffsets(listOf(0x96L, 0x2D2BL, 0x6256L, 0xB327L), 0xD84AL)
        assertEquals("nljDXdC8B_pDwbdY1vZJvdrAZI4-", MusicBrainz.discId(t))
        assertEquals("1+4+55370+150+11563+25174+45863", MusicBrainz.tocParam(t))
    }

    @Test fun enhancedCdUsesAudioSessionOnly() {
        // 3 audio tracks + data track: lead-out for the ID = data start - 11400
        val t = tocFromOffsets(listOf(150L, 20000L, 40000L, 80000L), 120000L, dataLast = true)
        assertEquals("1+3+68600+150+20000+40000", MusicBrainz.tocParam(t))
    }

    @Test fun parseLookup() {
        val json = """
        {"releases":[
          {"id":"other","title":"Wrong","media":[{"discs":[{"id":"zzz"}],"tracks":[{"position":1,"title":"x"}]}]},
          {"id":"rel-1","title":"Great Album","date":"1999-05-01",
           "artist-credit":[{"name":"Band","joinphrase":" feat. "},{"name":"Guest","joinphrase":""}],
           "media":[{"discs":[{"id":"DISC-ID"}],"tracks":[
              {"position":1,"title":"Intro é","recording":{"title":"Intro"}},
              {"position":2,"title":"Song","artist-credit":[{"name":"Solo","joinphrase":""}]}
           ]}]}
        ]}"""
        val a = MusicBrainz.parse(json, "DISC-ID", 2)!!
        assertEquals("Great Album", a.title)
        assertEquals("Band feat. Guest", a.artist)
        assertEquals("1999-05-01", a.date)
        assertEquals("rel-1", a.releaseId)
        assertEquals("Intro é", a.tracks[1]!!.title)
        assertNull(a.tracks[1]!!.artist)
        assertEquals("Solo", a.tracks[2]!!.artist)
    }

    @Test fun parseFuzzyFallsBackToTrackCount() {
        val json = """{"releases":[{"id":"r","title":"T","media":[{"tracks":[{"position":1,"title":"a"},{"position":2,"title":"b"}]}]}]}"""
        assertEquals("T", MusicBrainz.parse(json, "nope", 2)!!.title)
        assertNull(MusicBrainz.parse(json, "nope", 3))
    }

    @Test fun jsonBasics() {
        val v = Json.parse("""{"a":[1,2.5,-3e2,true,false,null],"b":"q\"\\\n"}""") as Map<*, *>
        assertEquals(listOf(1.0, 2.5, -300.0, true, false, null), v["a"])
        assertEquals("q\"\\\n", v["b"])
    }
}
