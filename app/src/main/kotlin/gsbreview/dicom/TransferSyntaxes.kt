package gsbreview.dicom

data class TransferSyntaxInfo(val uid: String, val name: String, val pixelDecodable: Boolean, val supported: Boolean)

object TransferSyntaxes {
    private val table = mapOf(
        "1.2.840.10008.1.2" to TransferSyntaxInfo("1.2.840.10008.1.2", "Implicit VR Little Endian", true, true),
        "1.2.840.10008.1.2.1" to TransferSyntaxInfo("1.2.840.10008.1.2.1", "Explicit VR Little Endian", true, true),
        "1.2.840.10008.1.2.1.99" to TransferSyntaxInfo("1.2.840.10008.1.2.1.99", "Deflated Explicit VR Little Endian", false, false),
        "1.2.840.10008.1.2.2" to TransferSyntaxInfo("1.2.840.10008.1.2.2", "Explicit VR Big Endian", false, false),
        "1.2.840.10008.1.2.4.50" to TransferSyntaxInfo("1.2.840.10008.1.2.4.50", "JPEG Baseline", false, false),
        "1.2.840.10008.1.2.4.70" to TransferSyntaxInfo("1.2.840.10008.1.2.4.70", "JPEG Lossless", false, false),
        "1.2.840.10008.1.2.4.90" to TransferSyntaxInfo("1.2.840.10008.1.2.4.90", "JPEG 2000 Image Compression (Lossless Only)", false, false),
        "1.2.840.10008.1.2.5" to TransferSyntaxInfo("1.2.840.10008.1.2.5", "RLE Lossless", false, false)
    )

    fun lookup(uid: String?): TransferSyntaxInfo? = uid?.let { table[it] }
    fun isPixelDecodable(uid: String?): Boolean = lookup(uid)?.pixelDecodable ?: false
}
