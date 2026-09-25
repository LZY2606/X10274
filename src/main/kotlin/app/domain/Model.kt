package app.domain

import app.dicom.RawElement
import kotlinx.serialization.Serializable

/**
 * A measured value: raw representation is always kept; the normalized value is derived.
 * Every domain quantity carries a unit or a coordinate basis. Values that cannot be
 * converted stay in place (value == null) with a diagnostic attached.
 */
@Serializable
data class Vm(
    val raw: String,
    val value: Double? = null,
    val unit: String? = null,
    val basis: String? = null,
    val diagnostic: String? = null,
)

@Serializable
data class Vec3(val x: Double, val y: Double, val z: Double) {
    operator fun minus(o: Vec3) = Vec3(x - o.x, y - o.y, z - o.z)
    fun dot(o: Vec3) = x * o.x + y * o.y + z * o.z
    fun cross(o: Vec3) = Vec3(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x)
    fun norm() = kotlin.math.sqrt(dot(this))
    fun normalized(): Vec3 {
        val n = norm()
        return if (n == 0.0) Vec3(0.0, 0.0, 0.0) else Vec3(x / n, y / n, z / n)
    }
}

const val BASIS_LPS = "DICOM-LPS-Patient"
const val UNIT_MM = "mm"

/** One image frame extracted from a source file (single-frame files yield exactly one). */
@Serializable
data class FrameRecord(
    val frameKey: String,
    val sopInstanceUid: String,
    val seriesInstanceUid: String,
    val frameOfReferenceUid: String?,
    val instanceNumber: String?,
    val positionRaw: List<String>?,
    val orientationRaw: List<String>?,
    val spacingRaw: List<String>?,
    val geometrySourceTags: List<String>,
)

/** Source layer: an imported DICOM file. Raw tags and original bytes are preserved;
 *  no irreversible cleaning happens at import time. */
@Serializable
data class SourceFileRecord(
    val sha256: String,
    val fileName: String,
    val sizeBytes: Long,
    val transferSyntaxUid: String,
    val pixelDataBytes: Int,
    val pixelSha256: String?,
 val tags: List<RawElement>,
    val frames: List<FrameRecord>,
)

object RuleCodes {
    const val ORIENTATION_NON_ORTHOGONAL = "ORIENTATION_NON_ORTHOGONAL"
    const val DUPLICATE_POSITION = "DUPLICATE_POSITION"
    const val LOCAL_SPACING_JUMP = "LOCAL_SPACING_JUMP"
    const val MULTI_FRAME_OBJECT = "MULTI_FRAME_OBJECT"
    const val SOP_UID_CONFLICT = "SOP_UID_CONFLICT"
    const val MISSING_GEOMETRY = "MISSING_GEOMETRY"
    const val UNSUPPORTED_TRANSFER_SYNTAX = "UNSUPPORTED_TRANSFER_SYNTAX"
    const val INSTANCE_NUMBER_UNRELIABLE = "INSTANCE_NUMBER_UNRELIABLE"
    const val FRAME_OF_REFERENCE_SPLIT = "FRAME_OF_REFERENCE_SPLIT"
    const val NOT_DICOM = "NOT_DICOM"
}

/** Machine-readable judgement record attached to derivations, sets and candidates. */
@Serializable
data class Finding(
    val code: String,
    val severity: String,
    val message: String,
    val objectIds: List<String>,
)

@Serializable
data class GeometryNode(
    val nodeId: String,
    val frameKey: String,
    val fileSha: String,
    val sopInstanceUid: String,
    val seriesInstanceUid: String,
    val frameOfReferenceUid: String,
    val positionMm: Vec3,
    val rowDir: Vec3,
    val colDir: Vec3,
    val normal: Vec3,
    val axisOffsetMm: Double,
    val rowSpacingMm: Vm,
    val colSpacingMm: Vm,
    val positionRaw: List<String>,
    val orientationRaw: List<String>,
    val spacingRaw: List<String>,
    val instanceNumberRaw: String?,
    val orthoError: Double,
    val geometrySourceTags: List<String>,
    val ruleVersion: String,
    val paramVersion: Int,
)

@Serializable
data class VolumeCandidate(
    val candidateId: String,
    val setId: String,
    val label: String,
    val frameKeys: List<String>,
    val axisOffsetsMm: List<Double>,
    val spacingResidualsMm: List<Double>,
    val medianSpacingMm: Double?,
    val frameOfReferenceUid: String,
    val seriesInstanceUid: String,
    val findings: List<Finding>,
    val dependencies: List<String>,
)

@Serializable
data class CandidateSet(
    val setId: String,
    val kind: String,
    val seriesInstanceUid: String,
    val frameOfReferenceUid: String,
    val candidates: List<VolumeCandidate>,
    val findings: List<Finding>,
)

@Serializable
data class Bucket(
    val bucketId: String,
    val reasonCode: String,
    val frameKeys: List<String>,
    val diagnostics: List<String>,
)

@Serializable
data class Derivation(
    val derivationId: String,
    val ruleVersion: String,
    val paramVersion: Int,
    val parentDerivationId: String?,
    val roots: List<String>,
    val nodes: List<GeometryNode>,
    val candidateSets: List<CandidateSet>,
    val buckets: List<Bucket>,
    val findings: List<Finding>,
    val invalidatedObjectIds: List<String>,
    val retainedObjectIds: List<String>,
)

@Serializable
data class Decision(
    val decisionId: String,
    val targetSetId: String,
    val candidateId: String?,
    val action: String,
    val note: String,
    val baseEventId: String,
    val retracted: Boolean = false,
)

@Serializable
data class ConflictNote(
    val attemptedAction: String,
    val baseEventId: String,
    val headEventId: String,
    val diffSummary: List<String>,
)

@Serializable
data class FileRejection(
    val fileName: String,
    val sha256: String?,
    val reasonCode: String,
    val diagnostic: String,
    val excerptHex: String,
)

@Serializable
data class RejectedImport(
    val batchId: String,
    val rejections: List<FileRejection>,
)

@Serializable
data class ParamVersion(
    val version: Int,
    val orthoTolerance: Double,
    val spacingJumpFactor: Double,
    val duplicatePositionToleranceMm: Double,
    val note: String,
)

object EventType {
    const val FILES_RECORDED = "FILES_RECORDED"
    const val SOURCE_REVISED = "SOURCE_REVISED"
    const val IMPORT_REJECTED = "IMPORT_REJECTED"
    const val DERIVATION_COMPUTED = "DERIVATION_COMPUTED"
    const val PARAMS_REVISED = "PARAMS_REVISED"
    const val DECISION_RECORDED = "DECISION_RECORDED"
    const val DECISION_RETRACTED = "DECISION_RETRACTED"
    const val CONFLICT_NOTED = "CONFLICT_NOTED"
}

@Serializable
data class Event(
    val eventId: String,
    val branchId: String,
    val seq: Int,
    val prevEventId: String?,
    val type: String,
    val payloadJson: String,
)

@Serializable
data class FilesRecordedPayload(val files: List<SourceFileRecord>)

@Serializable
data class SourceRevisedPayload(
    val removedFileNames: List<String>,
    val addedFiles: List<SourceFileRecord>,
    val reason: String,
)

@Serializable
data class ImportRejectedPayload(val rejected: RejectedImport)

@Serializable
data class DerivationComputedPayload(val derivation: Derivation)

@Serializable
data class ParamsRevisedPayload(val params: ParamVersion, val reason: String)

@Serializable
data class DecisionRecordedPayload(val decision: Decision)

@Serializable
data class DecisionRetractedPayload(val decisionId: String, val reason: String)

@Serializable
data class ConflictNotedPayload(val note: ConflictNote)

@Serializable
data class BranchInfo(
    val branchId: String,
    val name: String,
    val parentBranchId: String?,
    val forkEventId: String?,
)
