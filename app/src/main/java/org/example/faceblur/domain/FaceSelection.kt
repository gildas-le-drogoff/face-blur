package org.example.faceblur.domain

import kotlin.math.max
import kotlin.math.min

/**
 * Filtre par score puis NMS glouton (IoU 0.4 comme InsightFace).
 * Renvoie les index des candidats conservés, par score décroissant : un index reste
 * stable quand le seuil varie, ce qui permet d'y rattacher l'exclusion d'un visage.
 */
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

// Même convention que la NMS d'InsightFace (+1 sur les dimensions).
internal fun iou(a: FaceBox, b: FaceBox): Float {
    val w = max(0f, min(a.right, b.right) - max(a.left, b.left) + 1)
    val h = max(0f, min(a.bottom, b.bottom) - max(a.top, b.top) + 1)
    val inter = w * h
    val areaA = (a.width + 1) * (a.height + 1)
    val areaB = (b.width + 1) * (b.height + 1)
    return inter / (areaA + areaB - inter)
}

/** Visages détectés retenus au [threshold] puis zones manuelles, avec leur état flouté/exclu. */
fun buildRegions(
    candidates: List<Face>,
    threshold: Float,
    manual: List<ManualRegion>,
    excluded: Set<RegionId>,
): List<FaceRegion> {
    val detected = nmsIndices(candidates, threshold).map { i ->
        val id = RegionId.Detected(i)
        FaceRegion(id, candidates[i].box, candidates[i].score, blurred = id !in excluded)
    }
    val drawn = manual.map { m ->
        val id = RegionId.Manual(m.id)
        FaceRegion(id, m.box, score = null, blurred = id !in excluded)
    }
    return detected + drawn
}

/**
 * Zone touchée en ([x], [y]) (coordonnées image), [tolerance] comprise.
 * Si plusieurs zones se recouvrent (foule), la plus proche du doigt l'emporte,
 * puis la plus petite, pour que les visages lointains restent sélectionnables.
 */
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

/** Bascule flouté ↔ exclu pour [id]. */
fun Set<RegionId>.toggled(id: RegionId): Set<RegionId> = if (id in this) this - id else this + id
