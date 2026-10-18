package com.stbplay.app.data

object UserErrors {
    /** Fixed diagnostic tags; never include commands, credentials or signed URLs. */
    fun playbackResolutionTag(error: Throwable): String {
        val message = error.message.orEmpty()
        return when {
            message.startsWith("Provider returned nothing_to_play") -> "NO_PLAYBACK_LINK"
            message == "Provider returned no playable file for this movie" -> "NO_MOVIE_FILE"
            message == "Provider did not return a playable stream link" -> "EMPTY_PLAYBACK_LINK"
            message == "Provider returned an unsupported playback command" -> "UNSUPPORTED_PORTAL_COMMAND"
            message == "Provider returned an unresolved localhost playback link" -> "UNRESOLVED_LOCAL_LINK"
            message.startsWith("Portal rejected create_link after authentication") -> "PORTAL_REJECTED_LINK"
            Regex("^Stalker Portal returned HTTP [0-9]{3}$").matches(message) -> "PORTAL_HTTP_" + message.takeLast(3)
            else -> "LINK_RESOLUTION_FAILED"
        }
    }

    fun playbackResolution(error: Throwable): String = when (playbackResolutionTag(error)) {
        "NO_PLAYBACK_LINK", "EMPTY_PLAYBACK_LINK" -> "The portal returned no playable link for this title. Try refreshing your source."
        "NO_MOVIE_FILE" -> "The portal returned no playable file for this title."
        "UNSUPPORTED_PORTAL_COMMAND" -> "The portal returned a playback command this player cannot use."
        "UNRESOLVED_LOCAL_LINK" -> "The portal returned an unresolved local stream address."
        "PORTAL_REJECTED_LINK" -> "The portal rejected the playback-link request for this title."
        else -> if (playbackResolutionTag(error).startsWith("PORTAL_HTTP_"))
            "The portal could not create a playback link (HTTP ${playbackResolutionTag(error).takeLast(3)}). Try again later."
        else connection(error)
    }

    fun connection(error: Throwable): String = when (error) {
        is ProviderRateLimitException -> "Your provider is receiving too many requests. Wait a minute before retrying."
        is java.net.UnknownHostException -> "The server could not be found. Check its URL and your internet connection."
        is java.net.SocketTimeoutException -> "The server took too long to respond. Check your connection and retry."
        is java.net.ConnectException -> "The server is not reachable. Check the URL and your connection, then retry."
        else -> "The provider could not complete this request. Check the URL and account details, then retry."
    }
}
