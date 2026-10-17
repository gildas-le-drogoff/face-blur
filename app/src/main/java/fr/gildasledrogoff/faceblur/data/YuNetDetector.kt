// SPDX-FileCopyrightText: 2026 Gildas Le Drogoff and FaceBlur contributors
//
// SPDX-License-Identifier: AGPL-3.0-or-later

package fr.gildasledrogoff.faceblur.data

import android.content.Context
import android.graphics.*
import androidx.core.graphics.createBitmap
import fr.gildasledrogoff.faceblur.FaceDetectionLog
import fr.gildasledrogoff.faceblur.data.onnx.OnnxModel
import fr.gildasledrogoff.faceblur.data.onnx.OnnxRunner
import fr.gildasledrogoff.faceblur.data.onnx.Tensor
import fr.gildasledrogoff.faceblur.domain.Face
import kotlin.math.max
import kotlin.math.roundToInt

class YuNetDetector(context: Context) {
    private val runner: OnnxRunner = FaceDetectionLog.timed(TAG, "Loading $MODEL") {
        OnnxRunner(OnnxModel.parse(context.assets.open(MODEL).use { it.readBytes() }))
    }

    fun detectCandidates(bitmap: Bitmap, minScore: Float = 0.2f): List<Face> {
        val scale = INPUT.toFloat() / max(bitmap.width, bitmap.height)
        val newW = max(1, (bitmap.width * scale).roundToInt())
        val newH = max(1, (bitmap.height * scale).roundToInt())
        val padW = (newW + ALIGN - 1) / ALIGN * ALIGN
        val padH = (newH + ALIGN - 1) / ALIGN * ALIGN
        val padded = createBitmap(padW, padH)
        Canvas(padded).apply {
            drawColor(Color.BLACK)
            drawBitmap(bitmap, null, Rect(0, 0, newW, newH), Paint(Paint.FILTER_BITMAP_FLAG))
        }
        val area = padW * padH
        val pixels = IntArray(area)
        padded.getPixels(pixels, 0, padW, 0, 0, padW, padH)
        padded.recycle()
        val input = FloatArray(3 * area)
        for (i in 0 until area) {
            val p = pixels[i]
            input[i] = (p and 0xFF).toFloat()
            input[area + i] = ((p shr 8) and 0xFF).toFloat()
            input[2 * area + i] = ((p shr 16) and 0xFF).toFloat()
        }
        val outputs = FaceDetectionLog.timed(TAG, "YuNet inference ${padW}x$padH") {
            runner.run(Tensor(intArrayOf(1, 3, padH, padW), input))
        }
        val faces = decodeYuNet(
            outputs,
            padW,
            padH,
            newW.toFloat() / bitmap.width,
            newH.toFloat() / bitmap.height,
            minScore,
        )
        FaceDetectionLog.d(TAG) { "${faces.size} candidates >= $minScore on ${bitmap.width}x${bitmap.height}" }
        return faces
    }

    private companion object {
        const val TAG = "Detector"
        const val MODEL = "face_detection_yunet_2026may.onnx"
        const val INPUT = 640
        const val ALIGN = 32
    }
}
