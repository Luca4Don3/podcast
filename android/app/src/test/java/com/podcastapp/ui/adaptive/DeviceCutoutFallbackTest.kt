package com.podcastapp.ui.adaptive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class DeviceCutoutFallbackTest {
    private val mi8 = checkNotNull(knownCutoutSpec("Xiaomi", " MI 8 ", "dipper"))
    private val honor = checkNotNull(knownCutoutSpec("HONOR", "BVL-N49", "HNBVL"))
    private val realme = checkNotNull(knownCutoutSpec("realme", "RMX3710", "REE2ADL1"))

    @Test
    fun identities_are_exact_and_case_insensitive() {
        assertEquals(89, mi8.topDepthPx)
        assertEquals(1080, mi8.panelWidthPx)
        assertEquals(2248, mi8.panelHeightPx)
        assertEquals(56, honor.reserveDp)
        assertEquals(48, realme.reserveDp)
        assertEquals(honor, knownCutoutSpec("honor", "bvl-an16", "hnbvl"))
        assertNull(knownCutoutSpec("Xiaomi", "MI 8", "other-device"))
        assertNull(knownCutoutSpec("Samsung", "SM-S918B", "dm3q"))
    }

    @Test
    fun notch_depth_is_scaled_and_never_added_to_platform_insets() {
        val safe = PxRect(0, 30, 1080, 2248)
        assertEquals(PxRect(0, 89, 1080, 2248), resolve(safe, 1080, 2248, spec = mi8))
        assertEquals(PxRect(0, 120, 1080, 2248), resolve(PxRect(0, 120, 1080, 2248), 1080, 2248, spec = mi8))
    }

    @Test
    fun notch_follows_the_physical_edge_selected_by_rotation() {
        assertEquals(
            PxRect(89, 0, 2248, 1080),
            resolve(PxRect(0, 0, 2248, 1080), 2248, 1080, spec = mi8, rotation = DisplayQuarterTurn.ROTATION_90),
        )
        assertEquals(
            PxRect(0, 0, 1080, 2159),
            resolve(PxRect(0, 0, 1080, 2248), 1080, 2248, spec = mi8, rotation = DisplayQuarterTurn.ROTATION_180),
        )
        assertEquals(
            PxRect(0, 0, 2159, 1080),
            resolve(PxRect(0, 0, 2248, 1080), 2248, 1080, spec = mi8, rotation = DisplayQuarterTurn.ROTATION_270),
        )
    }

    @Test
    fun centered_punch_hole_reserve_uses_dp_in_every_orientation() {
        assertEquals(
            PxRect(0, 168, 1280, 2800),
            resolve(PxRect(0, 72, 1280, 2800), 1280, 2800, density = 3f, spec = honor),
        )
        assertEquals(
            PxRect(168, 0, 2800, 1280),
            resolve(PxRect(0, 0, 2800, 1280), 2800, 1280, density = 3f, spec = honor,
                rotation = DisplayQuarterTurn.ROTATION_90),
        )
    }

    @Test
    fun fallback_is_disabled_without_full_native_display_evidence() {
        val safe = PxRect(0, 0, 1080, 2248)
        assertSame(safe, resolve(safe, 1080, 2248, spec = mi8, occupiesNativeDisplay = false))
        assertSame(safe, resolve(safe, 1080, 2248, spec = mi8, hasReportedCutout = true))
        assertSame(safe, resolve(safe, 1080, 2248, spec = mi8, hasFoldingFeatures = true))
        assertSame(safe, resolve(safe, 1080, 2248, spec = mi8, rotation = null))
        assertSame(safe, resolve(safe, 1080, 2248, spec = null))
        assertSame(safe, resolve(safe, 1000, 2248, spec = mi8))
        assertEquals(PxRect(0, 0, 0, 0), resolve(PxRect(0, 0, 0, 0), 1080, 2248, spec = mi8))
    }

    @Test
    fun implausible_depth_is_ignored() {
        val spec = CutoutFallbackSpec(1080, 2248, topDepthPx = 300)
        val safe = PxRect(0, 0, 1080, 2248)
        assertSame(safe, resolve(safe, 1080, 2248, spec = spec))
    }

    private fun resolve(
        safe: PxRect,
        width: Int,
        height: Int,
        density: Float = 1f,
        spec: CutoutFallbackSpec?,
        rotation: DisplayQuarterTurn? = DisplayQuarterTurn.NATURAL,
        occupiesNativeDisplay: Boolean = true,
        hasReportedCutout: Boolean = false,
        hasFoldingFeatures: Boolean = false,
    ) = resolveCutoutFallback(
        safe = safe,
        windowWidth = width,
        windowHeight = height,
        density = density,
        spec = spec,
        rotation = rotation,
        occupiesNativeDisplay = occupiesNativeDisplay,
        hasReportedCutout = hasReportedCutout,
        hasFoldingFeatures = hasFoldingFeatures,
    )
}
