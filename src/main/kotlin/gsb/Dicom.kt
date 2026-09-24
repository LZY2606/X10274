package gsb

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.Base64

/** Codes for import/derivation diagnostics; machine-readable, appear in events. */
object Diag {
    const val UNSUPPORTED_TRANSFER_SYNTAX = "UNSUPPORTED_TRANSFER_SYNTAX"
    const val MISSING_GEOMETRY = "MISSING_GEOMETRY"
    const val UNCONVERTIBLE_VALUE = "UNCONVERTIBLE_VALUE"
    const val UID_CONFLICT = "UID_CONFLICT"
    const val NON_ORTHOGONAL_ORIENTATION = "NON_ORTHOGONAL_ORIENTATION"
    const val DUPLICATE_POSITION = "DUPLICATE_POSITION"
    const val SPACING_JUMP = "SPACING_JUMP"
    const val MULTIFRAME_OBJECT = "MULTIFRAME_OBJECT"
    const val FRAME_OF_REFERENCE_SPLIT = "FRAME_OF_REFERENCE_SPLIT"
    const val INSTANCE_NUMBER_IGNORED = "INSTANCE_NUMBER_IGNORED"
}

data class ImportDiagnostic(
    val code: String,
    val tag: String?,
    val rawValue: String?,
    val message: String,
)

/**
 * One imported de-identified DICOM instance. [rawJson] is the verbatim original
 * representation; everything else is a derived, normalized view. Values that
 * cannot be converted stay only in [rawJson]/[tags] and produce diagnostics.
 */
data class ImportedInstance(
    val id: String,
    val rawJson: String,
    val sopUid: String,
    val seriesUid: String,
    val forUid: String?,
    val transferSyntax: String,
    val tags: Map<String, JsonElement>,
    val pixels: ByteArray?,
    val slices: List<SliceGeom>,
    val multiframe: Boolean,
    val diagnostics: List<ImportDiagnostic>,
)

object DicomImport {
    val json = Json { ignoreUnknownKeys = true }

    const val FORMAT = "pairwise-gsb/dicom-instance@1"

    fun parse(rawJson: String, supportedTransferSyntaxes: List<String>): ImportedInstance {
        val id = Ids.instance(rawJson)
        val diags = mutableListOf<ImportDiagnostic>()
        val root: JsonObject = try {
            json.parseToJsonElement(rawJson).jsonObject
        } catch (e: Exception) {
            // Unparseable material: keep raw, no derived view at all.
            return ImportedInstance(
                id = id, rawJson = rawJson, sopUid = "", seriesUid = "", forUid = null,
                transferSyntax = "", tags = emptyMap(), pixels = null, slices = emptyList(),
                multiframe = false,
                diagnostics = listOf(
                    ImportDiagnostic(Diag.UNCONVERTIBLE_VALUE, null, null, "material is not valid JSON: ${e.message}")
                ),
            )
        }
        val tags = (root["tags"] as? JsonObject)?.toMap() ?: emptyMap()
        val meta = (root["meta"] as? JsonObject)?.toMap() ?: emptyMap()

        fun tag(name: String): JsonElement? = tags[name]
        fun tagText(name: String): String? = (tag(name) as? JsonPrimitive)?.content

        val sopUid = tagText("SOPInstanceUID") ?: ""
        val seriesUid = tagText("SeriesInstanceUID") ?: ""
        val forUid = tagText("FrameOfReferenceUID")
        val transferSyntax =
            (meta["transfer_syntax_uid"] as? JsonPrimitive)?.content ?: tagText("TransferSyntaxUID") ?: ""

        val pixels: ByteArray?
        if (transferSyntax !in supportedTransferSyntaxes) {
            diags += ImportDiagnostic(
                Diag.UNSUPPORTED_TRANSFER_SYNTAX, "TransferSyntaxUID", transferSyntax,
                "transfer syntax $transferSyntax is not supported; raw tags and bytes preserved, pixels not decoded",
            )
            pixels = null
        } else {
            pixels = ((root["pixels"] as? JsonObject)?.get("data") as? JsonPrimitive)?.content?.let {
                try {
                    Base64.getDecoder().decode(it)
                } catch (e: Exception) {
                    diags += ImportDiagnostic(Diag.UNCONVERTIBLE_VALUE, "PixelData", "<base64>", "pixel payload is not valid base64")
                    null
                }
            }
        }

        val rows = tagText("Rows")?.toIntOrNull() ?: 0
        val cols = tagText("Columns")?.toIntOrNull() ?: 0
        val instanceNumber = tagText("InstanceNumber")?.toIntOrNull()

        fun vec(tagName: String): Vec3? {
            val el = tag(tagName) ?: return null
            val parts = (el as? JsonArray)?.map { (it as? JsonPrimitive)?.content ?: "" } ?: return null
            if (parts.size < 3) return null
            val nums = parts.take(3).map { it.toDoubleOrNull() }
            if (nums.any { it == null }) {
                diags += ImportDiagnostic(
                    Diag.UNCONVERTIBLE_VALUE, tagName, parts.joinToString("\\"),
                    "value of $tagName cannot be converted to numbers; original representation kept in place",
                )
                return null
            }
            return Vec3(nums[0]!!, nums[1]!!, nums[2]!!)
        }

        fun spacing(): Pair<Double, Double>? {
            val el = tag("PixelSpacing") ?: return null
            val parts = (el as? JsonArray)?.map { (it as? JsonPrimitive)?.content ?: "" } ?: return null
            if (parts.size < 2) return null
            val a = parts[0].toDoubleOrNull()
            val b = parts[1].toDoubleOrNull()
            if (a == null || b == null) {
                diags += ImportDiagnostic(
                    Diag.UNCONVERTIBLE_VALUE, "PixelSpacing", parts.joinToString("\\"),
                    "PixelSpacing cannot be converted to numbers; original representation kept in place",
                )
                return null
            }
            return a to b
        }

        val fg = root["functional_groups"] as? JsonObject
        val slices = mutableListOf<SliceGeom>()
        val multiframe = fg != null

        fun buildSlice(frameIndex: Int?, pos: Vec3?, ori: JsonElement?, sp: Pair<Double, Double>?): SliceGeom? {
            if (pos == null || ori == null || sp == null) return null
            val oParts = (ori as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content?.toDoubleOrNull() }
            if (oParts == null || oParts.size < 6) {
                diags += ImportDiagnostic(
                    Diag.UNCONVERTIBLE_VALUE, "ImageOrientationPatient", ori?.toString(),
                    "ImageOrientationPatient cannot be converted to 6 direction cosines",
                )
                return null
            }
            return SliceGeom(
                sliceId = Ids.slice(id, frameIndex),
                instanceId = id,
                frameIndex = frameIndex,
                position = pos,
                row = Vec3(oParts[0], oParts[1], oParts[2]).normalized(),
                col = Vec3(oParts[3], oParts[4], oParts[5]).normalized(),
                spacingRow = sp.first,
                spacingCol = sp.second,
                rows = rows,
                cols = cols,
                instanceNumber = instanceNumber,
            )
        }

        if (multiframe) {
            val perFrame = fg!!["PerFrame"] as? JsonArray ?: JsonArray(emptyList())
            val declared = (fg["NumberOfFrames"] as? JsonPrimitive)?.content?.toIntOrNull()
            if (declared != null && declared != perFrame.size) {
                diags += ImportDiagnostic(
                    Diag.UNCONVERTIBLE_VALUE, "NumberOfFrames", declared.toString(),
                    "declared NumberOfFrames=$declared but ${perFrame.size} per-frame functional groups present",
                )
            }
            perFrame.forEachIndexed { idx, frameEl ->
                val f = frameEl as? JsonObject ?: return@forEachIndexed
                val pos = (f["ImagePositionPatient"] as? JsonArray)?.let { arr ->
                    val n = arr.mapNotNull { (it as? JsonPrimitive)?.content?.toDoubleOrNull() }
                    if (n.size >= 3) Vec3(n[0], n[1], n[2]) else null
                }
                val ori = f["ImageOrientationPatient"] ?: tag("ImageOrientationPatient")
                val sp = spacing()
                val slice = buildSlice(idx, pos, ori, sp)
                if (slice != null) slices += slice
            }
            diags += ImportDiagnostic(
                Diag.MULTIFRAME_OBJECT, null, null,
                "multi-frame object expanded into ${slices.size} frame slices from functional groups",
            )
        } else {
            val pos = vec("ImagePositionPatient")
            val ori = tag("ImageOrientationPatient")
            val sp = spacing()
            if (pos == null || ori == null) {
                diags += ImportDiagnostic(
                    Diag.MISSING_GEOMETRY,
                    if (pos == null) "ImagePositionPatient" else "ImageOrientationPatient",
                    null,
                    "instance has no usable spatial geometry; kept as evidence, not placed in any volume",
                )
            } else {
                buildSlice(null, pos, ori, sp)?.let { slices += it }
            }
        }

        return ImportedInstance(
            id = id, rawJson = rawJson, sopUid = sopUid, seriesUid = seriesUid, forUid = forUid,
            transferSyntax = transferSyntax, tags = tags, pixels = pixels, slices = slices,
            multiframe = multiframe, diagnostics = diags,
        )
    }
}
