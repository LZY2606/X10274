package org.research.gsb.dicom.codec

import java.nio.charset.StandardCharsets

/** One parsed DICOM data element. Pixel data and multi-valued text stay in raw form. */
data class DicomElement(
    val tag: Tag,
    val vr: VR,
    /** Raw value bytes exactly as read (without padding), or empty for sequences. */
    val raw: ByteArray,
    val items: List<DicomDataset> = emptyList(),
    val undefinedLength: Boolean = false,
) {
    fun stringValue(): String {
        var s = String(raw, StandardCharsets.UTF_8).trimEnd(' ')
        if (vr == VR.UI) s = s.trimEnd('\u0000')
        return s
    }

    /** Backslash separated components as raw textual representations. */
    fun components(): List<String> =
        stringValue().split('\\').filter { it.isNotEmpty() }

    fun doubles(): List<Double?> = components().map { it.trim().toDoubleOrNull() }

    fun intValue(): Int? = stringValue().trim().toIntOrNull()

    companion object {
        fun ofString(tag: Tag, vr: VR, value: String): DicomElement =
            DicomElement(tag, vr, value.toByteArray(StandardCharsets.UTF_8))

        fun ofDoubles(tag: Tag, values: List<Double>): DicomElement =
            ofString(tag, VR.DS, values.joinToString("\\") { fmt(it) })

        fun ofShort(tag: Tag, vararg values: Int): DicomElement {
            val out = java.io.ByteArrayOutputStream()
            for (v in values) out.write(byteArrayOf((v and 0xFF), ((v ushr 8) and 0xFF)))
            return DicomElement(tag, VR.US, out.toByteArray())
        }

        fun ofInt(tag: Tag, value: Int, vr: VR = VR.IS): DicomElement =
            ofString(tag, vr, value.toString())

        /** Keep doubles with full fidelity but compact and locale independent. */
        fun fmt(v: Double): String {
            if (v.isNaN()) return "NaN"
            if (v == v.toLong().toDouble() && v in -1e6..1e6) return v.toLong().toString()
            return v.toString()
        }
    }
}
