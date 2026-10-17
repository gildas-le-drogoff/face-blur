package org.example.faceblur.data.onnx

import org.example.faceblur.FaceDetectionLog
import java.util.stream.IntStream
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/**
 * Exécute un graphe ONNX float32 de batch 1, en Kotlin pur et multithread.
 * Opérateurs couverts : ceux de YuNet (Conv, Relu, Sigmoid, MaxPool, Resize « nearest »,
 * Add, Transpose, Reshape). Tout autre opérateur lève une erreur explicite.
 */
class OnnxRunner(private val model: OnnxModel) {
    /** Dernier nœud lisant chaque valeur : libérée ensuite pour limiter la mémoire. */
    private val lastUse: Map<String, Int> = HashMap<String, Int>().apply {
        model.nodes.forEachIndexed { i, n -> n.inputs.forEach { put(it, i) } }
    }

    init {
        val unsupported = model.nodes.map { it.opType }.toSet() - SUPPORTED
        require(unsupported.isEmpty()) { "ONNX: unsupported operators $unsupported" }
    }

    fun run(input: Tensor): Map<String, Tensor> {
        val values = HashMap<String, Tensor>(model.floatInits)
        values[model.inputs.single()] = input
        val nanosPerOp = HashMap<String, Long>()
        var i = 0
        while (i < model.nodes.size) {
            val node = model.nodes[i]
            val start = System.nanoTime()
            // Conv suivie d'une Relu qui est seule à lire sa sortie : Relu appliquée dans la Conv.
            val next = model.nodes.getOrNull(i + 1)
            val fuseRelu = node.opType == "Conv" && next?.opType == "Relu" &&
                next.inputs.single() == node.outputs.single() && lastUse[node.outputs.single()] == i + 1 &&
                node.outputs.single() !in model.outputs
            val result = eval(node, values, fuseRelu)
            val op = if (fuseRelu) "Conv+Relu" else node.opType
            nanosPerOp[op] = (nanosPerOp[op] ?: 0L) + System.nanoTime() - start
            values[(if (fuseRelu) next!! else node).outputs.single()] = result
            for (name in node.inputs) {
                if (lastUse[name] == i && name !in model.outputs && name !in model.floatInits) values.remove(name)
            }
            i += if (fuseRelu) 2 else 1
        }
        FaceDetectionLog.d(TAG) {
            nanosPerOp.entries.sortedByDescending { it.value }
                .joinToString(prefix = "Time per operator: ") { "${it.key} ${it.value / 1_000_000} ms" }
        }
        return model.outputs.associateWith { values.getValue(it) }
    }

    private fun eval(node: OnnxNode, values: Map<String, Tensor>, fuseRelu: Boolean): Tensor {
        fun input(i: Int): Tensor = values.getValue(node.inputs[i])
        return when (node.opType) {
            "Conv" -> conv(
                node,
                input(0),
                input(1),
                node.inputs.getOrNull(2)?.takeIf { it.isNotEmpty() }?.let(values::getValue),
                fuseRelu,
            )
            "Relu" -> map(input(0)) { max(it, 0f) }
            "Sigmoid" -> map(input(0)) { 1f / (1f + exp(-it)) }
            "MaxPool" -> maxPool(node, input(0))
            "Resize" -> resize(node, input(0), node.inputs.getOrNull(2)?.let(values::get))
            "Add" -> add(input(0), input(1))
            "Transpose" -> transpose(input(0), node.ints("perm"))
            "Reshape" -> reshape(input(0), model.longInits.getValue(node.inputs[1]))
            else -> error("ONNX: unsupported operator ${node.opType}")
        }
    }

    private fun conv(node: OnnxNode, x: Tensor, w: Tensor, b: Tensor?, relu: Boolean): Tensor {
        require(node.string("auto_pad", "NOTSET") == "NOTSET") { "Conv: auto_pad not supported" }
        val (n, c, h, wd) = x.shape
        require(n == 1) { "Conv: batch must be 1" }
        val (m, cg, kh, kw) = w.shape
        val group = node.int("group", 1).toInt()
        require(cg * group == c && m % group == 0) { "Conv: bad group $group for ${x.shape.contentToString()}" }
        val pads = node.ints("pads") ?: LongArray(4)
        val strides = node.ints("strides") ?: longArrayOf(1, 1)
        val dil = node.ints("dilations") ?: longArrayOf(1, 1)
        val pt = pads[0].toInt()
        val pl = pads[1].toInt()
        val sh = strides[0].toInt()
        val sw = strides[1].toInt()
        val dh = dil[0].toInt()
        val dw = dil[1].toInt()
        val oh = (h + pt + pads[2].toInt() - dh * (kh - 1) - 1) / sh + 1
        val ow = (wd + pl + pads[3].toInt() - dw * (kw - 1) - 1) / sw + 1
        val out = FloatArray(m * oh * ow)
        val bias = b?.data
        val xs = x.data
        val ws = w.data

        if (kh == 1 && kw == 1 && sh == 1 && sw == 1 && pt == 0 && pl == 0 && group == 1) {
            // 1×1 : produit matriciel, découpé en blocs de pixels qui restent en cache,
            // 4 canaux d'entrée par passe pour lire/écrire la sortie 4 fois moins souvent.
            // Tâches fines (pixels × canaux de sortie) : les cœurs lents du SoC ne retardent pas les autres.
            val plane = h * wd
            val chunks = (plane + PW_CHUNK - 1) / PW_CHUNK
            val ocBlocks = (m + PW_OC_BLOCK - 1) / PW_OC_BLOCK
            parallelFor(chunks * ocBlocks) { task ->
                val start = (task % chunks) * PW_CHUNK
                val end = min(plane, start + PW_CHUNK)
                val oc0 = (task / chunks) * PW_OC_BLOCK
                for (oc in oc0 until min(m, oc0 + PW_OC_BLOCK)) {
                    val o = oc * plane
                    val bv = bias?.get(oc) ?: 0f
                    for (p in o + start until o + end) out[p] = bv
                    val wo = oc * c
                    var ic = 0
                    while (ic + 4 <= c) {
                        val w0 = ws[wo + ic]
                        val w1 = ws[wo + ic + 1]
                        val w2 = ws[wo + ic + 2]
                        val w3 = ws[wo + ic + 3]
                        val x0 = ic * plane - o
                        for (p in o + start until o + end) {
                            out[p] += w0 * xs[x0 + p] + w1 * xs[x0 + plane + p] +
                                w2 * xs[x0 + 2 * plane + p] + w3 * xs[x0 + 3 * plane + p]
                        }
                        ic += 4
                    }
                    while (ic < c) {
                        val wv = ws[wo + ic]
                        val xo = ic * plane - o
                        for (p in o + start until o + end) out[p] += wv * xs[xo + p]
                        ic++
                    }
                    if (relu) for (p in o + start until o + end) out[p] = max(out[p], 0f)
                }
            }
            return Tensor(intArrayOf(1, m, oh, ow), out)
        }

        // Cas général : pour chaque prise du noyau, la plage de colonnes de sortie valides
        // (0 <= ox * sw + kx * dw - pl < wd) est calculée à l'avance, sans test dans la boucle.
        val oxStart = IntArray(kw) { kx -> val off = kx * dw - pl; if (off >= 0) 0 else (-off + sw - 1) / sw }
        val oxEnd = IntArray(kw) { kx -> val off = kx * dw - pl; if (wd - 1 - off < 0) 0 else min(ow, (wd - 1 - off) / sw + 1) }
        // 3×3 : à l'intérieur de l'image, les 9 prises sont cumulées en une seule passe.
        val fused3x3 = kh == 3 && kw == 3
        val innerStart = oxStart.max()
        val innerEnd = oxEnd.min()
        val mg = m / group
        val bandRows = max(1, CHUNK / ow)
        val bands = (oh + bandRows - 1) / bandRows
        parallelFor(m * bands) { task ->
            val oc = task / bands
            val y0 = (task % bands) * bandRows
            val y1 = min(oh, y0 + bandRows)
            val g = oc / mg
            val bv = bias?.get(oc) ?: 0f
            for (oy in y0 until y1) {
                val outRow = (oc * oh + oy) * ow
                for (ox in 0 until ow) out[outRow + ox] = bv
                val iyTop = oy * sh - pt
                val inner = fused3x3 && innerStart < innerEnd && iyTop >= 0 && iyTop + 2 * dh < h
                for (icg in 0 until cg) {
                    val ic = g * cg + icg
                    val wb = (oc * cg + icg) * kh * kw
                    if (inner) {
                        val r0 = (ic * h + iyTop) * wd - pl
                        val r1 = r0 + dh * wd
                        val r2 = r1 + dh * wd
                        val w00 = ws[wb]
                        val w01 = ws[wb + 1]
                        val w02 = ws[wb + 2]
                        val w10 = ws[wb + 3]
                        val w11 = ws[wb + 4]
                        val w12 = ws[wb + 5]
                        val w20 = ws[wb + 6]
                        val w21 = ws[wb + 7]
                        val w22 = ws[wb + 8]
                        val d2 = 2 * dw
                        for (ox in innerStart until innerEnd) {
                            val ix = ox * sw
                            out[outRow + ox] +=
                                w00 * xs[r0 + ix] + w01 * xs[r0 + ix + dw] + w02 * xs[r0 + ix + d2] +
                                w10 * xs[r1 + ix] + w11 * xs[r1 + ix + dw] + w12 * xs[r1 + ix + d2] +
                                w20 * xs[r2 + ix] + w21 * xs[r2 + ix + dw] + w22 * xs[r2 + ix + d2]
                        }
                    }
                    // Prises restantes : tout le noyau sur les bords, sinon les colonnes extérieures.
                    for (ky in 0 until kh) {
                        val iy = iyTop + ky * dh
                        if (iy < 0 || iy >= h) continue
                        val inRow = (ic * h + iy) * wd
                        for (kx in 0 until kw) {
                            val wv = ws[wb + ky * kw + kx]
                            val base = inRow + kx * dw - pl
                            if (inner) {
                                for (ox in oxStart[kx] until innerStart) out[outRow + ox] += wv * xs[base + ox * sw]
                                for (ox in innerEnd until oxEnd[kx]) out[outRow + ox] += wv * xs[base + ox * sw]
                            } else {
                                for (ox in oxStart[kx] until oxEnd[kx]) out[outRow + ox] += wv * xs[base + ox * sw]
                            }
                        }
                    }
                }
                if (relu) for (ox in 0 until ow) out[outRow + ox] = max(out[outRow + ox], 0f)
            }
        }
        return Tensor(intArrayOf(1, m, oh, ow), out)
    }

    private fun maxPool(node: OnnxNode, x: Tensor): Tensor {
        require(node.int("ceil_mode", 0) == 0L) { "MaxPool: ceil_mode not supported" }
        require(node.ints("pads")?.all { it == 0L } != false) { "MaxPool: pads not supported" }
        val (_, c, h, w) = x.shape
        val k = node.ints("kernel_shape")!!
        val s = node.ints("strides") ?: longArrayOf(1, 1)
        val kh = k[0].toInt()
        val kw = k[1].toInt()
        val sh = s[0].toInt()
        val sw = s[1].toInt()
        val oh = (h - kh) / sh + 1
        val ow = (w - kw) / sw + 1
        val out = FloatArray(c * oh * ow)
        val xs = x.data
        parallelFor(c) { ch ->
            for (oy in 0 until oh) for (ox in 0 until ow) {
                var best = Float.NEGATIVE_INFINITY
                for (ky in 0 until kh) {
                    val row = (ch * h + oy * sh + ky) * w + ox * sw
                    for (kx in 0 until kw) best = max(best, xs[row + kx])
                }
                out[(ch * oh + oy) * ow + ox] = best
            }
        }
        return Tensor(intArrayOf(1, c, oh, ow), out)
    }

    private fun resize(node: OnnxNode, x: Tensor, scales: Tensor?): Tensor {
        require(
            node.string("mode", "nearest") == "nearest" &&
                node.string("coordinate_transformation_mode", "half_pixel") == "asymmetric" &&
                node.string("nearest_mode", "round_prefer_floor") == "floor",
        ) { "Resize: only nearest/asymmetric/floor is supported" }
        require(scales != null && scales.data.size == 4) { "Resize: scales required" }
        val (_, c, h, w) = x.shape
        val fy = scales.data[2]
        val fx = scales.data[3]
        val oh = (h * fy).toInt()
        val ow = (w * fx).toInt()
        val out = FloatArray(c * oh * ow)
        val xs = x.data
        parallelFor(c) { ch ->
            for (oy in 0 until oh) {
                val iy = min(h - 1, (oy / fy).toInt())
                for (ox in 0 until ow) {
                    out[(ch * oh + oy) * ow + ox] = xs[(ch * h + iy) * w + min(w - 1, (ox / fx).toInt())]
                }
            }
        }
        return Tensor(intArrayOf(1, c, oh, ow), out)
    }

    private fun add(a: Tensor, b: Tensor): Tensor {
        require(a.shape.contentEquals(b.shape)) { "Add: broadcasting not supported" }
        return Tensor(a.shape, FloatArray(a.data.size) { a.data[it] + b.data[it] })
    }

    private fun transpose(x: Tensor, perm: LongArray?): Tensor {
        val rank = x.shape.size
        val p = perm?.map { it.toInt() } ?: (rank - 1 downTo 0).toList()
        val outShape = IntArray(rank) { x.shape[p[it]] }
        val inStrides = IntArray(rank).also { s ->
            var acc = 1
            for (d in rank - 1 downTo 0) { s[d] = acc; acc *= x.shape[d] }
        }
        // Pas dans l'entrée correspondant à chaque dimension de sortie.
        val strides = IntArray(rank) { inStrides[p[it]] }
        val out = FloatArray(x.data.size)
        val idx = IntArray(rank)
        var src = 0
        for (i in out.indices) {
            out[i] = x.data[src]
            var d = rank - 1
            while (d >= 0) {
                idx[d]++
                src += strides[d]
                if (idx[d] < outShape[d]) break
                src -= strides[d] * outShape[d]
                idx[d] = 0
                d--
            }
        }
        return Tensor(outShape, out)
    }

    private fun reshape(x: Tensor, spec: LongArray): Tensor {
        val shape = IntArray(spec.size) { if (spec[it] == 0L) x.shape[it] else spec[it].toInt() }
        val known = shape.filter { it != -1 }.fold(1) { a, b -> a * b }
        val inferred = shape.indexOf(-1)
        if (inferred >= 0) shape[inferred] = x.data.size / known
        return Tensor(shape, x.data)
    }

    private inline fun map(x: Tensor, crossinline f: (Float) -> Float): Tensor =
        Tensor(x.shape, FloatArray(x.data.size) { f(x.data[it]) })

    private companion object {
        const val TAG = "OnnxRunner"
        val SUPPORTED = setOf("Conv", "Relu", "Sigmoid", "MaxPool", "Resize", "Add", "Transpose", "Reshape")

        /** Taille des blocs de travail (pixels de sortie) répartis entre les cœurs. */
        const val CHUNK = 4096

        /** Conv 1×1 : pixels et canaux de sortie par tâche. */
        const val PW_CHUNK = 1024
        const val PW_OC_BLOCK = 8

        fun parallelFor(count: Int, body: (Int) -> Unit) {
            if (count == 1) body(0) else IntStream.range(0, count).parallel().forEach { body(it) }
        }
    }
}
