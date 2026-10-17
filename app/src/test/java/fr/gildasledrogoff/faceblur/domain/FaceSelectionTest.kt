// SPDX-FileCopyrightText: 2026 Gildas Le Drogoff and FaceBlur contributors
//
// SPDX-License-Identifier: AGPL-3.0-or-later

package fr.gildasledrogoff.faceblur.domain
import org.junit.Assert.*
import org.junit.Test
class FaceSelectionTest {
    private fun createFace(left: Float, top: Float, right: Float, bottom: Float, score: Float) =
        Face(FaceBox(left, top, right, bottom), score, emptyList())
    private val candidates = listOf(
        createFace(0f, 0f, 100f, 100f, 0.9f),
        createFace(5f, 5f, 105f, 105f, 0.8f),
        createFace(300f, 0f, 350f, 50f, 0.6f),
        createFace(500f, 500f, 520f, 520f, 0.3f),
    )
    @Test
    fun `nms keeps the best overlapping box and filters by score`() {
        assertEquals(listOf(0, 2), nmsIndices(candidates, 0.5f))
        assertEquals(listOf(0, 2, 3), nmsIndices(candidates, 0.2f))
        assertEquals(emptyList<Int>(), nmsIndices(candidates, 0.95f))
    }
    @Test
    fun `candidate indices are stable when the threshold changes`() {
        val highThresholdIndices = nmsIndices(candidates, 0.7f)
        val lowThresholdIndices = nmsIndices(candidates, 0.2f)
        assertTrue(lowThresholdIndices.containsAll(highThresholdIndices))
    }
    @Test
    fun `regions combine detections and manual zones with their blur state`() {
        val manual = listOf(ManualRegion(7, FaceBox(10f, 200f, 60f, 260f)))
        val blurred = setOf<RegionId>(RegionId.Detected(0), RegionId.Manual(7))
        val regions = buildRegions(candidates, 0.5f, manual, blurred)
        assertEquals(listOf(RegionId.Detected(0), RegionId.Detected(2), RegionId.Manual(7)), regions.map { it.id })
        assertEquals(listOf(true, false, true), regions.map { it.blurred })
        assertNull(regions.last().score)
        assertTrue(regions.last().isManual)
    }
    @Test
    fun `detected faces are not blurred by default`() {
        assertTrue(buildRegions(candidates, 0.2f, emptyList(), emptySet()).none { it.blurred })
    }
    @Test
    fun `blur choice survives a threshold round trip`() {
        val blurred = setOf<RegionId>(RegionId.Detected(3))
        assertTrue(buildRegions(candidates, 0.5f, emptyList(), blurred).none { it.id == RegionId.Detected(3) })
        val restored = buildRegions(candidates, 0.2f, emptyList(), blurred).single { it.id == RegionId.Detected(3) }
        assertTrue(restored.blurred)
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
    fun `tap box takes the median face size, or a share of the image without faces`() {
        val faces = listOf(FaceBox(0f, 0f, 10f, 20f), FaceBox(0f, 0f, 40f, 60f), FaceBox(0f, 0f, 400f, 400f))
        assertEquals(FaceBox(80f, 70f, 120f, 130f), tapBox(100f, 100f, faces, 1000, 800))
        val halfSide = 800 * FALLBACK_TAP_FRACTION / 2
        assertEquals(FaceBox(100f - halfSide, 100f - halfSide, 100f + halfSide, 100f + halfSide), tapBox(100f, 100f, emptyList(), 1000, 800))
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
    fun `export defaults to webp with metadata stripped`() {
        assertEquals(ExportFormat.WEBP, ExportOptions().format)
        assertFalse(ExportOptions().keepMetadata)
    }
}
