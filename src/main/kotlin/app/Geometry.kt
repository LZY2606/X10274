package app
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt

/** 3D vector in DICOM LPS patient basis, unit mm for positions. */
data class Vec3(val x: Double, val y: Double, val z: Double) {
    operator fun minus(o: Vec3) = Vec3(x - o.x, y - o.y, z - o.z)
    fun dot(o: Vec3) = x * o.x + y * o.y + z * o.z
    fun cross(o: Vec3) = Vec3(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x)
    fun norm() = sqrt(dot(this))
    fun normalized(): Vec3 {
        val n = norm()
        return if (n == 0.0) this else Vec3(x / n, y / n, z / n)
    }
    fun fmt() = "%.4f,%.4f,%.4f".format(x, y, z)
}

object RuleSet {
    const val RULE_VERSION = "geom-1.0"
    const val PARAM_ORTH_TOL_DEG = "orthogonalityToleranceDeg"
    const val PARAM_SPACING_TOL_MM = "spacingResidualToleranceMm"
    const val PARAM_DUP_EPS_MM = "duplicatePositionEpsilonMm"
    val PARAM_KEYS = listOf(PARAM_ORTH_TOL_DEG, PARAM_SPACING_TOL_MM, PARAM_DUP_EPS_MM)
    val SUPPORTED_TRANSFER_SYNTAXES = setOf(
        "1.2.840.10008.1.2",    // Implicit VR Little Endian
        "1.2.840.10008.1.2.1",  // Explicit VR Little Endian
        "1.2.840.10008.1.2.2"   // Explicit VR Big Endian
    )
}

/** Tunable parameters; every change bumps param_version and is event-sourced. */
data class Params(
    val orthogonalityToleranceDeg: Double = 0.5,
    val spacingResidualToleranceMm: Double = 0.05,
    val duplicatePositionEpsilonMm: Double = 0.01
) {
    fun value(key: String): Double = when (key) {
        RuleSet.PARAM_ORTH_TOL_DEG -> orthogonalityToleranceDeg
        RuleSet.PARAM_SPACING_TOL_MM -> spacingResidualToleranceMm
        RuleSet.PARAM_DUP_EPS_MM -> duplicatePositionEpsilonMm
        else -> throw IllegalArgumentException("unknown param $key")
    }
    fun with(key: String, v: Double): Params = when (key) {
        RuleSet.PARAM_ORTH_TOL_DEG -> copy(orthogonalityToleranceDeg = v)
        RuleSet.PARAM_SPACING_TOL_MM -> copy(spacingResidualToleranceMm = v)
        RuleSet.PARAM_DUP_EPS_MM -> copy(duplicatePositionEpsilonMm = v)
        else -> throw IllegalArgumentException("unknown param $key")
    }
}

object DiagKind {
    const val NON_ORTHOGONAL = "ORIENTATION_NON_ORTHOGONAL"
    const val DUPLICATE_POSITION = "DUPLICATE_POSITION"
    const val SPACING_JUMP = "SPACING_JUMP"
    const val MULTIFRAME = "MULTIFRAME_OBJECT"
    const val UID_CONFLICT = "UID_CONFLICT"
    const val MISSING_GEOMETRY = "MISSING_GEOMETRY"
    const val UNSUPPORTED_TS = "UNSUPPORTED_TRANSFER_SYNTAX"
    const val FORK_FOR = "FORK_FRAME_OF_REFERENCE"
    const val PARSE_FAILURE = "PARSE_FAILURE"
    const val INSTANCE_NUMBER_IGNORED = "INSTANCE_NUMBER_IGNORED_AS_SPATIAL_TRUTH"
}

data class Diag(val kind: String, val detail: String, val tag: String? = null, val rawValue: String? = null)

/** Raw imported instance: tags kept verbatim (original representation). */
data class RawInstance(
    val id: String,
    val transferSyntax: String,
    val tags: Map<String, String>,
    val pixelB64: String?,
    val sourceRef: String
)

/** Derived, normalized layer. Raw values stay in the instance row. */
data class DerivedGeometry(
    val instanceId: String,
    val seriesUid: String,
    val sopUid: String,
    val frameOfReferenceUid: String?,
    val transferSyntax: String,
    val positionMm: Vec3?,
    val rowDir: Vec3?,
    val colDir: Vec3?,
    val spacingRowMm: Double?,
    val spacingColMm: Double?,
    val instanceNumber: Int?,
    val numberOfFrames: Int?,
    val framePositionsMm: List<Vec3>?,
    val diagnostics: List<Diag>
)

object DicomParse {
    fun parseVec3(raw: String): Vec3? {
        val p = raw.split("\\").map { it.trim().toDoubleOrNull() }
        if (p.size != 3 || p.any { it == null }) return null
        return Vec3(p[0]!!, p[1]!!, p[2]!!)
    }
    fun parseDoubleList(raw: String): List<Double>? {
        val p = raw.split("\\").map { it.trim().toDoubleOrNull() }
        return if (p.any { it == null }) null else p.filterNotNull()
    }
    fun parseInt(raw: String): Int? = raw.trim().toIntOrNull()

    fun derive(raw: RawInstance): DerivedGeometry {
        val diags = mutableListOf<Diag>()
        val t = raw.tags
        fun vec(tag: String): Vec3? {
            val v = t[tag] ?: return null
            val parsed = parseVec3(v)
            if (parsed == null) diags += Diag(DiagKind.PARSE_FAILURE, "cannot convert $tag to 3 floats; raw kept in place", tag, v)
            return parsed
        }
        val position = vec("ImagePositionPatient")
        val iop = t["ImageOrientationPatient"]
        var rowDir: Vec3? = null
        var colDir: Vec3? = null
        if (iop != null) {
            val l = parseDoubleList(iop)
            if (l == null || l.size != 6) {
                diags += Diag(DiagKind.PARSE_FAILURE, "cannot convert ImageOrientationPatient to 6 floats; raw kept in place", "ImageOrientationPatient", iop)
            } else {
                rowDir = Vec3(l[0], l[1], l[2]); colDir = Vec3(l[3], l[4], l[5])
            }
        }
        var spacingRow: Double? = null
        var spacingCol: Double? = null
        val ps = t["PixelSpacing"]
        if (ps != null) {
            val l = parseDoubleList(ps)
            if (l == null || l.size != 2) {
                diags += Diag(DiagKind.PARSE_FAILURE, "cannot convert PixelSpacing to 2 floats; raw kept in place", "PixelSpacing", ps)
            } else { spacingRow = l[0]; spacingCol = l[1] }
        }
        val instanceNumber = t["InstanceNumber"]?.let { rawStr ->
            parseInt(rawStr) ?: run { diags += Diag(DiagKind.PARSE_FAILURE, "InstanceNumber not an integer; raw kept", "InstanceNumber", rawStr); null }
        }
        val numberOfFrames = t["NumberOfFrames"]?.let { parseInt(it) }
        var framePositions: List<Vec3>? = null
        val fg = t["PerFrameFunctionalGroups"]
        if (fg != null) {
            // simplified functional group encoding: JSON array of {"ImagePositionPatient":"x\\y\\z"}
            try {
                val arr = kotlinx.serialization.json.Json.parseToJsonElement(fg).jsonArray
                framePositions = arr.map { el ->
                    val ipp = el.jsonObject["ImagePositionPatient"]!!.jsonPrimitive.content
                    parseVec3(ipp) ?: throw IllegalArgumentException("bad frame position $ipp")
                }
            } catch (e: Exception) {
                diags += Diag(DiagKind.PARSE_FAILURE, "functional group parse failed: ${e.message}; raw kept", "PerFrameFunctionalGroups", fg)
            }
        }
        if (raw.transferSyntax !in RuleSet.SUPPORTED_TRANSFER_SYNTAXES) {
            diags += Diag(DiagKind.UNSUPPORTED_TS, "transfer syntax ${raw.transferSyntax} not supported for pixel decode; metadata retained", "TransferSyntaxUID", raw.transferSyntax)
        }
        if (position == null || rowDir == null || colDir == null) {
            diags += Diag(DiagKind.MISSING_GEOMETRY, "ImagePosition/Orientation missing or unconvertible; instance kept with diagnostic, not dropped")
        }
        return DerivedGeometry(
            instanceId = raw.id,
            seriesUid = t["SeriesInstanceUID"] ?: "",
            sopUid = t["SOPInstanceUID"] ?: "",
            frameOfReferenceUid = t["FrameOfReferenceUID"],
            transferSyntax = raw.transferSyntax,
            positionMm = position, rowDir = rowDir, colDir = colDir,
            spacingRowMm = spacingRow, spacingColMm = spacingCol,
            instanceNumber = instanceNumber,
            numberOfFrames = numberOfFrames, framePositionsMm = framePositions,
            diagnostics = diags
        )
    }
}
