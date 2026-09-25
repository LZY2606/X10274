package app.dicom

import kotlinx.serialization.Serializable
import java.security.MessageDigest

/** Tag key formatted as "GGGG,EEEE" uppercase hex. */
fun tagKey(group: Int, element: Int): String = "%04X,%04X".format(group, element)

const val TAG_SOP_INSTANCE_UID = "0008,0018"
const val TAG_SOP_CLASS_UID = "0008,0016"
const val TAG_STUDY_INSTANCE_UID = "0020,000D"
const val TAG_SERIES_INSTANCE_UID = "0020,000E"
const val TAG_FRAME_OF_REFERENCE_UID = "0020,0052"
const val TAG_IMAGE_POSITION_PATIENT = "0020,0032"
const val TAG_IMAGE_ORIENTATION_PATIENT = "0020,0037"
const val TAG_PIXEL_SPACING = "0028,0030"
const val TAG_INSTANCE_NUMBER = "0020,0013"
const val TAG_NUMBER_OF_FRAMES = "0028,0008"
const val TAG_PIXEL_DATA = "7FE0,0010"
const val TAG_SHARED_FUNCTIONAL_GROUPS = "5200,9229"
const val TAG_PER_FRAME_FUNCTIONAL_GROUPS = "5200,9230"
const val TAG_PIXEL_MEASURES_SEQUENCE = "0028,9110"
const val TAG_PLANE_POSITION_SEQUENCE = "0020,9113"
const val TAG_PLANE_ORIENTATION_SEQUENCE = "0020,9116"
const val TAG_TRANSFER_SYNTAX_UID = "0002,0010"

const val TS_IMPLICIT_VR_LE = "1.2.840.10008.1.2"
const val TS_EXPLICIT_VR_LE = "1.2.840.10008.1.2.1"
const val TS_EXPLICIT_VR_BE = "1.2.840.10008.1.2.2"
const val TS_RLE_LOSSLESS = "1.2.840.10008.1.2.5"

val SUPPORTED_TRANSFER_SYNTAXES = setOf(TS_IMPLICIT_VR_LE, TS_EXPLICIT_VR_LE)

/** One raw DICOM element exactly as read; original representation is always preserved. */
@Serializable
data class RawElement(
    val tag: String,
    val vr: String,
    /** Raw textual representation (string VRs decoded, binary VRs hex-encoded, SQ = summary). */
    val raw: String,
    /** Backslash-split value components, trimmed but otherwise unparsed. */
    val values: List<String>,
    val length: Long,
    /** Sequence items (only for VR=SQ); each item is a list of elements. */
    val items: List<List<RawElement>> = emptyList(),
)

open class DicomException(message: String) : Exception(message)

class TransferSyntaxException(val transferSyntaxUid: String) :
    DicomException("Unsupported transfer syntax: $transferSyntaxUid")

class NotDicomException(message: String) : DicomException(message)

data class ParsedDicom(
    val fileName: String,
    val sha256: String,
    val transferSyntaxUid: String,
    val metaElements: List<RawElement>,
    val elements: List<RawElement>,
    val pixelDataBytes: Int,
    val pixelSha256: String?,
) {
    fun find(tag: String): RawElement? = elements.firstOrNull { it.tag == tag }
}

private val STRING_VRS = setOf("AE", "AS", "CS", "DA", "DS", "DT", "IS", "LO", "LT", "PN", "SH", "ST", "TM", "UC", "UI", "UR", "UT")
private val LONG_LENGTH_VRS = setOf("OB", "OD", "OF", "OL", "OW", "SQ", "UC", "UN", "UR", "UT")

/** Minimal VR dictionary used when reading Implicit VR Little Endian datasets. */
private val IMPLICIT_VR_DICTIONARY = mapOf(
    "0008,0016" to "UI", "0008,0018" to "UI", "0008,0020" to "DA", "0008,0030" to "TM",
    "0008,0060" to "CS", "0008,103E" to "LO",
    "0010,0010" to "PN", "0010,0020" to "LO",
    "0020,000D" to "UI", "0020,000E" to "UI", "0020,0013" to "IS",
    "0020,0032" to "DS", "0020,0037" to "DS", "0020,0052" to "UI",
    "0020,9113" to "SQ", "0020,9116" to "SQ",
    "0028,0002" to "US", "0028,0004" to "CS", "0028,0008" to "IS",
    "0028,0010" to "US", "0028,0011" to "US", "0028,0030" to "DS",
    "0028,0100" to "US", "0028,0101" to "US", "0028,0103" to "US",
    "0028,9110" to "SQ",
    "5200,9229" to "SQ", "5200,9230" to "SQ",
    "7FE0,0010" to "OW",
)

private val NUL = Char(0)

private class Cursor(val bytes: ByteArray, var pos: Int, val explicitVr: Boolean) {
    fun u16(): Int {
        val v = (bytes[pos].toInt() and 0xFF) or ((bytes[pos + 1].toInt() and 0xFF) shl 8)
        pos += 2
        return v
    }

    fun u32(): Long {
        val v = (bytes[pos].toLong() and 0xFF) or
            ((bytes[pos + 1].toLong() and 0xFF) shl 8) or
            ((bytes[pos + 2].toLong() and 0xFF) shl 16) or
            ((bytes[pos + 3].toLong() and 0xFF) shl 24)
        pos += 4
        return v
    }

    fun peekTag(): Pair<Int, Int> {
        val g = (bytes[pos].toInt() and 0xFF) or ((bytes[pos + 1].toInt() and 0xFF) shl 8)
        val e = (bytes[pos + 2].toInt() and 0xFF) or ((bytes[pos + 3].toInt() and 0xFF) shl 8)
        return g to e
    }

    fun take(n: Int): ByteArray {
        val out = bytes.copyOfRange(pos, pos + n)
        pos += n
        return out
    }
}

object DicomReader {

    fun parse(fileName: String, bytes: ByteArray): ParsedDicom {
        if (bytes.size < 132) throw NotDicomException("file too small for DICOM preamble (${bytes.size} bytes)")
        val magic = String(bytes, 128, 4, Charsets.US_ASCII)
        if (magic != "DICM") throw NotDicomException("missing DICM magic at offset 128")

        val sha = sha256Hex(bytes)
        val metaCursor = Cursor(bytes, 132, explicitVr = true)
        val meta = mutableListOf<RawElement>()
        while (metaCursor.pos + 8 <= bytes.size) {
            val (g, _) = metaCursor.peekTag()
            if (g != 0x0002) break
            meta.add(readElement(metaCursor))
        }
        val tsUid = meta.firstOrNull { it.tag == TAG_TRANSFER_SYNTAX_UID }?.values?.firstOrNull()
            ?: throw NotDicomException("missing (0002,0010) TransferSyntaxUID in file meta header")
        if (tsUid !in SUPPORTED_TRANSFER_SYNTAXES) throw TransferSyntaxException(tsUid)

        val explicit = tsUid == TS_EXPLICIT_VR_LE
        val cursor = Cursor(bytes, metaCursor.pos, explicit)
        val elements = mutableListOf<RawElement>()
        var pixelBytes = 0
        var pixelSha: String? = null
        while (cursor.pos + 8 <= bytes.size) {
            val el = readElement(cursor)
            if (el.tag == TAG_PIXEL_DATA) {
                pixelBytes = el.length.toInt()
                pixelSha = el.values.firstOrNull()
            }
            elements.add(el)
        }
        return ParsedDicom(fileName, sha, tsUid, meta, elements, pixelBytes, pixelSha)
    }

    private fun readElement(cursor: Cursor): RawElement {
        val group = cursor.u16()
        val element = cursor.u16()
        val tag = tagKey(group, element)
        val vr: String
        val length: Long
        if (cursor.explicitVr) {
            vr = String(cursor.take(2), Charsets.US_ASCII)
            if (vr in LONG_LENGTH_VRS) {
                cursor.take(2)
                length = cursor.u32()
            } else {
                length = cursor.u16().toLong()
            }
        } else {
            vr = IMPLICIT_VR_DICTIONARY[tag] ?: "UN"
            length = cursor.u32()
        }

        if (tag == TAG_PIXEL_DATA) {
            if (length == 0xFFFFFFFFL) throw DicomException("encapsulated (undefined length) PixelData is not supported")
            val data = cursor.take(length.toInt())
            return RawElement(tag, vr, "<pixel data ${data.size} bytes>", listOf(sha256Hex(data)), length)
        }
        if (vr == "SQ") {
            val items = readSequence(cursor, length)
            return RawElement(tag, vr, "SQ(${items.size} item(s))", emptyList(), length, items)
        }
        if (length == 0xFFFFFFFFL) throw DicomException("undefined length element $tag with VR $vr is not supported")
        val data = cursor.take(length.toInt())
        if (vr in STRING_VRS) {
            val decoded = String(data, Charsets.ISO_8859_1).trimEnd(' ', NUL)
            val values = decoded.split('\\').map { it.trim() }.filter { it.isNotEmpty() }
            return RawElement(tag, vr, decoded.trim(), values, length)
        }
        return RawElement(tag, vr, data.joinToString("") { "%02X".format(it) }, emptyList(), length)
    }

    private fun readSequence(cursor: Cursor, length: Long): List<List<RawElement>> {
        val items = mutableListOf<List<RawElement>>()
        val seqEnd = if (length == 0xFFFFFFFFL) null else cursor.pos + length.toInt()
        while (true) {
            if (seqEnd != null && cursor.pos >= seqEnd) break
            if (cursor.pos + 8 > cursor.bytes.size) break
            val (g, e) = cursor.peekTag()
            if (g == 0xFFFE && e == 0xE0DD) { // Sequence Delimitation Item
                cursor.take(8)
                break
            }
            if (!(g == 0xFFFE && e == 0xE000)) throw DicomException("expected Item (FFFE,E000) inside sequence")
            cursor.take(4)
            val itemLen = cursor.u32()
            val itemElements = mutableListOf<RawElement>()
            val itemEnd = if (itemLen == 0xFFFFFFFFL) null else cursor.pos + itemLen.toInt()
            while (true) {
                if (itemEnd != null && cursor.pos >= itemEnd) break
                if (cursor.pos + 8 > cursor.bytes.size) break
                val (ig, ie) = cursor.peekTag()
                if (ig == 0xFFFE && ie == 0xE00D) { // Item Delimitation Item
                    cursor.take(8)
                    break
                }
                itemElements.add(readElement(cursor))
            }
            items.add(itemElements)
        }
        return items
    }

    fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
