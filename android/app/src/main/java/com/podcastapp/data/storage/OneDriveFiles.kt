package com.podcastapp.data.storage

import java.util.Locale
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

internal data class OneDriveItem(val id: String, val name: String, val folder: Boolean, val mime: String?)
internal class OneDrivePageCursor(val url: HttpUrl)
internal data class OneDrivePage(val items: List<OneDriveItem>, val next: OneDrivePageCursor?)

private val graphChildrenPath = Regex("/v1\\.0/(me/drive|drives/[^/]+|users/[^/]+/drive)/(root|items/[^/]+)/children")

internal fun HttpUrl.isOneDriveChildrenUrl() =
    isPublicHttps() && host == oneDriveGraph.host && graphChildrenPath.matches(encodedPath)

private val audioExtensions = setOf("mp3", "m4a", "m4b", "aac", "flac", "ogg", "oga", "opus", "wav", "amr", "ac3", "ec3", "aiff", "aif")

internal fun parseOneDrivePage(json: JsonObject): OneDrivePage {
    val values = json["value"] as? JsonArray ?: throw OneDriveError("OneDrive 返回了无效的目录。")
    val items = values.mapNotNull { item ->
        val entry = item as? JsonObject ?: return@mapNotNull null
        val id = entry.string("id")?.takeIf(String::isNotBlank) ?: return@mapNotNull null
        val name = entry.string("name")?.takeIf(String::isNotBlank) ?: return@mapNotNull null
        val folder = entry["folder"] is JsonObject
        val file = entry["file"] as? JsonObject
        val mime = file?.string("mimeType")?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT)
        val extension = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        if (!folder && (file == null || (mime?.startsWith("audio/") != true && extension !in audioExtensions))) {
            return@mapNotNull null
        }
        OneDriveItem(id, name, folder, mime?.takeIf { it.startsWith("audio/") })
    }
    val next = json.string("@odata.nextLink")?.let { link ->
        val nextUrl = link.toHttpUrlOrNull()?.takeIf { it.isOneDriveChildrenUrl() }
            ?: throw OneDriveError("OneDrive 目录分页地址无效。")
        OneDrivePageCursor(nextUrl)
    }
    return OneDrivePage(items.sortedByDescending { it.folder }, next)
}
