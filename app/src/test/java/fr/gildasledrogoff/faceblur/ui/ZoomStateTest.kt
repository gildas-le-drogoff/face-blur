// SPDX-FileCopyrightText: 2026 Gildas Le Drogoff and FaceBlur contributors
//
// SPDX-License-Identifier: AGPL-3.0-or-later

package fr.gildasledrogoff.faceblur.ui
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import org.junit.Assert.*
import org.junit.Test
class ZoomStateTest {
    private fun createFittedZoom() = ZoomState().apply { setBounds(Size(1000f, 1000f), Size(2000f, 1000f)) }
    private fun assertOffset(expected: Offset, actual: Offset) {
        assertEquals(expected.x, actual.x, 1e-3f)
        assertEquals(expected.y, actual.y, 1e-3f)
    }
    @Test
    fun `starts fitted and centred`() {
        val zoom = createFittedZoom()
        assertEquals(0.5f, zoom.scale, 1e-6f)
        assertOffset(Offset(0f, 250f), zoom.offset)
        assertFalse(zoom.isZoomed)
    }
    @Test
    fun `screen and image coordinates are inverse`() {
        val zoom = createFittedZoom()
        zoom.transform(Offset(300f, 400f), 3f, Offset(-20f, 10f))
        val imagePoint = Offset(1234f, 567f)
        assertOffset(imagePoint, zoom.toImage(zoom.toScreen(imagePoint)))
    }
    @Test
    fun `zooming keeps the point under the fingers in place`() {
        val zoom = createFittedZoom()
        val centroid = Offset(400f, 500f)
        val before = zoom.toImage(centroid)
        zoom.transform(centroid, 2f, Offset.Zero)
        assertTrue(zoom.isZoomed)
        assertOffset(before, zoom.toImage(centroid))
    }
    @Test
    fun `cannot zoom out past fit nor in past the maximum`() {
        val zoom = createFittedZoom()
        zoom.transform(Offset(500f, 500f), 0.1f, Offset.Zero)
        assertEquals(0.5f, zoom.scale, 1e-6f)
        zoom.transform(Offset(500f, 500f), 1000f, Offset.Zero)
        assertEquals(ZoomState.MAX_PIXEL_SCALE, zoom.scale, 1e-6f)
    }
    @Test
    fun `panning is clamped so the image never leaves an empty band`() {
        val zoom = createFittedZoom()
        zoom.transform(Offset(500f, 500f), 4f, Offset(100_000f, 100_000f))
        assertOffset(Offset.Zero, zoom.offset)
        zoom.transform(Offset(500f, 500f), 1f, Offset(-100_000f, -100_000f))
        assertOffset(Offset(-3000f, -1000f), zoom.offset)
    }
    @Test
    fun `zoom buttons keep the centre of the view in place`() {
        val zoom = createFittedZoom()
        val centre = Offset(500f, 500f)
        val before = zoom.toImage(centre)
        zoom.zoomBy(ZoomState.ZOOM_STEP)
        assertTrue(zoom.isZoomed)
        assertOffset(before, zoom.toImage(centre))
        zoom.zoomBy(1 / ZoomState.ZOOM_STEP)
        assertFalse(zoom.isZoomed)
    }
    @Test
    fun `cannot zoom in once at the maximum`() {
        val zoom = createFittedZoom()
        assertTrue(zoom.canZoomIn)
        repeat(10) { zoom.zoomBy(ZoomState.ZOOM_STEP) }
        assertEquals(ZoomState.MAX_PIXEL_SCALE, zoom.scale, 1e-6f)
        assertFalse(zoom.canZoomIn)
    }
    @Test
    fun `one finger panning moves the zoomed image`() {
        val zoom = createFittedZoom()
        zoom.zoomBy(4f)
        val before = zoom.offset
        zoom.panBy(Offset(-50f, -40f))
        assertOffset(before + Offset(-50f, -40f), zoom.offset)
    }
    @Test
    fun `reset returns to the fitted view`() {
        val zoom = createFittedZoom()
        zoom.transform(Offset(200f, 200f), 3f, Offset(50f, 50f))
        zoom.reset()
        assertEquals(0.5f, zoom.scale, 1e-6f)
        assertOffset(Offset(0f, 250f), zoom.offset)
    }
}
