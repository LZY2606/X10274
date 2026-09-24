package dicomreview.dicom

/**
 * 来源层（raw）：只描述“材料里有什么”，不做任何不可逆清洗。
 * 数值型 tag 同时保留原始 token（rawValues，即文件里的字符表示）与规范化值；
 * 无法转换的值留在 normalizedValues=null 并记录诊断，绝不丢弃。
 */
data class RawAttribute(
    val tagCode: Int,
    val keyword: String,
    val vr: String,
    val hex: String,
    /** 反斜杠连接的多值原始表示；UI/CS/DS/IS 等均逐 token 保留。 */
    val rawValues: List<String>,
    /** 可转换时给出规范化数值；不可转换为 null 并在 diagnostics 说明。 */
    val normalizedValues: List<Double>?,
    val diagnostics: List<String>,
    /** tag 来自顶层、共享 functional group 还是第 n 帧 functional group。 */
    val source: String,
    val frameIndex: Int?,
)

enum class PixelAvailability { DECODED_MONOCHROME8, ENCAPSULATED_UNSUPPORTED, ABSENT }

data class RawFrame(
    val frameIndex: Int,
    /** 该帧几何/度量 tag 的来源路径，例如 PerFrame[2]/PlanePositionSequence。 */
    val attributes: List<RawAttribute>,
    val pixelPreview: List<Int>?,
)

data class ParsedDicom(
    val bytes: ByteArray,
    val transferSyntaxUid: String,
    val transferSyntaxSupported: Boolean,
    val pixelAvailability: PixelAvailability,
    val topLevel: List<RawAttribute>,
    val frames: List<RawFrame>,
    val diagnostics: List<String>,
) {
    fun attr(code: Int): RawAttribute? = topLevel.firstOrNull { it.tagCode == code }
    fun string(code: Int): String? = attr(code)?.rawValues?.firstOrNull()

    override fun equals(other: Any?): Boolean =
        other is ParsedDicom && bytes.contentEquals(other.bytes)
    override fun hashCode(): Int = bytes.contentHashCode()
}

/** 本工具认识的传输语法：仅未压缩 little endian 可解码像素；其余保留元数据并诊断。 */
object TransferSyntaxes {
    const val IMPLICIT_LE = "1.2.840.10008.1.2"
    const val EXPLICIT_LE = "1.2.840.10008.1.2.1"
    const val DEFLATED = "1.2.840.10008.1.2.1.99"
    const val JPEG_BASELINE = "1.2.840.10008.1.2.4.50"
    const val JPEG2000_LOSSLESS = "1.2.840.10008.1.2.4.90"
    const val JPEG2000 = "1.2.840.10008.1.2.4.91"
    const val RLE = "1.2.840.10008.1.2.5"

    val decodablePixels = setOf(IMPLICIT_LE, EXPLICIT_LE)

    fun name(uid: String): String = when (uid) {
        IMPLICIT_LE -> "Implicit VR Little Endian"
        EXPLICIT_LE -> "Explicit VR Little Endian"
        DEFLATED -> "Deflated Explicit VR Little Endian"
        JPEG_BASELINE -> "JPEG Baseline (Process 1)"
        JPEG2000_LOSSLESS -> "JPEG 2000 Image Compression (Lossless Only)"
        JPEG2000 -> "JPEG 2000 Image Compression"
        RLE -> "RLE Lossless"
        else -> "Unknown ($uid)"
    }
}
