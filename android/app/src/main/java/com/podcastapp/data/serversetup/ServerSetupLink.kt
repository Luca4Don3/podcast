package com.podcastapp.data.serversetup

import com.podcastapp.data.ServerAddress
import java.net.URI
import java.net.URISyntaxException
import java.nio.charset.CharacterCodingException

/**
 * A server address, optionally carrying a login pair in one explicit HTTPS paste format:
 *
 * - https://email%40example.com:encoded-password@host/api/v1/
 * - https://host/api/v1/#username=email%40example.com&password=encoded
 *
 * The fragment is not form data: a literal '+' stays '+'. Escape delimiters that are
 * part of a credential (e.g. '%26' for '&' in a fragment value). Credentials use UTF-8
 * percent escapes decoded once; controls, whitespace and backslashes are not permitted.
 */
internal class ServerSetupLink private constructor(raw: String) {
    val baseUrl: String
    val email: String?
    val password: String?

    init {
        if (raw.length > MAX_INPUT_LENGTH || raw.any { it.isISOControl() }) invalid()
        val input = raw.trim()
        if (input.isEmpty() || input.any { it.isWhitespace() || it == BACKSLASH }) invalid()

        val uri = try {
            URI(if (input.contains("://")) input else "https://$input")
        } catch (_: URISyntaxException) {
            invalid()
        }
        val authority = uri.rawAuthority ?: invalid()
        if (uri.host == null || uri.rawQuery != null) invalid()
        val userInfo = uri.rawUserInfo
        val fragment = uri.rawFragment
        if (userInfo == null && fragment == null) {
            baseUrl = normalize(input)
            email = null
            password = null
        } else {
            if (!input.startsWith("https://", ignoreCase = true) || (userInfo != null && fragment != null)) invalid()
            if (userInfo != null) {
                if (authority.count { it == '@' } != 1) invalid()
                email = decodeCredential(userInfo.substringBefore(':'))
                password = decodeCredential(userInfo.substringAfter(':', missingDelimiterValue = ""))
            } else {
                val fields = fragment.orEmpty().split('&', limit = 3)
                if (fields.size != 2) invalid()
                var username: String? = null
                var secret: String? = null
                for (field in fields) {
                    val value = field.substringAfter('=', missingDelimiterValue = "")
                    when (field.substringBefore('=')) {
                        "username" -> if (username == null) username = value else invalid()
                        "password" -> if (secret == null) secret = value else invalid()
                        else -> invalid()
                    }
                }
                email = decodeCredential(username ?: invalid())
                password = decodeCredential(secret ?: invalid())
            }
            val at = email.indexOf('@')
            if (at <= 0 || at >= email.lastIndex || at != email.lastIndexOf('@')) invalid()
            // Remove both credential carriers before delegating to the plain address parser.
            val hostAndPort = authority.substringAfter('@')
            baseUrl = normalize("https://$hostAndPort${uri.rawPath}")
        }
    }

    override fun toString(): String = "ServerSetupLink"

    companion object {
        private const val MAX_INPUT_LENGTH = 4096
        private const val BACKSLASH = '\\'
        private const val INVALID_LINK = "服务器设置链接无效"

        fun parse(raw: String): ServerSetupLink = ServerSetupLink(raw)

        private fun normalize(address: String): String = try {
            ServerAddress.optional(address) ?: invalid()
        } catch (_: IllegalArgumentException) {
            invalid()
        }

        private fun decodeCredential(encoded: String): String {
            val decoded = try {
                val bytes = encoded.encodeToByteArray(throwOnInvalidSequence = true)
                var read = 0
                var write = 0
                while (read < bytes.size) {
                    val byte = bytes[read++]
                    bytes[write++] = if (byte == '%'.code.toByte()) {
                        if (read + 1 >= bytes.size) invalid()
                        val high = bytes[read++].toInt().toChar().digitToIntOrNull(16) ?: invalid()
                        val low = bytes[read++].toInt().toChar().digitToIntOrNull(16) ?: invalid()
                        ((high shl 4) or low).toByte()
                    } else {
                        byte
                    }
                }
                bytes.decodeToString(endIndex = write, throwOnInvalidSequence = true)
            } catch (_: CharacterCodingException) {
                invalid()
            }
            if (decoded.isEmpty() || decoded.any { it.isISOControl() || it.isWhitespace() || it == BACKSLASH }) invalid()
            return decoded
        }

        private fun invalid(): Nothing = throw IllegalArgumentException(INVALID_LINK)
    }
}
