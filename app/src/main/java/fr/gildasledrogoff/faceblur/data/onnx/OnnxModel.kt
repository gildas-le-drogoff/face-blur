// SPDX-FileCopyrightText: 2026 Gildas Le Drogoff and FaceBlur contributors
//
// SPDX-License-Identifier: AGPL-3.0-or-later

package fr.gildasledrogoff.faceblur.data.onnx

import java.nio.ByteBuffer
import java.nio.ByteOrder

class OnnxModel(
    val nodes: List<OnnxNode>,
    val floatInitializers: Map<String, Tensor>,
    val longInitializers: Map<String, LongArray>,
    val inputs: List<String>,
    val outputs: List<String>,
) {
    companion object {
        fun parse(bytes: ByteArray): OnnxModel {
            var graph: ProtoReader? = null
            ProtoReader(bytes).forEachField { field, reader ->
                if (field == 7) graph = reader.readMessage() else reader.skip()
            }
            return parseGraph(requireNotNull(graph) { "ONNX: graph missing" })
        }

        private fun parseGraph(graph: ProtoReader): OnnxModel {
            val nodes = ArrayList<OnnxNode>()
            val floats = HashMap<String, Tensor>()
            val longs = HashMap<String, LongArray>()
            val inputs = ArrayList<String>()
            val outputs = ArrayList<String>()
            graph.forEachField { field, reader ->
                when (field) {
                    1 -> nodes += parseNode(reader.readMessage())
                    5 -> parseTensor(reader.readMessage(), floats, longs)
                    11 -> inputs += readValueInfoName(reader.readMessage())
                    12 -> outputs += readValueInfoName(reader.readMessage())
                    else -> reader.skip()
                }
            }
            inputs.removeAll { it in floats || it in longs }
            return OnnxModel(nodes, floats, longs, inputs, outputs)
        }

        private fun readValueInfoName(valueInfo: ProtoReader): String {
            var name = ""
            valueInfo.forEachField { field, reader ->
                if (field == 1) name = reader.readString() else reader.skip()
            }
            return name
        }

        private fun parseNode(node: ProtoReader): OnnxNode {
            val inputs = ArrayList<String>()
            val outputs = ArrayList<String>()
            var opType = ""
            var domain = ""
            val attributes = HashMap<String, Attribute>()
            node.forEachField { field, reader ->
                when (field) {
                    1 -> inputs += reader.readString()
                    2 -> outputs += reader.readString()
                    4 -> opType = reader.readString()
                    5 -> parseAttribute(reader.readMessage()).let {
                        attributes[it.first] = it.second
                    }

                    7 -> domain = reader.readString()
                    else -> reader.skip()
                }
            }
            require(domain.isEmpty() || domain == "ai.onnx") { "ONNX: unsupported domain $domain for $opType" }
            return OnnxNode(opType, inputs, outputs, attributes)
        }

        private fun parseAttribute(attribute: ProtoReader): Pair<String, Attribute> {
            var name = ""
            var floatValue = 0f
            var intValue = 0L
            var stringValue = ""
            val ints = ArrayList<Long>()
            val floats = ArrayList<Float>()
            attribute.forEachField { field, reader ->
                when (field) {
                    1 -> name = reader.readString()
                    2 -> floatValue = reader.readFixed32Float()
                    3 -> intValue = reader.readVarint()
                    4 -> stringValue = reader.readString()
                    7 -> reader.readRepeatedFloats(floats)
                    8 -> reader.readRepeatedVarints(ints)
                    else -> reader.skip()
                }
            }
            return name to Attribute(
                floatValue,
                intValue,
                stringValue,
                ints.toLongArray(),
                floats.toFloatArray()
            )
        }

        private fun parseTensor(
            tensor: ProtoReader,
            floats: MutableMap<String, Tensor>,
            longs: MutableMap<String, LongArray>
        ) {
            val dims = ArrayList<Long>()
            var dataType = 0
            var name = ""
            var rawData: ProtoReader? = null
            val floatData = ArrayList<Float>()
            val longData = ArrayList<Long>()
            tensor.forEachField { field, reader ->
                when (field) {
                    1 -> reader.readRepeatedVarints(dims)
                    2 -> dataType = reader.readVarint().toInt()
                    4 -> reader.readRepeatedFloats(floatData)
                    7 -> reader.readRepeatedVarints(longData)
                    8 -> name = reader.readString()
                    9 -> rawData = reader.readMessage()
                    14 -> require(reader.readVarint() == 0L) { "ONNX: external data not supported ($name)" }
                    else -> reader.skip()
                }
            }
            val shape = IntArray(dims.size) { dims[it].toInt() }
            val count = shape.fold(1) { product, dim -> product * dim }
            val bytes = rawData?.sliceRemaining()
            when (dataType) {
                TYPE_FLOAT -> {
                    val data = bytes?.asFloatBuffer()
                        ?.let { buffer -> FloatArray(buffer.remaining()).also { buffer.get(it) } }
                        ?: floatData.toFloatArray()
                    require(data.size == count) { "ONNX: $name has ${data.size} values for ${shape.contentToString()}" }
                    floats[name] = Tensor(shape, data)
                }

                TYPE_INT64 -> {
                    val data = bytes?.asLongBuffer()
                        ?.let { buffer -> LongArray(buffer.remaining()).also { buffer.get(it) } }
                        ?: longData.toLongArray()
                    require(data.size == count) { "ONNX: $name has ${data.size} values for ${shape.contentToString()}" }
                    longs[name] = data
                }

                else -> error("ONNX: unsupported tensor type $dataType ($name)")
            }
        }

        private const val TYPE_FLOAT = 1
        private const val TYPE_INT64 = 7
    }
}

class OnnxNode(
    val opType: String,
    val inputs: List<String>,
    val outputs: List<String>,
    val attributes: Map<String, Attribute>,
) {
    fun getInt(name: String, default: Long): Long = attributes[name]?.intValue ?: default
    fun getInts(name: String): LongArray? = attributes[name]?.ints
    fun getString(name: String, default: String): String = attributes[name]?.stringValue ?: default
}

class Attribute(
    val floatValue: Float,
    val intValue: Long,
    val stringValue: String,
    val ints: LongArray,
    val floats: FloatArray
)

class Tensor(val shape: IntArray, val data: FloatArray) {
    init {
        require(shape.fold(1) { product, dim -> product * dim } == data.size) { "Tensor: ${shape.contentToString()} vs ${data.size}" }
    }
}

internal class ProtoReader(
    private val buffer: ByteArray,
    private var position: Int = 0,
    private val end: Int = buffer.size
) {
    private var wireType = 0
    fun forEachField(block: (field: Int, reader: ProtoReader) -> Unit) {
        while (position < end) {
            val tag = readVarint().toInt()
            wireType = tag and 7
            block(tag ushr 3, this)
        }
    }

    fun readVarint(): Long {
        var result = 0L
        var shift = 0
        while (true) {
            val byte = buffer[position++].toInt()
            result = result or ((byte and 0x7F).toLong() shl shift)
            if (byte and 0x80 == 0) return result
            shift += 7
        }
    }

    fun readFixed32Float(): Float {
        val bits =
            (buffer[position].toInt() and 0xFF) or ((buffer[position + 1].toInt() and 0xFF) shl 8) or
                    ((buffer[position + 2].toInt() and 0xFF) shl 16) or ((buffer[position + 3].toInt() and 0xFF) shl 24)
        position += 4
        return Float.fromBits(bits)
    }

    fun readMessage(): ProtoReader {
        val length = readVarint().toInt()
        val message = ProtoReader(buffer, position, position + length)
        position += length
        return message
    }

    fun readString(): String {
        val length = readVarint().toInt()
        val string = String(buffer, position, length, Charsets.UTF_8)
        position += length
        return string
    }

    fun sliceRemaining(): ByteBuffer =
        ByteBuffer.wrap(buffer, position, end - position).slice().order(ByteOrder.LITTLE_ENDIAN)

    fun readRepeatedVarints(out: MutableList<Long>) {
        if (wireType == WIRE_TYPE_LENGTH_DELIMITED) {
            val packed = readMessage()
            while (packed.position < packed.end) out += packed.readVarint()
        } else {
            out += readVarint()
        }
    }

    fun readRepeatedFloats(out: MutableList<Float>) {
        if (wireType == WIRE_TYPE_LENGTH_DELIMITED) {
            val packed = readMessage()
            while (packed.position < packed.end) out += packed.readFixed32Float()
        } else {
            out += readFixed32Float()
        }
    }

    fun skip() {
        when (wireType) {
            0 -> readVarint()
            1 -> position += 8
            WIRE_TYPE_LENGTH_DELIMITED -> {
                val length = readVarint().toInt()
                position += length
            }

            5 -> position += 4
            else -> error("Protobuf: unsupported wire type $wireType")
        }
    }

    private companion object {
        const val WIRE_TYPE_LENGTH_DELIMITED = 2
    }
}
