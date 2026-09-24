package dicomlab

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.math.abs
import kotlin.math.sqrt

/** 3D vector in the DICOM Reference Coordinate System (LPS), unit = mm. */
@Serializable
data class Vec3(val x: Double, val y: Double, val z: Double) {
    operator fun minus(o: Vec3) = Vec3(x - o.x, y - o.y, z - o.z)
    operator fun plus(o: Vec3) = Vec3(x + o.x, y + o.y, z + o.z)
    operator fun times(s: Double) = Vec3(x * s, y * s, z * s)
    fun dot(o: Vec3) = x * o.x + y * o.y + z * o.z
    fun cross(o: Vec3) = Vec3(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x)
    fun norm() = sqrt(x * x + y * y + z * z)
    fun normalized(): Vec3 {
        val n = norm()
        return if (n == 0.0) this else Vec3(x / n, y / n, z / n)
    }
}

/**
 * Grouping rule parameters. Every value carries its unit/basis explicitly:
 * lengths are millimetres in the patient (LPS) frame; tolerances are dimensionless.
 */
@Serializable
data class RuleParams(
    val orthogonalityTolerance: Double = 1e-3,   // |row·col| must be <= this (dimensionless)
    val normTolerance: Double = 1e-3,            // | |v| - 1 | must be <= this (dimensionless)
    val duplicateToleranceMm: Double = 0.05,     // |Δprojection| below this = duplicate position (mm)
    val spacingToleranceMm: Double = 0.05,      // accepted residual vs median spacing (mm)
    val spacingJumpFactor: Double = 1.5,         // local spacing outside [m/f, m*f] = jump (dimensionless)
    val supportedTransferSyntaxes: List<String> = listOf(
        "1.2.840.10008.1.2",    // Implicit VR Little Endian
        "1.2.840.10008.1.2.1",  // Explicit VR Little Endian
        "1.2.840.10008.1.2.2"   // Explicit VR Big Endian
    )
) {
    companion object {
        val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
        fun parse(s: String): RuleParams = json.decodeFromString(s)
    }
    fun toJson(): String = json.encodeToString(this)
}

object Geometry {
    /** Parse a DICOM DS multi-value string like "0.5\\-12\\301.25" (unit: mm, LPS frame). */
    fun parseVec3(raw: String): Vec3? {
        val p = raw.trim().split('\\').mapNotNull { it.trim().toDoubleOrNull() }
        return if (p.size == 3) Vec3(p[0], p[1], p[2]) else null
    }

    fun parseVec6(raw: String): Pair<Vec3, Vec3>? {
        val p = raw.trim().split('\\').mapNotNull { it.trim().toDoubleOrNull() }
        return if (p.size == 6) Vec3(p[0], p[1], p[2]) to Vec3(p[3], p[4], p[5]) else null
    }

    /** PixelSpacing "rowSpacing\\colSpacing" in mm. */
    fun parsePair(raw: String): Pair<Double, Double>? {
        val p = raw.trim().split('\\').mapNotNull { it.trim().toDoubleOrNull() }
        return if (p.size == 2) p[0] to p[1] else null
    }

    /** Orthogonality + unit-norm check, performed BEFORE any spatial use of the orientation. */
    fun orientationIssues(row: Vec3, col: Vec3, params: RuleParams): List<String> {
        val issues = mutableListOf<String>()
        if (abs(row.dot(col)) > params.orthogonalityTolerance)
            issues.add("row·col=${"%.6f".format(row.dot(col))} exceeds orthogonalityTolerance=${params.orthogonalityTolerance}")
        if (abs(row.norm() - 1.0) > params.normTolerance)
            issues.add("|row|=${"%.6f".format(row.norm())} exceeds normTolerance=${params.normTolerance}")
        if (abs(col.norm() - 1.0) > params.normTolerance)
            issues.add("|col|=${"%.6f".format(col.norm())} exceeds normTolerance=${params.normTolerance}")
        return issues
    }

    /** Slice normal = normalized row × col (mm basis, LPS). */
    fun normal(row: Vec3, col: Vec3): Vec3 = row.cross(col).normalized()

    /** Spatial projection coordinate of a position onto the stack normal (mm). InstanceNumber is never consulted. */
    fun projection(position: Vec3, normal: Vec3): Double = position.dot(normal)
}
