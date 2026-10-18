package com.stbplay.app.data

import org.junit.Assert.*
import org.junit.Test

class VisibleSourceUrlTest {
    @Test fun plainPortalAddressIsPreserved() {
        assertEquals("http://provider.invalid:8080/c/", VisibleSourceUrl.mask("http://provider.invalid:8080/c/"))
    }
    @Test fun accountQueryHeadersAndUserInfoAreNotShown() {
        val shown = VisibleSourceUrl.mask("http://user:PRIVATE@provider.invalid/get.php?username=user&password=PRIVATE|Authorization=SECRET")
        assertEquals("http://provider.invalid/get.php?username=•••&password=•••", shown)
        assertFalse(shown.contains("PRIVATE")); assertFalse(shown.contains("SECRET"))
    }
    @Test fun generatedPlaybackCredentialsAreMasked() {
        assertEquals("https://provider.invalid/live/•••/•••/1.ts", VisibleSourceUrl.mask("https://provider.invalid/live/user/PRIVATE/1.ts"))
    }
}
