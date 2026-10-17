// SPDX-FileCopyrightText: 2026 Gildas Le Drogoff and FaceBlur contributors
//
// SPDX-License-Identifier: AGPL-3.0-or-later

package fr.gildasledrogoff.faceblur.data
import fr.gildasledrogoff.faceblur.data.onnx.Tensor
import fr.gildasledrogoff.faceblur.domain.FaceBox
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.ln
class YuNetDecoderTest {
    private fun createEmptyOutputs(): Map<String, Tensor> = YUNET_STRIDES.flatMap { stride ->
        val cells = (32 / stride) * (32 / stride)
        listOf(
            "cls_$stride" to Tensor(intArrayOf(1, cells, 1), FloatArray(cells)),
            "obj_$stride" to Tensor(intArrayOf(1, cells, 1), FloatArray(cells)),
            "bbox_$stride" to Tensor(intArrayOf(1, cells, 4), FloatArray(cells * 4)),
            "kps_$stride" to Tensor(intArrayOf(1, cells, 10), FloatArray(cells * 10)),
        )
    }.toMap()
    @Test
    fun `decodes box, score and landmarks in source coordinates`() {
        val outputs = createEmptyOutputs()
        val cellIndex = 2 * 4 + 1
        outputs.getValue("cls_8").data[cellIndex] = 0.81f
        outputs.getValue("obj_8").data[cellIndex] = 1f
        outputs.getValue("bbox_8").data.apply {
            this[cellIndex * 4] = 0.5f
            this[cellIndex * 4 + 1] = 0.5f
            this[cellIndex * 4 + 2] = ln(2f)
            this[cellIndex * 4 + 3] = ln(4f)
        }
        val faces = decodeYuNet(outputs, 32, 32, 0.5f, 0.5f, minScore = 0.5f)
        assertEquals(1, faces.size)
        val face = faces.single()
        assertEquals(0.9f, face.score, 1e-5f)
        val expected = FaceBox(8f, 8f, 40f, 72f)
        assertEquals(expected.left, face.box.left, 1e-3f)
        assertEquals(expected.top, face.box.top, 1e-3f)
        assertEquals(expected.right, face.box.right, 1e-3f)
        assertEquals(expected.bottom, face.box.bottom, 1e-3f)
        assertEquals(5, face.keypoints.size)
        assertEquals(16f, face.keypoints[0].first, 1e-3f)
        assertEquals(32f, face.keypoints[0].second, 1e-3f)
    }
    @Test
    fun `drops candidates below the minimum score and clamps raw scores`() {
        val outputs = createEmptyOutputs()
        outputs.getValue("cls_16").data[0] = 1.2f
        outputs.getValue("obj_16").data[0] = 0.01f
        outputs.getValue("cls_32").data[0] = 1.5f
        outputs.getValue("obj_32").data[0] = 2f
        val faces = decodeYuNet(outputs, 32, 32, 1f, 1f, minScore = 0.2f)
        assertEquals(1, faces.size)
        assertEquals(1f, faces.single().score, 1e-6f)
    }
}
