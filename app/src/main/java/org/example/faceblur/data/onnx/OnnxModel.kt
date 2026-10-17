package org.example.faceblur.data.onnx

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Lecture minimale d'un fichier .onnx (protobuf) : nœuds, attributs et initialiseurs
 * float32/int64 stockés dans le fichier. Suffisant pour YuNet, sans dépendance native.
 * Numéros de champs : onnx/onnx.proto.
 */
class OnnxModel(
    val nodes: List<OnnxNode>,
    val floatInits: Map<String, Tensor>,
    val longInits: Map<String, LongArray>,
    val inputs: List<String>,
    val outputs: List<String>,
) {
    companion object {
        fun parse(bytes: ByteArray): OnnxModel {
            var graph: ProtoReader? = null
            ProtoReader(bytes).forEachField { field, r ->
                if (field == 7) graph = r.message() else r.skip()
            }
            return parseGraph(requireNotNull(graph) { "ONNX: graph missing" })
        }

        private fun parseGraph(g: ProtoReader): OnnxModel {
            val nodes = ArrayList<OnnxNode>()
            val floats = HashMap<String, Tensor>()
            val longs = HashMap<String, LongArray>()
            val inputs = ArrayList<String>()
            val outputs = ArrayList<String>()
            g.forEachField { field, r ->
                when (field) {
                    1 -> nodes += parseNode(r.message())
                    5 -> parseTensor(r.message(), floats, longs)
                    11 -> inputs += valueInfoName(r.message())
                    12 -> outputs += valueInfoName(r.message())
                    else -> r.skip()
                }
            }
            // Les anciens exports listent aussi les initialiseurs parmi les entrées.
            inputs.removeAll { it in floats || it in longs }
            return OnnxModel(nodes, floats, longs, inputs, outputs)
        }

        private fun valueInfoName(r: ProtoReader): String {
            var name = ""
            r.forEachField { field, f -> if (field == 1) name = f.string() else f.skip() }
            return name
        }

        private fun parseNode(r: ProtoReader): OnnxNode {
            val inputs = ArrayList<String>()
            val outputs = ArrayList<String>()
            var opType = ""
            var domain = ""
            val attrs = HashMap<String, Attribute>()
            r.forEachField { field, f ->
                when (field) {
                    1 -> inputs += f.string()
                    2 -> outputs += f.string()
                    4 -> opType = f.string()
                    5 -> parseAttribute(f.message()).let { attrs[it.first] = it.second }
                    7 -> domain = f.string()
                    else -> f.skip()
                }
            }
            require(domain.isEmpty() || domain == "ai.onnx") { "ONNX: unsupported domain $domain for $opType" }
            return OnnxNode(opType, inputs, outputs, attrs)
        }

        private fun parseAttribute(r: ProtoReader): Pair<String, Attribute> {
            var name = ""
            var f = 0f
            var i = 0L
            var s = ""
            val ints = ArrayList<Long>()
            val floats = ArrayList<Float>()
            r.forEachField { field, a ->
                when (field) {
                    1 -> name = a.string()
                    2 -> f = a.fixed32Float()
                    3 -> i = a.varint()
                    4 -> s = a.string()
                    7 -> a.repeatedFloats(floats)
                    8 -> a.repeatedVarints(ints)
                    else -> a.skip()
                }
            }
            return name to Attribute(f, i, s, ints.toLongArray(), floats.toFloatArray())
        }

        private fun parseTensor(r: ProtoReader, floats: MutableMap<String, Tensor>, longs: MutableMap<String, LongArray>) {
            val dims = ArrayList<Long>()
            var dataType = 0
            var name = ""
            var raw: ProtoReader? = null
            val floatData = ArrayList<Float>()
            val longData = ArrayList<Long>()
            r.forEachField { field, t ->
                when (field) {
                    1 -> t.repeatedVarints(dims)
                    2 -> dataType = t.varint().toInt()
                    4 -> t.repeatedFloats(floatData)
                    7 -> t.repeatedVarints(longData)
                    8 -> name = t.string()
                    9 -> raw = t.message()
                    14 -> require(t.varint() == 0L) { "ONNX: external data not supported ($name)" }
                    else -> t.skip()
                }
            }
            val shape = IntArray(dims.size) { dims[it].toInt() }
            val count = shape.fold(1) { a, b -> a * b }
            val bytes = raw?.remaining()
            when (dataType) {
                TYPE_FLOAT -> {
                    val data = bytes?.asFloatBuffer()?.let { b -> FloatArray(b.remaining()).also { b.get(it) } }
                        ?: floatData.toFloatArray()
                    require(data.size == count) { "ONNX: $name has ${data.size} values for ${shape.contentToString()}" }
                    floats[name] = Tensor(shape, data)
                }
                TYPE_INT64 -> {
                    val data = bytes?.asLongBuffer()?.let { b -> LongArray(b.remaining()).also { b.get(it) } }
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
    val attrs: Map<String, Attribute>,
) {
    fun int(name: String, default: Long): Long = attrs[name]?.i ?: default
    fun ints(name: String): LongArray? = attrs[name]?.ints
    fun string(name: String, default: String): String = attrs[name]?.s ?: default
}

class Attribute(val f: Float, val i: Long, val s: String, val ints: LongArray, val floats: FloatArray)

/** Tenseur float32 dense, ordre ligne par ligne (NCHW pour les images). */
class Tensor(val shape: IntArray, val data: FloatArray) {
    init {
        require(shape.fold(1) { a, b -> a * b } == data.size) { "Tensor: ${shape.contentToString()} vs ${data.size}" }
    }
}

/** Lecteur protobuf (wire format) limité à ce qu'utilise ONNX. */
internal class ProtoReader(private val buf: ByteArray, private var pos: Int = 0, private val end: Int = buf.size) {
    private var wireType = 0

    fun forEachField(block: (field: Int, reader: ProtoReader) -> Unit) {
        while (pos < end) {
            val tag = varint().toInt()
            wireType = tag and 7
            block(tag ushr 3, this)
        }
    }

    fun varint(): Long {
        var result = 0L
        var shift = 0
        while (true) {
            val b = buf[pos++].toInt()
            result = result or ((b and 0x7F).toLong() shl shift)
            if (b and 0x80 == 0) return result
            shift += 7
        }
    }

    fun fixed32Float(): Float {
        val bits = (buf[pos].toInt() and 0xFF) or ((buf[pos + 1].toInt() and 0xFF) shl 8) or
            ((buf[pos + 2].toInt() and 0xFF) shl 16) or ((buf[pos + 3].toInt() and 0xFF) shl 24)
        pos += 4
        return Float.fromBits(bits)
    }

    fun message(): ProtoReader {
        val len = varint().toInt()
        val sub = ProtoReader(buf, pos, pos + len)
        pos += len
        return sub
    }

    fun string(): String {
        val len = varint().toInt()
        val s = String(buf, pos, len, Charsets.UTF_8)
        pos += len
        return s
    }

    fun remaining(): ByteBuffer = ByteBuffer.wrap(buf, pos, end - pos).slice().order(ByteOrder.LITTLE_ENDIAN)

    /** Champ répété, encodé « packed » (wire type 2) ou un élément par champ. */
    fun repeatedVarints(out: MutableList<Long>) {
        if (wireType == WIRE_LEN) {
            val sub = message()
            while (sub.pos < sub.end) out += sub.varint()
        } else {
            out += varint()
        }
    }

    fun repeatedFloats(out: MutableList<Float>) {
        if (wireType == WIRE_LEN) {
            val sub = message()
            while (sub.pos < sub.end) out += sub.fixed32Float()
        } else {
            out += fixed32Float()
        }
    }

    fun skip() {
        when (wireType) {
            0 -> varint()
            1 -> pos += 8
            WIRE_LEN -> {
                // varint() avance pos : à lire avant de l'ajouter.
                val len = varint().toInt()
                pos += len
            }
            5 -> pos += 4
            else -> error("Protobuf: unsupported wire type $wireType")
        }
    }

    private companion object {
        const val WIRE_LEN = 2
    }
}
