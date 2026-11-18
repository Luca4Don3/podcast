package com.podcastapp.data.remote

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class RegisterRequest(
    val email: String,
    val password: String,
    val displayName: String = "",
    val inviteCode: String = "",
    val deviceId: String = "",
)

@Serializable
data class LoginRequest(
    val email: String,
    val password: String,
    val deviceId: String = "",
)

@Serializable
data class RefreshRequest(
    val refreshToken: String,
    val deviceId: String = "",
)

@Serializable
data class LogoutRequest(
    val refreshToken: String = "",
    val allDevices: Boolean = false,
)

@Serializable
data class UserDto(
    val id: String = "",
    val email: String = "",
    val displayName: String = "",
    val role: String = "user",
    val createdAt: String = "",
)

@Serializable
data class TokenPairDto(
    val accessToken: String,
    val refreshToken: String,
    val expiresIn: Long = 0,
    val tokenType: String = "Bearer",
)

@Serializable
data class AuthResponse(
    val user: UserDto,
    val token: TokenPairDto,
)

@Serializable
data class UserEnvelope(val user: UserDto)

@Serializable
data class StatusResponse(val status: String = "ok")

@Serializable
data class PodcastDto(
    val id: String,
    val feedUrl: String = "",
    val title: String = "",
    val author: String = "",
    val description: String = "",
    val imageUrl: String = "",
    val link: String = "",
    val language: String = "",
    val category: String = "",
    val explicit: Boolean = false,
    val lastFetchedAt: String = "",
    val createdAt: String = "",
    val updatedAt: String = "",
    val subscribed: Boolean = false,
    val subscribedAt: String? = null,
)

@Serializable
data class PodcastList(
    val items: List<PodcastDto> = emptyList(),
    val total: Int = 0,
    val limit: Int = 0,
    val offset: Int = 0,
)

@Serializable
data class PodcastEnvelope(val podcast: PodcastDto)

@Serializable
data class AddPodcastRequest(val feedUrl: String)

@Serializable
data class SubscribeRequest(val subscribed: Boolean = true, val notify: Boolean = true)

@Serializable
data class EpisodeDto(
    val id: String,
    val podcastId: String = "",
    val guid: String = "",
    val title: String = "",
    val description: String = "",
    val audioUrl: String = "",
    val durationSeconds: Int = 0,
    val publishedAt: String = "",
    val imageUrl: String = "",
    val episodeNumber: Int = 0,
    val seasonNumber: Int = 0,
)

@Serializable
data class EpisodeList(
    val items: List<EpisodeDto> = emptyList(),
    val total: Int = 0,
    val limit: Int = 0,
    val offset: Int = 0,
)

@Serializable
data class EpisodeEnvelope(val episode: EpisodeDto, val podcast: PodcastDto? = null)

@Serializable
data class ProgressDto(
    val id: String = "",
    val episodeId: String = "",
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val completed: Boolean = false,
    val playedAt: String = "",
    val updatedAt: String = "",
)

@Serializable
data class ProgressList(
    val items: List<ProgressDto> = emptyList(),
    val episodes: List<EpisodeDto> = emptyList(),
    val total: Int = 0,
)

@Serializable
data class ProgressRequest(
    val positionMs: Long,
    val durationMs: Long,
    val completed: Boolean = false,
    val playedAt: String = "",
)

@Serializable
data class ActionDto(
    val episodeId: String,
    val starred: Boolean = false,
    val queued: Boolean = false,
    val downloaded: Boolean = false,
    val updatedAt: String = "",
)

@Serializable
data class ActionList(
    val items: List<ActionDto> = emptyList(),
    val episodes: List<EpisodeDto> = emptyList(),
)

@Serializable
data class ActionRequest(
    val starred: Boolean? = null,
    val queued: Boolean? = null,
    val downloaded: Boolean? = null,
)

@Serializable
data class PlaylistDto(
    val id: String,
    val title: String = "",
    val description: String = "",
    val itemCount: Int = 0,
    val createdAt: String = "",
    val updatedAt: String = "",
)

@Serializable
data class PlaylistList(val items: List<PlaylistDto> = emptyList())

@Serializable
data class PlaylistDetail(
    val playlist: PlaylistDto,
    val episodes: List<EpisodeDto> = emptyList(),
)

@Serializable
data class CreatePlaylistRequest(val title: String, val description: String = "")

@Serializable
data class DiscoverItemDto(
    val name: String = "",
    val artist: String = "",
    val feedUrl: String = "",
    val artworkUrl: String = "",
    val genre: String = "",
    val episodeCount: Int = 0,
    val country: String = "",
)

@Serializable
data class DiscoverList(val items: List<DiscoverItemDto> = emptyList())

@Serializable
data class SettingsDto(
    val playbackSpeed: Double = 1.0,
    val skipForward: Int = 30,
    val skipBack: Int = 15,
    val autoDownload: Boolean = false,
    val downloadPolicy: String = "wifi",
    val theme: String = "system",
    val notificationOn: Boolean = true,
)

@Serializable
data class SyncItem(
    val episodeId: String,
    val positionMs: Long,
    val durationMs: Long,
    val completed: Boolean = false,
    val playedAt: String = "",
    val starred: Boolean? = null,
    val queued: Boolean? = null,
    val downloaded: Boolean? = null,
)

@Serializable
data class SyncRequest(val items: List<SyncItem> = emptyList())

@Serializable
data class SyncSnapshot(
    val subscriptions: List<PodcastDto> = emptyList(),
    val progress: List<ProgressDto> = emptyList(),
    val actions: List<ActionDto> = emptyList(),
    val playlists: List<PlaylistDto> = emptyList(),
    val settings: SettingsDto? = null,
    val serverTime: String = "",
)

@Serializable
data class ApiErrorBody(val code: String = "", val message: String = "")

@Serializable
data class UpdateProfileRequest(val displayName: String)

@Serializable
data class ChangePasswordRequest(val currentPassword: String, val newPassword: String)
