package org.example.faceblur.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FaceSelectionTest {

    private fun face(l: Float, t: Float, r: Float, b: Float, score: Float) =
        Face(FaceBox(l, t, r, b), score, emptyList())

    private val candidates = listOf(
        face(0f, 0f, 100f, 100f, 0.9f), // 0
        face(5f, 5f, 105f, 105f, 0.8f), // 1 : recouvre 0 → supprimé
        face(300f, 0f, 350f, 50f, 0.6f), // 2
        face(500f, 500f, 520f, 520f, 0.3f), // 3 : sous le seuil par défaut
    )

    @Test
    fun `nms keeps the best overlapping box and filters by score`() {
        assertEquals(listOf(0, 2), nmsIndices(candidates, 0.5f))
        assertEquals(listOf(0, 2, 3), nmsIndices(candidates, 0.2f))
        assertEquals(emptyList<Int>(), nmsIndices(candidates, 0.95f))
    }

    @Test
    fun `candidate indices are stable when the threshold changes`() {
        val high = nmsIndices(candidates, 0.7f)
        val low = nmsIndices(candidates, 0.2f)
        assertTrue(low.containsAll(high))
    }

    @Test
    fun `regions combine detections and manual zones with their exclusion state`() {
        val manual = listOf(ManualRegion(7, FaceBox(10f, 200f, 60f, 260f)))
        val excluded = setOf<RegionId>(RegionId.Detected(2))
        val regions = buildRegions(candidates, 0.5f, manual, excluded)

        assertEquals(listOf(RegionId.Detected(0), RegionId.Detected(2), RegionId.Manual(7)), regions.map { it.id })
        assertEquals(listOf(true, false, true), regions.map { it.blurred })
        assertNull(regions.last().score)
        assertTrue(regions.last().isManual)
    }

    @Test
    fun `exclusion survives a threshold round trip`() {
        val excluded = setOf<RegionId>(RegionId.Detected(3))
        assertTrue(buildRegions(candidates, 0.5f, emptyList(), excluded).none { it.id == RegionId.Detected(3) })
        val back = buildRegions(candidates, 0.2f, emptyList(), excluded).single { it.id == RegionId.Detected(3) }
        assertFalse(back.blurred)
    }

    @Test
    fun `hit test prefers the smallest region under the finger`() {
        val big = FaceRegion(RegionId.Detected(0), FaceBox(0f, 0f, 200f, 200f), 0.9f, true)
        val small = FaceRegion(RegionId.Detected(1), FaceBox(50f, 50f, 70f, 70f), 0.9f, true)
        assertEquals(small.id, hitTest(listOf(big, small), 60f, 60f, tolerance = 0f)?.id)
        assertEquals(big.id, hitTest(listOf(big, small), 150f, 150f, tolerance = 0f)?.id)
    }

    @Test
    fun `hit test uses the tolerance for tiny faces and picks the closest`() {
        val left = FaceRegion(RegionId.Detected(0), FaceBox(0f, 0f, 10f, 10f), 0.9f, true)
        val right = FaceRegion(RegionId.Detected(1), FaceBox(20f, 0f, 30f, 10f), 0.9f, true)
        assertNull(hitTest(listOf(left, right), 15f, 5f, tolerance = 2f))
        assertEquals(right.id, hitTest(listOf(left, right), 16f, 5f, tolerance = 6f)?.id)
        assertEquals(left.id, hitTest(listOf(left, right), 13f, 5f, tolerance = 6f)?.id)
    }

    @Test
    fun `toggling twice restores the selection`() {
        val id = RegionId.Manual(1)
        val once = emptySet<RegionId>().toggled(id)
        assertEquals(setOf(id), once)
        assertEquals(emptySet<RegionId>(), once.toggled(id))
    }

    @Test
    fun `face box helpers normalise, expand and clamp`() {
        val box = FaceBox.fromCorners(50f, 80f, 10f, 20f)
        assertEquals(FaceBox(10f, 20f, 50f, 80f), box)
        assertEquals(FaceBox(6f, 14f, 54f, 86f), box.expanded(0.1f))
        assertEquals(FaceBox(0f, 0f, 40f, 30f), FaceBox(-5f, -5f, 60f, 90f).clampedTo(40, 30))
    }

    @Test
    fun `export file name carries the format extension`() {
        assertEquals("IMG_1_blur_42.webp", exportFileName("IMG_1", "blur", ExportFormat.WEBP, 42))
        assertEquals("IMG_1_42.png", exportFileName("IMG_1", "", ExportFormat.PNG, 42))
        assertEquals("image/jpeg", ExportFormat.JPEG.mimeType)
    }

    @Test
    fun `metadata is stripped by default`() {
        assertFalse(ExportOptions().keepMetadata)
    }
}
