package com.podcastapp.ui.adaptive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenProfileTest {
    @Test fun classifiesTheSafeWindowInDpAndKeepsItsOrigin() {
        val safe = PxRect(32, 48, 752, 1648)
        val profile = computeScreenProfile(safe, density = 2f)

        assertEquals(safe, profile.singlePane.boundsPx)
        assertEquals(360f, profile.singlePane.widthDp, 0f)
        assertEquals(800f, profile.singlePane.heightDp, 0f)
        assertEquals(ScreenSize.NARROW_TALL, profile.singlePane.size)
        assertEquals(NavigationMode.BOTTOM_BAR, profile.singlePane.navigation)
        assertNull(profile.split)
        assertFalse(profile.avoidsFold)

        assertEquals(ScreenSize.NORMAL, region(360, 719).size)
        assertEquals(ScreenSize.NORMAL, region(400, 800).size)
        assertEquals(ScreenSize.NORMAL, region(599, 900).size)
        assertEquals(ScreenSize.SHORT_LANDSCAPE, region(900, 479).size)
        assertEquals(ScreenSize.WIDE, region(600, 480).size)
        assertEquals(ScreenSize.WIDE, region(850, 1000).size)
    }

    @Test fun railRequiresBothWidthAndHeight() {
        assertEquals(NavigationMode.BOTTOM_BAR, region(599, 900).navigation)
        assertEquals(NavigationMode.BOTTOM_BAR, region(900, 319).navigation)
        assertEquals(NavigationMode.RAIL, region(600, 320).navigation)
        assertEquals(NavigationMode.RAIL, region(840, 900).navigation)
    }

    @Test fun keepsPhysicalOrderAndExcludesTheWholeVerticalHinge() {
        val safe = PxRect(20, 40, 2040, 1600)
        val hinge = FoldFeature(PxRect(800, 0, 840, 1620), vertical = true, occluding = true)
        val profile = computeScreenProfile(safe, 2f, listOf(hinge))
        val split = profile.split as ScreenSplit.LeftRight

        assertEquals(PxRect(20, 40, 800, 1600), split.left.boundsPx)
        assertEquals(PxRect(840, 40, 2040, 1600), split.right.boundsPx)
        assertEquals(ScreenSize.NARROW_TALL, split.left.size)
        assertEquals(ScreenSize.WIDE, split.right.size)
        assertEquals(NavigationMode.BOTTOM_BAR, split.left.navigation)
        assertEquals(NavigationMode.RAIL, split.right.navigation)
        assertEquals(split.right, profile.singlePane)
        assertTrue(profile.avoidsFold)
    }

    @Test fun zeroWidthSeparatingCreaseCreatesLeftAndRightPanes() {
        val safe = PxRect(10, 20, 1010, 720)
        val crease = FoldFeature(PxRect(510, 0, 510, 740), vertical = true, separating = true)
        val profile = computeScreenProfile(safe, 1f, listOf(crease))
        val split = profile.split as ScreenSplit.LeftRight

        assertEquals(PxRect(10, 20, 510, 720), split.left.boundsPx)
        assertEquals(PxRect(510, 20, 1010, 720), split.right.boundsPx)
        assertEquals(split.left, profile.singlePane)
        assertTrue(profile.avoidsFold)
    }

    @Test fun halfOpenedHorizontalCreaseCreatesTopAndBottomPanesEvenWithZeroHeight() {
        val safe = PxRect(30, 60, 1530, 1660)
        val crease = FoldFeature(PxRect(0, 900, 1600, 900), vertical = false, halfOpened = true)
        val profile = computeScreenProfile(safe, 2f, listOf(crease))
        val split = profile.split as ScreenSplit.Tabletop

        assertEquals(PxRect(30, 60, 1530, 900), split.top.boundsPx)
        assertEquals(PxRect(30, 900, 1530, 1660), split.bottom.boundsPx)
        assertEquals(420f, split.top.heightDp, 0f)
        assertEquals(380f, split.bottom.heightDp, 0f)
        assertEquals(split.top, profile.singlePane)
        assertTrue(profile.avoidsFold)
    }

    @Test fun flatHorizontalHingeIsAvoidedWithoutEnablingTabletop() {
        val safe = PxRect(30, 20, 900, 1000)
        val hinge = FoldFeature(PxRect(0, 350, 900, 390), vertical = false, separating = true)
        val profile = computeScreenProfile(safe, 1f, listOf(hinge))

        assertEquals(PxRect(30, 390, 900, 1000), profile.singlePane.boundsPx)
        assertNull(profile.split)
        assertTrue(profile.avoidsFold)
    }

    @Test fun ignoresNonSeparatingFlatCreasesAndFeaturesOutsideTheSafeWindow() {
        val safe = PxRect(60, 40, 900, 800)
        val folds = listOf(
            FoldFeature(PxRect(500, 0, 500, 1000), vertical = true),
            FoldFeature(PxRect(0, 200, 1000, 200), vertical = false),
            FoldFeature(PxRect(40, 0, 60, 1000), vertical = true, occluding = true),
            FoldFeature(PxRect(700, 0, 700, 40), vertical = true, separating = true),
            FoldFeature(PxRect(0, 800, 1000, 800), vertical = false, halfOpened = true),
        )
        val profile = computeScreenProfile(safe, 1f, folds)

        assertEquals(safe, profile.singlePane.boundsPx)
        assertNull(profile.split)
        assertFalse(profile.avoidsFold)
    }

    @Test fun multipleParallelHingesMayLeaveTheLargestPaneBetweenThem() {
        val safe = PxRect(0, 0, 1000, 800)
        val folds = listOf(
            FoldFeature(PxRect(140, 0, 160, 800), vertical = true, occluding = true),
            FoldFeature(PxRect(800, 0, 800, 800), vertical = true, separating = true),
        )
        val profile = computeScreenProfile(safe, 1f, folds)

        assertEquals(PxRect(160, 0, 800, 800), profile.singlePane.boundsPx)
        assertEquals(NavigationMode.RAIL, profile.singlePane.navigation)
        assertNull(profile.split)
        assertTrue(profile.avoidsFold)
    }

    @Test fun crossingFoldsChooseTheLargestContinuousPaneRegardlessOfInputOrder() {
        val safe = PxRect(0, 0, 1200, 800)
        val folds = listOf(
            FoldFeature(PxRect(700, 0, 700, 800), vertical = true, separating = true),
            FoldFeature(PxRect(0, 200, 1200, 220), vertical = false, halfOpened = true),
        )
        val profile = computeScreenProfile(safe, 1f, folds)

        assertEquals(PxRect(0, 220, 700, 800), profile.singlePane.boundsPx)
        assertNull(profile.split)
        assertTrue(profile.avoidsFold)
        assertEquals(profile, computeScreenProfile(safe, 1f, folds.reversed()))
    }

    @Test fun partialOcclusionAvoidsTheObstructionWithoutCreatingTwoPaneLayout() {
        val safe = PxRect(20, 30, 1020, 1030)
        val fold = FoldFeature(PxRect(500, 30, 550, 230), vertical = true, occluding = true)
        val profile = computeScreenProfile(safe, 1f, listOf(fold))

        assertEquals(PxRect(20, 230, 1020, 1030), profile.singlePane.boundsPx)
        assertNull(profile.split)
        assertTrue(profile.avoidsFold)
    }

    @Test fun neverFabricatesSpaceForAnEmptyOrFullyOccludedSafeWindow() {
        val empty = PxRect(20, 40, 20, 500)
        assertTrue(computeScreenProfile(empty, 1f).singlePane.boundsPx.isEmpty)

        val safe = PxRect(20, 40, 500, 650)
        val fold = FoldFeature(boundsPx = safe, vertical = true, occluding = true)
        val profile = computeScreenProfile(safe, 1f, listOf(fold))
        assertTrue(profile.singlePane.boundsPx.isEmpty)
        assertNull(profile.split)
        assertTrue(profile.avoidsFold)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsInvalidDensity() {
        computeScreenProfile(PxRect(0, 0, 600, 800), Float.NaN)
    }

    private fun region(width: Int, height: Int): ScreenRegion =
        computeScreenProfile(PxRect(0, 0, width, height), 1f).singlePane
}
