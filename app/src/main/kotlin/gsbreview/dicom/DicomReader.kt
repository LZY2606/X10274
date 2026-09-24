package gsbreview.dicom

import java.nio.charset.StandardCharsets

data class RawTag(
    val path: String,
    val name: String?,
    val vr: String?,
    val rawRepr: String,
    val kind: String = "tag",
    val ord: Int = 0
)

data class FrameFunctionalGroup(
    val frameNo: Int,
    val imagePositionPatient: List<Double>?,
    val imageOrientationPatient: List<Double>?,
    val pixelSpacing: List<Double>?,
    val positionTagPath: String?
)

data class ParsedDicom(
    val tags: List<RawTag>,
    val transferSyntaxUid: String?,
    val sopClassUid: String?,
    val sopInstanceUid: String?,
    val seriesInstanceUid: String?,
    val frameOfReferenceUid: String?,
    val instanceNumber: String?,
    val isMultiframe: Boolean,
    val numberOfFrames: Int,
    val pixelDataRange: IntRange?,
    val pixelEncoding: String,
    val frames: List<FrameFunctionalGroup>
)

private const val TAG_ITEM = 0xFFFEE000
private const val TAG_ITEM_DELIM = 0xFFFEE00D
private const val TAG_SEQ_DELIM = 0xFFFEE0DD
private const val TAG_PIXEL_DATA = 0x7FE00010

class DicomParseException(message: String) : RuntimeException(message)

object DicomReader {

    fun parse(bytes: ByteArray): ParsedDicom {
        if (bytes.size < 132 ||
            bytes[128] != 'D'.code.toByte() || bytes[129] != 'I'.code.toByte() ||
            bytes[130] != 'C'.code.toByte() || bytes[131] != 'M'.code.toByte()
        ) {
            throw DicomParseException("not a DICOM part 10 file (missing preamble magic)")
        }
        val ctx = ParseContext(bytes)
        // File meta information group is always explicit VR little endian.
        while (ctx.pos < bytes.size) {
            val tag = ctx.peekTag()
            if ((tag ushr 16) != 0x0002) break
            ctx.readElement(explicit = true, pathStack = ArrayDeque())
        }
        val tsUid = ctx.tagValue("(0002,0010)")?.clean()
        val explicit = when (tsUid) {
            null, "1.2.840.10008.1.2", "1.2.840.10008.1.2.2" -> false
            else -> true
        }
        ctx.readDataSet(explicit, end = bytes.size, pathStack = ArrayDeque())

        val nFrames = ctx.tagValue("(0028,0008)")?.clean()?.toIntOrNull() ?: 1
        val frames = extractFunctionalGroups(ctx.tags, nFrames)

        return ParsedDicom(
            tags = ctx.tags.sortedBy { it.ord },
            transferSyntaxUid = tsUid,
            sopClassUid = ctx.tagValue("(0008,0016)")?.clean(),
            sopInstanceUid = ctx.tagValue("(0008,0018)")?.clean(),
            seriesInstanceUid = ctx.tagValue("(0020,000E)")?.clean(),
            frameOfReferenceUid = ctx.tagValue("(0020,0052)")?.clean(),
            instanceNumber = ctx.tagValue("(0020,0013)")?.clean(),
            isMultiframe = nFrames > 1,
            numberOfFrames = nFrames,
            pixelDataRange = ctx.pixelRange,
            pixelEncoding = ctx.pixelEncoding,
            frames = frames
        )
    }

    private fun extractFunctionalGroups(tags: List<RawTag>, nFrames: Int): List<FrameFunctionalGroup> {
        if (nFrames <= 1) {
            return listOf(
                FrameFunctionalGroup(
                    frameNo = 1,
                    imagePositionPatient = parseDoubles(tagValue(tags, "(0020,0032)")),
                    imageOrientationPatient = parseDoubles(tagValue(tags, "(0020,0037)")),
                    pixelSpacing = parseDoubles(tagValue(tags, "(0028,0030)")),
                    positionTagPath = tagPathPresent(tags, "(0020,0032)")
                )
            )
        }
        return (1..nFrames).map { frame ->
            val ipp = perFrameValue(tags, frame, "(0020,0032)")
                ?: tagValue(tags, "(5200,9229)[0].(0020,9113)[0].(0020,0032)")
            val iop = perFrameValue(tags, frame, "(0020,0037)")
                ?: tagValue(tags, "(5200,9229)[0].(0020,9116)[0].(0020,0037)")
            val ps = perFrameValue(tags, frame, "(0028,0030)")
                ?: tagValue(tags, "(5200,9229)[0].(0028,9110)[0].(0028,0030)")
            FrameFunctionalGroup(
                frameNo = frame,
                imagePositionPatient = parseDoubles(ipp),
                imageOrientationPatient = parseDoubles(iop),
                pixelSpacing = parseDoubles(ps),
                positionTagPath = perFramePath(tags, frame, "(0020,0032)")
            )
        }
    }

    private fun perFrameValue(tags: List<RawTag>, frame: Int, leaf: String): String? =
        tags.firstOrNull {
            it.path.startsWith("(5200,9230)[${frame - 1}].") && it.path.endsWith(leaf)
        }?.rawRepr

    private fun perFramePath(tags: List<RawTag>, frame: Int, leaf: String): String? =
        tags.firstOrNull {
            it.path.startsWith("(5200,9230)[${frame - 1}].") && it.path.endsWith(leaf)
        }?.path

    private fun tagValue(tags: List<RawTag>, path: String): String? {
        tags.firstOrNull { it.path == path }?.let { return it.rawRepr }
        return tags.filter { it.path.endsWith(".$path") }
            .minByOrNull { it.path.count { c -> c == '.' } }?.rawRepr
    }

    private fun tagPathPresent(tags: List<RawTag>, path: String): String? =
        tags.firstOrNull { it.path == path }?.path

    private fun parseDoubles(s: String?): List<Double>? =
        s?.split('\\')?.mapNotNull { it.clean().toDoubleOrNull() }?.takeIf { it.isNotEmpty() }

    private fun String.clean() = trim().replace('\u0000', ' ').trim()

    private class ParseContext(val bytes: ByteArray) {
        var pos = 132
        var ord = 0
        val tags = mutableListOf<RawTag>()
        var pixelRange: IntRange? = null
        var pixelEncoding = "native"

        fun tagValue(path: String): String? {
            tags.firstOrNull { it.path == path }?.let { return it.rawRepr }
            return tags.filter { it.path.endsWith(".$path") }
                .minByOrNull { it.path.count { c -> c == '.' } }?.rawRepr
        }

        fun peekTag(): Int = u16(pos) shl 16 or u16(pos + 2)

        fun readDataSet(explicit: Boolean, end: Int, pathStack: ArrayDeque<String>) {
            while (pos + 8 <= end) {
                val tag = peekTag()
                if (tag == TAG_SEQ_DELIM) { pos += 8; return }
                readElement(explicit, pathStack)
            }
        }

        fun readElement(explicit: Boolean, pathStack: ArrayDeque<String>) {
            val tag = peekTag()
            if (tag == TAG_ITEM || tag == TAG_ITEM_DELIM || tag == TAG_SEQ_DELIM) return
            var vr: String
            var headerLen: Int
            if (explicit) {
                vr = String(bytes, pos + 4, 2, StandardCharsets.US_ASCII)
                if (vr in setOf("OB", "OW", "OF", "OD", "SQ", "UT", "UN")) {
                    headerLen = 12
                } else {
                    headerLen = 8
                }
            } else {
                vr = DicomDictionary.implicitVr(tag)
                headerLen = 8
            }
            val length = le32(pos + headerLen - 4)
            val valueStart = pos + headerLen
            val valueEnd = valueStart + length
            val leaf = tagPath(tag)
            val path = joinPath(pathStack, leaf)

            when {
                tag == TAG_PIXEL_DATA -> {
                    pixelRange = if (length == -1) valueStart until bytes.size else valueStart until valueEnd
                    pixelEncoding = detectPixelEncoding(valueStart, length)
                    emit(RawTag(path, DicomDictionary.name(tag), vr,
                        if (length == -1) "<encapsulated pixel data>" else "<binary ${length}>",
                        "pixel", ord++))
                    skipValue(length, valueStart)
                }
                vr == "SQ" -> {
                    emit(RawTag(path, DicomDictionary.name(tag), "SQ", "", "sequence-start", ord++))
                    readSequence(explicit, length, valueStart, leaf, pathStack)
                }
                else -> {
                    if (length < 0 || valueEnd > bytes.size) {
                        throw DicomParseException("declared value length exceeds file at $path")
                    }
                    val repr = renderValue(vr, valueStart, length)
                    emit(RawTag(path, DicomDictionary.name(tag), vr, repr, "tag", ord++))
                    pos = valueEnd
                }
            }
        }

        private fun readSequence(explicit: Boolean, declaredLen: Int, start: Int, seqLeaf: String,
                                 outer: ArrayDeque<String>) {
            pos = start
            val end = if (declaredLen == -1) bytes.size else start + declaredLen
            var itemIndex = 0
            while (pos + 8 <= end) {
                val tag = peekTag()
                if (tag == TAG_SEQ_DELIM) { pos += 8; return }
                if (tag != TAG_ITEM) {
                    if (declaredLen == -1) return
                    throw DicomParseException("expected item in sequence $seqLeaf, got ${tagPath(tag)}")
                }
                val itemLen = le32(pos + 4)
                val itemStart = pos + 8
                val itemEnd = if (itemLen == -1) {
                    findDelimiterEnd(itemStart, TAG_ITEM_DELIM)
                } else itemStart + itemLen
                val stack = ArrayDeque(outer.toList())
                stack.addLast("$seqLeaf[$itemIndex]")
                var p = itemStart
                while (p + 8 <= itemEnd) {
                    val subTag = u16(p) shl 16 or u16(p + 2)
                    if (subTag == TAG_ITEM_DELIM) { p += 8; break }
                    pos = p
                    readElement(explicit, stack)
                    if (pos == p) break
                    p = pos
                }
                pos = itemEnd
                if (itemLen == -1) {
                    // pos advanced to position after item delimiter by findDelimiterEnd semantics
                }
                itemIndex++
            }
            if (declaredLen == -1 && pos + 8 <= bytes.size && peekTag() == TAG_SEQ_DELIM) pos += 8
        }

        private fun findDelimiterEnd(start: Int, delimiter: Int): Int {
            var p = start
            while (p + 8 <= bytes.size) {
                val t = u16(p) shl 16 or u16(p + 2)
                if (t == delimiter) return p + 8
                // Skip current element naively using declared length is unsafe for nested;
                // this generator only produces fixed-length item content with explicit delimiter.
                val vr = DicomDictionary.implicitVr(t)
                val len = le32(p + 4)
                p += 8 + if (len < 0) 0 else len
                if (len < 0) return bytes.size
            }
            return bytes.size
        }

        private fun skipValue(length: Int, valueStart: Int) {
            if (length == -1) {
                var p = valueStart
                while (p + 8 <= bytes.size) {
                    if (peekTagAt(p) == TAG_SEQ_DELIM) { pos = p + 8; return }
                    p += 8 + le32(p + 4)
                }
                pos = bytes.size
            } else {
                pos = valueStart + length
            }
        }

        private fun detectPixelEncoding(start: Int, length: Int): String {
            if (length == -1) return "encapsulated"
            if (length >= 8 && peekTagAt(start) == TAG_ITEM) return "encapsulated"
            return "native"
        }

        private fun renderValue(vr: String, start: Int, len: Int): String {
            if (len <= 0) return ""
            return when (vr) {
                "OB", "OW", "OF", "OD", "UN" -> "<binary $len>"
                else -> String(bytes, start, len, StandardCharsets.UTF_8)
            }
        }

        private fun peekTagAt(o: Int) = u16(o) shl 16 or u16(o + 2)

        fun u16(o: Int) = (bytes[o].toInt() and 0xFF) or ((bytes[o + 1].toInt() and 0xFF) shl 8)
        fun le32(o: Int) =
            (bytes[o].toInt() and 0xFF) or ((bytes[o + 1].toInt() and 0xFF) shl 8) or
                ((bytes[o + 2].toInt() and 0xFF) shl 16) or ((bytes[o + 3].toInt()) shl 24)

        private fun emit(t: RawTag) { tags += t }
    }

    private fun joinPath(stack: ArrayDeque<String>, leaf: String) =
        if (stack.isEmpty()) leaf else stack.joinToString(".") + "." + leaf

    private fun tagPath(tag: Int) = "(%04X,%04X)".format(tag ushr 16, tag and 0xFFFF)
}
