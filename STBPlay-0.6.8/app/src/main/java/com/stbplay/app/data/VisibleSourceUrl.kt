package com.stbplay.app.data

/** Display only; the original saved URL remains intact for editing/connection. */
object VisibleSourceUrl {
    fun mask(value: String): String = runCatching {
        val uri = java.net.URI(value.substringBefore('|'))
        val authority = uri.rawAuthority?.substringAfterLast('@') ?: return@runCatching "Saved source"
        val pieces = uri.rawPath.orEmpty().split('/').toMutableList()
        if (pieces.getOrNull(1) in listOf("live", "movie", "series") && pieces.size >= 5) {
            pieces[2] = "•••"; pieces[3] = "•••"
        }
        val query = uri.rawQuery?.split('&')?.joinToString("&") { part -> if ('=' in part) part.substringBefore('=') + "=•••" else part }
        "${uri.scheme}://$authority${pieces.joinToString("/")}" + (query?.let { "?$it" } ?: "")
    }.getOrDefault("Saved source")
}
