package com.stbplay.app.data

import com.stbplay.app.data.db.EpgProgramEntity
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

object XmlTvParser {
    fun parse(inputStream: InputStream, playlistId: Long = 0): List<EpgProgramEntity> = inputStream.use { stream ->
        val parser = XmlPullParserFactory.newInstance().newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(stream, null)
        val programs = mutableListOf<EpgProgramEntity>()
        var inProgram = false
        var channel = ""
        var start: Long? = null
        var end: Long? = null
        var title = ""
        var description: String? = null
        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "programme" -> {
                        inProgram = true
                        channel = parser.getAttributeValue(null, "channel").orEmpty()
                        start = parseDate(parser.getAttributeValue(null, "start"))
                        end = parseDate(parser.getAttributeValue(null, "stop"))
                        title = ""; description = null
                    }
                    "title" -> if (inProgram) { val value = parser.nextText(); if (title.isBlank()) title = value }
                    "desc" -> if (inProgram) { val value = parser.nextText(); if (description == null) description = value }
                }
            } else if (parser.eventType == XmlPullParser.END_TAG && parser.name == "programme") {
                val from = start; val to = end
                if (channel.isNotBlank() && title.isNotBlank() && from != null && to != null && to > from) {
                    programs += EpgProgramEntity(channelId = channel, title = title, description = description,
                        startMillis = from, endMillis = to, playlistId = playlistId)
                }
                inProgram = false
            }
            parser.next()
        }
        programs
    }

    internal fun parseDate(value: String?): Long? {
        val parts = value?.trim()?.split(Regex("\\s+")) ?: return null
        val digits = parts.firstOrNull() ?: return null
        val pattern = when (digits.length) {
            8 -> "yyyyMMdd"; 10 -> "yyyyMMddHH"; 12 -> "yyyyMMddHHmm"; 14 -> "yyyyMMddHHmmss"; else -> return null
        }
        val zone = parts.getOrNull(1) ?: "+0000"
        if (!digits.all { it.isDigit() } || !zone.matches(Regex("[+-][0-9]{4}"))) return null
        val format = SimpleDateFormat("$pattern Z", Locale.US).apply { isLenient = false; timeZone = TimeZone.getTimeZone("UTC") }
        return runCatching { format.parse("$digits $zone")?.time }.getOrNull()
    }
}
