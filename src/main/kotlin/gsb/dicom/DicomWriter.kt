package gsb.dicom

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** 构造 Explicit VR Little Endian 数据集，用于生成仓库自带的离线案例包。 */
class DatasetBuilder {
    val elements = mutableListOf<Element>()

    fun add(tag: Long, vr: String, value: String): DatasetBuilder {
        elements += Element(tag, vr, value.toByteArray(Charsets.US_ASCII))
        return this
    }

    fun addUS(tag: Long, vararg values: Int): DatasetBuilder {
        val buf = ByteBuffer.allocate(values.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        values.forEach { buf.putShort(it.toShort()) }
        elements += Element(tag, "US", buf.array())
        return this
    }

    fun addDS(tag: Long, vararg values: Double): DatasetBuilder =
        add(tag, "DS", values.joinToString("\\") { trimDouble(it) })

    fun addIS(tag: Long, vararg values: Int): DatasetBuilder =
        add(tag, "IS", values.joinToString("\\") { it.toString() })

    fun addSQ(tag: Long, items: List<Dataset>): DatasetBuilder {
        elements += Element(tag, "SQ", ByteArray(0), items)
        return this
    }

    fun addOW(tag: Long, bytes: ByteArray): DatasetBuilder {
        elements += Element(tag, "OW", bytes)
        return this
    }

    fun build(): Dataset = elements.toList()

    companion object {
        fun trimDouble(v: Double): String =
            if (v == v.toLong().toDouble()) v.toLong().toString() else v.toString()
    }
}

object DicomWriter {
    private val LONG_LENGTH = setOf("OB", "OW", "OF", "SQ", "UT", "UN")

    /** 写出完整 DICOM 文件（128 字节前导 + DICM + 文件元信息 + 数据集）。 */
    fun writeFile(meta: Dataset, dataset: Dataset, transferSyntax: String = Dicom.TS_EXPLICIT_LE): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(ByteArray(128))
        out.write("DICM".toByteArray(Charsets.US_ASCII))
        val metaWithTs = DatasetBuilder().add(Tags.TransferSyntaxUID, "UI", transferSyntax).build() + meta
        metaWithTs.forEach { writeElement(out, it) }
        dataset.forEach { writeElement(out, it) }
        return out.toByteArray()
    }

    private fun writeElement(out: ByteArrayOutputStream, el: Element) {
        writeTag(out, el.tag)
        val items = el.items
        if (el.vr == "SQ" && items != null) {
            out.write("SQ".toByteArray())
            out.write(byteArrayOf(0, 0))
            val body = ByteArrayOutputStream()
            for (item in items) {
                writeTag(out = body, tag = 0xFFFEE000L)
                val itemBody = ByteArrayOutputStream()
                item.forEach { writeElement(itemBody, it) }
                val ib = itemBody.toByteArray()
                writeU32(body, ib.size)
                body.write(ib)
            }
            val b = body.toByteArray()
            writeU32(out, b.size)
            out.write(b)
            return
        }
        out.write(el.vr.toByteArray(Charsets.US_ASCII))
        var value = el.raw
        if (value.size % 2 == 1) {
            val pad: Byte = when (el.vr) {
                "UI" -> 0
                "OB", "OW", "UN" -> 0
                else -> 0x20
            }
            value = value + pad
        }
        if (el.vr in LONG_LENGTH) {
            out.write(byteArrayOf(0, 0))
            writeU32(out, value.size)
        } else {
            writeU16(out, value.size)
        }
        out.write(value)
    }

    private fun writeTag(out: ByteArrayOutputStream, tag: Long) {
        writeU16(out, ((tag shr 16) and 0xFFFF).toInt())
        writeU16(out, (tag and 0xFFFF).toInt())
    }

    private fun writeU16(out: ByteArrayOutputStream, v: Int) {
        out.write(v and 0xFF); out.write((v shr 8) and 0xFF)
    }

    private fun writeU32(out: ByteArrayOutputStream, v: Int) {
        out.write(v and 0xFF); out.write((v shr 8) and 0xFF)
        out.write((v shr 16) and 0xFF); out.write((v shr 24) and 0xFF)
    }
}
