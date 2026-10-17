// SPDX-FileCopyrightText: 2026 Gildas Le Drogoff and FaceBlur contributors
//
// SPDX-License-Identifier: AGPL-3.0-or-later

package fr.gildasledrogoff.faceblur.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import fr.gildasledrogoff.faceblur.domain.FaceBox
import fr.gildasledrogoff.faceblur.domain.FaceRegion
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

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
    isPanModeEnabled: Boolean = false,
) {
    val dimens = LocalDimens.current
    val colors = MaterialTheme.colorScheme
    val currentOnTap by rememberUpdatedState(onTap)
    val currentOnBoxDrawn by rememberUpdatedState(onBoxDrawn)
    val currentIsPanModeEnabled by rememberUpdatedState(isPanModeEnabled)
    var draftStart by remember { mutableStateOf<Offset?>(null) }
    var draftEnd by remember { mutableStateOf<Offset?>(null) }
    var isFocused by remember { mutableStateOf(false) }
    val keyPanStep = with(LocalDensity.current) { KEY_PAN_STEP.toPx() }
    key(zoom) {
        Canvas(
            modifier
                .clipToBounds()
                .onSizeChanged {
                    zoom.setBounds(
                        it.toSize(),
                        Size(image.width.toFloat(), image.height.toFloat())
                    )
                }
                .semantics { contentDescription = description }
                .onFocusChanged { isFocused = it.isFocused }
                .onKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                    when (event.key) {
                        Key.Plus, Key.Equals, Key.NumPadAdd -> zoom.zoomBy(ZoomState.ZOOM_STEP)
                        Key.Minus, Key.NumPadSubtract -> zoom.zoomBy(1 / ZoomState.ZOOM_STEP)
                        Key.DirectionLeft -> zoom.panBy(Offset(keyPanStep, 0f))
                        Key.DirectionRight -> zoom.panBy(Offset(-keyPanStep, 0f))
                        Key.DirectionUp -> zoom.panBy(Offset(0f, keyPanStep))
                        Key.DirectionDown -> zoom.panBy(Offset(0f, -keyPanStep))
                        else -> return@onKeyEvent false
                    }
                    true
                }
                .focusable()
                .pointerInput(zoom) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            if (event.type != PointerEventType.Scroll) continue
                            val change = event.changes.firstOrNull() ?: continue
                            val scrollDeltaY = change.scrollDelta.y
                            if (scrollDeltaY == 0f) continue
                            zoom.zoomBy(SCROLL_ZOOM_BASE.pow(-scrollDeltaY), change.position)
                            change.consume()
                        }
                    }
                }
                .pointerInput(zoom, dimens.touchTolerance) {
                    val tolerance = dimens.touchTolerance.toPx()
                    detectTapGestures { position ->
                        val imagePosition = zoom.toImage(position)
                        currentOnTap(imagePosition.x, imagePosition.y, tolerance / zoom.scale)
                    }
                }
                .pointerInput(zoom) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val touchSlop = viewConfiguration.touchSlop
                        var isDragging = false
                        var isMultiTouch = false
                        do {
                            val event = awaitPointerEvent()
                            val pressed = event.changes.filter { it.pressed }
                            if (pressed.size >= 2) {
                                isMultiTouch = true
                                draftStart = null
                                draftEnd = null
                                zoom.transform(
                                    event.calculateCentroid(),
                                    event.calculateZoom(),
                                    event.calculatePan()
                                )
                                event.changes.forEach { it.consume() }
                            } else if (pressed.size == 1) {
                                val change = pressed.first()
                                if (!isDragging && (isMultiTouch || (change.position - down.position).getDistance() > touchSlop)) {
                                    isDragging = true
                                }
                                if (isDragging) {
                                    if (currentIsPanModeEnabled && zoom.isZoomed) {
                                        zoom.panBy(change.positionChange())
                                    } else if (!isMultiTouch) {
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
                        if (start != null && end != null && abs(end.x - start.x) > touchSlop && abs(
                                end.y - start.y
                            ) > touchSlop
                        ) {
                            val imageStart = zoom.toImage(start)
                            val imageEnd = zoom.toImage(end)
                            currentOnBoxDrawn(
                                FaceBox.fromCorners(
                                    imageStart.x,
                                    imageStart.y,
                                    imageEnd.x,
                                    imageEnd.y
                                )
                            )
                        }
                    }
                },
        ) {
            withTransform({
                translate(zoom.offset.x, zoom.offset.y)
                scale(zoom.scale, zoom.scale, pivot = Offset.Zero)
            }) {
                drawImage(
                    image,
                    dstSize = IntSize(image.width, image.height),
                    filterQuality = if (zoom.scale >= 2f) FilterQuality.None else FilterQuality.Low,
                )
            }
            val strokeWidth = dimens.overlayStroke.toPx()
            val dashEffect =
                PathEffect.dashPathEffect(floatArrayOf(strokeWidth * 4, strokeWidth * 3))
            if (showOverlay) {
                for (region in regions) {
                    val color =
                        if (region.blurred) FACE_MARKER_COLOR else UNBLURRED_MARKER_COLOR
                    val topLeft = zoom.toScreen(Offset(region.box.left, region.box.top))
                    val bottomRight = zoom.toScreen(Offset(region.box.right, region.box.bottom))
                    drawOutlinedRect(
                        topLeft,
                        bottomRight,
                        color,
                        colors.scrim,
                        strokeWidth,
                        dashEffect
                    )
                }
            }
            val start = draftStart
            val end = draftEnd
            if (start != null && end != null) {
                drawOutlinedRect(
                    Offset(min(start.x, end.x), min(start.y, end.y)),
                    Offset(max(start.x, end.x), max(start.y, end.y)),
                    DRAFT_MARKER_COLOR, colors.scrim, strokeWidth * DRAFT_STROKE_FACTOR, null,
                )
            }
            if (isFocused) drawRect(colors.primary, style = Stroke(strokeWidth * 1))
        }
    }
}

private fun DrawScope.drawOutlinedRect(
    topLeft: Offset,
    bottomRight: Offset,
    color: Color,
    haloColor: Color,
    strokeWidth: Float,
    pathEffect: PathEffect?,
) {
    val size = Size(bottomRight.x - topLeft.x, bottomRight.y - topLeft.y)
    drawRect(
        haloColor.copy(alpha = HALO_ALPHA),
        topLeft,
        size,
        style = Stroke(strokeWidth * 1, pathEffect = pathEffect)
    )
    drawRect(color, topLeft, size, style = Stroke(strokeWidth, pathEffect = pathEffect))
}

private const val HALO_ALPHA = 0.5f
private const val MARKER_ALPHA = 0.73f
private const val DRAFT_STROKE_FACTOR = 1.5f
private val DRAFT_MARKER_COLOR = Color(0xFF00B85E)
internal val FACE_MARKER_COLOR = DRAFT_MARKER_COLOR.copy(alpha = MARKER_ALPHA)
private val UNBLURRED_MARKER_COLOR = Color.White.copy(alpha = MARKER_ALPHA)
private const val SCROLL_ZOOM_BASE = 1.2f
private val KEY_PAN_STEP = 64.dp
