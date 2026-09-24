package gsb.geom

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.add
import kotlin.math.abs
import kotlin.math.sqrt

data class Vec3(val x: Double, val y: Double, val z: Double) {
    operator fun minus(o: Vec3) = Vec3(x - o.x, y - o.y, z - o.z)
    operator fun times(s: Double) = Vec3(x * s, y * s, z * s)
    infix fun dot(o: Vec3) = x * o.x + y * o.y + z * o.z
    infix fun cross(o: Vec3) = Vec3(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x)
    fun norm() = sqrt(this dot this)
    fun normalized(): Vec3 {
        val n = norm()
        return if (n == 0.0) this else this * (1.0 / n)
    }
    fun toList() = listOf(x, y, z)
}

/** 归组参数。所有派生结论都记录所依赖的参数键，参数修订时据此计算失效范围。 */
@Serializable
data class GroupParams(
    val orthoTolerance: Double = 1e-3,
    val normTolerance: Double = 1e-3,
    val duplicateToleranceMm: Double = 0.01,
    val spacingJumpThresholdMm: Double = 0.5,
    val version: Int = 1,
) {
    fun changedKeys(other: GroupParams): List<String> {
        val keys = mutableListOf<String>()
        if (orthoTolerance != other.orthoTolerance) keys += "orthoTolerance"
        if (normTolerance != other.normTolerance) keys += "normTolerance"
        if (duplicateToleranceMm != other.duplicateToleranceMm) keys += "duplicateToleranceMm"
        if (spacingJumpThresholdMm != other.spacingJumpThresholdMm) keys += "spacingJumpThresholdMm"
        return keys
    }

    companion object {
        val ALL_KEYS = listOf("orthoTolerance", "normTolerance", "duplicateToleranceMm", "spacingJumpThresholdMm")
    }
}

/**
 * 派生层的切片输入。来源层（原始 tag / 原始字节）与此分离：
 * 这里只保存从 DICOM 派生出的几何量，单位 mm，坐标基准为患者坐标系 (LPS)。
 */
data class SliceInput(
    val sourceId: String,
    val sourceName: String,
    val contentHash: String,
    val frameIndex: Int,
    val frameCount: Int,
    val sopUid: String?,
    val seriesUid: String?,
    val frameOfReferenceUid: String?,
    val position: Vec3?,
    val rowDir: Vec3?,
    val colDir: Vec3?,
    val pixelSpacingMm: Pair<Double, Double>?,
    val instanceNumber: Int?,
    val transferSyntax: String,
    val supported: Boolean,
    val deriveDiagnostics: List<String> = emptyList(),
)

data class SlicePlacement(
    val slice: SliceInput,
    val proj: Double?,
    val residualMm: Double?,
    val diagnostics: List<String>,
)

data class CandidateProposal(
    val kind: String,
    val reason: JsonObject,
    val depSourceIds: List<String>,
    val depParamKeys: List<String>,
    val slices: List<SlicePlacement>,
)

data class GeoEvent(val type: String, val payload: JsonObject)

data class GroupResult(val candidates: List<CandidateProposal>, val events: List<GeoEvent>)

object Grouper {

    fun group(slices: List<SliceInput>, params: GroupParams): GroupResult {
        val events = mutableListOf<GeoEvent>()
        val candidates = mutableListOf<CandidateProposal>()

        // 1) 传输语法不支持：保留对象与诊断，不参与几何归组
        val (unsupported, rest0) = slices.partition { !it.supported }
        unsupported.groupBy { it.sourceId }.forEach { (srcId, ss) ->
            events += GeoEvent("transfer_syntax.unsupported", buildJsonObject {
                put("source_id", srcId)
                put("source_name", ss.first().sourceName)
                put("transfer_syntax", ss.first().transferSyntax)
            })
            candidates += CandidateProposal(
                kind = "unsupported_ts",
                reason = buildJsonObject {
                    put("rule", "transfer syntax 不在支持集合内，保留原始字节，不做几何解释")
                    put("transfer_syntax", ss.first().transferSyntax)
                },
                depSourceIds = listOf(srcId),
                depParamKeys = emptyList(),
                slices = ss.map { SlicePlacement(it, null, null, it.deriveDiagnostics + "unsupported_transfer_syntax") },
            )
        }

        // 2) UID 冲突：同一 SOPInstanceUID 对应不同内容 → 冲突分支，绝不合并
        val sopGroups = rest0.filter { it.sopUid != null }.groupBy { it.sopUid!! }
        val conflicted = sopGroups.filter { (_, ss) -> ss.map { it.contentHash }.distinct().size > 1 }
        var rest = rest0
        if (conflicted.isNotEmpty()) {
            val conflictSlices = conflicted.values.flatten().toSet()
            rest = rest0.filter { it !in conflictSlices }
            conflicted.forEach { (uid, ss) ->
                events += GeoEvent("geometry.uid_conflict", buildJsonObject {
                    put("sop_instance_uid", uid)
                    putJsonArray("sources") { ss.forEach { add(it.sourceId) } }
                    put("distinct_contents", ss.map { it.contentHash }.distinct().size)
                })
                candidates += CandidateProposal(
                    kind = "uid_conflict",
                    reason = buildJsonObject {
                        put("rule", "同一 SOPInstanceUID 映射到不同内容，作为冲突分支保留，等待人工裁定")
                        put("sop_instance_uid", uid)
                    },
                    depSourceIds = ss.map { it.sourceId }.distinct(),
                    depParamKeys = emptyList(),
                    slices = ss.map { SlicePlacement(it, null, null, it.deriveDiagnostics + "uid_conflict") },
                )
            }
        }

        // 3) 缺失几何：没有 IPP/IOP 的对象单独成候选，不猜测位置
        val (missing, geo) = rest.partition { it.position == null || it.rowDir == null || it.colDir == null }
        missing.groupBy { it.seriesUid ?: "<none>" }.forEach { (uid, ss) ->
            events += GeoEvent("geometry.missing_geometry", buildJsonObject {
                put("series_instance_uid", uid)
                putJsonArray("sources") { ss.forEach { add(it.sourceId) } }
            })
            candidates += CandidateProposal(
                kind = "no_geometry",
                reason = buildJsonObject {
                    put("rule", "缺少 ImagePositionPatient/ImageOrientationPatient，无法定位，保留证据等待裁定")
                    put("series_instance_uid", uid)
                },
                depSourceIds = ss.map { it.sourceId }.distinct(),
                depParamKeys = emptyList(),
                slices = ss.map { SlicePlacement(it, null, null, it.deriveDiagnostics + "missing_geometry") },
            )
        }

        // 4) 空间归组：SeriesInstanceUID + FrameOfReferenceUID 为键；不同 FoR 永不合并
        geo.groupBy { (it.seriesUid ?: "<none>") to (it.frameOfReferenceUid ?: "<none>") }
            .forEach { (key, ss) -> groupSpatial(key, ss, params, events, candidates) }

        return GroupResult(candidates, events)
    }

    private fun groupSpatial(
        key: Pair<String, String>,
        slices: List<SliceInput>,
        params: GroupParams,
        events: MutableList<GeoEvent>,
        candidates: MutableList<CandidateProposal>,
    ) {
        val (seriesUid, forUid) = key
        // 方向向量先做正交与容差检查
        val diagBySlice = mutableMapOf<SliceInput, MutableList<String>>()
        fun diag(s: SliceInput) = diagBySlice.getOrPut(s) { s.deriveDiagnostics.toMutableList() }
        for (s in slices) {
            val row = s.rowDir!!; val col = s.colDir!!
            val dot = row dot col
            val normErr = maxOf(abs(row.norm() - 1.0), abs(col.norm() - 1.0))
            if (abs(dot) > params.orthoTolerance || normErr > params.normTolerance) {
                diag(s) += "non_orthogonal"
                events += GeoEvent("geometry.non_orthogonal", buildJsonObject {
                    put("source_id", s.sourceId)
                    put("frame_index", s.frameIndex)
                    put("row_dot_col", dot)
                    put("norm_error", normErr)
                    put("ortho_tolerance", params.orthoTolerance)
                    put("norm_tolerance", params.normTolerance)
                })
            }
        }
        val multiFrameSources = slices.filter { it.frameCount > 1 }.map { it.sourceId }.distinct()
        multiFrameSources.forEach { src ->
            events += GeoEvent("geometry.multiframe", buildJsonObject {
                put("source_id", src)
                put("frames", slices.first { it.sourceId == src }.frameCount)
            })
        }

        // 空间排序真值是投影坐标，不是 InstanceNumber
        val normal = (slices.first().rowDir!! cross slices.first().colDir!!).normalized()
        fun projOf(s: SliceInput) = s.position!! dot normal
        val sorted = slices.sortedBy { projOf(it) }
        val byInstance = slices.sortedBy { it.instanceNumber ?: 0 }
        if (byInstance != sorted && slices.all { it.instanceNumber != null }) {
            events += GeoEvent("geometry.instance_number_not_truth", buildJsonObject {
                put("series_instance_uid", seriesUid)
                put("rule", "InstanceNumber 顺序与空间投影顺序不一致，按投影坐标排序")
                putJsonArray("projection_order") { sorted.forEach { add(it.sourceName + "#" + it.frameIndex) } }
                putJsonArray("instance_number_order") { byInstance.forEach { add(it.sourceName + "#" + it.frameIndex) } }
            })
        }

        // 重复位置：产生多候选分支，保留全部合法候选
        val duplicateClusters = mutableListOf<List<SliceInput>>()
        var i = 0
        while (i < sorted.size) {
            var j = i
            while (j + 1 < sorted.size && abs(projOf(sorted[j + 1]) - projOf(sorted[i])) <= params.duplicateToleranceMm) j++
            if (j > i) duplicateClusters += sorted.subList(i, j + 1).toList()
            i = j + 1
        }

        val variants: List<Pair<List<SliceInput>, String?>> = if (duplicateClusters.isEmpty()) {
            listOf(sorted to null)
        } else {
            val cluster = duplicateClusters.first()
            events += GeoEvent("geometry.duplicate_position", buildJsonObject {
                put("series_instance_uid", seriesUid)
                put("position_proj_mm", projOf(cluster.first()))
                put("duplicate_tolerance_mm", params.duplicateToleranceMm)
                putJsonArray("sources") { cluster.forEach { add(it.sourceId) } }
                put("variants", cluster.size)
            })
            cluster.map { kept -> (sorted - cluster.toSet() + kept).sortedBy { projOf(it) } to kept.sourceId }
        }

        for ((variant, keptSource) in variants) {
            val placements = computeSpacing(variant, params, diagBySlice, normal, events, seriesUid)
            val kind = when {
                keptSource != null -> "duplicate_position_branch"
                multiFrameSources.isNotEmpty() -> "multiframe_volume"
                else -> "series_volume"
            }
            // 间距跳变 → 拆成分支候选，不强合
            val jumpAfter = placements.zipWithNext()
                .mapIndexedNotNull { idx, (a, b) -> if ((b.residualMm ?: 0.0).let { abs(it) > params.spacingJumpThresholdMm }) idx else null }
            if (jumpAfter.isEmpty() || variant.size < 2) {
                candidates += CandidateProposal(
                    kind = kind,
                    reason = buildJsonObject {
                        put("rule", "按 SeriesInstanceUID+FrameOfReferenceUID 归组，以投影坐标排序")
                        put("series_instance_uid", seriesUid)
                        put("frame_of_reference_uid", forUid)
                        put("sort_key", "projection_along_normal_mm")
                        if (keptSource != null) put("kept_duplicate_source", keptSource)
                    },
                    depSourceIds = variant.map { it.sourceId }.distinct(),
                    depParamKeys = GroupParams.ALL_KEYS,
                    slices = placements,
                )
            } else {
                val segments = mutableListOf<List<SlicePlacement>>()
                var start = 0
                for (idx in jumpAfter) { segments += placements.subList(start, idx + 1); start = idx + 1 }
                segments += placements.subList(start, placements.size)
                segments.filter { it.isNotEmpty() }.forEachIndexed { segIdx, seg ->
                    candidates += CandidateProposal(
                        kind = "series_volume_split",
                        reason = buildJsonObject {
                            put("rule", "局部间距跳变超过阈值，拆分为分支候选而不强合")
                            put("series_instance_uid", seriesUid)
                            put("frame_of_reference_uid", forUid)
                            put("segment_index", segIdx)
                            put("spacing_jump_threshold_mm", params.spacingJumpThresholdMm)
                        },
                        depSourceIds = seg.map { it.slice.sourceId }.distinct(),
                        depParamKeys = GroupParams.ALL_KEYS,
                        slices = seg,
                    )
                }
            }
        }
    }

    private fun computeSpacing(
        sorted: List<SliceInput>,
        params: GroupParams,
        diagBySlice: Map<SliceInput, MutableList<String>>,
        normal: Vec3,
        events: MutableList<GeoEvent>,
        seriesUid: String,
    ): List<SlicePlacement> {
        fun projOf(s: SliceInput) = s.position!! dot normal
        if (sorted.size < 2) {
            return sorted.map { SlicePlacement(it, projOf(it), null, diagBySlice[it] ?: it.deriveDiagnostics) }
        }
        val diffs = sorted.zipWithNext().map { (a, b) -> projOf(b) - projOf(a) }
        val nominal = diffs.sorted()[diffs.size / 2]
        val residuals = diffs.map { it - nominal }
        residuals.forEachIndexed { idx, res ->
            if (abs(res) > params.spacingJumpThresholdMm) {
                events += GeoEvent("geometry.spacing_jump", buildJsonObject {
                    put("series_instance_uid", seriesUid)
                    put("gap_mm", diffs[idx])
                    put("nominal_mm", nominal)
                    put("residual_mm", res)
                    put("threshold_mm", params.spacingJumpThresholdMm)
                    put("before_source", sorted[idx].sourceId)
                    put("after_source", sorted[idx + 1].sourceId)
                })
            }
        }
        return sorted.mapIndexed { idx, s ->
            SlicePlacement(
                slice = s,
                proj = projOf(s),
                residualMm = if (idx == 0) null else residuals[idx - 1],
                diagnostics = diagBySlice[s] ?: s.deriveDiagnostics,
            )
        }
    }
}
