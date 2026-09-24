package org.gsb.dicomreview.dicom

import java.io.ByteArrayOutputStream

/** Explicit VR little endian writer used to materialize the offline case pack. */
object DicomWriter {

    private val EXTENDED_VRS = setOf("OB", "OD", "OF", "OL", "OV", "OW", "SQ", "UC", "UN", "UR", "UT")

    class Build {
        val out = ByteArrayOutputStream()

        fun ui(tag: Tag, value: String) = writeText(tag, "UI", value)
        fun cs(tag: Tag, value: String) = writeText(tag, "CS", value)
        fun is_(tag: Tag, value: String) = writeText(tag, "IS", value)
        fun ds(tag: Tag, value: String) = writeText(tag, "DS", value)
        fun us(tag: Tag, value: Int) {
            writeHeader(tag, "US", 2)
            out.write(value and 0xFF); out.write((value ushr 8) and 0xFF)
        }
        fun rawTag(tag: Tag, vr: String, value: ByteArray) {
            writeHeader(tag, vr, value.size)
            out.write(value)
        }
        fun encapsulatedPixel(fragments: List<ByteArray>) {
            writeHeader(DicomTags.PixelData, "OB", -1)
            fragments.forEach { frag ->
                writeTag(Tag(0xFFFE, 0xE000)); writeInt32(frag.size); out.write(frag)
            }
            writeTag(Tag(0xFFFE, 0xE0DD)); writeInt32(0)
        }

        fun sequence(tag: Tag, block: SequenceScope.() -> Unit) {
            val scope = SequenceScope()
            scope.block()
            val payload = scope.out.toByteArray()
            writeHeader(tag, "SQ", -1)
            writeTag(Tag(0xFFFE, 0xE000)); writeInt32(payload.size); out.write(payload)
            writeTag(Tag(0xFFFE, 0xE0DD)); writeInt32(0)
        }

        internal fun writeText(tag: Tag, vr: String, value: String) {
            val bytes = value.toByteArray(Charsets.US_ASCII)
            val padded = if ((bytes.size and 1) == 1) bytes + 0x20 else bytes
            writeHeader(tag, vr, padded.size)
            out.write(padded)
        }

        internal fun writeHeader(tag: Tag, vr: String, length: Int) {
            writeTag(tag)
            out.write(vr.toByteArray(Charsets.US_ASCII))
            if (vr in EXTENDED_VRS) { out.write(0); out.write(0); writeInt32(length) }
            else writeInt16(length)
        }

        internal fun writeTag(tag: Tag) { writeInt16(tag.group); writeInt16(tag.element) }
        internal fun writeInt16(v: Int) { out.write(v and 0xFF); out.write((v ushr 8) and 0xFF) }
        internal fun writeInt32(v: Int) {
            out.write(v and 0xFF); out.write((v ushr 8) and 0xFF)
            out.write((v ushr 16) and 0xFF); out.write((v ushr 24) and 0xFF)
        }
    }

    class SequenceScope {
        val out = ByteArrayOutputStream()
        /** Add an item built from an independent dataset. */
        fun item(block: Build.() -> Unit) {
            val item = Build().apply(block)
            val payload = item.out.toByteArray()
            writeTag(Tag(0xFFFE, 0xE000)); writeInt32(payload.size); out.write(payload)
        }
        private fun writeTag(tag: Tag) { writeInt16(tag.group); writeInt16(tag.element) }
        private fun writeInt16(v: Int) { out.write(v and 0xFF); out.write((v ushr 8) and 0xFF) }
        private fun writeInt32(v: Int) {
            out.write(v and 0xFF); out.write((v ushr 8) and 0xFF)
            out.write((v ushr 16) and 0xFF); out.write((v ushr 24) and 0xFF)
        }
    }

    data class WriteOptions(
        val transferSyntaxUID: String = "1.2.840.10008.1.2.1",
        val encapsulated: Boolean = false
    )

    fun write(options: WriteOptions = WriteOptions(), block: Build.() -> Unit): ByteArray {
        val body = Build().apply(block)
        val meta = Build()
        meta.ui(DicomTags.TransferSyntaxUID, options.transferSyntaxUID)
        val metaBytes = meta.out.toByteArray()

        val full = ByteArrayOutputStream()
        full.write(ByteArray(128))
        full.write("DICM".toByteArray(Charsets.US_ASCII))
        full.write(metaBytes)
        full.write(body.out.toByteArray())
        return full.toByteArray()
    }
}
