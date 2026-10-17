// SPDX-FileCopyrightText: 2026 Gildas Le Drogoff and FaceBlur contributors
//
// SPDX-License-Identifier: AGPL-3.0-or-later

package fr.gildasledrogoff.faceblur.data

import fr.gildasledrogoff.faceblur.data.onnx.Tensor
import fr.gildasledrogoff.faceblur.domain.Face
import fr.gildasledrogoff.faceblur.domain.FaceBox
import kotlin.math.exp
import kotlin.math.sqrt

internal val YUNET_STRIDES = intArrayOf(8, 16, 32)
internal fun decodeYuNet(
    outputs: Map<String, Tensor>,
    inputWidth: Int,
    inputHeight: Int,
    scaleX: Float,
    scaleY: Float,
    minScore: Float,
): List<Face> {
    val faces = ArrayList<Face>()
    for (stride in YUNET_STRIDES) {
        val cols = inputWidth / stride
        val rows = inputHeight / stride
        val cls = outputs.getValue("cls_$stride").data
        val obj = outputs.getValue("obj_$stride").data
        val bbox = outputs.getValue("bbox_$stride").data
        val keypoints = outputs.getValue("kps_$stride").data
        require(cls.size == rows * cols) { "YuNet: cls_$stride has ${cls.size} values for ${cols}x$rows" }
        for (idx in cls.indices) {
            val score = sqrt(cls[idx].coerceIn(0f, 1f) * obj[idx].coerceIn(0f, 1f))
            if (score < minScore) continue
            val c = idx % cols
            val r = idx / cols
            val cx = (c + bbox[idx * 4]) * stride
            val cy = (r + bbox[idx * 4 + 1]) * stride
            val w = exp(bbox[idx * 4 + 2]) * stride
            val h = exp(bbox[idx * 4 + 3]) * stride
            val box = FaceBox(
                (cx - w / 2) / scaleX,
                (cy - h / 2) / scaleY,
                (cx + w / 2) / scaleX,
                (cy + h / 2) / scaleY,
            )
            val points = (0 until 5).map { n ->
                (keypoints[idx * 10 + 2 * n] + c) * stride / scaleX to (keypoints[idx * 10 + 2 * n + 1] + r) * stride / scaleY
            }
            faces += Face(box, score, points)
        }
    }
    return faces
}
