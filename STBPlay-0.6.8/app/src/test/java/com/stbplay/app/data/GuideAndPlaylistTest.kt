package com.stbplay.app.data

import com.stbplay.app.model.MediaKind
import org.junit.Assert.*
import org.junit.Test

class GuideAndPlaylistTest {
    @Test fun xmltvScopesProgramsAndHandlesOffsetsAndInvalidEntries() {
        val xml = """<tv><programme channel="news" start="20261013100000 +0530" stop="20261013110000 +0530"><title>News &amp; Weather</title><desc>Details</desc></programme><programme channel="news" start="bad" stop="bad"><title>Invalid</title></programme><programme channel="news" start="20261013120000 +0000" stop="20261013110000 +0000"><title>Reversed</title></programme></tv>"""
        val programs = XmlTvParser.parse(xml.byteInputStream(), 42)
        assertEquals(1, programs.size)
        assertEquals(42L, programs.single().playlistId)
        assertEquals("News & Weather", programs.single().title)
        assertEquals("Details", programs.single().description)
        assertEquals(XmlTvParser.parseDate("20261013043000 +0000"), programs.single().startMillis)
        assertEquals(3_600_000L, programs.single().endMillis - programs.single().startMillis)
        assertNull(XmlTvParser.parseDate("20260230100000 +0000"))
    }
    @Test fun missingTimezoneUsesUtcAndPartialTimestampsAreAccepted() {
        assertEquals(XmlTvParser.parseDate("20261013000000 +0000"), XmlTvParser.parseDate("20261013"))
        assertEquals(XmlTvParser.parseDate("20261013123000 +0000"), XmlTvParser.parseDate("202610131230"))
    }
    @Test fun m3uRetainsGuideAndSeparatesPlayableCatalogs() {
        val result = M3uParser.parse("""#EXTM3U x-tvg-url="guide.xml"
#EXTINF:-1 tvg-id="news" group-title="Live",News
live.m3u8
#EXTINF:-1 group-title="Movies",Film
film.mp4
#EXTINF:-1 group-title="Series",Episode
show.mp4
""", "https://provider.invalid/list.m3u")
        assertEquals("guide.xml", result.epgUrl)
        assertEquals(listOf(MediaKind.LIVE, MediaKind.MOVIE, MediaKind.EPISODE), result.items.map { it.kind })
        assertEquals("https://provider.invalid/live.m3u8", result.items.first().streamUrl)
        assertEquals("news", result.items.first().epgId)
    }
}
