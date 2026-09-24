package gsb.dicom

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.add

open class DicomException(message: String) : Exception(message)

/** 传输语法不被支持：不是导入失败，而是作为领域反例保留原始字节与诊断。 */
class UnsupportedTransferSyntax(val tsUid: String) :
    DicomException("unsupported transfer syntax: $tsUid")

fun tagOf(group: Int, element: Int): Long = (group.toLong() shl 16) or element.toLong()

fun tagKey(tag: Long): String = "%04X,%04X".format((tag shr 16) and 0xFFFF, tag and 0xFFFF)

/** 一个 DICOM 元素。SQ 通过 [items] 递归；其余 VR 的原始字节保存在 [raw]。 */
data class Element(
    val tag: Long,
    val vr: String,
    val raw: ByteArray,
    val items: List<List<Element>>? = null,
)

typealias Dataset = List<Element>

object Tags {
    val TransferSyntaxUID = tagOf(0x0002, 0x0010)
    val SOPClassUID = tagOf(0x0008, 0x0016)
    val SOPInstanceUID = tagOf(0x0008, 0x0018)
    val Modality = tagOf(0x0008, 0x0060)
    val StudyInstanceUID = tagOf(0x0020, 0x000D)
    val SeriesInstanceUID = tagOf(0x0020, 0x000E)
    val InstanceNumber = tagOf(0x0020, 0x0013)
    val ImagePositionPatient = tagOf(0x0020, 0x0032)
    val ImageOrientationPatient = tagOf(0x0020, 0x0037)
    val FrameOfReferenceUID = tagOf(0x0020, 0x0052)
    val SliceThickness = tagOf(0x0018, 0x0050)
    val PixelSpacing = tagOf(0x0028, 0x0030)
    val Rows = tagOf(0x0028, 0x0010)
    val Columns = tagOf(0x0028, 0x0011)
    val NumberOfFrames = tagOf(0x0028, 0x0008)
    val PixelData = tagOf(0x7FE0, 0x0010)
    val SharedFunctionalGroupsSequence = tagOf(0x5200, 0x9229)
    val PerFrameFunctionalGroupsSequence = tagOf(0x5200, 0x9230)
    val PixelMeasuresSequence = tagOf(0x0028, 0x9110)
    val PlanePositionSequence = tagOf(0x0020, 0x9113)
    val PlaneOrientationSequence = tagOf(0x0020, 0x9116)
}

private class Reader(val bytes: ByteArray, var pos: Int = 0) {
    fun remaining() = bytes.size - pos
    fun u16(): Int {
        val v = (bytes[pos].toInt() and 0xFF) or ((bytes[pos + 1].toInt() and 0xFF) shl 8)
        pos += 2; return v
    }
    fun u32(): Long {
        val v = (bytes[pos].toLong() and 0xFF) or
            ((bytes[pos + 1].toLong() and 0xFF) shl 8) or
            ((bytes[pos + 2].toLong() and 0xFF) shl 16) or
            ((bytes[pos + 3].toLong() and 0xFF) shl 24)
        pos += 4; return v
    }
    fun take(n: Int): ByteArray {
        if (n < 0 || pos + n > bytes.size) throw DicomException("truncated element value")
        val out = bytes.copyOfRange(pos, pos + n); pos += n; return out
    }
    fun tag(): Long {
        val g = u16(); val e = u16()
        return (g.toLong() shl 16) or e.toLong()
    }
}

private val LONG_LENGTH_VRS = setOf("OB", "OW", "OF", "SQ", "UT", "UN", "OD", "OL", "OV", "UC", "UR")
private const val UNDEFINED: Long = 0xFFFFFFFFL
private const val ITEM_TAG: Long = 0xFFFEE000L
private const val ITEM_DELIM: Long = 0xFFFEE00DL
private const val SEQ_DELIM: Long = 0xFFFEE0DDL

object Dicom {
    const val TS_IMPLICIT_LE = "1.2.840.10008.1.2"
    const val TS_EXPLICIT_LE = "1.2.840.10008.1.2.1"
    const val TS_EXPLICIT_BE = "1.2.840.10008.1.2.2"

    fun isSupported(ts: String): Boolean = ts == TS_IMPLICIT_LE || ts == TS_EXPLICIT_LE

    class File(
        val transferSyntax: String,
        val meta: Dataset,
        val dataset: Dataset,
        val rawBytes: ByteArray,
    )

    /** 解析完整文件；不支持的传输语法抛 [UnsupportedTransferSyntax]，结构损坏抛 [DicomException]。 */
    fun parse(bytes: ByteArray): File {
        if (bytes.size < 132 || String(bytes, 128, 4) != "DICM") {
            throw DicomException("missing DICM preamble")
        }
        val r = Reader(bytes, 132)
        val meta = mutableListOf<Element>()
        // 文件元信息永远是 Explicit VR Little Endian
        while (r.remaining() >= 8) {
            val save = r.pos
            val tag = r.tag()
            if ((tag shr 16) != 0x0002L) { r.pos = save; break }
            meta += readExplicitElement(r, tag)
        }
        val ts = meta.str(Tags.TransferSyntaxUID) ?: TS_IMPLICIT_LE
        if (!isSupported(ts)) throw UnsupportedTransferSyntax(ts)
        val explicit = ts == TS_EXPLICIT_LE
        val dataset = readDataset(r, r.remaining(), explicit)
        return File(ts, meta, dataset, bytes)
    }

    private fun readDataset(r: Reader, length: Int, explicit: Boolean): Dataset {
        val end = r.pos + length
        val out = mutableListOf<Element>()
        while (r.pos < end && r.remaining() >= 8) {
            val tag = r.tag()
            if (tag == SEQ_DELIM || tag == ITEM_DELIM) { r.pos += 4; break }
            out += if (explicit) readExplicitElement(r, tag) else readImplicitElement(r, tag)
        }
        return out
    }

    private fun readExplicitElement(r: Reader, tag: Long): Element {
        val vr = String(r.take(2))
        val length: Long = if (vr in LONG_LENGTH_VRS) {
            r.take(2); r.u32()
        } else r.u16().toLong()
        if (vr == "SQ") return Element(tag, vr, ByteArray(0), readItems(r, length, explicit = true))
        if (length == UNDEFINED) throw DicomException("undefined length non-SQ element ${tagKey(tag)}")
        return Element(tag, vr, r.take(length.toInt()))
    }

    private fun readImplicitElement(r: Reader, tag: Long): Element {
        val len = r.u32()
        if (len == UNDEFINED) {
            // 隐式 VR 下的未定义长度按 SQ 处理
            return Element(tag, "SQ", ByteArray(0), readItems(r, len, explicit = false))
        }
        return Element(tag, implicitVr(tag), r.take(len.toInt()))
    }

    private fun readItems(r: Reader, sqLength: Long, explicit: Boolean): List<Dataset> {
        val items = mutableListOf<Dataset>()
        val end = if (sqLength == UNDEFINED) Long.MAX_VALUE else r.pos + sqLength
        while (r.pos < end && r.remaining() >= 8) {
            val tag = r.tag()
            val len = r.u32()
            if (tag == SEQ_DELIM) break
            if (tag != ITEM_TAG) throw DicomException("expected item tag, got ${tagKey(tag)}")
            val ds = if (len == UNDEFINED) {
                // 读至 item delimitation
                val inner = mutableListOf<Element>()
                while (true) {
                    val t = r.tag()
                    if (t == ITEM_DELIM) { r.pos += 4; break }
                    r.pos -= 4
                    inner += if (explicit) readExplicitElement(r, r.tag())
                    else readImplicitElement(r, r.tag())
                }
                inner
            } else {
                val sub = Reader(r.take(len.toInt()))
                readDataset(sub, len.toInt(), explicit)
            }
            items += ds
        }
        return items
    }

    private fun implicitVr(tag: Long): String = when (tag) {
        Tags.SOPInstanceUID, Tags.SOPClassUID, Tags.SeriesInstanceUID,
        Tags.StudyInstanceUID, Tags.FrameOfReferenceUID -> "UI"
        Tags.ImagePositionPatient, Tags.ImageOrientationPatient,
        Tags.PixelSpacing, Tags.SliceThickness -> "DS"
        Tags.InstanceNumber, Tags.NumberOfFrames -> "IS"
        Tags.Rows, Tags.Columns -> "US"
        Tags.PixelData -> "OW"
        Tags.SharedFunctionalGroupsSequence, Tags.PerFrameFunctionalGroupsSequence,
        Tags.PixelMeasuresSequence, Tags.PlanePositionSequence, Tags.PlaneOrientationSequence -> "SQ"
        else -> "UN"
    }
}

fun Dataset.find(tag: Long): Element? = firstOrNull { it.tag == tag }

/** 原始字符串表示：仅去掉尾部填充（空格/NUL），不做任何数值规范化。 */
fun Dataset.str(tag: Long): String? {
    val el = find(tag) ?: return null
    if (el.items != null) return null
    return String(el.raw, Charsets.US_ASCII).trimEnd('\u0000', ' ')
}

fun Dataset.strings(tag: Long): List<String> = str(tag)?.split('\\') ?: emptyList()

/** 数值解析失败时返回 null；调用方保留原始字符串并附诊断。 */
fun Dataset.doubles(tag: Long): List<Double>? {
    val el = find(tag) ?: return null
    return when (el.vr) {
        "DS", "IS" -> strings(tag).map { it.trim().toDoubleOrNull() ?: return null }
        "FD" -> (0 until el.raw.size / 8).map { i ->
            java.nio.ByteBuffer.wrap(el.raw, i * 8, 8)
                .order(java.nio.ByteOrder.LITTLE_ENDIAN).double
        }
        "FL" -> (0 until el.raw.size / 4).map { i ->
            java.nio.ByteBuffer.wrap(el.raw, i * 4, 4)
                .order(java.nio.ByteOrder.LITTLE_ENDIAN).float.toDouble()
        }
        "US" -> (0 until el.raw.size / 2).map { i ->
            ((el.raw[i * 2].toInt() and 0xFF) or ((el.raw[i * 2 + 1].toInt() and 0xFF) shl 8)).toDouble()
        }
        else -> null
    }
}

fun Dataset.seq(tag: Long): List<Dataset>? = find(tag)?.items

/** 把数据集导出为 JSON：保留每个 tag 的 VR 与原始字符串表示，SQ 递归。 */
fun datasetToJson(ds: Dataset): JsonObject = buildJsonObject {
    for (el in ds) {
        val key = tagKey(el.tag)
        val items = el.items
        if (items != null) {
            put(key, buildJsonObject {
                put("vr", "SQ")
                putJsonArray("items") { items.forEach { add(datasetToJson(it)) } }
            })
        } else {
            put(key, buildJsonObject {
                put("vr", el.vr)
                when (el.vr) {
                    "OB", "OW", "OF", "UN", "OD", "OL", "OV" -> put("bytes", el.raw.size)
                    else -> put("value", String(el.raw, Charsets.US_ASCII).trimEnd('\u0000', ' '))
                }
            })
        }
    }
}
