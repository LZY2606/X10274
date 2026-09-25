package app

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

@Serializable
data class Diagnostic(val code: String, val message: String, val tag: String? = null)

@Serializable
data class RawTag(val keyword: String, val rawValue: String) {
    val tagId: String get() = keyword
}

@Serializable
data class FrameGroup(
    val imagePositionPatient: List<Double>? = null,
    val instanceNumber: Int? = null
)

/** Derived, normalized geometry. Units: mm, LPS patient basis. */
@Serializable
data class ParsedInstance(
    val sopUid: String? = null,
    val seriesUid: String? = null,
    val frameOfReferenceUid: String? = null,
    val transferSyntax: String? = null,
    val instanceNumber: Int? = null,
    val imagePositionPatient: List<Double>? = null,   // mm, LPS
    val imageOrientationPatient: List<Double>? = null, // row\col direction cosines
    val pixelSpacing: List<Double>? = null,            // mm/px row\col
    val sliceThicknessMm: Double? = null,
    val rows: Int? = null,
    val columns: Int? = null,
    val frames: List<FrameGroup>? = null,
    val diagnostics: List<Diagnostic> = emptyList()
)

object TransferSyntax {
    const val IMPLICIT_LE = "1.2.840.10008.1.2"
    const val EXPLICIT_LE = "1.2.840.10008.1.2.1"
    const val EXPLICIT_BE = "1.2.840.10008.1.2.2"
    val SUPPORTED = setOf(IMPLICIT_LE, EXPLICIT_LE, EXPLICIT_BE)
}

/**
 * Import layer: keeps the raw tag text untouched; the derived layer below
 * normalizes values. Values that cannot be converted stay in place (raw)
 * and get a diagnostic instead of being dropped.
 */
object DicomTags {

    private val LINE = Regex("""^\([0-9A-Fa-f]{4},[0-9A-Fa-f]{4}\)\s+([A-Za-z0-9]+)\s*=\s*(.*)$""")

    fun parseRawTags(text: String): List<RawTag> =
        text.lines().mapNotNull { line ->
            val t = line.trim()
            if (t.isEmpty() || t.startsWith("#")) null
            else {
                val m = LINE.find(t)
                if (m != null) {
                    val value = m.groupValues[4].substringBefore("  #").trim()
                    RawTag(m.groupValues[3], value)
                } else null
            }
        }

    fun derive(tags: List<RawTag>): ParsedInstance {
        val map = tags.associateBy { it.keyword }
        val diags = mutableListOf<Diagnostic>()

        fun doubles(keyword: String, tag: String, count: Int? = null): List<Double>? {
            val raw = map[keyword]?.rawValue ?: return null
            val parts = raw.split("\\").map { it.trim() }
            val out = mutableListOf<Double>()
            for (p in parts) {
                val v = p.toDoubleOrNull()
                if (v == null) {
                    diags += Diagnostic("VALUE_NOT_CONVERTIBLE",
                        "value '$p' of $tag kept raw; not convertible to a number", tag)
                    return null
                }
                out += v
            }
            if (count != null && out.size != count) {
                diags += Diagnostic("VALUE_ARITY_MISMATCH",
                    "$tag expects $count values, found ${out.size}; kept raw", tag)
                return null
            }
            return out
        }

        fun int(keyword: String, tag: String): Int? {
            val raw = map[keyword]?.rawValue ?: return null
            val v = raw.trim().toIntOrNull()
            if (v == null) diags += Diagnostic("VALUE_NOT_CONVERTIBLE",
                "value '$raw' of $tag kept raw; not convertible to an integer", tag)
            return v
        }

        val ts = map["TransferSyntaxUID"]?.rawValue
        if (ts != null && ts !in TransferSyntax.SUPPORTED) {
            diags += Diagnostic("UNSUPPORTED_TRANSFER_SYNTAX",
                "transfer syntax $ts is not supported for pixel decoding; raw bytes retained",
                "(0002,0010)")
        }

        val frames = map["PerFrameFunctionalGroupsSequence"]?.rawValue?.let { raw ->
            if (raw.startsWith("JSON:")) {
                try {
                    Json.parseToJsonElement(raw.removePrefix("JSON:")).jsonArray.map { el ->
                        val o = el.jsonObject
                        FrameGroup(
                            imagePositionPatient = o["ImagePositionPatient"]?.jsonArray
                                ?.map { it.jsonPrimitive.content.toDouble() },
                            instanceNumber = o["InstanceNumber"]?.jsonPrimitive?.content?.toIntOrNull()
                        )
                    }
                } catch (e: Exception) {
                    diags += Diagnostic("VALUE_NOT_CONVERTIBLE",
                        "PerFrameFunctionalGroupsSequence kept raw: ${e.message}", "(5200,9230)")
                    null
                }
            } else {
                diags += Diagnostic("VALUE_NOT_CONVERTIBLE",
                    "PerFrameFunctionalGroupsSequence encoding not recognized; kept raw", "(5200,9230)")
                null
            }
        }

        return ParsedInstance(
            sopUid = map["SOPInstanceUID"]?.rawValue,
            seriesUid = map["SeriesInstanceUID"]?.rawValue,
            frameOfReferenceUid = map["FrameOfReferenceUID"]?.rawValue,
            transferSyntax = ts,
            instanceNumber = int("InstanceNumber", "(0020,0013)"),
            imagePositionPatient = doubles("ImagePositionPatient", "(0020,0032)", 3),
            imageOrientationPatient = doubles("ImageOrientationPatient", "(0020,0037)", 6),
            pixelSpacing = doubles("PixelSpacing", "(0028,0030)", 2),
            sliceThicknessMm = map["SliceThickness"]?.rawValue?.toDoubleOrNull(),
            rows = int("Rows", "(0028,0010)"),
            columns = int("Columns", "(0028,0011)"),
            frames = frames,
            diagnostics = diags
        )
    }
}
