package com.stbplay.app.data

import org.junit.Assert.*
import org.junit.Test

class PlaybackResolutionErrorsTest {
    @Test fun missingPlaybackLinkIsNotReportedAsACodecOrAccountError() {
        val error = IllegalStateException("Provider returned nothing_to_play: this stream has no available playback link")
        assertEquals("NO_PLAYBACK_LINK", UserErrors.playbackResolutionTag(error))
        assertTrue(UserErrors.playbackResolution(error).contains("no playable link"))
        assertFalse(UserErrors.playbackResolution(error).contains("account"))
    }

    @Test fun diagnosticsDoNotExposeProviderUrlsOrCredentials() {
        val error = java.io.IOException("https://private.invalid/secret-user/secret-password/movie?token=secret-token")
        assertEquals("LINK_RESOLUTION_FAILED", UserErrors.playbackResolutionTag(error))
        assertFalse(UserErrors.playbackResolution(error).contains("secret"))
        val http = IllegalStateException("Stalker Portal returned HTTP 503")
        assertEquals("PORTAL_HTTP_503", UserErrors.playbackResolutionTag(http))
        assertTrue(UserErrors.playbackResolution(http).contains("503"))
    }
}
