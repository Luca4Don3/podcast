package com.podcastapp.ui.adaptive

import android.app.Activity
import android.os.Build
import android.view.Display
import android.view.Surface
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.core.view.ViewCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import androidx.window.layout.WindowMetricsCalculator
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlin.math.roundToInt

/** The feature bounds reported by WindowManager are in activity-window pixels. */
@Composable
internal fun rememberFoldingFeatures(activity: Activity): List<FoldFeature> {
    val layout = remember(activity) {
        WindowInfoTracker.getOrCreate(activity).windowLayoutInfo(activity).map { info ->
            info.displayFeatures.filterIsInstance<FoldingFeature>().map { feature ->
                val rect = feature.bounds
                FoldFeature(
                    boundsPx = PxRect(rect.left, rect.top, rect.right, rect.bottom),
                    vertical = feature.orientation == FoldingFeature.Orientation.VERTICAL,
                    separating = feature.isSeparating,
                    occluding = feature.occlusionType == FoldingFeature.OcclusionType.FULL,
                    halfOpened = feature.state == FoldingFeature.State.HALF_OPENED,
                )
            }
        }.catch { emit(emptyList()) }
    }
    return layout.collectAsStateWithLifecycle(initialValue = emptyList()).value
}

@Composable
internal fun AdaptiveWindow(
    activity: Activity?,
    folds: List<FoldFeature>,
    content: @Composable (ScreenProfile) -> Unit,
) {
    var windowPosition by remember { mutableStateOf<IntOffset?>(null) }
    val view = LocalView.current
    val configuration = LocalConfiguration.current
    val spec = remember { knownCutoutSpec(Build.MANUFACTURER, Build.MODEL, Build.DEVICE) }
    BoxWithConstraints(
        Modifier.fillMaxSize().onGloballyPositioned {
            val position = it.positionInWindow()
            if (position.x.isFinite() && position.y.isFinite()) {
                windowPosition = IntOffset(position.x.roundToInt(), position.y.roundToInt())
            }
        },
    ) {
        val density = LocalDensity.current
        val direction = LocalLayoutDirection.current
        val safe = WindowInsets.safeDrawing
        val left = safe.getLeft(density, direction).coerceIn(0, constraints.maxWidth)
        val top = safe.getTop(density).coerceIn(0, constraints.maxHeight)
        val right = (constraints.maxWidth - safe.getRight(density, direction)).coerceAtLeast(left)
        val bottom = (constraints.maxHeight - safe.getBottom(density)).coerceAtLeast(top)
        val systemBounds = PxRect(left, top, right, bottom)
        val origin = windowPosition ?: IntOffset.Zero
        val cutout = WindowInsets.displayCutout
        val rootInsets = if (spec != null) ViewCompat.getRootWindowInsets(view) else null
        val reportedCutout = rootInsets?.displayCutout?.boundingRects?.isNotEmpty() == true ||
            cutout.getTop(density) > 0 || cutout.getBottom(density) > 0 ||
            cutout.getLeft(density, direction) > 0 || cutout.getRight(density, direction) > 0
        val display = view.display
        val rotation = when (display?.rotation) {
            Surface.ROTATION_0 -> DisplayQuarterTurn.NATURAL
            Surface.ROTATION_90 -> DisplayQuarterTurn.ROTATION_90
            Surface.ROTATION_180 -> DisplayQuarterTurn.ROTATION_180
            Surface.ROTATION_270 -> DisplayQuarterTurn.ROTATION_270
            else -> null
        }
        val multiWindow = activity?.isInMultiWindowMode != false
        val fullDisplay = remember(
            activity, configuration, constraints, windowPosition, display?.displayId,
            rotation, multiWindow, reportedCutout, rootInsets != null, folds.isEmpty(), spec,
        ) {
            activity != null && spec != null && rootInsets != null && !reportedCutout && !multiWindow &&
                folds.isEmpty() && windowPosition == IntOffset.Zero && display?.displayId == Display.DEFAULT_DISPLAY &&
                fillsDisplay(activity, constraints.maxWidth, constraints.maxHeight)
        }
        val bounds = resolveCutoutFallback(
            safe = systemBounds,
            windowWidth = constraints.maxWidth,
            windowHeight = constraints.maxHeight,
            density = density.density,
            spec = spec,
            rotation = rotation,
            occupiesNativeDisplay = fullDisplay,
            hasReportedCutout = reportedCutout,
            hasFoldingFeatures = folds.isNotEmpty(),
        )
        val profile = remember(bounds, systemBounds, density.density, folds, origin) {
            computeScreenProfile(bounds, density.density, folds.map { feature ->
                val b = feature.boundsPx
                feature.copy(boundsPx = PxRect(b.left - origin.x, b.top - origin.y, b.right - origin.x, b.bottom - origin.y))
            }).copy(usesModelCutout = bounds != systemBounds)
        }
        content(profile)
    }
}

private fun fillsDisplay(activity: Activity, width: Int, height: Int): Boolean = runCatching {
    val metrics = WindowMetricsCalculator.getOrCreate()
    val current = metrics.computeCurrentWindowMetrics(activity).bounds
    val maximum = metrics.computeMaximumWindowMetrics(activity).bounds
    current == maximum && maximum.width() == width && maximum.height() == height
}.getOrDefault(false)

/** Keep the main pane composed as the feature bounds/posture change. */
@Composable
internal fun PlacePanes(
    primary: ScreenRegion,
    secondary: ScreenRegion? = null,
    main: @Composable () -> Unit,
    supporting: @Composable () -> Unit = {},
) {
    val insets = WindowInsets.safeDrawing
    Layout(
        modifier = Modifier.fillMaxSize(),
        content = {
            Box(Modifier.clipToBounds().consumeWindowInsets(insets)) { main() }
            if (secondary != null) {
                Box(Modifier.clipToBounds().consumeWindowInsets(insets)) { supporting() }
            }
        },
    ) { nodes, constraints ->
        val bounds = primary.boundsPx
        val mainPane = nodes[0].measure(Constraints.fixed(bounds.width, bounds.height))
        val extraBounds = secondary?.boundsPx
        val extraPane = extraBounds?.let { nodes[1].measure(Constraints.fixed(it.width, it.height)) }
        layout(constraints.maxWidth, constraints.maxHeight) {
            mainPane.place(bounds.left, bounds.top)
            if (extraPane != null && extraBounds != null) extraPane.place(extraBounds.left, extraBounds.top)
        }
    }
}
