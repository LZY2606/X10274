package app.dicom

import java.io.ByteArrayOutputStream

/** Writable element for building synthetic DICOM files (case pack + tests). */
sealed interface WEl {
    val tag: String
    val vr: String
}

data class WStr(override val tag: String, override val vr: String, val value: String) : WEl
data class WBin(override val tag: String, override val vr: String, val bytes: ByteArray) : WEl
data class WSq(override val tag: String, val items: List<List<WEl>>) : WEl {
    override val vr: String get() = "SQ"
}

object DicomWriter {

    private val LONG_LENGTH_VRS = setOf("OB", "OD", "OF", "OL", "OW", "SQ", "UC", "UN", "UR", "UT")

    /** Builds a complete .dcm byte array: preamble + DICM + meta header + dataset. */
    fun buildFile(
        transferSyntaxUid: String,
        sopClassUid: String,
        sopInstanceUid: String,
        dataset: List<WEl>,
    ): ByteArray {
        val explicit = transferSyntaxUid != TS_IMPLICIT_VR_LE
        val meta = mutableListOf<WEl>(
            WBin("0002,0001", "OB", byteArrayOf(0, 1)),
            WStr("0002,0002", "UI", sopClassUid),
            WStr("0002,0003", "UI", sopInstanceUid),
            WStr("0002,0010", "UI", transferSyntaxUid),
            WStr("0002,0012", "UI", "1.2.826.0.1.3680043.10.543.1"),
            WStr("0002,0013", "SH", "GEOMBENCH_1.0"),
        )
        val metaBody = ByteArrayOutputStream()
        meta.forEach { writeElement(metaBody, it, explicitVr = true) }
        val metaBytes = metaBody.toByteArray()

        val out = ByteArrayOutputStream()
        out.write(ByteArray(128))
        out.write("DICM".toByteArray(Charsets.US_ASCII))
        writeElement(out, WBin("0002,0000", "UL", u32Bytes(metaBytes.size)), explicitVr = true)
        out.write(metaBytes)
        dataset.forEach { writeElement(out, it, explicitVr = explicit) }
        return out.toByteArray()
    }

    private fun writeElement(out: ByteArrayOutputStream, el: WEl, explicitVr: Boolean) {
        val (group, elem) = parseTag(el.tag)
        writeU16(out, group)
        writeU16(out, elem)
        when (el) {
            is WSq -> {
                val body = ByteArrayOutputStream()
                el.items.forEach { item ->
                    val itemBody = ByteArrayOutputStream()
                    item.forEach { writeElement(itemBody, it, explicitVr) }
                    val itemBytes = itemBody.toByteArray()
                    writeU16(body, 0xFFFE)
                    writeU16(body, 0xE000)
                    writeU32(body, itemBytes.size)
                    body.write(itemBytes)
                }
                writeVrHeader(out, el.vr, body.size(), explicitVr)
                out.write(body.toByteArray())
            }
            is WStr -> {
                var bytes = el.value.toByteArray(Charsets.ISO_8859_1)
                if (bytes.size % 2 == 1) {
                    val pad: Byte = if (el.vr == "UI") 0 else 0x20
                    bytes = bytes + pad
                }
                writeVrHeader(out, el.vr, bytes.size, explicitVr)
                out.write(bytes)
            }
            is WBin -> {
                writeVrHeader(out, el.vr, el.bytes.size, explicitVr)
                out.write(el.bytes)
            }
        }
    }

    private fun writeVrHeader(out: ByteArrayOutputStream, vr: String, length: Int, explicitVr: Boolean) {
        if (explicitVr) {
            out.write(vr.toByteArray(Charsets.US_ASCII))
            if (vr in LONG_LENGTH_VRS) {
                out.write(byteArrayOf(0, 0))
                writeU32(out, length)
            } else {
                writeU16(out, length)
            }
        } else {
            writeU32(out, length)
        }
    }

    private fun parseTag(tag: String): Pair<Int, Int> {
        val (g, e) = tag.split(",")
        return g.toInt(16) to e.toInt(16)
    }

    private fun writeU16(out: ByteArrayOutputStream, v: Int) {
        out.write(v and 0xFF)
        out.write((v shr 8) and 0xFF)
    }

    private fun writeU32(out: ByteArrayOutputStream, v: Int) {
        out.write(v and 0xFF)
        out.write((v shr 8) and 0xFF)
        out.write((v shr 16) and 0xFF)
        out.write((v shr 24) and 0xFF)
    }

    private fun u32Bytes(v: Int): ByteArray {
        val out = ByteArrayOutputStream()
        writeU32(out, v)
        return out.toByteArray()
    }

    /** 16x16 16-bit monochrome pixel data with a deterministic gradient pattern. */
    fun pixelBytes(seed: Int, frames: Int = 1, rows: Int = 16, cols: Int = 16): ByteArray {
        val out = ByteArrayOutputStream()
        for (f in 0 until frames) {
            for (r in 0 until rows) {
                for (c in 0 until cols) {
                    val v = (seed * 31 + f * 977 + r * cols + c) and 0xFFFF
                    out.write(v and 0xFF)
                    out.write((v shr 8) and 0xFF)
                }
            }
        }
        return out.toByteArray()
    }
}
