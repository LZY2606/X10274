package dicomreview.dicom

import java.io.ByteArrayOutputStream

/** Minimal Explicit VR Little Endian DICOM encoder used to synthesise fixtures. */
class DicomWriter(private val encapsulated: Boolean = false) {
    private val out = ByteArrayOutputStream()
    private val meta = ByteArrayOutputStream()

    fun write(): ByteArray {
        val file = ByteArrayOutputStream()
        file.write(ByteArray(128))
        file.write("DICM".toByteArray())
        val metaBytes = meta.toByteArray()
        writeUInt16(file, 0x0002); writeUInt16(file, 0x0000)
        file.write("UL".toByteArray()); writeUInt16(file, metaBytes.size + 12)
        writeUInt32(file, metaBytes.size + 4)
        file.write(metaBytes)
        file.write(out.toByteArray())
        return file.toByteArray()
    }

    fun meta(tag: Int, vr: String, value: String) = writeOne(meta, tag, vr, value.toByteArray(Charsets.US_ASCII))

    fun attr(tag: Int, vr: String, value: String) {
        val bytes = value.toByteArray(Charsets.US_ASCII)
        writeOne(out, tag, vr, bytes)
    }

    /** Encapsulated (unsupported transfer syntax) pixel fragment. */
    fun pixelEncapsulated(payloadSize: Int) {
        writeHeader(out, Tags.PixelData, "OB", -1)
        writeUInt32(out, 0xFFFEE000); writeUInt32(out, payloadSize)
        out.write(ByteArray(payloadSize) { (it % 251).toByte() })
        writeUInt32(out, 0xFFFEE0DD); writeUInt32(out, 0)
    }

    fun pixelNative(rows: Int, cols: Int, bits: Int = 16) {
        val n = rows * cols * (bits / 8)
        val bytes = ByteArray(n) { (it % 251).toByte() }
        writeHeader(out, Tags.PixelData, "OW", bytes.size)
        out.write(bytes)
    }

    /** Write a sequence with items; each item body built by [build]. */
    fun sequence(tag: Int, items: List<DicomWriter.() -> Unit>) {
        writeHeader(out, tag, "SQ", -1)
        items.forEach { body ->
            val item = DicomWriter()
            item.body()
            val bytes = item.out.toByteArray()
            writeUInt32(out, 0xFFFEE000); writeUInt32(out, bytes.size)
            out.write(bytes)
        }
        writeUInt32(out, 0xFFFEE0DD); writeUInt32(out, 0)
    }

    private fun writeOne(target: ByteArrayOutputStream, tag: Int, vr: String, bytes: ByteArray) {
        writeHeader(target, tag, vr, bytes.size)
        target.write(bytes)
    }

    private fun writeHeader(target: ByteArrayOutputStream, tag: Int, vr: String, len: Int) {
        writeUInt16(target, tag ushr 16); writeUInt16(target, tag and 0xFFFF)
        target.write(vr.toByteArray())
        if (vr in SHORT_VR) { target.write(ByteArray(2)); writeUInt32(target, len) }
        else writeUInt16(target, len)
    }

    private fun writeUInt16(t: ByteArrayOutputStream, v: Int) { t.write(v and 0xFF); t.write((v ushr 8) and 0xFF) }
    private fun writeUInt32(t: ByteArrayOutputStream, v: Long) {
        val i = v.toInt()
        t.write(i and 0xFF); t.write((i ushr 8) and 0xFF); t.write((i ushr 16) and 0xFF); t.write((i ushr 24) and 0xFF)
    }
    private fun writeUInt32(t: ByteArrayOutputStream, v: Int) = writeUInt32(t, v.toLong())

    companion object {
        private val SHORT_VR = setOf("OB", "OD", "OF", "OL", "OW", "SQ", "UC", "UR", "UT", "UN")
    }
}
