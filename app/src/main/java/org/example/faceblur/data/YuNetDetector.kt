package org.example.faceblur.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import androidx.core.graphics.createBitmap
import org.example.faceblur.FaceDetectionLog
import org.example.faceblur.data.onnx.OnnxModel
import org.example.faceblur.data.onnx.OnnxRunner
import org.example.faceblur.data.onnx.Tensor
import org.example.faceblur.domain.Face
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Détecteur de visages YuNet (face_detection_yunet_2026may.onnx, OpenCV Zoo, licence MIT),
 * exécuté par [OnnxRunner] : aucune bibliothèque native.
 */
class YuNetDetector(context: Context) {
    private val runner: OnnxRunner = FaceDetectionLog.timed(TAG, "Loading $MODEL") {
        OnnxRunner(OnnxModel.parse(context.assets.open(MODEL).use { it.readBytes() }))
    }

    /** Toutes les détections au-dessus de [minScore], avant NMS (voir nmsIndices). */
    fun detectCandidates(bitmap: Bitmap, minScore: Float = 0.2f): List<Face> {
        // Plus grand côté ramené à INPUT, ratio conservé, complété en noir (en bas et à
        // droite) jusqu'à un multiple de 32 comme FaceDetectorYN.
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

        // NCHW, BGR, valeurs 0..255 sans normalisation.
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
