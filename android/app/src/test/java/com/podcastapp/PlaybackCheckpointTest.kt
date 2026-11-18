package com.podcastapp.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test

class PlaybackCheckpointTest {
    @Test fun idlePausedAndLivePlaybackDoNotScheduleAPeriodicTick() {
        assertNull(playbackTickDelayMs(playingPodcast = false, hasUi = true, untilSyncMs = 5_000))
        assertNull(playbackTickDelayMs(playingPodcast = false, hasUi = false, untilSyncMs = 5_000))
    }

    @Test fun foregroundUsesOneSecondAndBackgroundWaitsForSync() {
        assertEquals(1_000L, playbackTickDelayMs(true, true, 5_000))
        assertEquals(5_000L, playbackTickDelayMs(true, false, 5_000))
        // Subscribing or unsubscribing must not postpone the existing sync deadline.
        assertEquals(250L, playbackTickDelayMs(true, true, 250))
        assertEquals(250L, playbackTickDelayMs(true, false, 250))
        assertEquals(0L, playbackTickDelayMs(true, false, -1))
    }

    @Test fun stationsAndUnknownDurationsHaveNoPodcastCheckpoint() {
        assertNull(progressCheckpoint("station:radio", 4_000, 10_000, false))
        assertNull(progressCheckpoint(null, 4_000, 10_000, false))
        assertNull(progressCheckpoint("podcast", 4_000, 0, false))
    }

    @Test fun completionSavesOldEpisodeAtEndAndBackwardSeekCanSaveZero() {
        assertEquals(ProgressCheckpoint("old", 10_000, 10_000, true), progressCheckpoint("old", 9_995, 10_000, true))
        assertEquals(ProgressCheckpoint("old", 0, 10_000, false), progressCheckpoint("old", 0, 10_000, false))
    }

    @Test fun leavingEndedEpisodeDoesNotUndoCompletion() {
        val completed = ProgressCheckpoint("old", 10_000, 10_000, true)
        val onTransition = completed.copy(completed = false)
        assertFalse(shouldReplaceCheckpoint(completed, onTransition, explicitSeek = false))
        assertTrue(shouldReplaceCheckpoint(completed, onTransition, explicitSeek = true))
        assertTrue(shouldReplaceCheckpoint(completed, onTransition.copy(positionMs = 0), explicitSeek = true))
    }
}
