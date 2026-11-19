package com.podcastapp.data.storage

import java.util.Locale
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

internal data class SmbLocation private constructor(val host: String, val port: Int, val share: String, val url: String) {
    companion object {
        fun parse(value: String): SmbLocation {
            val raw = value.trim()
            val message = "请填写 smb://主机/共享名（可选端口），不要在地址中填写凭据；子目录另填。"
            require(raw.startsWith("smb://", ignoreCase = true) && raw.none { it.isISOControl() || it in "\\?#" }) { message }
            val authority = raw.substring(6).substringBefore('/')
            require(authority.isNotBlank() && authority.none { it == '@' || it == '%' || it.isWhitespace() } && !authority.endsWith(':')) { message }
            val path = raw.substring(6).substringAfter('/', "").removeSuffix("/")
            require('/' !in path && path.isNotEmpty()) { message }
            // OkHttp is used only for parsing/escaping, never for establishing the SMB connection.
            val http = ("http://" + raw.substring(6)).toHttpUrlOrNull() ?: throw IllegalArgumentException(message)
            val share = http.pathSegments.first()
            require(validSmbSegment(share) && !share.equals("IPC$", true)) { message }
            val port = if (authority.startsWith('[')) authority.substringAfter(']').takeIf { it.startsWith(':') } else authority.substringAfter(':', "").takeIf { it.isNotEmpty() }
            val smbPort = if (port == null) 445 else http.port
            val host = if (':' in http.host) "[${http.host}]" else http.host
            val endpoint = if (smbPort == 445) host else "$host:$smbPort"
            return SmbLocation(http.host, smbPort, share, "smb://$endpoint/${http.encodedPathSegments.first()}")
        }
    }
}

internal class SmbCredentials(val username: String, val password: String, val domain: String = "") {
    fun validate() {
        require(username.isNotBlank() && (username + domain).none { it.isISOControl() || it == '\\' }) {
            "请填写 SMB 用户名；Windows 域或工作组可单独填写。"
        }
    }
}

internal fun validSmbSegment(name: String): Boolean = name.isNotBlank() && !name.endsWith('.') && !name.last().isWhitespace() &&
    name.none { it.isISOControl() || it in "\\/:*?\"<>|" }

internal fun normalizeSmbRoot(value: String): String = value.trim().replace('\\', '/').trim('/').also {
    smbPathSegments(it)
}

internal fun smbPathSegments(path: String): List<String> = if (path.isEmpty()) emptyList() else path.split('/').also {
    require(it.all(::validSmbSegment)) { "SMB 路径含无效的目录或文件名。" }
}

internal fun smbAudioMime(name: String): String? = when (name.substringAfterLast('.', "").lowercase(Locale.ROOT)) {
    "mp3" -> "audio/mpeg"
    "m4a", "m4b" -> "audio/mp4"
    "aac" -> "audio/aac"
    "ogg", "oga", "opus" -> "audio/ogg"
    "wav", "wave" -> "audio/wav"
    "flac" -> "audio/flac"
    else -> null
}
