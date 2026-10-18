package com.stbplay.app.data

import com.stbplay.app.SourceProtocol
import org.junit.Assert.*
import org.junit.Test

class UsabilityTest {
    @Test fun urlsRequireHttpHostAndRejectMalformedOrUnsupportedInput() {
        for (url in listOf("provider.example", "http://", "file:///tmp/list.m3u", "https://bad host/list", "")) assertFalse(SourceValidation.validUrl(url))
        assertTrue(SourceValidation.validUrl(" https://provider.example:8080/list.m3u?token=example "))
        assertTrue(SourceValidation.validUrl("http://127.0.0.1:9000/playlist"))
    }
    @Test fun errorsBelongToTheSelectedProtocolAndItsActualFields() {
        val stalker = SourceValidation.errors(SourceProtocol.STALKER, "http://portal.example/c/", "bad", "", "", "", "")
        assertEquals(setOf("mac"), stalker.keys)
        assertTrue(SourceValidation.errors(SourceProtocol.STALKER, "http://portal.example/c/", "00:1a:79:12:34:56", "", "", "", "").isEmpty())
        assertEquals(setOf("username", "password"), SourceValidation.errors(SourceProtocol.XTREAM, "", "", "https://server.example", "", "", "").keys)
        assertEquals(setOf("playlist"), SourceValidation.errors(SourceProtocol.M3U, "", "", "", "", "", "bad").keys)
    }
    @Test fun resumeSkipsBriefAndNearlyFinishedPlaybackButKeepsUnknownDuration() {
        assertEquals(0L, PlaybackProgress.resumePosition(9_999, 100_000))
        assertEquals(60_000L, PlaybackProgress.resumePosition(60_000, 600_000))
        assertEquals(0L, PlaybackProgress.resumePosition(580_000, 600_000))
        assertEquals(0L, PlaybackProgress.resumePosition(5_800_000, 6_000_000))
        assertEquals(60_000L, PlaybackProgress.resumePosition(60_000, 0))
        assertEquals(0L, PlaybackProgress.resumePosition(-1, 0))
    }
    @Test fun friendlyErrorsDoNotExposeProviderUrlsOrCredentials() {
        val message = UserErrors.connection(IllegalStateException("https://private.example?password=secret"))
        assertFalse(message.contains("secret"))
        assertFalse(message.contains("private.example"))
        assertTrue(UserErrors.connection(java.net.SocketTimeoutException()).contains("retry", true))
    }
}
