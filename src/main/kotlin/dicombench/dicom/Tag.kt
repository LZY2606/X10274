package dicombench.dicom

/**
 * A single raw DICOM data element. Sequences retain their nested items verbatim
 * so that functional group tags and every original representation survive import.
 */
data class DicomElement(
    val group: Int,
    val element: Int,
    val vr: String,
    val bytes: ByteArray,
    val items: List<List<DicomElement>> = emptyList(),
) {
    val tagGE: Int get() = (group shl 16) or element
    fun tagHex(): String = "%04X,%04X".format(group, element)

    /** Original textual representation; binary payloads are described, not re-encoded. */
    val rawText: String by lazy { decodeText(vr, bytes) }

    override fun equals(other: Any?) = other is DicomElement && tagGE == other.tagGE && vr == other.vr
    override fun hashCode() = 31 * tagGE + vr.hashCode()

    companion object {
        const val PIXEL_DATA = 0x7FE00010
        const val TRANSFER_SYNTAX_UID = 0x00020010

        private val LONG_VR = setOf("OB", "OD", "OF", "OL", "OW", "UN", "UC", "UR", "UT", "SQ")

        fun longForm(vr: String) = vr in LONG_VR

        @OptIn(ExperimentalStdlibApi::class)
        fun decodeText(vr: String, bytes: ByteArray): String = when (vr) {
            "OB", "OD", "OF", "OL", "OW", "UN" ->
                "<binary ${bytes.size} bytes sha256:${sha256Hex(bytes)}>"
            else -> {
                val s = bytes.toString(Charsets.UTF_8)
                s.trimEnd().trimEnd { ch -> ch.code == 0 }
            }
        }
    }
}

data class ParsedDicom(
    val elements: List<DicomElement>,
    val transferSyntaxUid: String?,
    val warnings: List<String>,
) {
    fun find(ge: Int): DicomElement? = elements.firstOrNull { it.tagGE == ge }
    fun text(ge: Int): String? = find(ge)?.rawText?.ifBlank { null }
}

internal fun sha256Hex(bytes: ByteArray): String {
    val md = java.security.MessageDigest.getInstance("SHA-256")
    return md.digest(bytes).joinToString("") { "%02x".format(it) }
}
