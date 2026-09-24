package org.research.gsb.dicom.codec

import java.io.ByteArrayOutputStream

/** Writes Part 10 files with explicit VR little-endian, or encapsulated pixel data for JPEG TS. */
class DicomWriter(private val transferSyntax: String = DicomReader.ExplicitVRLittleEndian) {

    fun write(file: DicomFile): ByteArray {
        val out = ByteArrayOutputStream()
        val preamble = if (file.preamble.size == 128) file.preamble else ByteArray(128)
        out.write(preamble)
        out.write("DICM".toByteArray(Charsets.US_ASCII))

        val sopClass = file.dataset.string(Tags.SOPClassUID) ?: "1.2.840.10008.5.1.4.1.1.77.1.1"
        val sopInstance = file.dataset.string(Tags.SOPInstanceUID) ?: UIDs.generate()
        val metaElements = listOf(
            DicomElement(Tag(0x0002, 0x0001), VR.OB, byteArrayOf(0, 1)),
            DicomElement.ofString(Tags.MediaStorageSOPClassUID, VR.UI, sopClass),
            DicomElement.ofString(Tags.MediaStorageSOPInstanceUID, VR.UI, sopInstance),
            DicomElement.ofString(Tags.TransferSyntaxUID, VR.UI, transferSyntax),
            DicomElement.ofString(Tag(0x0002, 0x0013), VR.SH, "GSB_BENCH"),
        )
        writeDataset(out, metaElements, encapsulated = false)

        val encapsulated = transferSyntax != DicomReader.ExplicitVRLittleEndian
        writeDataset(out, file.dataset.elements, encapsulated)
        return out.toByteArray()
    }

    private fun writeDataset(out: ByteArrayOutputStream, elements: List<DicomElement>, encapsulated: Boolean) {
        for (el in elements.sortedBy { it.tag.code }) {
            writeTag(out, el.tag)
            if (el.vr == VR.SQ) {
                writeHeader(out, VR.SQ, -1)
                for (item in el.items) {
                    writeTag(out, Tag(0xFFFE, 0xE000))
                    val itemBytes = ByteArrayOutputStream()
                    writeDataset(itemBytes, item.elements, encapsulated)
                    writeInt(out, itemBytes.size())
                    out.write(itemBytes.toByteArray())
                }
                writeTag(out, Tag(0xFFFE, 0xE0DD))
                writeInt(out, 0)
                continue
            }
            if (encapsulated && el.tag == Tags.PixelData) {
                writeHeader(out, el.vr, -1)
                // Single fragment encapsulation including BOT of zero length.
                writeTag(out, Tag(0xFFFE, 0xE000))
                writeInt(out, 0) // basic offset table
                writeTag(out, Tag(0xFFFE, 0xE000))
                writeInt(out, el.raw.size)
                out.write(el.raw)
                writeTag(out, Tag(0xFFFE, 0xE0DD))
                writeInt(out, 0)
                continue
            }
            val payload = padForVr(el.vr, el.raw)
            writeHeader(out, el.vr, payload.size)
            out.write(payload)
        }
    }

    private fun padForVr(vr: VR, raw: ByteArray): ByteArray {
        val even = if (vr == VR.OB || vr == VR.OW || vr == VR.UN) 0x00 else 0x20
        return if (raw.size % 2 == 0) raw else raw + byteArrayOf(even.toByte())
    }

    private fun writeHeader(out: ByteArrayOutputStream, vr: VR, length: Int) {
        out.write(vr.code.toByteArray(Charsets.US_ASCII))
        if (vr.longHeader) {
            out.write(0); out.write(0)
            writeInt(out, length)
        } else {
            writeShort(out, length)
        }
    }

    private fun writeTag(out: ByteArrayOutputStream, tag: Tag) {
        writeShort(out, tag.group)
        writeShort(out, tag.element)
    }

    private fun writeShort(out: ByteArrayOutputStream, v: Int) {
        out.write(v and 0xFF); out.write((v ushr 8) and 0xFF)
    }

    private fun writeInt(out: ByteArrayOutputStream, v: Int) {
        out.write(v and 0xFF); out.write((v ushr 8) and 0xFF)
        out.write((v ushr 16) and 0xFF); out.write((v ushr 24) and 0xFF)
    }
}

object UIDs {
    private var counter = 0L
    fun generate(prefix: String = "1.2.826.0.1.3680043.8.498."): String {
        counter++
        return prefix + System.currentTimeMillis().toString(36) + counter.toString(36)
    }
}
