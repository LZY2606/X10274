package app.dicom

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Reads a DICOM dataset into flat Elements with sequence-item paths.
 * Handles Explicit VR and Implicit VR little endian, SQ (defined/undefined
 * length) and encapsulated pixel data items.
 */
class DatasetReader(
    private val buf: ByteArray,
    private val initialPos: Int,
    private val implicit: Boolean,
    private val stopAtGroup: Int? = null
) {
    private var pos = initialPos
    var lastEnd = initialPos
        private set

    private val bb: ByteBuffer = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN)

    fun readDataset(out: MutableList<DicomCodec.Element>): Int {
        val end = buf.size
        while (pos + 8 <= end) {
            val tag = (u16at(pos) shl 16) or u16at(pos + 2)
            if (tag == 0xFFFEE00D || tag == 0xFEFF00E0.toInt()) { pos += 8; continue }
            if (stopAtGroup != null && tag ushr 16 > stopAtGroup) break
            pos += 4
            var vr = "UN"
            var valueLen: Long
            if (!implicit) {
                vr = String(buf, pos, 2, Charsets.US_ASCII)
                pos += 2
                if (vr in VR_SHORT) {
                    valueLen = u16at(pos).toLong(); pos += 2
                } else {
                    pos += 2 // reserved
                    valueLen = u32at(pos); pos += 4
                }
            } else {
                valueLen = u32at(pos); pos += 4
                vr = DicomTags.vrOf(tag) ?: "UN"
            }
            if (tag == 0x7FE00010 && valueLen == 0xFFFFFFFFL) {
                val frags = readEncapsulated()
                frags.forEachIndexed { i, f ->
                    out += DicomCodec.Element(tag, "OB", f, "", fragment = true)
                }
                continue
            }
            if (valueLen == 0xFFFFFFFFL) {
                if (vr == "SQ" || DicomTags.vrOf(tag) == "SQ") {
                    readSequence(tag, "", out)
                } else {
                    // unknown-length non-SQ: skip until sequence delimitation not applicable
                    pos = end
                }
                continue
            }
            val len = valueLen.toInt()
            if (pos + len > end) throw DcmParseException("value length overruns dataset at ${DicomCodec.tagHex(tag)}")
            val data = buf.copyOfRange(pos, pos + len)
            out += DicomCodec.Element(tag, vr, data, "", fragment = false)
            pos += len
        }
        lastEnd = pos
        return pos
    }

    private fun readSequence(parentTag: Int, parentPath: String, out: MutableList<DicomCodec.Element>) {
        var itemIndex = 0
        while (pos + 8 <= buf.size) {
            val tag = (u16at(pos) shl 16) or u16at(pos + 2)
            if (tag == 0xFFFEE0DD) { pos += 8; return }
            if (tag != 0xFFFEE000) throw DcmParseException("expected sequence item at $pos, got ${DicomCodec.tagHex(tag)}")
            val itemLen = u32at(pos + 4)
            pos += 8
            val itemEnd = if (itemLen == 0xFFFFFFFFL) -1 else pos + itemLen.toInt()
            val base = "${DicomCodec.tagHex(parentTag)}[$itemIndex]/"
            readItemElements(base, itemEnd, out)
            itemIndex++
        }
    }

    private fun readItemElements(pathBase: String, itemEnd: Int, out: MutableList<DicomCodec.Element>) {
        val end = if (itemEnd < 0) buf.size else itemEnd
        while (pos + 8 <= end) {
            val tag = (u16at(pos) shl 16) or u16at(pos + 2)
            if (tag == 0xFFFEE00D) { pos += 8; if (itemEnd < 0) return else continue }
            pos += 4
            var vr = "UN"
            var valueLen: Long
            if (!implicit) {
                vr = String(buf, pos, 2, Charsets.US_ASCII); pos += 2
                if (vr in VR_SHORT) { valueLen = u16at(pos).toLong(); pos += 2 }
                else { pos += 2; valueLen = u32at(pos); pos += 4 }
            } else {
                valueLen = u32at(pos); pos += 4
                vr = DicomTags.vrOf(tag) ?: "UN"
            }
            if (valueLen == 0xFFFFFFFFL && (vr == "SQ" || DicomTags.vrOf(tag) == "SQ")) {
                readSequence(tag, pathBase, out)
                continue
            }
            if (valueLen == 0xFFFFFFFFL) {
                // encapsulated pixel fragment inside item (e.g. basic offset table handling)
                if (itemEnd < 0) { pos = end; return }
                pos = end; return
            }
            val len = valueLen.toInt()
            if (pos + len > end) {
                if (itemEnd < 0) { pos = end; return }
                throw DcmParseException("item element overruns at ${DicomCodec.tagHex(tag)}")
            }
            val data = buf.copyOfRange(pos, pos + len)
            out += DicomCodec.Element(tag, vr, data, pathBase, fragment = false)
            pos += len
        }
        if (itemEnd < 0 && pos < buf.size) {
            // seek delimiter alignment
        }
    }

    private fun readEncapsulated(): List<ByteArray> {
        val fragments = mutableListOf<ByteArray>()
        // first item is the basic offset table
        val tag = (u16at(pos) shl 16) or u16at(pos + 2)
        if (tag != 0xFFFEE000) throw DcmParseException("expected encapsulated item")
        val tableLen = u32at(pos + 4)
        pos += 8 + tableLen.toInt()
        while (pos + 8 <= buf.size) {
            val itag = (u16at(pos) shl 16) or u16at(pos + 2)
            val ilen = u32at(pos + 4)
            pos += 8
            if (itag == 0xFFFEE0DD) return fragments
            if (itag != 0xFFFEE000) throw DcmParseException("expected encapsulated fragment")
            fragments += buf.copyOfRange(pos, pos + ilen.toInt())
            pos += ilen.toInt()
        }
        return fragments
    }

    private fun u16at(p: Int) = bb.getShort(p).toInt() and 0xFFFF
    private fun u32at(p: Int) = bb.getInt(p).toLong() and 0xFFFFFFFFL

    companion object {
        val VR_SHORT = setOf(
            "AE", "AS", "AT", "CS", "DA", "DS", "DT", "FL", "FD", "IS", "LO",
            "LT", "PN", "SH", "SL", "SS", "ST", "TM", "UI", "UL", "US"
        )
    }
}
