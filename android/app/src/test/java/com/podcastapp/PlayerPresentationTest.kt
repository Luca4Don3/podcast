package com.podcastapp.ui.player

import com.podcastapp.data.remote.EpisodeDto
import com.podcastapp.data.remote.StationDto
import com.podcastapp.playback.NowPlaying
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerPresentationTest {
    @Test fun firstStationPlaybackGetsVisibleTitleAndLiveUiState() {
        val state = PlayerUiState().withNowPlaying(NowPlaying(
            station = StationDto(id = "s1", name = "我的音源", favicon = "https://audio.example.test/cover.png"),
        ))
        assertTrue(state.isLive)
        assertEquals("我的音源", state.title)
        assertEquals("直播音源", state.podcastTitle)
        assertEquals("https://audio.example.test/cover.png", state.artworkUrl)
    }

    @Test fun switchingFromPodcastToStationClearsStaleProgressAndError() {
        val state = PlayerUiState(title = "旧节目", positionMs = 88_000L, durationMs = 120_000L,
            speed = 1.5f, error = "旧错误").withNowPlaying(NowPlaying(station = StationDto(id = "s1", name = "直播")))
        assertTrue(state.isLive)
        assertEquals(0L, state.positionMs)
        assertEquals(0L, state.durationMs)
        assertEquals(1f, state.speed)
        assertNull(state.error)
    }

    @Test fun switchingBackToPodcastRestoresNonLiveStateWithoutStalePosition() {
        val state = PlayerUiState(isLive = true, title = "直播").withNowPlaying(
            NowPlaying(episode = EpisodeDto(id = "e1", title = "节目"), podcastTitle = "播客"),
        )
        assertFalse(state.isLive)
        assertEquals("节目", state.title)
        assertEquals("播客", state.podcastTitle)
        assertEquals(0L, state.positionMs)
        assertEquals(0L, state.durationMs)
    }

    @Test fun samePodcastMetadataUpdateKeepsPositionButLiveNeverDoes() {
        val previous = PlayerUiState(positionMs = 20_000, durationMs = 60_000)
        val podcast = previous.withNowPlaying(NowPlaying(episode = EpisodeDto(id = "e1")), resetPosition = false)
        assertEquals(20_000L, podcast.positionMs)
        val live = previous.withNowPlaying(NowPlaying(station = StationDto(id = "s1")), resetPosition = false)
        assertEquals(0L, live.positionMs)
        assertEquals("我的音源", live.title)
    }
}
