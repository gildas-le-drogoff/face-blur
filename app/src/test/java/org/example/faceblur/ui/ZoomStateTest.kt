package org.example.faceblur.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ZoomStateTest {

    /** Image 2000×1000 dans une vue 1000×1000 : ajustement 0,5, centrée verticalement. */
    private fun fitted() = ZoomState().apply { setBounds(Size(1000f, 1000f), Size(2000f, 1000f)) }

    private fun assertOffset(expected: Offset, actual: Offset) {
        assertEquals(expected.x, actual.x, 1e-3f)
        assertEquals(expected.y, actual.y, 1e-3f)
    }

    @Test
    fun `starts fitted and centred`() {
        val zoom = fitted()
        assertEquals(0.5f, zoom.scale, 1e-6f)
        assertOffset(Offset(0f, 250f), zoom.offset)
        assertFalse(zoom.isZoomed)
    }

    @Test
    fun `screen and image coordinates are inverse`() {
        val zoom = fitted()
        zoom.transform(Offset(300f, 400f), 3f, Offset(-20f, 10f))
        val p = Offset(1234f, 567f)
        assertOffset(p, zoom.toImage(zoom.toScreen(p)))
    }

    @Test
    fun `zooming keeps the point under the fingers in place`() {
        val zoom = fitted()
        val centroid = Offset(400f, 500f)
        val before = zoom.toImage(centroid)
        zoom.transform(centroid, 2f, Offset.Zero)
        assertTrue(zoom.isZoomed)
        assertOffset(before, zoom.toImage(centroid))
    }

    @Test
    fun `cannot zoom out past fit nor in past the maximum`() {
        val zoom = fitted()
        zoom.transform(Offset(500f, 500f), 0.1f, Offset.Zero)
        assertEquals(0.5f, zoom.scale, 1e-6f)
        zoom.transform(Offset(500f, 500f), 1000f, Offset.Zero)
        assertEquals(ZoomState.MAX_PIXEL_SCALE, zoom.scale, 1e-6f)
    }

    @Test
    fun `panning is clamped so the image never leaves an empty band`() {
        val zoom = fitted()
        zoom.transform(Offset(500f, 500f), 4f, Offset(100_000f, 100_000f))
        // Échelle 2 : image 4000×2000 dans 1000×1000 → décalage max (0, 0).
        assertOffset(Offset.Zero, zoom.offset)
        zoom.transform(Offset(500f, 500f), 1f, Offset(-100_000f, -100_000f))
        assertOffset(Offset(-3000f, -1000f), zoom.offset)
    }

    @Test
    fun `reset returns to the fitted view`() {
        val zoom = fitted()
        zoom.transform(Offset(200f, 200f), 3f, Offset(50f, 50f))
        zoom.reset()
        assertEquals(0.5f, zoom.scale, 1e-6f)
        assertOffset(Offset(0f, 250f), zoom.offset)
    }
}
