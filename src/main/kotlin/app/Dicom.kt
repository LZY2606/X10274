package app

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** A single DICOM tag exactly as read: raw bytes preserved, decoded value best-effort. */
data class DicomTag(
    val group: Int,
    val element: Int,
    val vr: String,
    val raw: ByteArray,
    val children: List<DicomTag> = emptyList(),
) {
    val key: String get() = "%04X,%04X".format(group, element)
    val rawHex: String get() = raw.joinToString("") { "%02x".format(it) }
    val text: String get() = when (vr) {
        "US" -> ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).let { b ->
            (0 until raw.size / 2).joinToString("\\") { b.getShort(it * 2).toInt().and(0xFFFF).toString() } }
        "UL" -> ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).let { b ->
            (0 until raw.size / 4).joinToString("\\") { b.getInt(it * 4).toUInt().toString() } }
        "SS" -> ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).let { b ->
            (0 until raw.size / 2).joinToString("\\") { b.getShort(it * 2).toString() } }
        "OB", "OW", "OF", "UN" -> "0x" + rawHex.take(64) + if (raw.size > 32) "…(${raw.size} bytes)" else ""
        else -> String(raw, Charsets.US_ASCII).trimEnd(' ', ' ')
    }
}

data class DicomFile(
    val transferSyntax: String,
    val transferSyntaxSupported: Boolean,
    val tags: List<DicomTag>,          // meta header + dataset (when supported)
    val rawBytes: ByteArray,
    val parseDiagnostic: String? = null, // e.g. unsupported transfer syntax
) {
    fun find(group: Int, element: Int, scope: List<DicomTag> = tags): DicomTag? =
        scope.firstOrNull { it.group == group && it.element == element }
    fun textOf(group: Int, element: Int, scope: List<DicomTag> = tags): String? =
        find(group, element, scope)?.text?.takeIf { it.isNotBlank() }
}

object DicomParser {
    const val EXPLICIT_VR_LE = "1.2.840.10008.1.2.1"
    private val LONG_VRS = setOf("OB", "OW", "OF", "SQ", "UT", "UN")

    fun parse(file: File): DicomFile = parse(file.readBytes())

    fun parse(bytes: ByteArray): DicomFile {
        if (bytes.size < 132 || !bytes.copyOfRange(128, 132).contentEquals("DICM".toByteArray())) {
            return DicomFile("unknown", false, emptyList(), bytes, "missing DICM preamble; raw preserved")
        }
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        buf.position(132)
        val meta = mutableListOf<DicomTag>()
        // File meta header is always Explicit VR Little Endian.
        while (buf.remaining() >= 8) {
            val mark = buf.position()
            val g = buf.short.toInt() and 0xFFFF
            if (g != 0x0002) { buf.position(mark); break }
            val e = buf.short.toInt() and 0xFFFF
            val vr = String(byteArrayOf(buf.get(), buf.get()))
            val len: Int
            if (vr in LONG_VRS) { buf.get(); buf.get(); len = buf.int } else len = buf.short.toInt() and 0xFFFF
            if (len < 0 || len > buf.remaining()) break
            val value = ByteArray(len); buf.get(value)
            meta.add(DicomTag(g, e, vr, value))
        }
        val ts = meta.firstOrNull { it.group == 0x0002 && it.element == 0x0010 }
            ?.let { String(it.raw, Charsets.US_ASCII).trimEnd('\u0000', ' ') } ?: "unknown"
        if (ts != EXPLICIT_VR_LE) {
            return DicomFile(ts, false, meta, bytes,
                "transfer syntax $ts not supported for dataset decode; raw bytes and meta header preserved")
        }
        return DicomFile(ts, true, meta + readTags(buf), bytes)
    }

    private fun readTags(buf: ByteBuffer, limit: Int = Int.MAX_VALUE): List<DicomTag> {
        val out = mutableListOf<DicomTag>()
        while (buf.remaining() >= 8 && buf.position() < limit) {
            val g = buf.short.toInt() and 0xFFFF
            val e = buf.short.toInt() and 0xFFFF
            if (g == 0xFFFE) { buf.position(buf.position() - 4); break } // item/delimitation: caller handles
            val vr = String(byteArrayOf(buf.get(), buf.get()))
            if (vr == "SQ") {
                buf.get(); buf.get()
                val len = buf.int
                val end = if (len == -1) Int.MAX_VALUE else buf.position() + len
                val children = mutableListOf<DicomTag>()
                while (buf.remaining() >= 8 && buf.position() < end) {
                    val ig = buf.short.toInt() and 0xFFFF
                    val ie = buf.short.toInt() and 0xFFFF
                    val ilen = buf.int
                    if (ig == 0xFFFE && ie == 0xE0DD) break
                    if (ig == 0xFFFE && ie == 0xE000) {
                        val iend = if (ilen == -1) Int.MAX_VALUE else buf.position() + ilen
                        children.addAll(readTags(buf, iend))
                        if (ilen == -1 && buf.remaining() >= 8) { buf.short; buf.short; buf.int }
                    } else {
                        buf.position(buf.position() - 8)
                        children.addAll(readTags(buf, end))
                    }
                }
                out.add(DicomTag(g, e, vr, ByteArray(0), children))
            } else {
                val len: Int
                if (vr in LONG_VRS) { buf.get(); buf.get(); len = buf.int } else len = buf.short.toInt() and 0xFFFF
                if (len < 0 || len > buf.remaining()) break
                val value = ByteArray(len); buf.get(value)
                out.add(DicomTag(g, e, vr, value))
            }
        }
        return out
    }
}

/** Well-known tag names for evidence display. */
val TAG_NAMES = mapOf(
    "0002,0010" to "TransferSyntaxUID",
    "0008,0016" to "SOPClassUID",
    "0008,0018" to "SOPInstanceUID",
    "0008,0020" to "StudyDate",
    "0010,0010" to "PatientName",
    "0010,0020" to "PatientID",
    "0020,000D" to "StudyInstanceUID",
    "0020,000E" to "SeriesInstanceUID",
    "0020,0052" to "FrameOfReferenceUID",
    "0020,0013" to "InstanceNumber",
    "0020,0032" to "ImagePositionPatient",
    "0020,0037" to "ImageOrientationPatient",
    "0020,9113" to "PlanePositionSequence",
    "0028,0004" to "PhotometricInterpretation",
    "0028,0008" to "NumberOfFrames",
    "0028,0010" to "Rows",
    "0028,0011" to "Columns",
    "0028,0030" to "PixelSpacing",
    "0028,0100" to "BitsAllocated",
    "5200,9230" to "PerFrameFunctionalGroupsSequence",
    "7FE0,0010" to "PixelData",
)
