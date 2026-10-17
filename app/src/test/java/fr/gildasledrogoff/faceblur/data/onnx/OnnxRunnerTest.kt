// SPDX-FileCopyrightText: 2026 Gildas Le Drogoff and FaceBlur contributors
//
// SPDX-License-Identifier: AGPL-3.0-or-later

package fr.gildasledrogoff.faceblur.data.onnx
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs
import kotlin.math.max
class OnnxRunnerTest {
    private val model = OnnxModel.parse(File("src/main/assets/face_detection_yunet_2026may.onnx").readBytes())
    @Test
    fun `parses YuNet graph`() {
        assertEquals(listOf("input"), model.inputs)
        assertEquals(12, model.outputs.size)
        assertEquals(53, model.nodes.count { it.opType == "Conv" })
        assertEquals(longArrayOf(1, -1, 4).toList(), model.longInitializers.getValue("362").toList())
    }
    @Test
    fun `matches onnxruntime on a non-square input`() {
        val height = 96
        val width = 160
        val input = FloatArray(3 * height * width)
        for (channel in 0 until 3) for (row in 0 until height) for (column in 0 until width) {
            input[(channel * height + row) * width + column] =
                ((channel * 37 + row * 11 + column * 5 + (column * row) % 13) % 256).toFloat()
        }
        val outputs = OnnxRunner(model).run(Tensor(intArrayOf(1, 3, height, width), input))
        for ((name, expected) in REFERENCE) {
            val data = outputs.getValue(name).data
            val actual = floatArrayOf(
                data.sum(),
                data.fold(0f) { total, value -> total + abs(value) },
                data[0],
                data[data.size / 3],
                data[data.size - 1],
            )
            for (index in expected.indices) {
                val tolerance = 1e-4f + 1e-3f * max(1f, abs(expected[index]))
                assertTrue(
                    "$name[$index]: ${actual[index]} vs ${expected[index]}",
                    abs(actual[index] - expected[index]) <= tolerance,
                )
            }
        }
        assertEquals(listOf(1, 240, 4), outputs.getValue("bbox_8").shape.toList())
    }
    private companion object {
        val REFERENCE = mapOf(
            "cls_8" to floatArrayOf(165.007172f, 165.007172f, 0.621207f, 0.642952f, 0.626565f),
            "cls_16" to floatArrayOf(30.039932f, 30.039932f, 0.547497f, 0.514658f, 0.536208f),
            "cls_32" to floatArrayOf(10.369347f, 10.369347f, 0.681173f, 0.689971f, 0.655346f),
            "obj_8" to floatArrayOf(0.050512f, 0.050512f, 0.000000f, 0.000000f, 0.000000f),
            "obj_16" to floatArrayOf(0.000110f, 0.000110f, 0.000009f, 0.000004f, 0.000002f),
            "obj_32" to floatArrayOf(2.538300f, 2.538300f, 0.004139f, 0.019045f, 0.007418f),
            "bbox_8" to floatArrayOf(1170.463877f, 1243.333465f, 0.908860f, 0.549606f, 1.546678f),
            "bbox_16" to floatArrayOf(94.342369f, 94.342369f, 0.574577f, 0.615291f, 0.271853f),
            "bbox_32" to floatArrayOf(56.664696f, 63.319867f, 1.764646f, 1.959104f, 1.238513f),
            "kps_8" to floatArrayOf(2303.416776f, 2979.196152f, 0.765798f, 1.490006f, 0.952168f),
            "kps_16" to floatArrayOf(247.207269f, 247.263865f, 0.269109f, 0.312561f, 0.498224f),
            "kps_32" to floatArrayOf(153.578947f, 162.227993f, 0.465520f, 0.605811f, 0.662315f),
        )
    }
}
