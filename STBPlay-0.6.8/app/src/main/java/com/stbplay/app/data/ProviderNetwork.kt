package com.stbplay.app.data

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** HTTP compatibility is retained, but an HTTPS request cannot silently downgrade. */
object ProviderNetwork {
    fun open(address: String, readTimeout: Int = 25000, configure: (HttpURLConnection) -> Unit = {}): HttpURLConnection {
        var current = URL(address)
        require(current.protocol.lowercase() in listOf("http", "https")) { "Use an HTTP(S) URL" }
        val original = current
        repeat(6) {
            val connection = current.openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = 12000; connection.readTimeout = readTimeout
                connection.instanceFollowRedirects = false
                configure(connection)
                if (current.authority != original.authority) {
                    connection.setRequestProperty("Authorization", "")
                    connection.setRequestProperty("Cookie", "")
                }
                val status = connection.responseCode
                if (status !in listOf(301, 302, 303, 307, 308)) return connection
                val target = connection.getHeaderField("Location") ?: throw IOException("Missing redirect location")
                val next = URL(current, target)
                if (next.protocol.lowercase() !in listOf("http", "https")) throw IOException("Unsupported redirect")
                if (current.protocol == "https" && next.protocol != "https") throw IOException("HTTPS downgrade refused")
                connection.disconnect()
                current = next
            } catch (e: Exception) { connection.disconnect(); throw e }
        }
        throw IOException("Too many provider redirects")
    }
}
