package com.stbplay.app.data

object AudioDiagnostics {
    fun messages(ready: Boolean, trackCount: Int, supportedCount: Int, selectedCount: Int, playerVolume: Int, deviceMuted: Boolean): List<String> = buildList {
        if (playerVolume == 0) add("Player is muted. Increase the player volume.")
        if (deviceMuted) add("Device media volume is muted. Check the TV, simulator or soundbar volume.")
        when {
            !ready && trackCount == 0 -> add("Audio information is available after the stream loads.")
            trackCount == 0 -> add("This stream reports no audio track. Try another title or check with your provider.")
            supportedCount == 0 -> add("Audio tracks were found, but this device cannot play their formats. Try another version of the movie or a compatible device.")
            selectedCount == 0 -> add("No audio track is selected. Choose Automatic audio or a supported track.")
            else -> add("An audio track is selected. If it is still silent, try another track and check the device's audio output.")
        }
    }
}
