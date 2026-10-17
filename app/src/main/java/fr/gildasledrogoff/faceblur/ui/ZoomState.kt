// SPDX-FileCopyrightText: 2026 Gildas Le Drogoff and FaceBlur contributors
//
// SPDX-License-Identifier: AGPL-3.0-or-later

package fr.gildasledrogoff.faceblur.ui

import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.center
import kotlin.math.max
import kotlin.math.min

@Stable
class ZoomState {
    var scale by mutableFloatStateOf(1f)
        private set
    var offset by mutableStateOf(Offset.Zero)
        private set
    private var viewport = Size.Zero
    private var content = Size.Zero
    private var fitScale = 1f
    val isZoomed: Boolean get() = scale > fitScale * (1 + EPSILON)
    private val maxScale: Float get() = max(fitScale * MAX_ZOOM, MAX_PIXEL_SCALE)
    val canZoomIn: Boolean get() = !content.isEmpty() && scale < maxScale * (1 - EPSILON)
    fun setBounds(viewport: Size, content: Size) {
        if (viewport == this.viewport && content == this.content) return
        this.viewport = viewport
        this.content = content
        reset()
    }

    fun reset() {
        if (viewport.isEmpty() || content.isEmpty()) return
        fitScale = min(viewport.width / content.width, viewport.height / content.height)
        scale = fitScale
        offset = clamp(Offset.Zero, fitScale)
    }

    fun transform(centroid: Offset, zoom: Float, pan: Offset) {
        if (viewport.isEmpty() || content.isEmpty()) return
        val newScale = (scale * zoom).coerceIn(fitScale, maxScale)
        val scaleRatio = newScale / scale
        offset = clamp(centroid - (centroid - offset) * scaleRatio + pan, newScale)
        scale = newScale
    }

    fun zoomBy(factor: Float, centroid: Offset = viewport.center) =
        transform(centroid, factor, Offset.Zero)

    fun panBy(delta: Offset) = transform(viewport.center, 1f, delta)
    fun toImage(screen: Offset): Offset = (screen - offset) / scale
    fun toScreen(image: Offset): Offset = image * scale + offset
    private fun clamp(offset: Offset, scale: Float): Offset {
        fun axis(value: Float, view: Float, length: Float): Float {
            val scaled = length * scale
            return if (scaled <= view) (view - scaled) / 2 else value.coerceIn(view - scaled, 0f)
        }
        return Offset(
            axis(offset.x, viewport.width, content.width),
            axis(offset.y, viewport.height, content.height)
        )
    }

    companion object {
        const val MAX_ZOOM = 8f
        const val MAX_PIXEL_SCALE = 4f
        const val ZOOM_STEP = 1.5f
        private const val EPSILON = 0.01f
    }
}
