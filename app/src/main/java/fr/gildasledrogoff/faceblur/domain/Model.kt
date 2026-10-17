// SPDX-FileCopyrightText: 2026 Gildas Le Drogoff and FaceBlur contributors
//
// SPDX-License-Identifier: AGPL-3.0-or-later

package fr.gildasledrogoff.faceblur.domain

import kotlin.math.max
import kotlin.math.min

data class FaceBox(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val centerX: Float get() = (left + right) / 2
    val centerY: Float get() = (top + bottom) / 2
    fun contains(x: Float, y: Float, tolerance: Float = 0f): Boolean =
        x >= left - tolerance && x <= right + tolerance && y >= top - tolerance && y <= bottom + tolerance

    fun expanded(fraction: Float): FaceBox = FaceBox(
        left - width * fraction,
        top - height * fraction,
        right + width * fraction,
        bottom + height * fraction,
    )

    fun clampedTo(imageWidth: Int, imageHeight: Int): FaceBox = FaceBox(
        left.coerceIn(0f, imageWidth.toFloat()),
        top.coerceIn(0f, imageHeight.toFloat()),
        right.coerceIn(0f, imageWidth.toFloat()),
        bottom.coerceIn(0f, imageHeight.toFloat()),
    )

    companion object {
        fun fromCorners(x0: Float, y0: Float, x1: Float, y1: Float): FaceBox =
            FaceBox(min(x0, x1), min(y0, y1), max(x0, x1), max(y0, y1))
    }
}

data class Face(val box: FaceBox, val score: Float, val keypoints: List<Pair<Float, Float>>)
sealed interface RegionId {
    data class Detected(val candidateIndex: Int) : RegionId
    data class Manual(val id: Int) : RegionId
}

data class ManualRegion(val id: Int, val box: FaceBox)
data class FaceRegion(val id: RegionId, val box: FaceBox, val score: Float?, val blurred: Boolean) {
    val isManual: Boolean get() = id is RegionId.Manual
}

enum class BlurType { GAUSSIAN, MOSAIC }

const val MIN_BLUR_STRENGTH = 1
const val MAX_BLUR_STRENGTH = 10
const val DEFAULT_BLUR_STRENGTH = 6
const val MIN_TILE_PERCENT = 5
const val MAX_TILE_PERCENT = 50
const val TILE_PERCENT_STEP = 5
const val DEFAULT_TILE_PERCENT = 25

data class BlurSettings(
    val type: BlurType = BlurType.MOSAIC,
    val strength: Int = DEFAULT_BLUR_STRENGTH,
    val tilePercent: Int = DEFAULT_TILE_PERCENT,
)

enum class ExportFormat(val extension: String, val mimeType: String) {
    JPEG("jpg", "image/jpeg"),
    PNG("png", "image/png"),
    WEBP("webp", "image/webp"),
}

data class ExportOptions(
    val format: ExportFormat = ExportFormat.WEBP,
    val keepMetadata: Boolean = false
)

fun exportFileName(
    baseName: String,
    suffix: String,
    format: ExportFormat,
    timestampMillis: Long
): String =
    listOf(baseName, suffix.ifEmpty { null }, timestampMillis.toString())
        .filterNotNull()
        .joinToString("_") + "." + format.extension
