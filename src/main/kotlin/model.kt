package bench

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * 领域模型。
 *
 * 分层约定（贯穿全系统）：
 *  1. source   —— 不可变原始材料：tag 原始表示 + 原始像素字节，只追加。
 *  2. interpret —— 对某一版 source 的解释（规范化结果），可重建、可多版本共存。
 *  3. derived  —— 规则版本 + 解释版本之上的几何候选计算，带依赖集合与失效范围。
 *  4. event    —— 追加式事件（导入 / 源数据改动 / 参数修订 / 裁定 / 撤回 / 冲突），组成分支。
 *  5. readmodel—— 在任意事件点重放出的可见结论，绝不覆盖历史。
 *
 * 所有领域量携带单位或坐标基准；无法转换的值留在原位置并附诊断。
 */

// ---------- 稳定身份 ----------

/** 基于内容的稳定身份：导出、删库、重导入后保持不变。 */
fun stableId(prefix: String, vararg contentParts: String): String {
    val h = sha256Hex(contentParts.joinToString("") { it })
    return "${prefix}_${h.take(20)}"
}

// ---------- 几何 ----------

/** 三维向量（带坐标基准说明，基准写在所属对象上）。 */
@Serializable
data class Vec3(val x: Double, val y: Double, val z: Double) {
    operator fun minus(o: Vec3) = Vec3(x - o.x, y - o.y, z - o.z)
    operator fun plus(o: Vec3) = Vec3(x + o.x, y + o.y, z + o.z)
    operator fun times(s: Double) = Vec3(x * s, y * s, z * s)
    fun dot(o: Vec3) = x * o.x + y * o.y + z * o.z
    fun cross(o: Vec3) = Vec3(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x)
    fun norm() = kotlin.math.sqrt(dot(this))
    fun normalized(): Vec3 {
        val n = norm()
        return if (n == 0.0) Vec3(0.0, 0.0, 0.0) else Vec3(x / n, y / n, z / n)
    }

    companion object {
        val ZERO = Vec3(0.0, 0.0, 0.0)
    }
}

/**
 * 切片平面（派生层规范化结果）。
 * row/col 为方向余弦，normal = row × col；origin 为 ImagePositionPatient（毫米，患者坐标系）。
 * frameId 指向具体帧（多帧对象形如 <instanceStableId>#f<n>）。
 */
@Serializable
data class SlicePlane(
    val frameId: String,
    val instanceStableId: String,
    val originMm: Vec3,
    val rowDir: Vec3,
    val colDir: Vec3,
    val normal: Vec3,
    val pixelSpacingRowMm: Double,
    val pixelSpacingColMm: Double,
    val rows: Int,
    val cols: Int,
    val frameOfReferenceUid: String?,
    val coordinateBasis: String = "DICOM Patient Coordinate System, millimetre"
) {
    /** 沿堆叠法线的投影位置（毫米）。 */
    fun projectionAlong(n: Vec3): Double = originMm.dot(n)
}

/** 间距残差：相邻帧沿法线方向的实际间隔与期望间隔之差。 */
@Serializable
data class SpacingResidual(
    val id: String,
    val fromFrameId: String,
    val toFrameId: String,
    val indexInOrder: Int,
    val expectedSpacingMm: Double?,
    val actualSpacingMm: Double,
    val residualMm: Double?,
    val kind: String // GAP | DUPLICATE | REVERSED | REGULAR
)

/** 单个候选的几何诊断（带规则 id 与严重度，机器可读）。 */
@Serializable
data class GeometryIssue(
    val id: String,
    val ruleId: String,
    val severity: String, // ERROR | WARN | INFO
    val message: String,
    val frameIds: List<String> = emptyList(),
    val measured: String? = null, // 带单位的测量值
    val expected: String? = null
)

// ---------- 来源材料（导入层） ----------

/** 一个 DICOM tag 的原始保留表示。 */
@Serializable
data class RawTag(
    val groupHex: String,      // "0020"
    val elementHex: String,    // "0032"
    val tagHex: String,        // "(0020,0032)"
    val name: String,          // 已知名（可能为 Unknown Tag）
    val vr: String?,           // 原 VR，可能未知
    val rawValue: String,      // 原始字符串表示（规范化前）
    val multiValue: List<String>? = null
)

/**
 * 单实例材料（一个 SOP）。多帧对象通过 functionalGroupJson / perFrameJson 保留
 * (5200,9229) SharedFunctionalGroupsSequence 与 (5200,9230) PerFrameFunctionalGroupsSequence。
 */
@Serializable
data class InstanceMaterial(
    val stableId: String,
    val sopClassUid: String?,
    val sopInstanceUid: String?,
    val seriesInstanceUid: String?,
    val frameOfReferenceUid: String?,
    val transferSyntaxUid: String?,
    val instanceNumber: String?,
    val numberOfFrames: Int?,
    val tags: List<RawTag>,
    val functionalGroupJson: JsonObject?,
    val perFrameJson: List<JsonObject>?,
    val pixelFile: String?,      // 相对 pack 的路径（像素与 metadata 分开保留）
    val pixelSha256: String?,
    val pixelBytes: Long,
    val importedAt: String
) {
    fun tag(g: String, e: String): RawTag? =
        tags.firstOrNull { it.groupHex.equals(g, true) && it.elementHex.equals(e, true) }
}

/** 一次导入的清单（pack-level）。 */
@Serializable
data class ImportBatch(
    val importId: String,
    val packName: String,
    val packVersion: String,
    val manifestSha256: String,
    val startedAt: String,
    val committedAt: String?,
    val status: String, // COMMITTED | ROLLED_BACK
    val instanceCount: Int
)

// ---------- 解释层（版本化，不改来源） ----------

/** 帧几何解释；缺几何时 geometry=null 且诊断保留在原位。 */
@Serializable
data class FrameInterpretation(
    val frameId: String,
    val instanceStableId: String,
    val frameIndex: Int?,
    val geometry: SlicePlane?,
    /** 无参数的几何测量（与规则版本无关）：row/col 范数与点积，供候选层做容差判定。 */
    val rowNorm: Double? = null,
    val colNorm: Double? = null,
    val dirDot: Double? = null,
    val status: String, // OK | MISSING_GEOMETRY | PARSE_ERROR | UNSUPPORTED_TRANSFER_SYNTAX
    val diagnostics: List<GeometryIssue>,
    val evidence: List<TagEvidence>
)

@Serializable
data class TagEvidence(
    val tagHex: String,
    val name: String,
    val vr: String?,
    val rawValue: String,
    val unitOrBasis: String?,
    val sourcePath: String // metadata 路径，如 tags/(0020,0037) 或 PerFrameFunctionalGroupsSequence[2]/...
)

@Serializable
data class Interpretation(
    val interpretationId: String,
    val importId: String,
    val instanceStableId: String,
    val transferSyntaxSupported: Boolean,
    val frames: List<FrameInterpretation>,
    val createdAt: String
)

// ---------- 规则与候选（派生层） ----------

@Serializable
data class RuleSet(
    val version: Int,
    val orthogonalityTolerance: Double, // 方向向量非正交判定阈值（无量纲，|row·col| 上限）
    val unitVectorTolerance: Double,    // 单位长度容差
    val gapTolerance: Double,           // 局部间距跳变阈值（毫米残差）
    val duplicateToleranceMm: Double,   // 重片位置容差（毫米）
    val useInstanceNumberAsOrder: Boolean, // 永远 false：InstanceNumber 不当空间排序真值
    val createdAt: String,
    val note: String
)

/** series/volume 候选：多解时保留全部合法候选，不强合。 */
@Serializable
data class SeriesCandidate(
    val candidateId: String,
    val branchSeed: String,              // 产生多解的分支原因码
    val seriesInstanceUid: String?,
    val frameOfReferenceUid: String?,
    val memberFrameIds: List<String>,
    val orderedFrameIds: List<String>,
    val orderingBasis: String,           // SPATIAL_PROJECTION | PROJECTION_TIE
    val instanceNumberTieBreak: Boolean,
    val spacingResiduals: List<SpacingResidual>,
    val medianSpacingMm: Double?,
    val issues: List<GeometryIssue>,
    val excludedFrameIds: List<String>,  // 非正交/缺几何/不支持传输语法 -> 不入体
    val dependencyIds: List<String>,     // 失效范围：解释 id + 规则版本
    val computedAtEventId: String?
)

// ---------- 事件 / 分支 ----------

@Serializable
data class Event(
    val eventId: String,
    val parentEventId: String?,
    val branch: String,
    val seq: Long,
    val type: String,
    // IMPORT | SOURCE_MUTATION | PARAMETER_REVISION | CANDIDATE_ACCEPTED |
    // CANDIDATE_REJECTED | PARAMETER_ADJUSTMENT | DECISION_WITHDRAWN | VERSION_CONFLICT
    val at: String,
    val actor: String,
    val payload: JsonObject,
    val baseVersion: String?,   // 乐观锁：提交时分支 tip
    val derivedInvalidationIds: List<String> = emptyList()
)

@Serializable
data class DecisionRecord(
    val id: String,
    val eventId: String,
    val branch: String,
    val candidateId: String,
    val action: String,         // ACCEPT | REJECT
    val rationale: String,
    val active: Boolean,
    val withdrawnByEventId: String?,
    val candidateSnapshot: SeriesCandidate
)

@Serializable
data class ReplayAssertion(
    val id: String,
    val replayRunId: String,
    val caseId: String,
    val ruleId: String?,
    val expected: String,
    val actual: String,
    val passed: Boolean,
    val sourceChain: String,    // 材料 -> 解释 -> 候选 -> 事件 的稳定 id 链
    val invalidationScope: String
)

@Serializable
data class ValueIdentity(
    val valueId: String,
    val kind: String,
    val value: JsonElement,
    val unitOrBasis: String?,
    val derivedFrom: List<String>
)
