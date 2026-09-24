package org.research.gsb.dicom.codec

import java.io.ByteArrayOutputStream

/**
 * Minimal PS3.5 Part 10 reader: Explicit VR little-endian plus preamble / meta group.
 * Encapsulated pixel data (JPEG transfer syntaxes) is retained verbatim so the
 * original bytes and transfer syntax remain available even though the bench cannot
 * decode the pixels.
 */
class DicomReader(data: ByteArray) {
    private val buf = data
    private var pos = 0

    fun read(): DicomFile {
        val preamble: ByteArray
        if (buf.size >= 132 && String(buf, 128, 4, Charsets.US_ASCII) == "DICM") {
            preamble = buf.copyOfRange(0, 128)
            pos = 132
        } else {
            preamble = ByteArray(0)
            pos = 0
        }

        val metaElements = mutableListOf<DicomElement>()
        if (preamble.isNotEmpty()) {
            while (pos + 8 <= buf.size) {
                val tag = peekTag()
                if (tag.group != 0x0002) break
                metaElements += readElement()
            }
        }
        val meta = if (metaElements.isEmpty()) null else DicomDataset(metaElements)
        val ts = meta?.string(Tags.TransferSyntaxUID)
        val explicit = ts == null || ts == ExplicitVRLittleEndian
        val mainElements = mutableListOf<DicomElement>()
        while (pos + 8 <= buf.size) {
            mainElements += readElement(explicit)
        }
        return DicomFile(preamble, meta, DicomDataset(mainElements))
    }

    private fun need(n: Int): ByteArray {
        if (pos + n > buf.size) throw DicomSyntaxException("Unexpected end of stream at $pos (need $n)")
        val out = buf.copyOfRange(pos, pos + n)
        pos += n
        return out
    }

    private fun u16(): Int {
        val b = need(2)
        return (b[0].toInt() and 0xFF) or ((b[1].toInt() and 0xFF) shl 8)
    }

    private fun u32(): Int {
        val b = need(4)
        return (b[0].toInt() and 0xFF) or ((b[1].toInt() and 0xFF) shl 8) or
            ((b[2].toInt() and 0xFF) shl 16) or ((b[3].toInt() and 0xFF) shl 24)
    }

    private fun peekTag(): Tag = Tag(u16(), u16())

    private fun isUndefined(length: Int) = length == -1 || length == 0xFFFFFFFF.toInt()

    private fun readElement(explicit: Boolean = true): DicomElement {
        val tag = peekTag()
        var vr: VR
        var length: Int
        if (explicit) {
            val vrCode = String(need(2), Charsets.US_ASCII)
            vr = VR.from(vrCode)
            length = if (vr.longHeader) {
                need(2) // reserved
                u32()
            } else {
                u16()
            }
        } else {
            vr = if (tag == Tags.PixelData) VR.OW else VR.UN
            length = u32()
        }

        if (vr == VR.SQ) {
            val items = if (isUndefined(length)) readItemsUndefined() else readItemsDefined(length)
            return DicomElement(tag, VR.SQ, ByteArray(0), items, isUndefined(length))
        }

        if (isUndefined(length)) {
            if (tag != Tags.PixelData) {
                throw DicomSyntaxException("Undefined length only supported for PixelData at $tag")
            }
            val fragments = readEncapsulatedPixelData()
            return DicomElement(tag, vr, fragments, emptyList(), true)
        }

        return DicomElement(tag, vr, need(length))
    }

    private fun readItemsDefined(totalLength: Int): List<DicomDataset> {
        val end = pos + totalLength
        val items = mutableListOf<DicomDataset>()
        while (pos + 8 <= end) {
            val tag = peekTag()
            val itemLength = u32()
            if (tag != Tag(0xFFFE, 0xE000)) {
                pos = end
                break
            }
            val itemEnd = pos + itemLength
            val elems = mutableListOf<DicomElement>()
            while (pos + 8 <= itemEnd) elems += readElement()
            items += DicomDataset(elems)
        }
        pos = end
        return items
    }

    private fun readItemsUndefined(): List<DicomDataset> {
        val items = mutableListOf<DicomDataset>()
        while (true) {
            val tag = peekTag()
            val length = u32()
            if (tag == Tag(0xFFFE, 0xE0DD)) break
            if (tag != Tag(0xFFFE, 0xE000)) throw DicomSyntaxException("Expected SQ item, got $tag")
            val elems = mutableListOf<DicomElement>()
            if (isUndefined(length)) {
                // Item content terminated by (FFFE,E00D).
                while (true) {
                    val t = peekTag()
                    val l = u32()
                    if (t == Tag(0xFFFE, 0xE00D)) break
                    // Rewind the tag/length and parse a normal element starting at tag.
                    pos -= 8
                    elems += readElement()
                }
            } else {
                val itemEnd = pos + length
                while (pos + 8 <= itemEnd) elems += readElement()
            }
            items += DicomDataset(elems)
        }
        return items
    }

    private fun readEncapsulatedPixelData(): ByteArray {
        val out = ByteArrayOutputStream()
        while (true) {
            val tag = peekTag()
            val length = u32()
            if (tag == Tag(0xFFFE, 0xE0DD)) break
            out.write(need(length))
        }
        return out.toByteArray()
    }

    companion object {
        const val ExplicitVRLittleEndian = "1.2.840.10008.1.2.1"
    }
}

data class DicomFile(
    val preamble: ByteArray,
    val meta: DicomDataset?,
    val dataset: DicomDataset,
)
