package gsbreview.dicom

import java.io.ByteArrayOutputStream

object DicomWriter {
    // Fixed little-endian transfer syntaxes used by the bundled offline case pack.
    const val TS_EXPLICIT_LE = "1.2.840.10008.1.2.1"
    const val TS_IMPLICIT_LE = "1.2.840.10008.1.2"
    const val TS_JPEG2000_LOSSLESS = "1.2.840.10008.1.2.4.90"

    private class Enc {
        val out = ByteArrayOutputStream()
        fun u16(v: Int) { out.write(v and 0xFF); out.write((v ushr 8) and 0xFF) }
        fun u32(v: Int) {
            out.write(v and 0xFF); out.write((v ushr 8) and 0xFF)
            out.write((v ushr 16) and 0xFF); out.write((v ushr 24) and 0xFF)
        }
        fun bytes(b: ByteArray) = out.write(b)
        fun padToEven() { if (out.size() % 2 == 1) out.write(0) }
    }

    private fun explicitElement(e: Enc, group: Int, element: Int, vr: String, value: ByteArray) {
        e.u16(group); e.u16(element)
        e.bytes(vr.toByteArray(Charsets.US_ASCII))
        if (vr in setOf("OB", "OW", "OF", "OD", "SQ", "UT", "UN")) {
            e.u16(0); e.u32(value.size)
        } else {
            e.u16(value.size)
        }
        e.bytes(value)
        if (value.size % 2 == 1) e.out.write(if (vr == "UI") 0 else 0)
    }

    private fun text(vr: String, s: String): ByteArray {
        val b = s.toByteArray(Charsets.UTF_8)
        return if (b.size % 2 == 1) b + 0 else b
    }

    private fun item(e: Enc, content: ByteArray) {
        e.u16(0xFFFE); e.u16(0xE000); e.u32(content.size); e.bytes(content)
    }

    data class InstanceSpec(
        val sopClassUid: String = "1.2.840.10008.5.1.4.1.1.2",
        val sopInstanceUid: String,
        val seriesInstanceUid: String,
        val frameOfReferenceUid: String?,
        val instanceNumber: String,
        val modality: String = "CT",
        val imagePosition: List<Double>? = null,
        val imageOrientation: List<Double>? = null,
        val pixelSpacing: List<Double>? = null,
        val sliceThickness: Double? = null,
        val rows: Int = 16,
        val columns: Int = 16,
        val multiframe: Boolean = false,
        val frames: List<FrameSpec> = emptyList(),
        val transferSyntaxUid: String = TS_EXPLICIT_LE,
        val rowsOverride: Int? = null,
        val colsOverride: Int? = null,
        val omitGeometry: Boolean = false,
        val pixelPayload: ByteArray? = null
    )

    data class FrameSpec(
        val imagePosition: List<Double>,
        val imageOrientation: List<Double>,
        val pixelSpacing: List<Double>
    )

    fun write(spec: InstanceSpec): ByteArray {
        val meta = Enc()
        fun metaEl(element: Int, vr: String, value: String) =
            explicitElement(meta, 0x0002, element, vr, text(vr, value))
        metaEl(0x0000, "UL", "200")
        metaEl(0x0001, "OB", byteArrayOf(0, 1))
        metaEl(0x0002, "UI", spec.sopClassUid)
        metaEl(0x0003, "UI", spec.sopInstanceUid)
        metaEl(0x0010, "UI", spec.transferSyntaxUid)
        metaEl(0x0012, "UI", "1.3.6.1.4.1.99999.1")

        val body = Enc()
        val explicit = spec.transferSyntaxUid != TS_IMPLICIT_LE
        fun el(group: Int, element: Int, vr: String, value: String) {
            if (explicit) explicitElement(body, group, element, vr, text(vr, value))
            else {
                body.u16(group); body.u16(element)
                body.u32(text(vr, value).size)
                body.bytes(text(vr, value))
            }
        }
        fun elBytes(group: Int, element: Int, vr: String, value: ByteArray, padded: Boolean = true) {
            val v = if (padded && value.size % 2 == 1) value + 0 else value
            if (explicit) explicitElement(body, group, element, vr, v)
            else { body.u16(group); body.u16(element); body.u32(v.size); body.bytes(v) }
        }
        fun ds(v: Double) = trimDs(v)

        el(0x0008, 0x0016, "UI", spec.sopClassUid)
        el(0x0008, 0x0018, "UI", spec.sopInstanceUid)
        el(0x0008, 0x0060, "CS", spec.modality)
        el(0x0010, 0x0020, "LO", "ANON")
        el(0x0020, 0x000D, "UI", "1.3.6.1.4.1.99999.2")
        el(0x0020, 0x000E, "UI", spec.seriesInstanceUid)
        el(0x0020, 0x0013, "IS", spec.instanceNumber)
        if (spec.frameOfReferenceUid != null) el(0x0020, 0x0052, "UI", spec.frameOfReferenceUid)

        val rows = spec.rowsOverride ?: spec.rows
        val cols = spec.colsOverride ?: spec.columns
        val nFrames = if (spec.multiframe) spec.frames.size else 1
        if (spec.multiframe) el(0x0028, 0x0008, "IS", nFrames.toString())
        el(0x0028, 0x0010, "US", rows.toString())
        el(0x0028, 0x0011, "US", cols.toString())
        el(0x0028, 0x0002, "US", "1")
        el(0x0028, 0x0004, "CS", "MONOCHROME2")
        el(0x0028, 0x0100, "US", "16")
        el(0x0028, 0x0101, "US", "16")
        el(0x0028, 0x0103, "US", "0")
        spec.sliceThickness?.let { el(0x0018, 0x0050, "DS", ds(it)) }

        if (spec.multiframe) {
            writeFunctionalGroups(body, explicit, spec.frames, rows, cols)
        } else if (!spec.omitGeometry) {
            spec.imageOrientation?.let { el(0x0020, 0x0037, "DS", it.joinToString("\\")) }
            spec.imagePosition?.let { el(0x0020, 0x0032, "DS", it.joinToString("\\")) }
            spec.pixelSpacing?.let { el(0x0028, 0x0030, "DS", it.joinToString("\\")) }
        }

        val pixels = spec.pixelPayload ?: syntheticPixels(rows, cols, nFrames)
        when (spec.transferSyntaxUid) {
            TS_JPEG2000_LOSSLESS -> {
                // No real JPEG2000 codec: write an encapsulated bogus codestream so the tool
                // must surface "transfer syntax unsupported" without discarding geometry.
                val enc = Enc()
                enc.u16(0xFFFE); enc.u16(0xE000); enc.u32(pixels.size)
                enc.bytes(pixels)
                enc.u16(0xFFFE); enc.u16(0xE0DD); enc.u32(0)
                elBytes(0x7FE0, 0x0010, "OB", enc.out.toByteArray(), padded = false)
            }
            else -> elBytes(0x7FE0, 0x0010, if (explicit) "OW" else "OW", pixels, padded = false)
        }

        val out = ByteArrayOutputStream()
        out.write(ByteArray(128))
        out.write("DICM".toByteArray(Charsets.US_ASCII))
        out.write(meta.out.toByteArray())
        out.write(body.out.toByteArray())
        return out.toByteArray()
    }

    private fun writeFunctionalGroups(
        body: Enc, explicit: Boolean, frames: List<FrameSpec>, rows: Int, cols: Int
    ) {
        fun seqEl(group: Int, element: Int, itemContent: ByteArray) {
            if (explicit) explicitElement(body, group, element, "SQ", run {
                val c = Enc(); item(c, itemContent); c.out.toByteArray()
            }) else {
                val c = Enc(); item(c, itemContent)
                body.u16(group); body.u16(element); body.u32(c.out.size()); body.bytes(c.out.toByteArray())
            }
        }
        fun childItemContent(f: FrameSpec): ByteArray {
            val c = Enc()
            explicitElement(c, 0x0020, 0x9113, "SQ", run {
                val p = Enc()
                item(p, run {
                    val pp = Enc()
                    explicitElement(pp, 0x0020, 0x0032, "DS", text("DS", f.imagePosition.joinToString("\\")))
                    pp.out.toByteArray()
                })
                p.out.toByteArray()
            })
            explicitElement(c, 0x0020, 0x9116, "SQ", run {
                val p = Enc()
                item(p, run {
                    val pp = Enc()
                    explicitElement(pp, 0x0020, 0x0037, "DS", text("DS", f.imageOrientation.joinToString("\\")))
                    pp.out.toByteArray()
                })
                p.out.toByteArray()
            })
            explicitElement(c, 0x0028, 0x9110, "SQ", run {
                val p = Enc()
                item(p, run {
                    val pp = Enc()
                    explicitElement(pp, 0x0028, 0x0030, "DS", text("DS", f.pixelSpacing.joinToString("\\")))
                    pp.out.toByteArray()
                })
                p.out.toByteArray()
            })
            return c.out.toByteArray()
        }
        val perFrame = Enc()
        frames.forEach { f -> item(perFrame, childItemContent(f)) }
        if (explicit) explicitElement(body, 0x5200, 0x9230, "SQ", perFrame.out.toByteArray())
        else {
            body.u16(0x5200); body.u16(0x9230)
            body.u32(perFrame.out.size()); body.bytes(perFrame.out.toByteArray())
        }
        @Suppress("UNUSED_PARAMETER") rows
    }

    private fun syntheticPixels(rows: Int, cols: Int, frames: Int): ByteArray {
        val total = rows * cols * 2 * frames
        val b = ByteArray(total)
        for (f in 0 until frames) {
            for (y in 0 until rows) {
                for (x in 0 until cols) {
                    val i = ((f * rows + y) * cols + x) * 2
                    val v = ((x * 73 + y * 131 + f * 211) and 0xFFFF)
                    b[i] = (v and 0xFF).toByte()
                    b[i + 1] = ((v ushr 8) and 0xFF).toByte()
                }
            }
        }
        return b
    }

    private fun trimDs(v: Double): String {
        if (v == v.toLong().toDouble()) return v.toLong().toString()
        return ((v * 10000.0).toLong() / 10000.0).toString()
    }
}
