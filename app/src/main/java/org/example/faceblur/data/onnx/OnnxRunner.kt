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
        for ((i, node) in model.nodes.withIndex()) {
            val start = System.nanoTime()
            val result = eval(node, values)
            nanosPerOp[node.opType] = (nanosPerOp[node.opType] ?: 0L) + System.nanoTime() - start
            values[node.outputs.single()] = result
            for (name in node.inputs) {
                if (lastUse[name] == i && name !in model.outputs && name !in model.floatInits) values.remove(name)
            }
        }
        FaceDetectionLog.d(TAG) {
            nanosPerOp.entries.sortedByDescending { it.value }
                .joinToString(prefix = "Time per operator: ") { "${it.key} ${it.value / 1_000_000} ms" }
        }
        return model.outputs.associateWith { values.getValue(it) }
    }

    private fun eval(node: OnnxNode, values: Map<String, Tensor>): Tensor {
        fun input(i: Int): Tensor = values.getValue(node.inputs[i])
        return when (node.opType) {
            "Conv" -> conv(node, input(0), input(1), node.inputs.getOrNull(2)?.takeIf { it.isNotEmpty() }?.let(values::getValue))
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

    private fun conv(node: OnnxNode, x: Tensor, w: Tensor, b: Tensor?): Tensor {
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
            // 1×1 : produit matriciel, découpé en blocs de pixels qui restent en cache.
            val plane = h * wd
            parallelFor((plane + CHUNK - 1) / CHUNK) { chunk ->
                val start = chunk * CHUNK
                val end = min(plane, start + CHUNK)
                for (oc in 0 until m) {
                    val o = oc * plane
                    val bv = bias?.get(oc) ?: 0f
                    for (p in start until end) out[o + p] = bv
                    for (ic in 0 until c) {
                        val wv = ws[oc * c + ic]
                        val xo = ic * plane - o
                        for (p in o + start until o + end) out[p] += wv * xs[xo + p]
                    }
                }
            }
            return Tensor(intArrayOf(1, m, oh, ow), out)
        }

        // Cas général (3×3 dont depthwise) : pour chaque prise du noyau, la plage de
        // colonnes valides est calculée à l'avance, la boucle interne n'a aucun test.
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
                for (icg in 0 until cg) {
                    val ic = g * cg + icg
                    for (ky in 0 until kh) {
                        val iy = oy * sh - pt + ky * dh
                        if (iy < 0 || iy >= h) continue
                        val inRow = (ic * h + iy) * wd
                        for (kx in 0 until kw) {
                            val wv = ws[((oc * cg + icg) * kh + ky) * kw + kx]
                            val off = kx * dw - pl
                            // Colonnes ox telles que 0 <= ox * sw + off < wd.
                            val oxStart = if (off >= 0) 0 else (-off + sw - 1) / sw
                            val oxEnd = if (wd - 1 - off < 0) 0 else min(ow, (wd - 1 - off) / sw + 1)
                            val base = inRow + off
                            for (ox in oxStart until oxEnd) out[outRow + ox] += wv * xs[base + ox * sw]
                        }
                    }
                }
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

        fun parallelFor(count: Int, body: (Int) -> Unit) {
            if (count == 1) body(0) else IntStream.range(0, count).parallel().forEach { body(it) }
        }
    }
}
