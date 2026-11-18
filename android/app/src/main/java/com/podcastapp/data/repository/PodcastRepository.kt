package com.podcastapp.data.repository

import com.podcastapp.data.ServiceLocator
import com.podcastapp.data.remote.AddPodcastRequest
import com.podcastapp.data.remote.DiscoverItemDto
import com.podcastapp.data.remote.EpisodeDto
import com.podcastapp.data.remote.PodcastDto

class PodcastRepository(private val locator: ServiceLocator) {

    suspend fun discover(query: String): List<DiscoverItemDto> = apiCall {
        locator.requireClient().api.discover(query).items
    }

    suspend fun addByFeedUrl(feedUrl: String): PodcastDto = apiCall {
        locator.requireClient().api.addPodcast(AddPodcastRequest(feedUrl)).podcast
    }

    suspend fun library(query: String? = null): List<PodcastDto> = apiCall {
        locator.requireClient().api.podcasts(query = query, limit = 100).items
    }

    suspend fun podcast(id: String): PodcastDto = apiCall {
        locator.requireClient().api.podcast(id)
    }

    suspend fun refresh(id: String): PodcastDto = apiCall {
        locator.requireClient().api.refreshPodcast(id).podcast
    }

    suspend fun episodes(podcastId: String, limit: Int = 200): List<EpisodeDto> = apiCall {
        locator.requireClient().api.episodes(podcastId, limit = limit).items
    }

    suspend fun episode(id: String): EpisodeDto = apiCall {
        locator.requireClient().api.episode(id).episode
    }
}
