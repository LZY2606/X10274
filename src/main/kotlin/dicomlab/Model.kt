package dicomlab

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.security.MessageDigest

fun stableId(prefix: String, vararg parts: String?): String {
    val md = MessageDigest.getInstance("SHA-1")
    val raw = parts.joinToString("|") { it ?: "∅" }
    val hex = md.digest(raw.toByteArray()).joinToString("") { "%02x".format(it) }
    return prefix + "_" + hex.take(20)
}

val jsonFmt = Json { prettyPrint = true; ignoreUnknownKeys = true }

/** One row of the immutable raw layer: a single-frame object or one frame of a multi-frame object. */
data class InstanceRec(
    val id: String,
    val importId: String,
    val sliceKey: String,
    val parentInstanceId: String?,
    val sopInstanceUid: String?,
    val seriesInstanceUid: String?,
    val frameOfReferenceUid: String?,
    val transferSyntaxUid: String?,
    val instanceNumber: Int?,
    val imagePositionPatient: String?,   // raw, mm in LPS; never rewritten
    val imageOrientationPatient: String?,// raw, direction cosines in LPS
    val pixelSpacing: String?,           // raw, mm
    val multiframeJson: String?,
    val rawTagsJson: String
)

data class CandidateRec(
    val id: String,
    val interpretationId: String,
    val kind: String,        // series | volume
    val branchKey: String,
    val label: String,
    val status: String,      // proposed | accepted | rejected
    val sortIndex: Int,
    val members: List<MemberRec> = emptyList()
)

data class MemberRec(
    val candidateId: String,
    val instanceId: String,
    val projection: Double?,   // mm along stack normal
    val spacingResidual: Double?, // mm vs median spacing of the candidate
    val sortIndex: Int
)

data class DiagnosticRec(
    val id: String,
    val interpretationId: String,
    val instanceId: String?,
    val code: String,
    val message: String,
    val detailJson: String
)

data class EventRec(
    val id: String,
    val interpretationId: String,
    val seq: Int,
    val type: String,   // INTERPRET | DECIDE | UNDO | PARAM_REVISION | SOURCE_PATCH
    val actor: String,
    val payloadJson: String,
    val snapshotJson: String,
    val createdAt: String
)

data class InterpretationRec(
    val id: String,
    val importId: String,
    val ruleSetId: String,
    val parentId: String?,
    val label: String,
    val createdAt: String,
    val version: Int,
    val note: String = ""
)

// ---------- offline case package ----------

@Serializable
data class CaseInstance(
    val sopInstanceUid: String? = null,
    val seriesInstanceUid: String? = null,
    val frameOfReferenceUid: String? = null,
    val transferSyntaxUid: String? = null,
    val instanceNumber: Int? = null,
    val imagePositionPatient: String? = null,
    val imageOrientationPatient: String? = null,
    val pixelSpacing: String? = null,
    val sliceThickness: String? = null,
    val rows: Int? = null,
    val columns: Int? = null,
    val group: String = "baseline",
    val note: String = "",
    val extraTags: Map<String, String> = emptyMap(),
    val multiframe: MultiFrame? = null
)

@Serializable
data class MultiFrame(
    val numberOfFrames: Int,
    val shared: Map<String, String> = emptyMap(),
    val perFrame: List<Map<String, String>> = emptyList()
)

@Serializable
data class CaseStage(val stage: String, val note: String)

@Serializable
data class CasePackage(
    val caseId: String,
    val title: String,
    val narrative: String,
    val coordinateBasis: String = "LPS patient frame, millimetres",
    val ruleSetName: String,
    val ruleSetVersion: Int,
    val ruleParams: RuleParams,
    val instances: List<CaseInstance>,
    val stages: List<CaseStage> = emptyList(),
    val expectedDiagnostics: List<String> = emptyList()
) {
    companion object {
        fun parse(s: String): CasePackage = jsonFmt.decodeFromString(s)
    }
}

/** Machine-readable judgement basis attached to every INTERPRET event. */
@Serializable
data class JudgementBasis(
    val sortTruth: String = "spatialProjection",   // InstanceNumber is never the spatial sort truth
    val instanceNumberUsedForSorting: Boolean = false,
    val orientationCheckedFirst: Boolean = true,
    val orthogonalityTolerance: Double,
    val duplicateToleranceMm: Double,
    val spacingJumpFactor: Double,
    val branchPolicy: String = "spacing-jump/duplicate-position/frame-of-reference produce branches, never forced merges",
    val unresolvedAmbiguityPolicy: String = "all legal candidates kept until human adjudication",
    val branches: List<String>,
    val diagnostics: List<String>
)
