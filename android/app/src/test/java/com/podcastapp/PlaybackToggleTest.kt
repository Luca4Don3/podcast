package com.podcastapp

import androidx.media3.common.Player
import com.podcastapp.playback.PlaybackToggle
import com.podcastapp.playback.playbackToggle
import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackToggleTest {
    @Test fun idlePlaybackPreparesEvenWhenFailedPlayerRetainsPlayIntent() {
        assertEquals(PlaybackToggle.PREPARE_AND_PLAY, playbackToggle(Player.STATE_IDLE, true))
        assertEquals(PlaybackToggle.PREPARE_AND_PLAY, playbackToggle(Player.STATE_IDLE, false))
    }

    @Test fun bufferingToggleUsesPlayIntentInsteadOfIsPlaying() {
        assertEquals(PlaybackToggle.PAUSE, playbackToggle(Player.STATE_BUFFERING, true))
        assertEquals(PlaybackToggle.PLAY, playbackToggle(Player.STATE_BUFFERING, false))
    }

    @Test fun endedPlaybackRestartsInsteadOfPausing() {
        assertEquals(PlaybackToggle.RESTART_AND_PLAY, playbackToggle(Player.STATE_ENDED, true))
        assertEquals(PlaybackToggle.RESTART_AND_PLAY, playbackToggle(Player.STATE_ENDED, false))
    }

    @Test fun readyPlaybackTogglesNormally() {
        assertEquals(PlaybackToggle.PAUSE, playbackToggle(Player.STATE_READY, true))
        assertEquals(PlaybackToggle.PLAY, playbackToggle(Player.STATE_READY, false))
    }
}
