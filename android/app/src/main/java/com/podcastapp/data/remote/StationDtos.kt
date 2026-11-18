package com.podcastapp.data.remote

import kotlinx.serialization.Serializable

/** 网络电台（直播音频流）。 */
@Serializable
data class StationDto(
    val id: String,
    val name: String = "",
    val streamUrl: String = "",
    val homepage: String = "",
    val favicon: String = "",
    val genre: String = "",
    val country: String = "",
    val language: String = "",
    val codec: String = "",
    val bitrate: Int = 0,
    val isHls: Boolean = false,
    val source: String = "manual",
    val externalId: String = "",
    val favorite: Boolean = false,
    val createdAt: String = "",
    val updatedAt: String = "",
)

@Serializable
data class StationList(
    val items: List<StationDto> = emptyList(),
    val total: Int = 0,
)

@Serializable
data class StationEnvelope(val station: StationDto)

@Serializable
data class AddStationRequest(
    val name: String = "",
    val streamUrl: String,
    val homepage: String = "",
    val favicon: String = "",
    val genre: String = "",
    val country: String = "",
    val language: String = "",
    val codec: String = "",
    val bitrate: Int = 0,
    val externalId: String = "",
)

@Serializable
data class FavoriteRequest(val favorite: Boolean = true)

@Serializable
data class FavoriteResponse(val favorite: Boolean = false)

/** 目录（Radio-Browser 等）返回的候选电台。 */
@Serializable
data class StationCandidateDto(
    val name: String = "",
    val streamUrl: String = "",
    val homepage: String = "",
    val favicon: String = "",
    val genre: String = "",
    val country: String = "",
    val language: String = "",
    val codec: String = "",
    val bitrate: Int = 0,
    val isHls: Boolean = false,
    val externalId: String = "",
    val votes: Int = 0,
)

@Serializable
data class StationCandidateList(
    val items: List<StationCandidateDto> = emptyList(),
    val total: Int = 0,
)

@Serializable
data class ImportStationsRequest(val stations: List<AddStationRequest>)

@Serializable
data class ImportStationsResponse(
    val imported: Int = 0,
    val items: List<StationDto> = emptyList(),
)

/** 流探测结果。 */
@Serializable
data class StreamProbeDto(
    val reachable: Boolean = false,
    val statusCode: Int = 0,
    val contentType: String = "",
    val isHls: Boolean = false,
    val finalUrl: String = "",
    val latencyMs: Long = 0,
    val error: String = "",
)
