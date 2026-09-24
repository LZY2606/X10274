package org.gsb.dicomreview.dicom

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * Minimal DICOM reader.
 *
 *  - Explicit VR / Implicit VR little endian bodies, including nested functional group sequences.
 *  - Encapsulated (compressed) pixel data: metadata parsed, pixel fragments retained as binary.
 *  - Big-endian explicit and deflated transfer syntaxes: header retained with a fatal diagnostic,
 *    no bytes are guessed.
 */
object DicomReader {

    private val EXTENDED_VRS = setOf("OB", "OD", "OF", "OL", "OV", "OW", "SQ", "UC", "UN", "UR", "UT")
    private val BINARY_VRS = setOf("OB", "OD", "OF", "OL", "OV", "OW", "UN")

    fun parse(bytes: ByteArray): ParsedDicom {
        val input = ByteArrayInputStream(bytes)
        val preamble = ByteArray(128)
        if (input.read(preamble) != 128) throw DicomParseException("file shorter than 128 byte DICOM preamble")
        val prefix = ByteArray(4)
        input.read(prefix)
        if (String(prefix, Charsets.US_ASCII) != "DICM") throw DicomParseException("missing 'DICM' prefix")

        val metaElements = mutableListOf<DicomElement>()
        var bodyStart = -1
        while (input.available() > 0) {
            val position = bytes.size - input.available()
            input.mark(8)
            val tag = readTagLE(input)
            if (tag.group != 0x0002) {
                input.reset()
                bodyStart = position
                break
            }
            metaElements.add(readExplicitElement(input, tag))
        }
        if (bodyStart < 0) bodyStart = bytes.size - input.available()

        val meta = DicomDataset(metaElements)
        val tsuid = meta[DicomTags.TransferSyntaxUID]?.singleString()

        if (tsuid == "1.2.840.10008.1.2.2") return ParsedDicom(
            meta, tsuid, true, bigEndian = true, pixelEncapsulated = false,
            "TRANSFER_SYNTAX_UNSUPPORTED: big endian explicit VR ($tsuid) not decoded; file meta header retained"
        )
        if (tsuid == "1.2.840.10008.1.2.1.99") return ParsedDicom(
            meta, tsuid, true, bigEndian = false, pixelEncapsulated = false,
            "TRANSFER_SYNTAX_UNSUPPORTED: deflated little endian ($tsuid) not decoded; file meta header retained"
        )

        val explicitVR = tsuid != "1.2.840.10008.1.2"
        val encapsulated = tsuid != null && tsuid !in setOf(
            "1.2.840.10008.1.2", "1.2.840.10008.1.2.1",
            "1.2.840.10008.1.2.1.99", "1.2.840.10008.1.2.2"
        )

        val bodyInput = ByteArrayInputStream(bytes, bodyStart, bytes.size - bodyStart)
        val body = parseDataset(bodyInput, bytes.copyOfRange(bodyStart, bytes.size), explicitVR)
        return ParsedDicom(DicomDataset(metaElements + body), tsuid, explicitVR, false, encapsulated, null)
    }

    private fun parseDataset(input: ByteArrayInputStream, region: ByteArray, explicitVR: Boolean): List<DicomElement> {
        val out = mutableListOf<DicomElement>()
        while (input.available() > 0) {
            val tag = readTagLE(input)
            if (tag.group == 0xFFFE && (tag.element == 0xE0DD || tag.element == 0xE00D)) break
            out.add(
                if (explicitVR) readExplicitElement(input, tag)
                else readImplicitElement(input, tag)
            )
        }
        return out
    }

    private fun readExplicitElement(input: ByteArrayInputStream, tag: Tag): DicomElement {
        val vr = String(readN(input, 2), Charsets.US_ASCII)
        val length = if (vr in EXTENDED_VRS) {
            input.skip(2)
            readUInt32LE(input)
        } else readUInt32LE(input, short = true)
        return DicomElement(tag, vr, readValue(input, vr, length, explicitVR = true))
    }

    private fun readImplicitElement(input: ByteArrayInputStream, tag: Tag): DicomElement {
        val length = readUInt32LE(input)
        val vr = if (tag.group == 0x7FE0) "OW" else "UN"
        return DicomElement(tag, vr, readValue(input, vr, length, explicitVR = false))
    }

    private fun readValue(input: ByteArrayInputStream, vr: String, length: Int, explicitVR: Boolean): DicomValue {
        if (length == -1) {
            if (vr == "SQ") return DicomValue.Sequence(readSequence(input, explicitVR, bounded = false))
            return DicomValue.Binary(readEncapsulatedPixel(input), vr)
        }
        val raw = readN(input, length)
        return when {
            vr == "SQ" -> DicomValue.Sequence(readSequence(ByteArrayInputStream(raw), explicitVR, bounded = true))
            vr in BINARY_VRS -> DicomValue.Binary(raw, vr)
            else -> DicomValue.RawString(asciiTrimmed(raw), vr, "vr=$vr;bytes=$length")
        }
    }

    private fun readSequence(input: ByteArrayInputStream, explicitVR: Boolean, bounded: Boolean): List<DicomDataset> {
        val items = mutableListOf<DicomDataset>()
        while (input.available() > 0) {
            val tag = readTagLE(input)
            val itemLen = readUInt32LE(input)
            when {
                tag.group == 0xFFFE && tag.element == 0xE0DD -> return items
                tag.group == 0xFFFE && tag.element == 0xE000 && itemLen >= 0 -> {
                    val itemBytes = readN(input, itemLen)
                    items.add(DicomDataset(parseDataset(ByteArrayInputStream(itemBytes), itemBytes, explicitVR)))
                }
                tag.group == 0xFFFE && tag.element == 0xE000 && itemLen == -1 -> {
                    items.add(DicomDataset(parseDataset(input, ByteArray(0), explicitVR)))
                }
                else -> { /* skip stray */ }
            }
        }
        if (!bounded) throw DicomParseException("unbounded sequence missing delimitation item")
        return items
    }

    private fun readEncapsulatedPixel(input: ByteArrayInputStream): ByteArray {
        val out = ByteArrayOutputStream()
        while (input.available() > 0) {
            val tag = readTagLE(input)
            val len = readUInt32LE(input)
            if (tag.group == 0xFFFE && tag.element == 0xE0DD) break
            if (tag.group == 0xFFFE && tag.element == 0xE000 && len >= 0) out.write(readN(input, len))
        }
        return out.toByteArray()
    }

    private fun asciiTrimmed(bytes: ByteArray): String {
        var end = bytes.size
        while (end > 0 && (bytes[end - 1] == 0.toByte() || bytes[end - 1] == ' '.code.toByte())) end--
        return String(bytes, 0, end, Charsets.US_ASCII).trim()
    }

    private fun readN(input: ByteArrayInputStream, n: Int): ByteArray {
        val out = ByteArray(n)
        var off = 0
        while (off < n) {
            val r = input.read(out, off, n - off)
            if (r == -1) throw DicomParseException("unexpected end of data, wanted $n got $off")
            off += r
        }
        return out
    }

    private fun readTagLE(input: ByteArrayInputStream): Tag {
        val group = readUInt32LE(input, short = true)
        val element = readUInt32LE(input, short = true)
        return Tag(group, element)
    }

    private fun readUInt32LE(input: ByteArrayInputStream, short: Boolean = false): Int {
        val bytesCount = if (short) 2 else 4
        var v = 0
        for (i in 0 until bytesCount) {
            val b = input.read()
            if (b == -1) throw DicomParseException("unexpected EOF")
            v = v or ((b and 0xFF) shl (8 * i))
        }
        if (v == 0xFFFFFFFF.toInt()) return -1
        return v
    }

    private fun Int.toIntExact(): Int = this
}
