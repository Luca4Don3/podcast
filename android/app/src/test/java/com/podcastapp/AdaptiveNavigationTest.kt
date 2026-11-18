package com.podcastapp.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptiveNavigationTest {
    @Test fun keepsBottomNavigationOnNarrowOrShortWindows() {
        assertFalse(useSideNavigation(320f, 640f))
        assertFalse(useSideNavigation(599f, 900f))
        assertFalse(useSideNavigation(900f, 319f))
    }

    @Test fun usesRailWhenBothDimensionsCanAccommodateIt() {
        assertTrue(useSideNavigation(600f, 320f))
        assertTrue(useSideNavigation(840f, 900f))
    }
}
