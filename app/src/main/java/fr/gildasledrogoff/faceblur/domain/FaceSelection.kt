// SPDX-FileCopyrightText: 2026 Gildas Le Drogoff and FaceBlur contributors
//
// SPDX-License-Identifier: AGPL-3.0-or-later

package fr.gildasledrogoff.faceblur.domain

import kotlin.math.max
import kotlin.math.min

fun nmsIndices(candidates: List<Face>, scoreThresh: Float, iouThresh: Float = 0.4f): List<Int> {
    val sorted = candidates.indices
        .filter { candidates[it].score >= scoreThresh }
        .sortedByDescending { candidates[it].score }
    val kept = ArrayList<Int>()
    for (i in sorted) {
        if (kept.none { iou(candidates[it].box, candidates[i].box) > iouThresh }) kept += i
    }
    return kept
}

internal fun iou(a: FaceBox, b: FaceBox): Float {
    val w = max(0f, min(a.right, b.right) - max(a.left, b.left) + 1)
    val h = max(0f, min(a.bottom, b.bottom) - max(a.top, b.top) + 1)
    val inter = w * h
    val areaA = (a.width + 1) * (a.height + 1)
    val areaB = (b.width + 1) * (b.height + 1)
    return inter / (areaA + areaB - inter)
}

fun buildRegions(
    candidates: List<Face>,
    threshold: Float,
    manual: List<ManualRegion>,
    blurred: Set<RegionId>,
): List<FaceRegion> {
    val detected = nmsIndices(candidates, threshold).map { i ->
        val id = RegionId.Detected(i)
        FaceRegion(id, candidates[i].box, candidates[i].score, blurred = id in blurred)
    }
    val drawn = manual.map { m ->
        val id = RegionId.Manual(m.id)
        FaceRegion(id, m.box, score = null, blurred = id in blurred)
    }
    return detected + drawn
}

fun hitTest(regions: List<FaceRegion>, x: Float, y: Float, tolerance: Float): FaceRegion? =
    regions
        .filter { it.box.contains(x, y, tolerance) }
        .minWithOrNull(
            compareBy<FaceRegion> { distanceToBox(it.box, x, y) }
                .thenBy { it.box.width * it.box.height },
        )

private fun distanceToBox(box: FaceBox, x: Float, y: Float): Float {
    val dx = max(0f, max(box.left - x, x - box.right))
    val dy = max(0f, max(box.top - y, y - box.bottom))
    return dx * dx + dy * dy
}

fun tapBox(
    x: Float,
    y: Float,
    detected: List<FaceBox>,
    imageWidth: Int,
    imageHeight: Int
): FaceBox {
    val width: Float
    val height: Float
    if (detected.isEmpty()) {
        width = min(imageWidth, imageHeight) * FALLBACK_TAP_FRACTION
        height = width
    } else {
        width = detected.map { it.width }.sorted()[detected.size / 2]
        height = detected.map { it.height }.sorted()[detected.size / 2]
    }
    return FaceBox(x - width / 2, y - height / 2, x + width / 2, y + height / 2)
}

const val FALLBACK_TAP_FRACTION = 0.15f
