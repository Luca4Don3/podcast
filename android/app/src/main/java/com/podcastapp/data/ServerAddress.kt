package com.podcastapp.data

import okhttp3.HttpUrl.Companion.toHttpUrl

/** A blank override is distinct from a server URL. */
object ServerAddress {
    fun optional(raw: String): String? {
        val input = raw.trim()
        if (input.isEmpty()) return null
        val explicit = if (input.contains("://")) input else "https://" + input
        require('?' !in explicit && '#' !in explicit) { "服务器地址不能带查询参数或片段" }
        val url = HttpSourceUrl.parse(explicit).toHttpUrl()
        // A bare domain uses the bundled backend's normal API route. Explicit API paths keep
        // working for existing configurations and reverse proxies.
        val path = url.encodedPath.trimEnd('/')
        return url.newBuilder().encodedPath(if (path.isEmpty()) "/api/v1/" else "$path/").build().toString()
    }

    fun effective(custom: String?, default: String?): String = (custom ?: default).orEmpty()
}
