package app.dicom

import app.core.Diagnostic
import app.core.DicomObject
import app.core.ParseStatus
import app.core.RawField
import app.core.sha1
import java.io.ByteArrayOutputStream
import java.util.zip.Inflater

class DcmParseException(message: String) : RuntimeException(message)

/**
 * Minimal DICOM Part 10 reader for geometry-metadata provenance.
 * File meta is Explicit VR Little Endian; datasets support Explicit/Implicit LE
 * and Deflated Explicit LE. Other transfer syntaxes are reported unsupported
 * while the original bytes stay intact in the blob store.
 */
object DicomCodec {
    const val DICM = "DICM"
    val SUPPORTED_SYNTAXES = setOf(
        "1.2.840.10008.1.2.1",    // Explicit VR Little Endian
        "1.2.840.10008.1.2",      // Implicit VR Little Endian
        "1.2.840.10008.1.2.1.99"  // Deflated Explicit VR LE
    )

    data class Element(
        val tag: Int, val vr: String, val data: ByteArray,
        val seqPath: String, val fragment: Boolean
    )

    data class ParseResult(val objectId: String, val bytes: ByteArray, val obj: DicomObject)

    fun parse(bytes: ByteArray, fileName: String): ParseResult {
        if (bytes.size < 132) throw DcmParseException("file smaller than DICOM preamble")
        if (String(bytes, 128, 4, Charsets.US_ASCII) != DICM)
            throw DcmParseException("missing DICM preamble magic")
        val objectId = sha1(bytes)

        val metaElements = mutableListOf<Element>()
        val metaReader = DatasetReader(bytes, 132, implicit = false, stopAtGroup = 0x0003)
        val metaEnd = metaReader.readDataset(metaElements)

        val tsUid = metaElements.firstOrNull { it.tag == 0x00020010 }?.let { decodeText(it.data) }
        val diagnostics = mutableListOf<Diagnostic>()
        val metaFields = metaElements.map { it.toRawField() }

        if (tsUid == null) {
            diagnostics += Diagnostic("DCM_NO_TRANSFERSYNTAX",
                "TransferSyntaxUID (0002,0010) missing", "00020010")
            return finish(objectId, fileName, bytes, null, ParseStatus.INVALID, metaFields, diagnostics)
        }
        if (tsUid !in SUPPORTED_SYNTAXES) {
            diagnostics += Diagnostic("DCM_UNSUPPORTED_TS",
                "Transfer syntax $tsUid not supported for pixel decoding; original bytes retained", "00020010")
            return finish(objectId, fileName, bytes, tsUid, ParseStatus.UNSUPPORTED_SYNTAX, metaFields, diagnostics)
        }

        var dataset = bytes
        var start = metaEnd
        if (tsUid == "1.2.840.10008.1.2.1.99") {
            dataset = inflate(bytes, metaEnd)
            start = 0
        }
        val reader = DatasetReader(dataset, start, implicit = tsUid == "1.2.840.10008.1.2")
        val elements = mutableListOf<Element>()
        reader.readDataset(elements)
        val fields = metaFields + elements.map { it.toRawField() }
        val status = if (elements.any { it.tag == 0x00200032 && it.data.isEmpty() })
            ParseStatus.GEOMETRY_PARTIAL else ParseStatus.PARSED
        return finish(objectId, fileName, bytes, tsUid, status, fields, diagnostics)
    }

    private fun finish(
        objectId: String, fileName: String, bytes: ByteArray, tsUid: String?,
        status: ParseStatus, fields: List<RawField>, diagnostics: List<Diagnostic>
    ): ParseResult {
        fun top(tag: Int) = fields.firstOrNull { it.tagHex == tagHex(tag) && it.seqPath.isEmpty() }
        fun str(tag: Int) = top(tag)?.valueRepr?.trim()?.trimEnd(' ')
        val obj = DicomObject(
            objectId = objectId,
            fileName = fileName,
            byteSize = bytes.size,
            transferSyntaxUid = tsUid,
            transferSyntaxSupported = status == ParseStatus.PARSED || status == ParseStatus.GEOMETRY_PARTIAL,
            parseStatus = status,
            sopClassUid = str(0x00080016),
            sopInstanceUid = str(0x00080018)
                ?: fields.firstOrNull { it.tagHex == tagHex(0x00020003) }?.valueRepr?.trim()?.trimEnd(' '),
            seriesUid = str(0x0020000E),
            studyUid = str(0x0020000D),
            frameOfReferenceUid = str(0x00200052),
            instanceNumber = str(0x00200013),
            numberOfFrames = str(0x00280008)?.toIntOrNull(),
            fields = fields,
            diagnostics = diagnostics
        )
        return ParseResult(objectId, bytes, obj)
    }

    private fun Element.toRawField(): RawField {
        val display = when (vr) {
            "OW", "OB", "OD", "OF", "OL", "UN" -> {
                val head = data.copyOfRange(0, minOf(16, data.size)).joinToString(" ") { "%02X".format(it) }
                if (data.size > 16) "<binary ${data.size} bytes: $head...>" else "<binary: $head>"
            }
            else -> decodeText(data)
        }
        return RawField(tagHex(tag), DicomTags.name(tag), vr, display, data.size, seqPath)
    }

    private fun inflate(bytes: ByteArray, start: Int): ByteArray {
        val inf = Inflater(true)
        inf.setInput(bytes, start, bytes.size - start)
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (!inf.finished()) {
            val n = inf.inflate(buf)
            if (n == 0) throw DcmParseException("cannot inflate deflated dataset")
            out.write(buf, 0, n)
        }
        inf.end()
        return out.toByteArray()
    }

    fun decodeText(data: ByteArray): String =
        String(data, Charsets.US_ASCII).trimEnd().substringBefore(Char(0)).trim()

    fun tagHex(tag: Int) = "(%04X,%04X)".format(tag ushr 16, tag and 0xFFFF)
    fun tagFromHex(h: String): Int {
        val n = h.filter { it.isLetterOrDigit() }.chunked(4).map { it.toInt(16) }
        return (n[0] shl 16) or n[1]
    }

    @Suppress("unused")
    private fun unusedSha(s: String) = sha1(s)
}
