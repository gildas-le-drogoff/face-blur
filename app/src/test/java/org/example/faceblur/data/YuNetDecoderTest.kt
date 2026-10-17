package org.example.faceblur.data

import org.example.faceblur.data.onnx.Tensor
import org.example.faceblur.domain.FaceBox
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.ln

class YuNetDecoderTest {
    /** Entrée 32×32 : grilles 4×4, 2×2 et 1×1. */
    private fun outputs(): Map<String, Tensor> = YUNET_STRIDES.flatMap { s ->
        val cells = (32 / s) * (32 / s)
        listOf(
            "cls_$s" to Tensor(intArrayOf(1, cells, 1), FloatArray(cells)),
            "obj_$s" to Tensor(intArrayOf(1, cells, 1), FloatArray(cells)),
            "bbox_$s" to Tensor(intArrayOf(1, cells, 4), FloatArray(cells * 4)),
            "kps_$s" to Tensor(intArrayOf(1, cells, 10), FloatArray(cells * 10)),
        )
    }.toMap()

    @Test
    fun `decodes box, score and landmarks in source coordinates`() {
        val out = outputs()
        // Stride 8, colonne 1, ligne 2.
        val idx = 2 * 4 + 1
        out.getValue("cls_8").data[idx] = 0.81f
        out.getValue("obj_8").data[idx] = 1f
        out.getValue("bbox_8").data.apply {
            this[idx * 4] = 0.5f
            this[idx * 4 + 1] = 0.5f
            this[idx * 4 + 2] = ln(2f)
            this[idx * 4 + 3] = ln(4f)
        }

        // Image d'origine deux fois plus grande que l'entrée.
        val faces = decodeYuNet(out, 32, 32, 0.5f, 0.5f, minScore = 0.5f)

        assertEquals(1, faces.size)
        val face = faces.single()
        assertEquals(0.9f, face.score, 1e-5f)
        val expected = FaceBox(8f, 8f, 40f, 72f)
        assertEquals(expected.left, face.box.left, 1e-3f)
        assertEquals(expected.top, face.box.top, 1e-3f)
        assertEquals(expected.right, face.box.right, 1e-3f)
        assertEquals(expected.bottom, face.box.bottom, 1e-3f)
        assertEquals(5, face.kps.size)
        assertEquals(16f, face.kps[0].first, 1e-3f)
        assertEquals(32f, face.kps[0].second, 1e-3f)
    }

    @Test
    fun `drops candidates below the minimum score and clamps raw scores`() {
        val out = outputs()
        out.getValue("cls_16").data[0] = 1.2f
        out.getValue("obj_16").data[0] = 0.01f
        out.getValue("cls_32").data[0] = 1.5f
        out.getValue("obj_32").data[0] = 2f

        val faces = decodeYuNet(out, 32, 32, 1f, 1f, minScore = 0.2f)

        assertEquals(1, faces.size)
        assertEquals(1f, faces.single().score, 1e-6f)
    }
}
