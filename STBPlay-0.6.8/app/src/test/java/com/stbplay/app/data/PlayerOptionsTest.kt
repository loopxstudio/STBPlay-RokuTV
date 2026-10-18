package com.stbplay.app.data

import com.stbplay.app.model.MediaItem
import com.stbplay.app.model.MediaKind
import org.junit.Assert.*
import org.junit.Test

class PlayerOptionsTest {
    @Test fun importedSubtitleFormatsAreExplicit() {
        assertEquals("application/x-subrip", PlayerOptions.subtitleMime("Movie.EN.SRT"))
        assertEquals("text/vtt", PlayerOptions.subtitleMime("captions.vtt"))
        assertEquals("text/x-ssa", PlayerOptions.subtitleMime("movie.ass"))
        assertEquals("text/x-ssa", PlayerOptions.subtitleMime("movie.ssa"))
        assertNull(PlayerOptions.subtitleMime("movie.mp4"))
        assertNull(PlayerOptions.subtitleMime("captions"))
    }
    @Test fun playerVolumeCannotExceedBounds() {
        assertEquals(0, PlayerOptions.volume(-10))
        assertEquals(100, PlayerOptions.volume(110))
        assertEquals(40, PlayerOptions.volume(40))
    }
    @Test fun fixedAspectFitsWideAndTallPlayerAreas() {
        val wide = PlayerOptions.frame(1000f, 400f, 4f / 3f)
        assertEquals(400f, wide.second, 0.01f)
        assertEquals(4f / 3f, wide.first / wide.second, 0.001f)
        val tall = PlayerOptions.frame(400f, 1000f, 16f / 9f)
        assertEquals(400f, tall.first, 0.01f)
        assertEquals(16f / 9f, tall.first / tall.second, 0.001f)
    }
    @Test fun favoritesRetainPlaybackMetadataAndKindAcrossRestart() {
        val original = listOf(
            MediaItem("stalker-movie-123", "Saved movie", "", "English", kind = MediaKind.MOVIE, cmd = "ffmpeg /media/123.mpg"),
            MediaItem("channel-12", "News", "https://example.test/live.m3u8", "US", logo = "https://example.test/logo.png"),
            MediaItem("series-10", "Show", "", kind = MediaKind.SERIES),
            MediaItem("episode-3", "Episode", "https://example.test/3.mp4", kind = MediaKind.EPISODE)
        )
        assertEquals(original, FavoriteCodec.decode(FavoriteCodec.encode(original)))
        assertEquals(original, FavoriteCodec.decode(FavoriteCodec.encode(original + original)))
    }
    @Test fun damagedFavoriteEntryDoesNotHideOtherPrograms() {
        val valid = FavoriteCodec.encode(listOf(MediaItem("1", "News", "http://example.test")))
        assertEquals(1, FavoriteCodec.decode(valid.dropLast(1) + ",{}]").size)
        assertTrue(FavoriteCodec.decode("broken json").isEmpty())
    }
    @Test fun providerAccountsHaveSeparateFavoriteLibraries() {
        assertNotEquals(Preferences.sourceKey("STALKER", "http://example.test", "MAC1"), Preferences.sourceKey("STALKER", "http://example.test", "MAC2"))
        assertEquals(Preferences.sourceKey("STALKER", "http://example.test/c/", "MAC1"), Preferences.sourceKey("STALKER", "http://example.test/stalker_portal/server/load.php", "MAC1"))
        assertNotEquals(Preferences.sourceKey("M3U", "http://example.test/list?user=1", ""), Preferences.sourceKey("M3U", "http://example.test/list?user=2", ""))
    }
}
