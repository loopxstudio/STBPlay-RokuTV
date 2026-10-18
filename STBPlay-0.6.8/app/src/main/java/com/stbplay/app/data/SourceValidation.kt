package com.stbplay.app.data

import com.stbplay.app.SourceProtocol
import java.net.URI

object SourceValidation {
    fun validUrl(value: String): Boolean = runCatching {
        val uri = URI(value.trim())
        uri.scheme?.lowercase() in listOf("http", "https") && !uri.host.isNullOrBlank()
    }.getOrDefault(false)

    fun errors(protocol: SourceProtocol, portal: String, mac: String, server: String, username: String, password: String, playlist: String): Map<String, String> = buildMap {
        when (protocol) {
            SourceProtocol.STALKER -> {
                if (!validUrl(portal)) put("portal", "Enter a complete http:// or https:// portal URL.")
                if (!Regex("(?i)[0-9a-f]{2}(:[0-9a-f]{2}){5}").matches(mac.trim())) put("mac", "Use six pairs, for example 00:1A:79:12:34:56.")
            }
            SourceProtocol.XTREAM -> {
                if (!validUrl(server)) put("server", "Enter a complete http:// or https:// server URL.")
                if (username.isBlank()) put("username", "Enter your username.")
                if (password.isBlank()) put("password", "Enter your password.")
            }
            SourceProtocol.M3U -> if (!validUrl(playlist)) put("playlist", "Enter a complete http:// or https:// playlist URL.")
        }
    }
}
