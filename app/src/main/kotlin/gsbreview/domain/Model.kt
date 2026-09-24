package gsbreview.domain

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

@Serializable
data class Vec3(val x: Double, val y: Double, val z: Double) {
    operator fun minus(o: Vec3) = Vec3(x - o.x, y - o.y, z - o.z)
    operator fun plus(o: Vec3) = Vec3(x + o.x, y + o.y, z + o.z)
    operator fun times(s: Double) = Vec3(x * s, y * s, z * s)
    fun dot(o: Vec3) = x * o.x + y * o.y + z * o.z
    fun cross(o: Vec3) = Vec3(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x)
    fun norm() = kotlin.math.sqrt(dot(this))
    fun normalized() = this * (1.0 / norm())
    fun asList() = listOf(x, y, z)
    companion object {
        fun cross(a: List<Double>, b: List<Double>) =
            Vec3(a[0], a[1], a[2]).cross(Vec3(b[0], b[1], b[2]))
    }
}

@Serializable
data class FrameGeometry(
    val frameKey: String,
    val subjectKey: String,
    val instanceNumber: String?,
    val imagePositionPatient: Vec3?,
    val rowCosines: List<Double>?,
    val columnCosines: List<Double>?,
    val pixelSpacingRowMm: Double?,
    val pixelSpacingColMm: Double?,
    val frameOfReferenceUID: String?,
    val normal: List<Double>?,
    val sliceCoordinate: Double?,
    val orthogonal: Boolean,
    val orthogonalityError: Double?,
    val unitBasis: String,
    val problems: List<String>
)

@Serializable
data class CandidateMember(
    val ord: Int,
    val frameKey: String,
    val subjectKey: String,
    val instanceNumber: String?,
    val position: List<Double>?,
    val spatialCoordinateMm: Double?,
    val spacingResidualMm: Double?,
    val instanceNumberMatchesSpatialOrder: Boolean?
)

@Serializable
data class VolumeCandidate(
    val candidateId: String,
    val volumeKey: String,
    val frameOfReferenceUID: String?,
    val orientationHash: String?,
    val status: String,
    val reason: String,
    val ordered: Boolean,
    val memberCount: Int,
    val members: List<CandidateMember>,
    val planes: List<SlicePlane>,
    val spacingResidualsMm: List<Double>,
    val accepted: Boolean = false
)

@Serializable
data class SlicePlane(
    val frameKey: String,
    val origin: List<Double>,
    val row: List<Double>,
    val col: List<Double>,
    val normal: List<Double>
)

@Serializable
data class Diagnostic(
    val diagnosticId: String,
    val code: String,
    val severity: String,
    val message: String,
    val subjectKey: String?,
    val frameKey: String?,
    val rawValue: String?,
    val locationTag: String?
)

@Serializable
data class GeomFact(
    val factId: String,
    val frameKey: String,
    val subjectKey: String,
    val fieldName: String,
    val rawValue: String,
    val unit: String?,
    val basis: String,
    val numeric: JsonElement?,
    val valid: Boolean
)

@Serializable
data class InterpretationView(
    val interpretationId: String,
    val ruleVersion: String,
    val parameterSetId: String,
    val batchId: String,
    val caseId: String?,
    val trigger: String,
    val status: String,
    val facts: List<GeomFact>,
    val candidates: List<VolumeCandidate>,
    val diagnostics: List<Diagnostic>,
    val invalidations: List<InvalidationView>
)

@Serializable
data class InvalidationView(
    val oldInterpretationId: String,
    val newInterpretationId: String?,
    val reason: String,
    val depType: String,
    val depKey: String,
    val scope: JsonObject
)
