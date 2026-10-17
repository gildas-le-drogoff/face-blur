package org.example.faceblur.ui

import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.window.core.layout.WindowSizeClass

enum class WidthClass { COMPACT, MEDIUM, EXPANDED }

@Composable
fun currentWidthClass(): WidthClass {
    val sizeClass = currentWindowAdaptiveInfoV2().windowSizeClass
    return when {
        sizeClass.isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_EXPANDED_LOWER_BOUND) -> WidthClass.EXPANDED
        sizeClass.isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND) -> WidthClass.MEDIUM
        else -> WidthClass.COMPACT
    }
}

/**
 * Barème unique des espacements, dérivé de la largeur de fenêtre.
 * [overlayStroke] (épaisseur) et [touchTolerance] (zone tactile) sont fixes par nature.
 * [previewWeight]/[controlsWeight] : partage image / réglages.
 */
@Immutable
data class Dimens(
    val screenPadding: Dp,
    val spacing: Dp,
    val sectionSpacing: Dp,
    val overlayStroke: Dp = 2.dp,
    val touchTolerance: Dp = 16.dp,
    val previewWeight: Float,
    val controlsWeight: Float,
)

private val CompactDimens = Dimens(
    screenPadding = 12.dp, spacing = 8.dp, sectionSpacing = 16.dp, previewWeight = 3f, controlsWeight = 2f,
)
private val MediumDimens = Dimens(
    screenPadding = 16.dp, spacing = 12.dp, sectionSpacing = 20.dp, previewWeight = 3f, controlsWeight = 2f,
)
private val ExpandedDimens = Dimens(
    screenPadding = 24.dp, spacing = 12.dp, sectionSpacing = 24.dp, previewWeight = 2f, controlsWeight = 1f,
)

fun dimensFor(widthClass: WidthClass): Dimens = when (widthClass) {
    WidthClass.COMPACT -> CompactDimens
    WidthClass.MEDIUM -> MediumDimens
    WidthClass.EXPANDED -> ExpandedDimens
}

val LocalDimens = staticCompositionLocalOf { CompactDimens }
