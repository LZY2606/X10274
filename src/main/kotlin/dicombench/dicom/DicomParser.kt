package dicombench.dicom

import java.nio.ByteOrder

/**
 * Tolerant DICOM Part 10 parser used by the geometry bench.
 *
 * Scope by design:
 *  - Explicit VR Little Endian (incl. meta header) and Implicit VR Little Endian.
 *  - Sequences with explicit/undefined length items (functional groups preserved).
 *  - Encapsulated pixel data (compressed transfer syntaxes) is recorded raw; pixels
 *    are never silently dropped, they become an unsupported-syntax diagnostic.
 */
object DicomParser {

    fun parse(data: ByteArray): ParsedDicom {
        val warnings = mutableListOf<String>()
        if (data.size < 132) error("File too short to be DICOM Part 10 (${data.size} bytes)")
        if (!isDicom(data)) error("Missing 'DICM' preamble at byte 128")

        var pos = 132
        var explicit = true
        var transferSyntax: String? = null
        val result = mutableListOf<DicomElement>()

        while (pos + 8 <= data.size) {
            val group = u16(data, pos); pos += 2
            val element = u16(data, pos); pos += 2
            val inMeta = group == 0x0002
            val useExplicit = inMeta || explicit
            val ge = (group shl 16) or element
            val vr: String
            var len: Long

            if (useExplicit) {
                val rawVr = String(data, pos, 2, Charsets.US_ASCII)
                pos += 2
                vr = if (rawVr.any { it.code < 0x41 || it.code > 0x5A })
                    DicomTags.vrByTag[ge] ?: "UN" else rawVr
                if (DicomElement.longForm(vr)) {
                    pos += 2
                    len = u32(data, pos); pos += 4
                } else {
                    len = u16(data, pos).toLong(); pos += 2
                }
            } else {
                vr = DicomTags.vrByTag[ge]
                    ?: if (ge == DicomTags.PixelData) "OW" else "UN"
                len = u32(data, pos); pos += 4
            }

            if (group == 0xFFFE) break
            if (len == 0xFFFFFFFFL) {
                if (vr == "SQ") {
                    val (items, consumed) = readSequence(data, pos, useExplicit)
                    pos += consumed
                    result += DicomElement(group, element, vr, ByteArray(0), items)
                } else {
                    val (fragment, consumed) = readEncapsulated(data, pos)
                    pos += consumed
                    result += DicomElement(group, element, vr, fragment)
                    warnings += "Encapsulated pixel data for ($vr) retained as raw fragment; pixel decoding not supported"
                }
                continue
            }

            check(len <= data.size - pos) { "Element (%04X,%04X) length $len exceeds file".format(group, element) }
            val end = pos + len.toInt()
            val body = data.copyOfRange(pos, end)
            pos = end

            if (vr == "SQ") {
                val (items, _) = readSequence(body, 0, useExplicit)
                result += DicomElement(group, element, vr, body, items)
            } else {
                result += DicomElement(group, element, vr, body)
                if (ge == DicomTags.TransferSyntaxUID) {
                    transferSyntax = body.toString(Charsets.US_ASCII).trim().trimEnd { it.code == 0 }
                    explicit = !transferSyntax.startsWith("1.2.840.10008.1.2.2")
                }
            }
        }
        return ParsedDicom(result, transferSyntax, warnings)
    }

    fun isDicom(data: ByteArray): Boolean =
        data.size >= 132 && data[128] == 68 && data[129] == 73 && data[130] == 67 && data[131] == 77

    private fun readSequence(data: ByteArray, start: Int, explicit: Boolean): Pair<List<List<DicomElement>>, Int> {
        val items = mutableListOf<List<DicomElement>>()
        var pos = start
        while (pos + 8 <= data.size) {
            val g = u16(data, pos); val e = u16(data, pos + 2)
            val itemLen = u32(data, pos + 4); pos += 8
            when {
                g == 0xFFFE && e == 0xE000 && itemLen == 0xFFFFFFFFL -> {
                    val end = findSequenceItemEnd(data, pos)
                    items += parseItem(data, pos, end, explicit)
                    pos = end + 8
                }
                g == 0xFFFE && e == 0xE000 -> {
                    items += parseItem(data, pos, pos + itemLen.toInt(), explicit)
                    pos += itemLen.toInt()
                }
                g == 0xFFFE && (e == 0xE00D || e == 0xE0DD) -> break
                else -> break
            }
        }
        return items to (pos - start)
    }

    private fun findSequenceItemEnd(data: ByteArray, start: Int): Int {
        var pos = start
        while (pos + 8 <= data.size) {
            val g = u16(data, pos); val e = u16(data, pos + 2)
            val len = u32(data, pos + 4)
            if (g == 0xFFFE && (e == 0xE00D || e == 0xE0DD)) return pos
            pos += 8
            pos = when (len) {
                0xFFFFFFFFL -> findSequenceItemEnd(data, pos)
                else -> pos + len.toInt()
            }
        }
        return data.size
    }

    private fun parseItem(data: ByteArray, start: Int, end: Int, explicit: Boolean): List<DicomElement> {
        var pos = start
        val out = mutableListOf<DicomElement>()
        while (pos + 8 <= end) {
            val group = u16(data, pos); pos += 2
            val element = u16(data, pos); pos += 2
            if (group == 0xFFFE) break
            val ge = (group shl 16) or element
            val vr: String
            val len: Long
            if (explicit) {
                val rawVr = String(data, pos, 2, Charsets.US_ASCII)
                pos += 2
                vr = if (rawVr.any { it.code < 0x41 || it.code > 0x5A })
                    DicomTags.vrByTag[ge] ?: "UN" else rawVr
                if (DicomElement.longForm(vr)) { pos += 2; len = u32(data, pos); pos += 4 }
                else { len = u16(data, pos).toLong(); pos += 2 }
            } else {
                vr = DicomTags.vrByTag[ge] ?: "UN"
                len = u32(data, pos); pos += 4
            }
            if (len > end - pos) break
            val itemEnd = pos + len.toInt()
            val body = data.copyOfRange(pos, itemEnd)
            pos = itemEnd
            val items = if (vr == "SQ") readSequence(body, 0, explicit).first else emptyList()
            out += DicomElement(group, element, vr, body, items)
        }
        return out
    }

    private fun readEncapsulated(data: ByteArray, start: Int): Pair<ByteArray, Int> {
        var pos = start
        val payload = java.io.ByteArrayOutputStream()
        while (pos + 8 <= data.size) {
            val g = u16(data, pos); val e = u16(data, pos + 2); val len = u32(data, pos + 4); pos += 8
            if (g == 0xFFFE && (e == 0xE00D || e == 0xE0DD)) break
            if (g == 0xFFFE && e == 0xE000 && len != 0xFFFFFFFFL) {
                payload.write(data, pos, len.toInt())
                pos += len.toInt()
            }
        }
        return payload.toByteArray() to (pos - start)
    }

    private fun u16(data: ByteArray, pos: Int): Int {
        val lo = data[pos].toInt() and 0xFF; val hi = data[pos + 1].toInt() and 0xFF
        return (hi shl 8) or lo
    }

    private fun u32(data: ByteArray, pos: Int): Long {
        var v = 0L
        for (i in 0..3) v = v or ((data[pos + i].toLong() and 0xFF) shl (8 * i))
        return v
    }
}
