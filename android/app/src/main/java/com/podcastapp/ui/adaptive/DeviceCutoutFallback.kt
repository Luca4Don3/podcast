package com.podcastapp.ui.adaptive

import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil

internal enum class DisplayQuarterTurn { NATURAL, ROTATION_90, ROTATION_180, ROTATION_270 }

internal data class CutoutFallbackSpec(
    val panelWidthPx: Int,
    val panelHeightPx: Int,
    /** Measured cutout depth in native portrait pixels (Xiaomi published values). */
    val topDepthPx: Int = 0,
    /** Conservative UI reserve in dp for centered punch-hole or software pill shapes. */
    val reserveDp: Int = 0,
)

private data class DeviceKey(val manufacturer: String, val model: String, val device: String)

/**
 * Exact identities from Google Play's supported-devices CSV. Unknown devices do not receive
 * a synthetic cutout: the platform's reported insets remain the only source for them.
 */
private val knownDevices = mapOf(
    // Native panel and notch depth published by Xiaomi: dev.mi.com pId=1608.
    DeviceKey("xiaomi", "mi 8", "dipper") to CutoutFallbackSpec(1080, 2248, topDepthPx = 89),
    DeviceKey("xiaomi", "mi 8 se", "sirius") to CutoutFallbackSpec(1080, 2244, topDepthPx = 85),
    DeviceKey("xiaomi", "mi 8 explorer edition", "ursa") to CutoutFallbackSpec(1080, 2248, topDepthPx = 89),
    DeviceKey("xiaomi", "mi 8 ud", "equuleus") to CutoutFallbackSpec(1080, 2248, topDepthPx = 89),
    DeviceKey("xiaomi", "mi8lite", "platina") to CutoutFallbackSpec(1080, 2280, topDepthPx = 82),
    DeviceKey("xiaomi", "redmi k30", "phoenix") to CutoutFallbackSpec(1080, 2400, topDepthPx = 92),
    // realme C55's Mini Capsule is a software animation around a normal centered hole.
    DeviceKey("realme", "rmx3710", "ree2adl1") to CutoutFallbackSpec(1080, 2400, reserveDp = 48),
    // Magic6 Pro has a centered dual camera aperture; 1264px panels are a different model.
    DeviceKey("honor", "bvl-n49", "hnbvl") to CutoutFallbackSpec(1280, 2800, reserveDp = 56),
    DeviceKey("honor", "bvl-an16", "hnbvl") to CutoutFallbackSpec(1280, 2800, reserveDp = 56),
)

internal fun knownCutoutSpec(manufacturer: String, model: String, device: String): CutoutFallbackSpec? {
    fun String.normalized() = trim().lowercase(Locale.ROOT)
    return knownDevices[DeviceKey(manufacturer.normalized(), model.normalized(), device.normalized())]
}

/**
 * Return the platform-safe rectangle, optionally narrowed by a model-derived edge reserve.
 * The fallback is deliberately conservative: it requires an exact identity, a full native
 * display, no fold, no reported cutout and a matching native aspect ratio. It never adds to
 * (and never subtracts from) an already larger platform inset: the final boundary is the
 * maximum of the two, so the same cutout cannot be reserved twice.
 */
internal fun resolveCutoutFallback(
    safe: PxRect,
    windowWidth: Int,
    windowHeight: Int,
    density: Float,
    spec: CutoutFallbackSpec?,
    rotation: DisplayQuarterTurn?,
    occupiesNativeDisplay: Boolean,
    hasReportedCutout: Boolean,
    hasFoldingFeatures: Boolean,
): PxRect {
    if (spec == null || rotation == null || !occupiesNativeDisplay || hasReportedCutout ||
        hasFoldingFeatures || safe.isEmpty || windowWidth <= 0 || windowHeight <= 0 ||
        !density.isFinite() || density <= 0f
    ) return safe

    val sideEdge = rotation == DisplayQuarterTurn.ROTATION_90 || rotation == DisplayQuarterTurn.ROTATION_270
    val naturalWidth = if (sideEdge) windowHeight else windowWidth
    val naturalHeight = if (sideEdge) windowWidth else windowHeight
    if (naturalWidth <= 0) return safe
    val expectedAspect = spec.panelHeightPx.toDouble() / spec.panelWidthPx
    if (abs((naturalHeight.toDouble() / naturalWidth) / expectedAspect - 1.0) > 0.012) return safe

    val depth = when {
        spec.topDepthPx > 0 -> ceil(spec.topDepthPx.toDouble() * naturalWidth / spec.panelWidthPx).toInt()
        spec.reserveDp > 0 -> ceil(spec.reserveDp.toDouble() * density).toInt()
        else -> return safe
    }
    if (depth <= 0 || depth > naturalWidth / 5) return safe

    // A natural top aperture follows the physical edge indicated by Surface rotation.
    val result = when (rotation) {
        DisplayQuarterTurn.NATURAL -> safe.copy(top = maxOf(safe.top, depth))
        DisplayQuarterTurn.ROTATION_90 -> safe.copy(left = maxOf(safe.left, depth))
        DisplayQuarterTurn.ROTATION_180 -> safe.copy(bottom = minOf(safe.bottom, windowHeight - depth))
        DisplayQuarterTurn.ROTATION_270 -> safe.copy(right = minOf(safe.right, windowWidth - depth))
    }
    return if (result.isEmpty) safe else result
}
