package org.example.faceblur.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.toSize
import org.example.faceblur.domain.FaceBox
import org.example.faceblur.domain.FaceRegion
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Image zoomable avec repères des visages.
 * - Deux doigts : zoom/déplacement. Un doigt qui glisse : tracé d'une zone ([onBoxDrawn]).
 * - Toucher : [onTap] en coordonnées image, avec la tolérance tactile convertie en pixels image.
 */
@Composable
fun ZoomableFaceCanvas(
    image: ImageBitmap,
    regions: List<FaceRegion>,
    showOverlay: Boolean,
    zoom: ZoomState,
    description: String,
    onTap: (x: Float, y: Float, tolerance: Float) -> Unit,
    onBoxDrawn: (FaceBox) -> Unit,
    modifier: Modifier = Modifier,
) {
    val dimens = LocalDimens.current
    val colors = MaterialTheme.colorScheme
    val currentOnTap by rememberUpdatedState(onTap)
    val currentOnBoxDrawn by rememberUpdatedState(onBoxDrawn)
    var draftStart by remember { mutableStateOf<Offset?>(null) }
    var draftEnd by remember { mutableStateOf<Offset?>(null) }

    // Nouveau nœud par ZoomState (donc par image) : onSizeChanged refournit les bornes au nouvel état.
    key(zoom) {
        Canvas(
            modifier
                .clipToBounds()
                .onSizeChanged { zoom.setBounds(it.toSize(), Size(image.width.toFloat(), image.height.toFloat())) }
                .semantics { contentDescription = description }
                .pointerInput(zoom, dimens.touchTolerance) {
                    val tolerance = dimens.touchTolerance.toPx()
                    detectTapGestures { p ->
                        val ip = zoom.toImage(p)
                        currentOnTap(ip.x, ip.y, tolerance / zoom.scale)
                    }
                }
                .pointerInput(zoom) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val slop = viewConfiguration.touchSlop
                        var dragging = false
                        var multiTouch = false
                        do {
                            val event = awaitPointerEvent()
                            val pressed = event.changes.filter { it.pressed }
                            if (pressed.size >= 2) {
                                // Un tracé en cours est abandonné dès qu'un second doigt se pose.
                                multiTouch = true
                                draftStart = null
                                draftEnd = null
                                zoom.transform(event.calculateCentroid(), event.calculateZoom(), event.calculatePan())
                                event.changes.forEach { it.consume() }
                            } else if (pressed.size == 1) {
                                val change = pressed.first()
                                if (!dragging && (multiTouch || (change.position - down.position).getDistance() > slop)) {
                                    dragging = true
                                }
                                if (dragging) {
                                    if (!multiTouch) {
                                        draftStart = down.position
                                        draftEnd = change.position
                                    }
                                    change.consume()
                                }
                            }
                        } while (event.changes.any { it.pressed })

                        val start = draftStart
                        val end = draftEnd
                        draftStart = null
                        draftEnd = null
                        if (start != null && end != null && abs(end.x - start.x) > slop && abs(end.y - start.y) > slop) {
                            val a = zoom.toImage(start)
                            val b = zoom.toImage(end)
                            currentOnBoxDrawn(FaceBox.fromCorners(a.x, a.y, b.x, b.y))
                        }
                    }
                },
        ) {
            withTransform({
                translate(zoom.offset.x, zoom.offset.y)
                scale(zoom.scale, zoom.scale, pivot = Offset.Zero)
            }) {
                // Au-delà de 2 px écran par px image : pixels nets, pour juger réellement le flou.
                drawImage(
                    image,
                    dstSize = IntSize(image.width, image.height),
                    filterQuality = if (zoom.scale >= 2f) FilterQuality.None else FilterQuality.Low,
                )
            }

            val stroke = dimens.overlayStroke.toPx()
            if (showOverlay) {
                val dash = PathEffect.dashPathEffect(floatArrayOf(stroke * 4, stroke * 3))
                for (region in regions) {
                    val color = when {
                        !region.blurred -> colors.error
                        region.isManual -> colors.tertiary
                        else -> colors.primary
                    }
                    val topLeft = zoom.toScreen(Offset(region.box.left, region.box.top))
                    val bottomRight = zoom.toScreen(Offset(region.box.right, region.box.bottom))
                    outlinedRect(topLeft, bottomRight, color, colors.scrim, stroke, if (region.blurred) null else dash)
                }
            }
            val start = draftStart
            val end = draftEnd
            if (start != null && end != null) {
                outlinedRect(
                    Offset(min(start.x, end.x), min(start.y, end.y)),
                    Offset(max(start.x, end.x), max(start.y, end.y)),
                    colors.tertiary, colors.scrim, stroke, null,
                )
            }
        }
    }
}

/** Rectangle avec un liseré sombre dessous, lisible sur n'importe quelle photo. */
private fun DrawScope.outlinedRect(
    topLeft: Offset,
    bottomRight: Offset,
    color: Color,
    halo: Color,
    stroke: Float,
    pathEffect: PathEffect?,
) {
    val size = Size(bottomRight.x - topLeft.x, bottomRight.y - topLeft.y)
    drawRect(halo.copy(alpha = HALO_ALPHA), topLeft, size, style = Stroke(stroke * 2))
    drawRect(color, topLeft, size, style = Stroke(stroke, pathEffect = pathEffect))
}

private const val HALO_ALPHA = 0.5f
