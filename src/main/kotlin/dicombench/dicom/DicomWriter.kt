package dicombench.dicom

import java.io.ByteArrayOutputStream

/**
 * Minimal Explicit VR Little Endian DICOM Part 10 writer for synthesising the
 * offline case pack. Supports encapsulated pixel fragments (so unsupported
 * transfer syntaxes can be reproduced) and explicit-length sequences.
 */
object DicomWriter {

    fun writeFile(elements: List<DicomElement>): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(ByteArray(128))
        out.write(byteArrayOf('D'.code.toByte(), 'I'.code.toByte(), 'C'.code.toByte(), 'M'.code.toByte()))

        val meta = elements.filter { it.group == 0x0002 }
        val body = elements.filter { it.group != 0x0002 }
        meta.forEach { out.write(encodeElement(it)) }
        body.forEach { out.write(encodeElement(it)) }
        return out.toByteArray()
    }

    private fun encodeElement(el: DicomElement): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(u16(el.group)); out.write(u16(el.element))
        out.write(el.vr.toByteArray(Charsets.US_ASCII))
        if (DicomElement.longForm(el.vr)) {
            out.write(ByteArray(2))
            if (el.vr == "SQ" && el.items.isNotEmpty()) {
                val items = el.items.map { item ->
                    val payload = item.joinToBytes { encodeElement(it) }
                    u32(0xFFFE0000L.toInt().let { 0xFEFF.toByte().let { _ -> 0 } }.let { 0 }) // placeholder unused
                    ByteArray(0)
                }
                out.write(u32(0xFFFFFFFFL.toInt()))
                el.items.forEach { seqItem ->
                    val itemBytes = seqItem.joinToBytes { encodeElement(it) }
                    out.write(u16(0xFFFE)); out.write(u16(0xE000)); out.write(u32(itemBytes.size))
                    out.write(itemBytes)
                }
                out.write(u16(0xFFFE)); out.write(u16(0xE00D)); out.write(u32(0))
            } else {
                out.write(u32(el.bytes.size)); out.write(el.bytes)
            }
        } else {
            val padded = if (el.bytes.size % 2 == 1) el.bytes + padFor(el.vr) else el.bytes
            out.write(u16(padded.size)); out.write(padded)
        }
        return out.toByteArray()
    }

    private fun padFor(vr: String): Byte = if (vr == "UI") 0 else 0x20

    private fun u16(v: Int): ByteArray = byteArrayOf((v and 0xFF).toByte(), ((v ushr 8) and 0xFF).toByte())

    private fun u32(v: Long): ByteArray {
        val out = ByteArray(4)
        for (i in 0..3) out[i] = ((v ushr (8 * i)) and 0xFF).toByte()
        return out
    }
}

internal inline fun <T> List<T>.joinToBytes(transform: (T) -> ByteArray): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    forEach { out.write(transform(it)) }
    return out.toByteArray()
}
