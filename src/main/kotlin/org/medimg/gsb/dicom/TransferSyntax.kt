package org.medimg.gsb.dicom

/** DICOM 传输语法登记表（子集）。像素可解码性决定能否渲染缩略图，不影响几何。 */
object TransferSyntaxes {
    const val IMPLICIT_LITTLE = "1.2.840.10008.1.2"
    const val EXPLICIT_LITTLE = "1.2.840.10008.1.2.1"
    const val DEFLATED = "1.2.840.10008.1.2.1.99"
    const val EXPLICIT_BIG = "1.2.840.10008.1.2.2"
    const val JPEG_BASELINE = "1.2.840.10008.1.2.4.50"
    const val JPEG_LOSSLESS = "1.2.840.10008.1.2.4.70"
    const val RLE = "1.2.840.10008.1.2.5"

    val names: Map<String, String> = mapOf(
        IMPLICIT_LITTLE to "Implicit VR Little Endian",
        EXPLICIT_LITTLE to "Explicit VR Little Endian",
        DEFLATED to "Deflated Explicit VR Little Endian",
        EXPLICIT_BIG to "Explicit VR Big Endian",
        JPEG_BASELINE to "JPEG Baseline (Process 1)",
        JPEG_LOSSLESS to "JPEG Lossless, Nonhierarchical (Process 14)",
        RLE to "RLE Lossless"
    )

    /** 本工具内置解码器能直接还原像素的传输语法。 */
    fun pixelDecodable(uid: String): Boolean =
        uid == IMPLICIT_LITTLE || uid == EXPLICIT_LITTLE

    fun encapsulated(uid: String): Boolean =
        uid == JPEG_BASELINE || uid == JPEG_LOSSLESS || uid == RLE

    fun name(uid: String): String = names[uid] ?: "Unknown Transfer Syntax"
}
