package org.medimg.gsb.dicom

import kotlinx.serialization.Serializable
import java.nio.charset.StandardCharsets

/** 一个 DICOM 数据集 = 元素的有序列表（顺序即文件中出现顺序，作为证据保留）。 */
typealias DicomDataset = List<DicomElement>

/**
 * 单个 DICOM 元素。来源层只做最小解码，不覆盖原始表示：
 * - 文本 VR（DS/IS/UI/CS…）保留 [text] 原文；
 * - 二进制 VR 保留 [binary] 原始字节（PixelData 不落在这里，见 [summary]）；
 * - SQ 保留每个 item 的完整嵌套数据集 [items]。
 */
@Serializable
data class DicomElement(
    val tag: Int,
    val name: String,
    val vr: String,
    val text: String? = null,
    val binaryHex: String? = null,
    val items: List<List<DicomElement>>? = null,
    /** 对 PixelData 等不内联的载荷记录摘要，例如 "OB encapsulated, 3 fragments, 42 bytes"。 */
    val summary: String? = null
) {
    /** 去掉 DICOM 文本尾部空格 pad（UI/CS 等）后的单值。 */
    val trimmed: String? get() = text?.trimEnd().ifEmpty { null }

    /** 按反斜杠拆分的 VM 值（不改变每个分量的原始表示）。 */
    fun strings(): List<String> =
        trimmed?.split('\\')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()

    fun doubles(): List<Double> = strings().mapNotNull { it.toDoubleOrNull() }

    fun ints(): List<Int> = strings().mapNotNull { it.toIntOrNull() }
}

/** 二进制标量的小端解码（不修改原始字节，仅在派生层使用）。 */
object BinaryCodec {
    fun us(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8)

    fun ss(b: ByteArray, off: Int): Int = us(b, off).toShort().toInt()

    fun ul(b: ByteArray, off: Int): Long =
        (b[off].toLong() and 0xFF) or ((b[off + 1].toLong() and 0xFF) shl 8) or
            ((b[off + 2].toLong() and 0xFF) shl 16) or ((b[off + 3].toLong() and 0xFF) shl 24)

    fun sl(b: ByteArray, off: Int): Int = ul(b, off).toInt()

    fun fl(b: ByteArray, off: Int): Float = Float.fromBits(ul(b, off).toInt())

    fun fd(b: ByteArray, off: Int): Double = Double.fromBits(
        (b[off].toLong() and 0xFF) or ((b[off + 1].toLong() and 0xFF) shl 8) or
            ((b[off + 2].toLong() and 0xFF) shl 16) or ((b[off + 3].toLong() and 0xFF) shl 24) or
            ((b[off + 4].toLong() and 0xFF) shl 32) or ((b[off + 5].toLong() and 0xFF) shl 40) or
            ((b[off + 6].toLong() and 0xFF) shl 48) or ((b[off + 7].toLong() and 0xFF) shl 56)
    )

    fun toHex(b: ByteArray): String = buildString(b.size * 2) {
        for (x in b) append("%02X".format(x))
    }
}

@Serializable
class ParsedDicom(
    val transferSyntaxUID: String,
    val transferSyntaxName: String,
    val meta: List<DicomElement>,
    val dataset: List<DicomElement>,
    val pixelNative: Boolean,
    val pixelLengthBytes: Long,
    val decodedPixels: Boolean,
    val diagnostics: List<String> = emptyList()
) {
    fun find(tag: Int): DicomElement? = dataset.firstOrNull { it.tag == tag }
    fun require(tag: Int): DicomElement =
        find(tag) ?: throw DicomParseException("缺少必需元素 ${Tags.nameOf(tag)}")
}

class DicomParseException(message: String, cause: Throwable? = null) : Exception(message, cause)
