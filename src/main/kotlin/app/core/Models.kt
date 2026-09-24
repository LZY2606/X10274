package app.core

import java.time.Instant

/** All domain quantities carry an explicit unit or coordinate reference frame. */
enum class UnitTag(val label: String) {
    MM("mm"), PX("px"), MM_PER_PX("mm/px"), DIMENSIONLESS("1"),
    DEG("deg"), BYTES("bytes"), UNKNOWN("unconvertible")
}

data class Quantity(val value: Double, val unit: UnitTag, val raw: String? = null) {
    companion object {
        fun mm(v: Double, raw: String? = null) = Quantity(v, UnitTag.MM, raw)
        fun px(v: Double, raw: String? = null) = Quantity(v, UnitTag.PX, raw)
        fun mmPerPx(v: Double, raw: String? = null) = Quantity(v, UnitTag.MM_PER_PX, raw)
    }
}

/** Original textual representation is preserved; normalization only happens in derived layers. */
data class RawField(
    val tagHex: String,          // (gggg,eeee)
    val name: String,
    val vr: String,              // VR as seen in the transfer syntax / UN when absent
    val valueRepr: String,       // exact textual representation from source (truncated display only)
    val valueLen: Int,
    val seqPath: String = "",    // e.g. (5200,9229)[0]/(0020,9111)
    val dataHash: String? = null, // sha1 of exact source bytes of the element value
    val evidenceId: String = sha1("$seqPath|$tagHex|$vr|$valueLen|$valueRepr|${dataHash ?: ""}")
)

data class DicomObject(
    val objectId: String,        // sha1 of file bytes
    val fileName: String,
    val byteSize: Int,
    val transferSyntaxUid: String?,
    val transferSyntaxSupported: Boolean,
    val parseStatus: ParseStatus,
    val sopClassUid: String?,
    val sopInstanceUid: String?,
    val seriesUid: String?,
    val studyUid: String?,
    val frameOfReferenceUid: String?,
    val instanceNumber: String?,      // raw, never a spatial ordering truth
    val numberOfFrames: Int?,
    val fields: List<RawField>,
    val diagnostics: List<Diagnostic>
)

enum class ParseStatus { PARSED, GEOMETRY_PARTIAL, UNSUPPORTED_SYNTAX, INVALID }

data class Diagnostic(val code: String, val message: String, val tagHex: String? = null)

data class Vec3(val x: Double, val y: Double, val z: Double) {
    operator fun minus(o: Vec3) = Vec3(x - o.x, y - o.y, z - o.z)
    operator fun plus(o: Vec3) = Vec3(x + o.x, y + o.y, z + o.z)
    operator fun times(s: Double) = Vec3(x * s, y * s, z * s)
    fun dot(o: Vec3) = x * o.x + y * o.y + z * o.z
    fun cross(o: Vec3) = Vec3(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x)
    fun norm() = Math.sqrt(dot(this))
    fun normalized(): Vec3 = norm().let { if (it == 0.0) this else Vec3(x / it, y / it, z / it) }
}

/** Coordinate basis is always explicit: DICOM patient coordinate system in millimetres. */
data class FrameGeometry(
    val objectId: String,
    val frameIndex: Int,           // 0-based; -1 for classic single-frame
    val positionPatient: Vec3?,    // mm, DICOM patient coordinate system (L/P/H)
    val rowCosines: Vec3?,         // dimensionless unit vectors
    val colCosines: Vec3?,
    val pixelSpacingRow: Quantity?, // mm/px
    val pixelSpacingCol: Quantity?,
    val instanceNumber: String?,
    val sourcePath: String,        // functional-group path the geometry came from
    val valid: Boolean,
    val diagnostics: List<Diagnostic>,
    val evidenceIds: List<String>
) {
    val normal: Vec3? get() = if (valid && rowCosines != null && colCosines != null)
        rowCosines.cross(colCosines).normalized() else null
    val frameId: String get() = sha1("$objectId|$frameIndex|$sourcePath")
}

enum class Severity { INFO, WARNING, ERROR }
enum class FindingKind {
    NON_ORTHOGONAL_DIRECTION, DUPLICATE_POSITION, LOCAL_SPACING_JUMP,
    MULTIFRAME_OBJECT, SOP_UID_CONFLICT, MISSING_GEOMETRY, UNSUPPORTED_TRANSFER_SYNTAX,
    INSTANCE_NUMBER_REORDER, FOR_SPLIT, UNEVEN_SPACING
}

data class Finding(
    val findingId: String,
    val kind: FindingKind,
    val severity: Severity,
    val message: String,
    val objectIds: List<String>,
    val frameIds: List<String>,
    val quantities: List<NamedQuantity>,
    val evidenceIds: List<String>,
    val ruleVersion: Int
)

data class NamedQuantity(val name: String, val quantity: Quantity)

/** A candidate series/volume: the system never silently merges ambiguous material. */
data class SeriesCandidate(
    val candidateId: String,
    val keySeriesUid: String?,
    val frameOfReferenceUid: String?,
    val label: String,
    val frameIds: List<String>,      // spatial order (projection), never InstanceNumber order
    val ordering: List<Double>,      // projection offsets in mm along normal
    val medianSpacing: Quantity,
    val spacingResiduals: List<Double>, // signed (gap - median), mm
    val findingIds: List<String>,
    val accepted: Boolean? = null,   // null = unreviewed
    val ruleVersion: Int,
    val ambiguityKey: String? = null // candidates with same key are mutually exclusive alternatives
)

data class RuleParams(
    val orthogonalityToleranceDeg: Double = 1.0,
    val unitTolerance: Double = 1e-4,
    val jumpRatio: Double = 1.5,         // local gap / median spacing
    val duplicateToleranceMm: Double = 0.01
) {
    val version: Int get() = 1
    val paramsHash: String get() = sha1("v$version|$orthogonalityToleranceDeg|$unitTolerance|$jumpRatio|$duplicateToleranceMm")
}

data class ImportBatch(
    val batchId: String, val createdAt: Instant, val objectIds: List<String>,
    val source: String, val rolledBack: Boolean = false
)

enum class EventType {
    IMPORT, PARAM_REVISION, SOURCE_CHANGE, CANDIDATE_ACCEPTED, CANDIDATE_REJECTED,
    BRANCH_CREATED, CONFLICT_DETECTED, ROLLBACK
}

data class LedgerEvent(
    val eventId: String,
    val seq: Long,
    val branchId: String,
    val parentEventId: String?,
    val type: EventType,
    val message: String,
    val payloadJson: String,
    val ruleVersion: Int,
    val ruleParamsHash: String,
    val dependsOnObjectIds: List<String>,
    val dependsOnCandidateIds: List<String>,
    val createdBy: String,
    val createdAt: Instant,
    val supersedesEventId: String? = null
)

fun sha1(s: String): String =
    java.security.MessageDigest.getInstance("SHA-1").digest(s.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
