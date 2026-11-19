package com.podcastapp.ui.adaptive

/** Window-relative pixels, in physical coordinates (left increases toward the right). */
data class PxRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = (right - left).coerceAtLeast(0)
    val height: Int get() = (bottom - top).coerceAtLeast(0)
    val isEmpty: Boolean get() = right <= left || bottom <= top
}

data class FoldFeature(
    val boundsPx: PxRect,
    val vertical: Boolean,
    val separating: Boolean = false,
    val occluding: Boolean = false,
    val halfOpened: Boolean = false,
)

enum class ScreenSize { NARROW_TALL, NORMAL, SHORT_LANDSCAPE, WIDE }

enum class NavigationMode { BOTTOM_BAR, RAIL }

data class ScreenRegion(
    val boundsPx: PxRect,
    val widthDp: Float,
    val heightDp: Float,
    val size: ScreenSize,
    val navigation: NavigationMode,
)

sealed interface ScreenSplit {
    data class LeftRight(val left: ScreenRegion, val right: ScreenRegion) : ScreenSplit
    data class Tabletop(val top: ScreenRegion, val bottom: ScreenRegion) : ScreenSplit
}

data class ScreenProfile(
    /** Largest safe rectangle. When empty, no UI should be placed in the window. */
    val singlePane: ScreenRegion,
    val split: ScreenSplit?,
    /** A fold intersects the safe window, even if it could not provide a supported split. */
    val avoidsFold: Boolean,
    val usesModelCutout: Boolean = false,
)

private const val NARROW_WIDTH_DP = 400f
private const val TALL_ASPECT_RATIO = 2f
private const val SHORT_HEIGHT_DP = 480f
private const val WIDE_WIDTH_DP = 600f
private const val MIN_RAIL_HEIGHT_DP = 320f

/**
 * [safeWindowPx] already excludes unsafe insets. Fold bounds must use the same window origin.
 * Only one full-span fold can produce a two-pane layout. Other intersecting folds are still
 * avoided by [ScreenProfile.singlePane], which prefers top, then physical left, on an area tie.
 */
fun computeScreenProfile(
    safeWindowPx: PxRect,
    density: Float,
    folds: List<FoldFeature> = emptyList(),
): ScreenProfile {
    require(density.isFinite() && density > 0f) { "density must be positive and finite" }

    val activeFolds = folds.filter {
        (it.separating || it.occluding || it.halfOpened) && it.hasFoldBounds() &&
            safeWindowPx.overlapsFold(it.boundsPx)
    }
    if (activeFolds.isEmpty()) return ScreenProfile(safeWindowPx.toRegion(density), null, false)

    var candidates = setOf(safeWindowPx)
    for (fold in activeFolds) {
        candidates = candidates.flatMapTo(mutableSetOf()) { it.without(fold.boundsPx) }
    }
    val largest = candidates.minWithOrNull(
        compareByDescending<PxRect> { it.width.toLong() * it.height }
            .thenBy { it.top }.thenBy { it.left }.thenBy { it.bottom }.thenBy { it.right },
    ) ?: PxRect(safeWindowPx.left, safeWindowPx.top, safeWindowPx.left, safeWindowPx.top)
    return ScreenProfile(
        singlePane = largest.toRegion(density),
        split = activeFolds.singleOrNull()?.split(safeWindowPx, density),
        avoidsFold = true,
    )
}

private fun PxRect.toRegion(density: Float): ScreenRegion {
    val widthDp = width / density
    val heightDp = height / density
    val size = when {
        isEmpty -> ScreenSize.NORMAL
        widthDp > heightDp && heightDp < SHORT_HEIGHT_DP -> ScreenSize.SHORT_LANDSCAPE
        widthDp >= WIDE_WIDTH_DP -> ScreenSize.WIDE
        widthDp < NARROW_WIDTH_DP && heightDp >= widthDp * TALL_ASPECT_RATIO -> ScreenSize.NARROW_TALL
        else -> ScreenSize.NORMAL
    }
    // Keep the existing app's rail threshold, applied to each usable pane, not the whole window.
    val navigation = if (widthDp >= WIDE_WIDTH_DP && heightDp >= MIN_RAIL_HEIGHT_DP) {
        NavigationMode.RAIL
    } else {
        NavigationMode.BOTTOM_BAR
    }
    return ScreenRegion(this, widthDp, heightDp, size, navigation)
}

private fun FoldFeature.hasFoldBounds(): Boolean = if (vertical) {
    boundsPx.top < boundsPx.bottom && boundsPx.left <= boundsPx.right
} else {
    boundsPx.left < boundsPx.right && boundsPx.top <= boundsPx.bottom
}

// Strict edges exclude boundary-only contact but still catch an interior zero-thickness fold.
private fun PxRect.overlapsFold(fold: PxRect): Boolean = !isEmpty &&
    left < fold.right && fold.left < right && top < fold.bottom && fold.top < bottom

private fun PxRect.without(fold: PxRect): List<PxRect> {
    if (!overlapsFold(fold)) return listOf(this)
    // A rectangle avoiding the fold lies in at least one of these four strips. Retain them all
    // until every fold has been applied so a later fold can't invalidate a greedy choice.
    return buildList {
        if (left < fold.left) add(copy(right = fold.left))
        if (fold.right < right) add(copy(left = fold.right))
        if (top < fold.top) add(copy(bottom = fold.top))
        if (fold.bottom < bottom) add(copy(top = fold.bottom))
    }
}

private fun FoldFeature.split(window: PxRect, density: Float): ScreenSplit? {
    val fold = boundsPx
    return when {
        vertical && fold.top <= window.top && fold.bottom >= window.bottom &&
            window.left < fold.left && fold.right < window.right -> ScreenSplit.LeftRight(
            left = window.copy(right = fold.left).toRegion(density),
            right = window.copy(left = fold.right).toRegion(density),
        )
        !vertical && halfOpened && fold.left <= window.left && fold.right >= window.right &&
            window.top < fold.top && fold.bottom < window.bottom -> ScreenSplit.Tabletop(
            top = window.copy(bottom = fold.top).toRegion(density),
            bottom = window.copy(top = fold.bottom).toRegion(density),
        )
        else -> null
    }
}
