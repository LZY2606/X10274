package gsb.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.MessageDigest

val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
val prettyJson = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }

fun sha256(text: String): String =
    MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

/** 原始 DICOM tag：导入时原样保留，绝不在导入层清洗。 */
@Serializable
data class RawTag(
    val g: String,
    val e: String,
    val vr: String,
    val value: String? = null,
    val items: List<List<RawTag>>? = null,
) {
    val key: String get() = "($g,$e)"
}

/** 一个去标识化的 DICOM 文件（metadata + 像素字节）。 */
@Serializable
data class InstanceFile(
    val fileName: String,
    val transferSyntaxUid: String,
    val pixelDataBase64: String,
    val amends: String? = null,
    val tags: List<RawTag>,
)

@Serializable
data class Vec3(val x: Double, val y: Double, val z: Double) {
    operator fun minus(o: Vec3) = Vec3(x - o.x, y - o.y, z - o.z)
    fun dot(o: Vec3) = x * o.x + y * o.y + z * o.z
    fun cross(o: Vec3) = Vec3(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x)
    fun norm() = kotlin.math.sqrt(dot(this))
    fun scale(s: Double) = Vec3(x * s, y * s, z * s)
    fun fmt() = "(%.4f, %.4f, %.4f)".format(x, y, z)
}

/** 诊断：无法转换的值留在原位置并附诊断，不为画图丢弃。 */
@Serializable
data class Diagnostic(val code: String, val message: String, val rawValue: String? = null)

/** 派生层几何：单位 mm，坐标基准为 DICOM 患者坐标系（LPS），由 forUid 标识。 */
@Serializable
data class FrameGeometry(
    val frameId: String,
    val instanceId: String,
    val frameIndex: Int,
    val ippMm: Vec3? = null,
    val iopRow: Vec3? = null,
    val iopCol: Vec3? = null,
    val pixelSpacingMm: List<Double>? = null, // [row, col]，单位 mm
    val forUid: String? = null,
    val sopUid: String? = null,
    val seriesUid: String? = null,
    val instanceNumber: Int? = null,
    val diagnostics: List<Diagnostic> = emptyList(),
) {
    val partitionKey: String get() = "${seriesUid ?: "?"}|${forUid ?: "?"}"
}

@Serializable
data class Params(
    val version: Int,
    val orthogonalityTolerance: Double = 1e-3,
    val duplicatePositionEpsilonMm: Double = 1e-3,
    val spacingJumpFactor: Double = 1.5,
    val spacingResidualToleranceMm: Double = 0.01,
    val axisClusterTolerance: Double = 1e-3,
)

@Serializable
data class Candidate(
    val id: String,
    val kind: String, // volume | duplicate-variant | unpositioned
    val frameIds: List<String>,
    val residualsMm: List<Double> = emptyList(), // 每个相邻间距相对中位数的残差，单位 mm
    val flags: List<String> = emptyList(),
    val rationale: String,
)

@Serializable
data class CandidateSet(
    val id: String,
    val partitionKey: String, // seriesUid|forUid
    val ruleVersion: Int,
    val paramVersion: Int,
    val candidates: List<Candidate>,
    val diagnostics: List<Diagnostic> = emptyList(),
)

@Serializable
data class Adjudication(
    val id: String,
    val candidateId: String,
    val decision: String, // accept | reject
    val comment: String,
    val version: Int,
    val createdSeq: Long,
)

@Serializable
data class Event(
    val seq: Long,
    val id: String,
    val type: String,
    val payload: String,
    val ruleVersion: Int,
    val paramVersion: Int,
    val createdAt: String,
)

@Serializable
data class InstanceRow(
    val id: String,
    val fileName: String,
    val transferSyntax: String,
    val pixelB64: String,
    val tagsJson: String,
    val contentHash: String,
    val stage: String,
    val amendedBy: String?,
    val importedSeq: Long,
) {
    fun file(): InstanceFile = json.decodeFromString(tagsJson)
}

@Serializable
data class CandidateSetRow(
    val id: String,
    val partitionKey: String,
    val ruleVersion: Int,
    val paramVersion: Int,
    val setJson: String,
    val createdSeq: Long,
    val supersededSeq: Long?,
) {
    fun set(): CandidateSet = json.decodeFromString(setJson)
}

@Serializable
data class AdjudicationRow(
    val id: String,
    val candidateId: String,
    val decision: String,
    val comment: String,
    val version: Int,
    val createdSeq: Long,
    val undoneSeq: Long?,
)

/** 导出包：身份、顺序与来源关系全部显式保存。 */
@Serializable
data class ExportBundle(
    val formatVersion: Int = 1,
    val meta: Map<String, String>,
    val events: List<Event>,
    val instances: List<InstanceRow>,
    val frames: List<FrameRow>,
    val params: List<ParamRow>,
    val candidateSets: List<CandidateSetRow>,
    val adjudications: List<AdjudicationRow>,
    val dependencies: List<DependencyRow>,
)

@Serializable
data class FrameRow(
    val id: String,
    val instanceId: String,
    val frameIndex: Int,
    val geomJson: String,
    val derivedSeq: Long,
) {
    fun geom(): FrameGeometry = json.decodeFromString(geomJson)
}

@Serializable
data class ParamRow(val version: Int, val jsonText: String, val createdSeq: Long)

@Serializable
data class DependencyRow(val candidateSetId: String, val instanceId: String)
