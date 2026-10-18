package com.stbplay.app.data

object PlaybackProgress {
    fun resumePosition(position: Long, duration: Long): Long {
        if (position < 10_000) return 0
        if (duration > 0 && (position >= duration - 30_000 || position.toDouble() / duration >= .95)) return 0
        return position
    }
}
