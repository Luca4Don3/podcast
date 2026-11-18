package com.podcastapp.data.repository

import com.podcastapp.data.ServiceLocator
import com.podcastapp.data.remote.AddStationRequest
import com.podcastapp.data.remote.FavoriteRequest
import com.podcastapp.data.remote.ImportStationsRequest
import com.podcastapp.data.remote.ImportStationsResponse
import com.podcastapp.data.remote.StationCandidateDto
import com.podcastapp.data.remote.StationDto
import com.podcastapp.data.remote.StreamProbeDto

class StationRepository(private val locator: ServiceLocator) {

    suspend fun list(): List<StationDto> = apiCall {
        locator.requireClient().api.stations().items
    }

    suspend fun add(
        name: String,
        streamUrl: String,
        genre: String = "",
        country: String = "",
        codec: String = "",
        bitrate: Int = 0,
        favicon: String = "",
        homepage: String = "",
        externalId: String = "",
    ): StationDto = apiCall {
        locator.requireClient().api.addStation(
            AddStationRequest(
                name = name.trim(),
                streamUrl = streamUrl.trim(),
                genre = genre,
                country = country,
                codec = codec,
                bitrate = bitrate,
                favicon = favicon,
                homepage = homepage,
                externalId = externalId,
            ),
        ).station
    }

    suspend fun remove(id: String) = apiCall {
        locator.requireClient().api.deleteStation(id)
    }

    suspend fun setFavorite(id: String, favorite: Boolean) = apiCall {
        if (favorite) {
            locator.requireClient().api.favoriteStation(id, FavoriteRequest(true))
        } else {
            locator.requireClient().api.unfavoriteStation(id)
        }
    }

    /** 探测流是否可用：返回内容类型 / 是否 HLS / 延迟。 */
    suspend fun probe(streamUrl: String): StreamProbeDto = apiCall {
        locator.requireClient().api.probeStation(streamUrl.trim())
    }

    /** 从公开电台目录搜索候选。 */
    suspend fun discover(query: String = "", country: String = "", tag: String = ""): List<StationCandidateDto> = apiCall {
        locator.requireClient().api.discoverStations(
            query = query.ifBlank { null },
            country = country.ifBlank { null },
            tag = tag.ifBlank { null },
        ).items
    }

    /** 批量导入候选电台，返回成功条数。 */
    suspend fun import(candidates: List<StationCandidateDto>): Int = apiCall {
        locator.requireClient().api.importStations(
            ImportStationsRequest(stations = candidates.map { it.toRequest() }),
        ).imported
    }

    /** Import only user-pasted, validated sources through the existing batch API. */
    suspend fun importSources(stations: List<AddStationRequest>): ImportStationsResponse = apiCall {
        locator.requireClient().api.importStations(ImportStationsRequest(stations))
    }

    /** 逐个添加（用于目录里只点单个的情况）。 */
    suspend fun addCandidate(candidate: StationCandidateDto): StationDto = apiCall {
        locator.requireClient().api.addStation(candidate.toRequest()).station
    }
}

/** 候选 → 添加请求。 */
fun StationCandidateDto.toRequest(): AddStationRequest = AddStationRequest(
    name = name,
    streamUrl = streamUrl,
    homepage = homepage,
    favicon = favicon,
    genre = genre,
    country = country,
    language = language,
    codec = codec,
    bitrate = bitrate,
    externalId = externalId,
)
