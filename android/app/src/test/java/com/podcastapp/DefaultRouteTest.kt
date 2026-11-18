package com.podcastapp.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DefaultRouteTest {
    @Test fun lateWifiLossDoesNotMarkReplacementRouteOffline() {
        val state = DefaultRouteState<String>()
        state.seed("Wi-Fi")
        state.available("VPN")
        state.lost("Wi-Fi")
        assertEquals("VPN", state.current)
        state.lost("VPN")
        assertNull(state.current)
    }

    @Test fun lateInitialSnapshotDoesNotOverrideCallback() {
        val state = DefaultRouteState<String>()
        state.available("VPN")
        state.seed("Wi-Fi")
        assertEquals("VPN", state.current)
        state.lost("VPN")
        state.seed("VPN")
        assertNull(state.current)
    }
}
