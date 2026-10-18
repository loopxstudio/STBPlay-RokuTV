package com.stbplay.app.data

/** Values persisted by name so future additions do not change existing choices. */
enum class AspectMode(val label: String, val ratio: Float? = null) {
    FIT("Fit"), CROP("Fill / crop"), STRETCH("Stretch"), WIDE("16:9", 16f / 9f), CLASSIC("4:3", 4f / 3f)
}

object PlayerOptions {
    fun volume(value: Int) = value.coerceIn(0, 100)
    fun subtitleMime(name: String): String? = when (name.substringAfterLast('.', "").lowercase()) {
        "srt" -> "application/x-subrip"
        "vtt" -> "text/vtt"
        "ass", "ssa" -> "text/x-ssa"
        else -> null
    }
    fun frame(width: Float, height: Float, ratio: Float): Pair<Float, Float> {
        val w = minOf(width, height * ratio)
        return w to w / ratio
    }
}
