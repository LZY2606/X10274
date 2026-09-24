package gsb.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * 来源层（raw）与解释层（derived）严格分开。
 * 这里的类型同时用于 SQLite 持久化、HTTP 传输与档案导出。
 * 所有对界面暴露的值对象都带稳定身份（id / fingerprint）。
 */

// ---------- 来源层：去标识 DICOM 材料（原始 tag 全保留） ----------

@Serializable
data class RawTag(
    val group: Int,
    val element: Int,
    val vr: String,
    val keyword: String? = null,
    val raw: String,
    /** SQ 序列的每个 data set item（多帧 functional group 用到）。 */
    val items: List<List<RawTag>>? = null
) {
    val tagCode: String get() = "(%04X,%04X)".format(group, element)
}

@Serializable
data class MaterialEnvelope(
    /** 包内稳定键（如 ax_000）；进入内容哈希，重放/补丁用它定位“同一份材料”。 */
    val localKey: String,
    val pixelFile: String? = null,
    val pixelLength: Int? = null,
    val tags: List<RawTag>
)

@Serializable
data class MaterialInput(
    val envelope: MaterialEnvelope,
    val pixelBytes: ByteArray? = null
) {
    override fun equals(other: Any?): Boolean = other is MaterialInput &&
        other.envelope == envelope && other.pixelBytes.contentEqualsOrNull(pixelBytes)
    override fun hashCode(): Int = envelope.hashCode() * 31 + (pixelBytes?.contentHashCode() ?: 0)
}

private fun ByteArray?.contentEqualsOrNull(other: ByteArray?): Boolean =
    if (this == null || other == null) this == other else this.contentEquals(other)

// ---------- 规则参数（内容寻址，版本化） ----------

@Serializable
data class RuleParams(
    /** 行/列方向余弦点积绝对值上限（正交容差，无量纲）。 */
    val orthogonalityMaxDot: Double = 0.05,
    /** 相邻间距与中位间距之比的允许上限（局部间距跳变容差）。 */
    val jumpRatioMax: Double = 2.5,
    /** 判定重片的位置差上限，单位 mm（患者坐标系）。 */
    val duplicateEpsilonMm: Double = 0.05
) {
    companion object {
        val DEFAULT = RuleParams()
    }
}

@Serializable
data class RuleSet(
    val id: String,
    val params: RuleParams,
    val note: String = ""
)

// ---------- 事件日志（唯一可变事实源，append-only，哈希链） ----------

@Serializable
data class EventRecord(
    val seq: Long,
    val id: String,
    val branch: String,
    /** 分叉基点事件；同分支线性追加时为上一事件。 */
    val parentEventId: String?,
    val type: String, // IMPORT / RULE_REVISION / SOURCE_PATCH / DECISION
    val payload: JsonObject,
    val wallClockIso: String,
    val prevHash: String,
    val contentHash: String
)

// ---------- 解释层 DTO（每个值带稳定身份） ----------

@Serializable
data class Tagged(
    val raw: String,
    val normalized: String? = null,
    val unit: String? = null,
    /** 坐标/数值基准，如 DICOM patient coordinate system (mm, LPS)。 */
    val basis: String? = null,
    val diagnostic: String? = null,
    val tagId: String
)

@Serializable
data class Vec3Dto(val x: Double, val y: Double, val z: Double)

@Serializable
data class TagEvidence(
    val id: String,
    val tagCode: String,
    val vr: String,
    val keyword: String?,
    val raw: String,
    val path: String,
    val interpreted: Tagged? = null
)

@Serializable
data class Finding(
    val id: String,
    val code: String,
    val severity: String, // INFO / WARN / ERROR
    val message: String,
    val frameIds: List<String> = emptyList(),
    val materialIds: List<String> = emptyList(),
    val candidateFingerprint: String? = null
)

@Serializable
data class FrameOrderEntry(
    val frameId: String,
    /** 沿法向的投影坐标，mm（患者坐标系）。 */
    val projectionTMm: Double,
    val instanceNumber: String?,
    /** InstanceNumber 顺序是否与空间顺序一致；InstanceNumber 永远不当真值。 */
    val agreesWithInstanceNumber: Boolean?
)

@Serializable
data class SpacingResidual(
    val id: String,
    val fromFrameId: String,
    val toFrameId: String,
    val deltaMm: Double,
    val baselineMm: Double,
    val ratio: Double,
    val level: String // OK / WARN / ERROR
)

@Serializable
data class CandidateView(
    val fingerprint: String,
    val groupKey: String,
    val hypothesis: String,
    // SPAN_CLEAN / SPAN_FLAGGED / SEGMENT / DUP_PICK / DUP_UNRESOLVED / UNPLACEABLE / NONORTHOGONAL
    val frameIds: List<String>,
    val orderByT: List<FrameOrderEntry>,
    val spacing: List<SpacingResidual>,
    val duplicateSets: List<List<String>>,
    val jumpBetween: List<List<String>>,
    val frameOfReferenceUid: String?,
    val normal: Vec3Dto?,
    val seriesUidHints: List<String>,
    val completeVolume: Boolean,
    val pickedDuplicates: Map<String, String>,
    val findings: List<Finding>,
    val ruleSetId: String
)

@Serializable
data class DecisionView(
    val eventId: String,
    val seq: Long,
    val branch: String,
    val candidateFingerprint: String,
    val action: String, // ACCEPT / REJECT / REVOKE
    val rationale: String,
    val baseSeq: Long,
    val status: String, // LIVE / SUPERSEDED / REVOKED
    val supersedeReason: String? = null
)

@Serializable
data class EventView(
    val seq: Long,
    val id: String,
    val branch: String,
    val parentEventId: String?,
    val type: String,
    val payload: JsonObject,
    val wallClockIso: String,
    val onCurrentPath: Boolean
)

@Serializable
data class BranchInfo(val name: String, val forkEventId: String?, val parentBranch: String?)

@Serializable
data class MaterialView(
    val id: String,
    val localKey: String,
    val sopInstanceUid: String?,
    val seriesInstanceUid: String?,
    val frameOfReferenceUid: String?,
    val transferSyntaxUid: String?,
    val pixelRenderable: Boolean,
    val frames: Int
)

@Serializable
data class StateView(
    val branch: String,
    val tipSeq: Long,
    val atEventId: String?,
    val historical: Boolean,
    val branches: List<BranchInfo>,
    val ruleSet: RuleSet,
    val materials: List<MaterialView>,
    val tagEvidence: List<TagEvidence>,
    val frames: List<FrameView>,
    val groups: List<GroupView>,
    val candidates: List<CandidateView>,
    val decisions: List<DecisionView>,
    val events: List<EventView>,
    val pathEventIds: List<String>,
    val findings: List<Finding>
)

@Serializable
data class GroupView(
    val key: String,
    val kind: String, // PLACED / NONORTHOGONAL / UNPLACEABLE
    val frameIds: List<String>,
    val frameOfReferenceUid: String?,
    val normal: Vec3Dto?
)

@Serializable
data class FrameView(
    val id: String,
    val materialId: String,
    val localKey: String,
    val frameIndex: Int,
    val sopInstanceUid: Tagged?,
    val seriesInstanceUid: Tagged?,
    val frameOfReferenceUid: Tagged?,
    val instanceNumber: Tagged?,
    val transferSyntaxUid: Tagged,
    val pixelRenderable: Boolean,
    val pixelLength: Int?,
    val imagePositionPatient: Tagged?,
    val imageOrientationPatient: Tagged?,
    val pixelSpacing: Tagged?,
    val functionalGroupTagId: String?,
    val placement: String, // PLACED / NONORTHOGONAL / UNPLACEABLE
    val origin: Vec3Dto?,
    val rowCos: Vec3Dto?,
    val colCos: Vec3Dto?,
    val normal: Vec3Dto?,
    val orthogonalityDot: Double?,
    val rowSpacingMm: Double?,
    val colSpacingMm: Double?,
    val findings: List<Finding>
)

@Serializable
data class VersionConflict(
    val error: String = "VERSION_CONFLICT",
    val message: String,
    val baseSeq: Long,
    val currentTipSeq: Long,
    val branch: String,
    val submitted: JsonObject,
    val competing: EventView
)
