// SPDX-FileCopyrightText: 2026 Gildas Le Drogoff and FaceBlur contributors
//
// SPDX-License-Identifier: AGPL-3.0-or-later

package fr.gildasledrogoff.faceblur.data.onnx

import fr.gildasledrogoff.faceblur.FaceDetectionLog
import java.util.stream.IntStream
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

class OnnxRunner(private val model: OnnxModel) {
    private val lastUseIndex: Map<String, Int> = HashMap<String, Int>().apply {
        model.nodes.forEachIndexed { index, node -> node.inputs.forEach { put(it, index) } }
    }

    init {
        val unsupported = model.nodes.map { it.opType }.toSet() - SUPPORTED_OPERATORS
        require(unsupported.isEmpty()) { "ONNX: unsupported operators $unsupported" }
    }

    fun run(input: Tensor): Map<String, Tensor> {
        val values = HashMap<String, Tensor>(model.floatInitializers)
        values[model.inputs.single()] = input
        val nanosPerOperator = HashMap<String, Long>()
        var nodeIndex = 0
        while (nodeIndex < model.nodes.size) {
            val node = model.nodes[nodeIndex]
            val start = System.nanoTime()
            val next = model.nodes.getOrNull(nodeIndex + 1)
            val shouldFuseRelu = node.opType == "Conv" && next?.opType == "Relu" &&
                    next.inputs.single() == node.outputs.single() &&
                    lastUseIndex[node.outputs.single()] == nodeIndex + 1 &&
                    node.outputs.single() !in model.outputs
            val result = evaluate(node, values, shouldFuseRelu)
            val operatorName = if (shouldFuseRelu) "Conv+Relu" else node.opType
            nanosPerOperator[operatorName] =
                (nanosPerOperator[operatorName] ?: 0L) + System.nanoTime() - start
            values[(if (shouldFuseRelu) next else node).outputs.single()] = result
            for (name in node.inputs) {
                if (lastUseIndex[name] == nodeIndex && name !in model.outputs && name !in model.floatInitializers) {
                    values.remove(name)
                }
            }
            nodeIndex += if (shouldFuseRelu) 2 else 1
        }
        FaceDetectionLog.d(TAG) {
            nanosPerOperator.entries.sortedByDescending { it.value }
                .joinToString(prefix = "Time per operator: ") { "${it.key} ${it.value / 1_000_000} ms" }
        }
        return model.outputs.associateWith { values.getValue(it) }
    }

    private fun evaluate(
        node: OnnxNode,
        values: Map<String, Tensor>,
        shouldFuseRelu: Boolean
    ): Tensor {
        fun input(index: Int): Tensor = values.getValue(node.inputs[index])
        return when (node.opType) {
            "Conv" -> convolve(
                node,
                input(0),
                input(1),
                node.inputs.getOrNull(2)?.takeIf { it.isNotEmpty() }?.let(values::getValue),
                shouldFuseRelu,
            )

            "Relu" -> map(input(0)) { max(it, 0f) }
            "Sigmoid" -> map(input(0)) { 1f / (1f + exp(-it)) }
            "MaxPool" -> maxPool(node, input(0))
            "Resize" -> resize(node, input(0), node.inputs.getOrNull(2)?.let(values::get))
            "Add" -> add(input(0), input(1))
            "Transpose" -> transpose(input(0), node.getInts("perm"))
            "Reshape" -> reshape(input(0), model.longInitializers.getValue(node.inputs[1]))
            else -> error("ONNX: unsupported operator ${node.opType}")
        }
    }

    private fun convolve(
        node: OnnxNode,
        input: Tensor,
        weight: Tensor,
        bias: Tensor?,
        applyRelu: Boolean,
    ): Tensor {
        require(node.getString("auto_pad", "NOTSET") == "NOTSET") { "Conv: auto_pad not supported" }
        val (batch, inChannels, inHeight, inWidth) = input.shape
        require(batch == 1) { "Conv: batch must be 1" }
        val (outChannels, channelsPerGroup, kernelHeight, kernelWidth) = weight.shape
        val group = node.getInt("group", 1).toInt()
        require(channelsPerGroup * group == inChannels && outChannels % group == 0) {
            "Conv: bad group $group for ${input.shape.contentToString()}"
        }
        val pads = node.getInts("pads") ?: LongArray(4)
        val strides = node.getInts("strides") ?: longArrayOf(1, 1)
        val dilations = node.getInts("dilations") ?: longArrayOf(1, 1)
        val padTop = pads[0].toInt()
        val padLeft = pads[1].toInt()
        val strideY = strides[0].toInt()
        val strideX = strides[1].toInt()
        val dilationY = dilations[0].toInt()
        val dilationX = dilations[1].toInt()
        val outHeight =
            (inHeight + padTop + pads[2].toInt() - dilationY * (kernelHeight - 1) - 1) / strideY + 1
        val outWidth =
            (inWidth + padLeft + pads[3].toInt() - dilationX * (kernelWidth - 1) - 1) / strideX + 1
        val out = FloatArray(outChannels * outHeight * outWidth)
        val biasData = bias?.data
        val inputData = input.data
        val weightData = weight.data
        val isPointwise = kernelHeight == 1 && kernelWidth == 1 && strideY == 1 && strideX == 1 &&
                padTop == 0 && padLeft == 0 && group == 1
        if (isPointwise) {
            val plane = inHeight * inWidth
            val chunks = (plane + POINTWISE_CHUNK - 1) / POINTWISE_CHUNK
            val outChannelBlocks =
                (outChannels + POINTWISE_OUT_CHANNEL_BLOCK - 1) / POINTWISE_OUT_CHANNEL_BLOCK
            parallelFor(chunks * outChannelBlocks) { task ->
                val start = (task % chunks) * POINTWISE_CHUNK
                val end = min(plane, start + POINTWISE_CHUNK)
                val firstOutChannel = (task / chunks) * POINTWISE_OUT_CHANNEL_BLOCK
                val endOutChannel = min(outChannels, firstOutChannel + POINTWISE_OUT_CHANNEL_BLOCK)
                for (outChannel in firstOutChannel until endOutChannel) {
                    val outOffset = outChannel * plane
                    val biasValue = biasData?.get(outChannel) ?: 0f
                    for (outIndex in outOffset + start until outOffset + end) out[outIndex] =
                        biasValue
                    val weightOffset = outChannel * inChannels
                    var inChannel = 0
                    while (inChannel + 4 <= inChannels) {
                        val weight0 = weightData[weightOffset + inChannel]
                        val weight1 = weightData[weightOffset + inChannel + 1]
                        val weight2 = weightData[weightOffset + inChannel + 2]
                        val weight3 = weightData[weightOffset + inChannel + 3]
                        val inputOffset = inChannel * plane - outOffset
                        for (outIndex in outOffset + start until outOffset + end) {
                            out[outIndex] += weight0 * inputData[inputOffset + outIndex] +
                                    weight1 * inputData[inputOffset + plane + outIndex] +
                                    weight2 * inputData[inputOffset + 2 * plane + outIndex] +
                                    weight3 * inputData[inputOffset + 3 * plane + outIndex]
                        }
                        inChannel += 4
                    }
                    while (inChannel < inChannels) {
                        val weightValue = weightData[weightOffset + inChannel]
                        val inputOffset = inChannel * plane - outOffset
                        for (outIndex in outOffset + start until outOffset + end) {
                            out[outIndex] += weightValue * inputData[inputOffset + outIndex]
                        }
                        inChannel++
                    }
                    if (applyRelu) {
                        for (outIndex in outOffset + start until outOffset + end) out[outIndex] =
                            max(out[outIndex], 0f)
                    }
                }
            }
            return Tensor(intArrayOf(1, outChannels, outHeight, outWidth), out)
        }
        val outColumnStart = IntArray(kernelWidth) { kernelColumn ->
            val offset = kernelColumn * dilationX - padLeft
            if (offset >= 0) 0 else (-offset + strideX - 1) / strideX
        }
        val outColumnEnd = IntArray(kernelWidth) { kernelColumn ->
            val offset = kernelColumn * dilationX - padLeft
            if (inWidth - 1 - offset < 0) 0 else min(outWidth, (inWidth - 1 - offset) / strideX + 1)
        }
        val isKernel3x3 = kernelHeight == 3 && kernelWidth == 3
        val innerColumnStart = outColumnStart.max()
        val innerColumnEnd = outColumnEnd.min()
        val outChannelsPerGroup = outChannels / group
        val bandRows = max(1, CHUNK / outWidth)
        val bands = (outHeight + bandRows - 1) / bandRows
        parallelFor(outChannels * bands) { task ->
            val outChannel = task / bands
            val firstOutRow = (task % bands) * bandRows
            val endOutRow = min(outHeight, firstOutRow + bandRows)
            val groupIndex = outChannel / outChannelsPerGroup
            val biasValue = biasData?.get(outChannel) ?: 0f
            for (outRow in firstOutRow until endOutRow) {
                val outRowOffset = (outChannel * outHeight + outRow) * outWidth
                for (outColumn in 0 until outWidth) out[outRowOffset + outColumn] = biasValue
                val inRowTop = outRow * strideY - padTop
                val hasInnerRun =
                    isKernel3x3 && innerColumnStart < innerColumnEnd && inRowTop >= 0 &&
                            inRowTop + 2 * dilationY < inHeight
                for (channelInGroup in 0 until channelsPerGroup) {
                    val inChannel = groupIndex * channelsPerGroup + channelInGroup
                    val weightBase =
                        (outChannel * channelsPerGroup + channelInGroup) * kernelHeight * kernelWidth
                    if (hasInnerRun) {
                        val topRowOffset = (inChannel * inHeight + inRowTop) * inWidth - padLeft
                        val middleRowOffset = topRowOffset + dilationY * inWidth
                        val bottomRowOffset = middleRowOffset + dilationY * inWidth
                        val weightTopLeft = weightData[weightBase]
                        val weightTopCenter = weightData[weightBase + 1]
                        val weightTopRight = weightData[weightBase + 2]
                        val weightMiddleLeft = weightData[weightBase + 3]
                        val weightMiddleCenter = weightData[weightBase + 4]
                        val weightMiddleRight = weightData[weightBase + 5]
                        val weightBottomLeft = weightData[weightBase + 6]
                        val weightBottomCenter = weightData[weightBase + 7]
                        val weightBottomRight = weightData[weightBase + 8]
                        val doubleDilationX = 2 * dilationX
                        for (outColumn in innerColumnStart until innerColumnEnd) {
                            val inColumn = outColumn * strideX
                            out[outRowOffset + outColumn] +=
                                weightTopLeft * inputData[topRowOffset + inColumn] +
                                        weightTopCenter * inputData[topRowOffset + inColumn + dilationX] +
                                        weightTopRight * inputData[topRowOffset + inColumn + doubleDilationX] +
                                        weightMiddleLeft * inputData[middleRowOffset + inColumn] +
                                        weightMiddleCenter * inputData[middleRowOffset + inColumn + dilationX] +
                                        weightMiddleRight * inputData[middleRowOffset + inColumn + doubleDilationX] +
                                        weightBottomLeft * inputData[bottomRowOffset + inColumn] +
                                        weightBottomCenter * inputData[bottomRowOffset + inColumn + dilationX] +
                                        weightBottomRight * inputData[bottomRowOffset + inColumn + doubleDilationX]
                        }
                    }
                    for (kernelRow in 0 until kernelHeight) {
                        val inRow = inRowTop + kernelRow * dilationY
                        if (inRow < 0 || inRow >= inHeight) continue
                        val inRowOffset = (inChannel * inHeight + inRow) * inWidth
                        for (kernelColumn in 0 until kernelWidth) {
                            val weightValue =
                                weightData[weightBase + kernelRow * kernelWidth + kernelColumn]
                            val inOffset = inRowOffset + kernelColumn * dilationX - padLeft
                            if (hasInnerRun) {
                                for (outColumn in outColumnStart[kernelColumn] until innerColumnStart) {
                                    out[outRowOffset + outColumn] += weightValue * inputData[inOffset + outColumn * strideX]
                                }
                                for (outColumn in innerColumnEnd until outColumnEnd[kernelColumn]) {
                                    out[outRowOffset + outColumn] += weightValue * inputData[inOffset + outColumn * strideX]
                                }
                            } else {
                                for (outColumn in outColumnStart[kernelColumn] until outColumnEnd[kernelColumn]) {
                                    out[outRowOffset + outColumn] += weightValue * inputData[inOffset + outColumn * strideX]
                                }
                            }
                        }
                    }
                }
                if (applyRelu) {
                    for (outColumn in 0 until outWidth) {
                        out[outRowOffset + outColumn] = max(out[outRowOffset + outColumn], 0f)
                    }
                }
            }
        }
        return Tensor(intArrayOf(1, outChannels, outHeight, outWidth), out)
    }

    private fun maxPool(node: OnnxNode, input: Tensor): Tensor {
        require(node.getInt("ceil_mode", 0) == 0L) { "MaxPool: ceil_mode not supported" }
        require(node.getInts("pads")?.all { it == 0L } != false) { "MaxPool: pads not supported" }
        val (_, channels, inHeight, inWidth) = input.shape
        val kernelShape = node.getInts("kernel_shape")!!
        val strides = node.getInts("strides") ?: longArrayOf(1, 1)
        val kernelHeight = kernelShape[0].toInt()
        val kernelWidth = kernelShape[1].toInt()
        val strideY = strides[0].toInt()
        val strideX = strides[1].toInt()
        val outHeight = (inHeight - kernelHeight) / strideY + 1
        val outWidth = (inWidth - kernelWidth) / strideX + 1
        val out = FloatArray(channels * outHeight * outWidth)
        val inputData = input.data
        parallelFor(channels) { channel ->
            for (outRow in 0 until outHeight) for (outColumn in 0 until outWidth) {
                var best = Float.NEGATIVE_INFINITY
                for (kernelRow in 0 until kernelHeight) {
                    val inOffset =
                        (channel * inHeight + outRow * strideY + kernelRow) * inWidth + outColumn * strideX
                    for (kernelColumn in 0 until kernelWidth) best =
                        max(best, inputData[inOffset + kernelColumn])
                }
                out[(channel * outHeight + outRow) * outWidth + outColumn] = best
            }
        }
        return Tensor(intArrayOf(1, channels, outHeight, outWidth), out)
    }

    private fun resize(node: OnnxNode, input: Tensor, scales: Tensor?): Tensor {
        require(
            node.getString("mode", "nearest") == "nearest" &&
                    node.getString(
                        "coordinate_transformation_mode",
                        "half_pixel"
                    ) == "asymmetric" &&
                    node.getString("nearest_mode", "round_prefer_floor") == "floor",
        ) { "Resize: only nearest/asymmetric/floor is supported" }
        require(scales != null && scales.data.size == 4) { "Resize: scales required" }
        val (_, channels, inHeight, inWidth) = input.shape
        val scaleY = scales.data[2]
        val scaleX = scales.data[3]
        val outHeight = (inHeight * scaleY).toInt()
        val outWidth = (inWidth * scaleX).toInt()
        val out = FloatArray(channels * outHeight * outWidth)
        val inputData = input.data
        parallelFor(channels) { channel ->
            for (outRow in 0 until outHeight) {
                val inRow = min(inHeight - 1, (outRow / scaleY).toInt())
                for (outColumn in 0 until outWidth) {
                    val inColumn = min(inWidth - 1, (outColumn / scaleX).toInt())
                    out[(channel * outHeight + outRow) * outWidth + outColumn] =
                        inputData[(channel * inHeight + inRow) * inWidth + inColumn]
                }
            }
        }
        return Tensor(intArrayOf(1, channels, outHeight, outWidth), out)
    }

    private fun add(left: Tensor, right: Tensor): Tensor {
        require(left.shape.contentEquals(right.shape)) { "Add: broadcasting not supported" }
        return Tensor(left.shape, FloatArray(left.data.size) { left.data[it] + right.data[it] })
    }

    private fun transpose(input: Tensor, perm: LongArray?): Tensor {
        val rank = input.shape.size
        val permutation = perm?.map { it.toInt() } ?: (rank - 1 downTo 0).toList()
        val outShape = IntArray(rank) { input.shape[permutation[it]] }
        val inStrides = IntArray(rank).also { strides ->
            var stride = 1
            for (dim in rank - 1 downTo 0) {
                strides[dim] = stride; stride *= input.shape[dim]
            }
        }
        val strides = IntArray(rank) { inStrides[permutation[it]] }
        val out = FloatArray(input.data.size)
        val position = IntArray(rank)
        var sourceIndex = 0
        for (outIndex in out.indices) {
            out[outIndex] = input.data[sourceIndex]
            var dim = rank - 1
            while (dim >= 0) {
                position[dim]++
                sourceIndex += strides[dim]
                if (position[dim] < outShape[dim]) break
                sourceIndex -= strides[dim] * outShape[dim]
                position[dim] = 0
                dim--
            }
        }
        return Tensor(outShape, out)
    }

    private fun reshape(input: Tensor, spec: LongArray): Tensor {
        val shape =
            IntArray(spec.size) { if (spec[it] == 0L) input.shape[it] else spec[it].toInt() }
        val knownSize = shape.filter { it != -1 }.fold(1) { product, dim -> product * dim }
        val inferredDim = shape.indexOf(-1)
        if (inferredDim >= 0) shape[inferredDim] = input.data.size / knownSize
        return Tensor(shape, input.data)
    }

    private inline fun map(input: Tensor, crossinline transform: (Float) -> Float): Tensor =
        Tensor(input.shape, FloatArray(input.data.size) { transform(input.data[it]) })

    private companion object {
        const val TAG = "OnnxRunner"
        val SUPPORTED_OPERATORS =
            setOf("Conv", "Relu", "Sigmoid", "MaxPool", "Resize", "Add", "Transpose", "Reshape")
        const val CHUNK = 4096
        const val POINTWISE_CHUNK = 1024
        const val POINTWISE_OUT_CHANNEL_BLOCK = 8
        fun parallelFor(count: Int, body: (Int) -> Unit) {
            if (count == 1) body(0) else IntStream.range(0, count).parallel().forEach { body(it) }
        }
    }
}
