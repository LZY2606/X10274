package dgeom

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.security.MessageDigest

val JsonFmt = Json { ignoreUnknownKeys = true; explicitNulls = false }

fun sha1(s: String): String =
    MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

fun sha256(s: String): String =
    MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

/** DICOM tag identifiers used by the grouping rules. */
object Tags {
    const val SOPInstanceUID = "00080018"
    const val StudyInstanceUID = "0020000D"
    const val SeriesInstanceUID = "0020000E"
    const val FrameOfReferenceUID = "00200052"
    const val TransferSyntaxUID = "00020010"
    const val InstanceNumber = "00200013"
    const val ImagePositionPatient = "00200032"
    const val ImageOrientationPatient = "00200037"
    const val PixelSpacing = "00280030"
    const val SpacingBetweenSlices = "00180088"
    const val Modality = "00080060"
}

val SUPPORTED_TRANSFER_SYNTAXES = setOf(
    "1.2.840.10008.1.2",      // Implicit VR Little Endian
    "1.2.840.10008.1.2.1",    // Explicit VR Little Endian
    "1.2.840.10008.1.2.2",    // Explicit VR Big Endian
    "1.2.840.10008.1.2.4.50", // JPEG Baseline 8-bit
)

/** Rule parameters: one interpretation branch pins exactly one version of these. */
@Serializable
data class Params(
    val orthogonalityToleranceDeg: Double = 0.5,
    val unitNormTolerance: Double = 0.01,
    val spacingJumpFactor: Double = 1.5,
    val duplicateEpsilonMm: Double = 0.001,
)

data class Vec3(val x: Double, val y: Double, val z: Double) {
    operator fun minus(o: Vec3) = Vec3(x - o.x, y - o.y, z - o.z)
    fun dot(o: Vec3) = x * o.x + y * o.y + z * o.z
    fun cross(o: Vec3) = Vec3(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x)
    fun norm() = Math.sqrt(dot(this))
    fun normalized(): Vec3 {
        val n = norm()
        return if (n < 1e-12) Vec3(0.0, 0.0, 0.0) else Vec3(x / n, y / n, z / n)
    }
    fun toJson() = JsonArray(listOf(JsonPrimitive(x), JsonPrimitive(y), JsonPrimitive(z)))
}

/** Raw-layer instance: tags preserved verbatim; nothing is cleaned irreversibly. */
data class InstanceRec(
    val id: String,
    val sourceId: String,
    val sopUid: String,
    val seriesUid: String,
    val forUid: String?,
    val transferSyntax: String?,
    val instanceNumber: Int?,
    val rawTags: JsonObject,
    val functionalGroups: JsonObject?,
    val units: JsonObject?,
    val diagnostics: List<String>,
)

fun JsonObject.tagEntry(tag: String): JsonObject? = this[tag] as? JsonObject
fun JsonObject.tagValues(tag: String): List<JsonElement>? =
    tagEntry(tag)?.get("value")?.let { if (it is JsonArray) it.toList() else listOf(it) }
fun JsonObject.tagString(tag: String): String? =
    tagValues(tag)?.firstOrNull()?.let { (it as? JsonPrimitive)?.content }
fun JsonObject.tagDoubles(tag: String): List<Double>? {
    val vals = tagValues(tag) ?: return null
    val out = ArrayList<Double>(vals.size)
    for (v in vals) {
        val p = v as? JsonPrimitive ?: return null
        out.add(p.content.toDoubleOrNull() ?: return null)
    }
    return out
}

/** Parses one imported source document into the raw layer. Raw bytes stay in `sources`. */
object Importer {
    data class Parsed(val rec: InstanceRec, val diagnostics: List<String>)

    fun parse(sourceId: String, instanceId: String, raw: String): Parsed {
        val diags = mutableListOf<String>()
        val obj = try {
            JsonFmt.parseToJsonElement(raw).jsonObject
        } catch (e: Exception) {
            throw IllegalArgumentException("source is not valid JSON: ${e.message}")
        }
        val tags = (obj["tags"] as? JsonObject) ?: throw IllegalArgumentException("missing 'tags' object")
        val sop = tags.tagString(Tags.SOPInstanceUID) ?: throw IllegalArgumentException("missing SOPInstanceUID")
        val series = tags.tagString(Tags.SeriesInstanceUID) ?: throw IllegalArgumentException("missing SeriesInstanceUID")
        val forUid = tags.tagString(Tags.FrameOfReferenceUID)
        if (forUid == null) diags.add("missing_frame_of_reference_uid")
        val ts = tags.tagString(Tags.TransferSyntaxUID)
        if (ts == null) diags.add("missing_transfer_syntax")
        val instNum = tags.tagString(Tags.InstanceNumber)?.toIntOrNull()
        if (tags.tagString(Tags.InstanceNumber) != null && instNum == null) diags.add("unparsable_instance_number_kept_raw")
        if (tags.tagEntry(Tags.ImagePositionPatient) != null && tags.tagDoubles(Tags.ImagePositionPatient) == null)
            diags.add("unparsable_image_position_kept_raw")
        if (tags.tagEntry(Tags.ImageOrientationPatient) != null &&
            (tags.tagDoubles(Tags.ImageOrientationPatient)?.size != 6))
            diags.add("unparsable_image_orientation_kept_raw")
        val fg = obj["functionalGroups"] as? JsonObject
        val units = obj["units"] as? JsonObject
        return Parsed(
            InstanceRec(instanceId, sourceId, sop, series, forUid, ts, instNum, tags, fg, units, diags),
            diags
        )
    }
}
