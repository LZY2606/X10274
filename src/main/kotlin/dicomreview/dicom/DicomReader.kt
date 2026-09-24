package dicomreview.dicom

import dicomreview.model.ParsedFile
import dicomreview.model.RawTag

object TransferSyntaxes {
    const val ImplicitVRLittleEndian = "1.2.840.10008.1.2"
    const val ExplicitVRLittleEndian = "1.2.840.10008.1.2.1"
    const val ExplicitVRBigEndian = "1.2.840.10008.1.2.2"

    val SUPPORTED_PIXEL = setOf(
        ImplicitVRLittleEndian,
        ExplicitVRLittleEndian,
        ExplicitVRBigEndian
    )
    fun isSupportedPixel(ts: String?): Boolean = ts != null && ts in SUPPORTED_PIXEL
}

/**
 * Minimal DICOM parser. Every element is preserved verbatim as [RawTag],
 * including elements nested in sequences (with a `Group,Element/index/...`
 * path). Pixel data is recorded as bulk with its byte length — never dropped.
 * Handles explicit/implicit VR, little endian datasets, defined and undefined
 * length items/sequences, and encapsulated pixel data.
 */
class DicomReader(private val data: ByteArray) {
    private val tags = mutableListOf<RawTag>()
    private val warnings = mutableListOf<String>()
    var transferSyntax: String? = null; private set
    var mediaSopClass: String? = null; private set
    var pixelBytes: Long = 0; private set

    private var pos = 0

    companion object {
        fun parse(bytes: ByteArray, relativePath: String): ParsedFile {
            if (bytes.size < 132 || !String(bytes, 128, 4).equals("DICM"))
                throw DicomParseException("missing DICM preamble at $relativePath")
            val r = DicomReader(bytes)
            r.readFileMetaInformation()
            val implicit = r.transferSyntax == TransferSyntaxes.ImplicitVRLittleEndian
            r.readDataset(end = bytes.size, explicit = !implicit, littleEndian = true, path = "")
            return ParsedFile(relativePath, r.mediaSopClass, r.transferSyntax,
                r.tags.toList(), r.pixelBytes, r.warnings.toList())
        }
    }

    /** File meta information is always Explicit VR Little Endian. */
    private fun readFileMetaInformation() {
        pos = 132
        while (pos + 8 <= data.size) {
            val tag = u16(true) shl 16 or u16(true)
            if ((tag ushr 16) != 0x0002) { pos -= 8; return }
            val vr = ascii(2)
            val short = vr in SHORT_VR
            if (short) pos += 2
            val len = if (short) u32(true) else u16(true)
            readValue(tag, vr, len, explicit = true, le = true, path = "")
        }
    }

    private fun readDataset(end: Int, explicit: Boolean, le: Boolean, path: String) {
        while (pos + 8 <= end) {
            val tagStart = pos
            val tag = u16(le) shl 16 or u16(le)
            if (tag == 0xFFFEE0DD) { pos += 4; return } // sequence delim
            val vr: String
            val len: Int
            if (explicit) {
                vr = ascii(2)
                if (vr in SHORT_VR) { pos += 2; len = u32(le) } else len = u16(le)
            } else {
                vr = implicitVr(tag)
                len = u32(le)
            }
            if (len == -1) readUndefined(tag, vr, explicit, le, path)
            else {
                if (pos.toLong() + len > data.size.toLong()) {
                    warnings.add("tag %08X length %d exceeds file at '%s'".format(tag, len, path))
                    return
                }
                readValue(tag, vr, len, explicit, le, path)
            }
        }
    }

    private fun readValue(tag: Int, vr: String, len: Int, explicit: Boolean, le: Boolean, path: String) {
        val start = pos
        if (vr == "SQ") {
            readItems(start + len, explicit, le, path, tag)
        } else if (tag == Tags.PixelData) {
            pixelBytes += len.toLong()
            tags += RawTag(tag ushr 16, tag and 0xFFFF, keywordOf(tag), vr,
                "<bulk pixel data>", bulk = true, bulkBytes = len.toLong(), path = path)
            pos = start + len
        } else {
            val raw = ascii(len).trimEnd('\u0000', ' ')
            if (tag == Tags.TransferSyntaxUID) transferSyntax = raw
            if (tag == Tags.MediaStorageSOPClassUID) mediaSopClass = raw
            tags += RawTag(tag ushr 16, tag and 0xFFFF, keywordOf(tag), vr, raw, path = path)
            pos = start + len
        }
    }

    /** Undefined length: either SQ items ending at sequence delim, or encapsulated pixels. */
    private fun readUndefined(tag: Int, vr: String, explicit: Boolean, le: Boolean, path: String) {
        if (vr == "SQ") {
            readItems(findDelim(0xFFFEE0DD, le) + 8, explicit, le, path, tag)
        } else {
            // encapsulated pixel data: skip fragments, counting payload bytes
            var payload = 0L
            while (pos + 8 <= data.size) {
                val itemTag = u32(le); val itemLen = u32(le)
                if (itemTag == 0xFFFEE0DD) break
                if (itemTag == 0xFFFEE000 && itemLen >= 0) { payload += itemLen; pos += itemLen }
                else break
            }
            pixelBytes += payload
            tags += RawTag(tag ushr 16, tag and 0xFFFF, keywordOf(tag), vr,
                "<encapsulated pixel data>", bulk = true, bulkBytes = payload, path = path)
        }
    }

    private fun readItems(end: Int, explicit: Boolean, le: Boolean, parentPath: String, seqTag: Int) {
        var index = 0
        val seqName = "%04X,%04X".format(seqTag ushr 16, seqTag and 0xFFFF)
        while (pos + 8 <= end) {
            val itemTag = u32(le)
            val itemLen = u32(le)
            if (itemTag == 0xFFFEE0DD) { pos = end; return }
            if (itemTag != 0xFFFEE000) { warnings.add("expected item in $seqName"); return }
            val itemPath = listOf(parentPath, seqName).filter { it.isNotEmpty() }.joinToString("/") + "/$index"
            if (itemLen == -1) {
                val itemEnd = findDelim(0xFFFEE00D, le)
                readDataset(itemEnd, explicit, le, itemPath)
                pos = itemEnd + 8
            } else {
                readDataset(pos + itemLen, explicit, le, itemPath)
            }
            index++
        }
        pos = end
    }

    private fun findDelim(delim: Int, le: Boolean): Int {
        var p = pos
        while (p + 8 <= data.size) {
            val t = u32At(p, le)
            if (t == delim) return p
            p += 2
        }
        throw DicomParseException("unterminated undefined-length element")
    }

    private fun u16(le: Boolean): Int {
        val b0 = data[pos].toInt() and 0xFF; val b1 = data[pos + 1].toInt() and 0xFF
        pos += 2
        return if (le) b0 or (b1 shl 8) else b1 or (b0 shl 8)
    }
    private fun u32(le: Boolean): Int {
        val v = u32At(pos, le); pos += 4; return v
    }
    private fun u32At(p: Int, le: Boolean): Int {
        val b = IntArray(4) { data[p + it].toInt() and 0xFF }
        return if (le) b[0] or (b[1] shl 8) or (b[2] shl 16) or (b[3] shl 24)
        else b[3] or (b[2] shl 8) or (b[1] shl 16) or (b[0] shl 24)
    }
    private fun ascii(n: Int): String {
        if (n == 0) return ""
        val s = String(data, pos, n, Charsets.US_ASCII)
        pos += n
        return s
    }

    private fun implicitVr(tag: Int): String {
        val g = tag ushr 16; val e = tag and 0xFFFF
        if (tag == Tags.PixelData) return "OW"
        if (g == 0x5200 || (e and 1) == 1) return "SQ"
        return "UN"
    }

    companion object {
        private val SHORT_VR = setOf("OB", "OD", "OF", "OL", "OW", "SQ", "UC", "UR", "UT", "UN")
    }
}

class DicomParseException(message: String) : RuntimeException(message)
