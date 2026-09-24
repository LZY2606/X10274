package review.dicom

/** A single DICOM data element. Sequences carry nested [items]; everything keeps its raw bytes. */
class DElem(
    val tag: Int,
    val vr: String,
    val bytes: ByteArray,
    val items: List<DicomDataset>? = null,
) {
    val group: Int get() = tag ushr 16
    val element: Int get() = tag and 0xFFFF
    val tagHex: String get() = "%04X%04X".format(group, element)

    /** Text exactly as encoded (trailing NUL padding removed, spaces preserved). Multi values split on '\'. */
    val rawText: String by lazy {
        val s = String(bytes, Charsets.ISO_8859_1)
        s.trimEnd('\u0000')
    }
    val values: List<String> get() = if (bytes.isEmpty()) emptyList() else rawText.split('\\')

    fun intValue(): Int? = when (vr) {
        "US" -> if (bytes.size >= 2) ((bytes[0].toInt() and 0xFF)) or ((bytes[1].toInt() and 0xFF) shl 16) else null
        "UL" -> bytes.take(4).foldIndexed(0) { i, acc, b -> acc or ((b.toInt() and 0xFF) shl (i * 8)) }
        "IS" -> rawText.trim().toIntOrNull()
        else -> null
    }

    override fun equals(other: Any?) = other is DElem && other.tag == tag
    override fun hashCode() = tag
}

/** Ordered list of elements preserving file order and original representation. */
class DicomDataset(private val elems: List<DElem> = emptyList()) : Iterable<DElem> {
    private val byTag = elems.associateBy { it.tag }
    override fun iterator() = elems.iterator()
    fun get(tag: Int): DElem? = byTag[tag]
    fun require(tag: Int): DElem = byTag.getValue(tag)
    fun all(): List<DElem> = elems
}

enum class SupportLevel { FULL, METADATA_ONLY, UNSUPPORTED }

data class ParseIssue(val code: String, val message: String, val tagPath: String? = null)

data class DicomFile(
    val bytes: ByteArray,
    val meta: DicomDataset,
    val dataset: DicomDataset,
    val transferSyntaxUid: String,
    val support: SupportLevel,
    val issues: List<ParseIssue>,
) {
    fun text(tag: Int): String? = dataset.get(tag)?.rawText?.trimEnd()
    fun metaText(tag: Int): String? = meta.get(tag)?.rawText?.trimEnd()
}
