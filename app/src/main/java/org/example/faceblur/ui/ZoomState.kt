package org.example.faceblur.ui

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import kotlin.math.max
import kotlin.math.min

/**
 * Transformation image → écran : `écran = image × scale + offset`.
 * Le zoom va de « image entière visible » à [MAX_PIXEL_SCALE] pixels écran par pixel image
 * (au moins [MAX_ZOOM] × l'ajustement), pour pouvoir contrôler le flou au pixel près.
 */
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

    /** À appeler quand la vue ou l'image change de taille : revient à l'ajustement. */
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

    /** Zoom de facteur [zoom] centré sur [centroid] puis déplacement de [pan] (pixels écran). */
    fun transform(centroid: Offset, zoom: Float, pan: Offset) {
        if (viewport.isEmpty() || content.isEmpty()) return
        val newScale = (scale * zoom).coerceIn(fitScale, maxScale)
        val k = newScale / scale
        offset = clamp(centroid - (centroid - offset) * k + pan, newScale)
        scale = newScale
    }

    fun toImage(screen: Offset): Offset = (screen - offset) / scale

    fun toScreen(image: Offset): Offset = image * scale + offset

    /** Image centrée si plus petite que la vue, sinon sans bande vide sur les bords. */
    private fun clamp(o: Offset, s: Float): Offset {
        fun axis(value: Float, view: Float, length: Float): Float {
            val scaled = length * s
            return if (scaled <= view) (view - scaled) / 2 else value.coerceIn(view - scaled, 0f)
        }
        return Offset(axis(o.x, viewport.width, content.width), axis(o.y, viewport.height, content.height))
    }

    companion object {
        const val MAX_ZOOM = 8f
        const val MAX_PIXEL_SCALE = 4f
        private const val EPSILON = 0.01f
    }
}
