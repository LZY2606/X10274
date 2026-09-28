package bench

import kotlin.math.abs

object RuleSets {
    fun initial(at: String = nowIso()): RuleSet = RuleSet(
        version = 1,
        orthogonalityTolerance = 1e-4,
        unitVectorTolerance = 1e-4,
        gapTolerance = 0.5,
        duplicateToleranceMm = 0.05,
        useInstanceNumberAsOrder = false,
        createdAt = at,
        note = "initial rule set"
    )

    fun revise(base: RuleSet, patch: RuleSet, at: String): RuleSet =
        patch.copy(version = base.version + 1, createdAt = at)
}

/**
 * 候选分组器。输入：所有解释（含实例级 UID 冲突信息）+ 当版规则集。
 * 输出：多个 SeriesCandidate；多解（重片 / 间距跳变）时保留全部合法候选。
 */
object Grouping {

    data class GroupKey(val seriesUid: String?, val forUid: String?)

    fun buildCandidates(
        interpretations: List<Interpretation>,
        materials: List<InstanceMaterial>,
        rules: RuleSet,
        atEventId: String?
    ): List<SeriesCandidate> {
        val candidates = mutableListOf<SeriesCandidate>()

        // UID 冲突：同 SOP UID 但字节/几何不同 -> 保留两份并挂 R-UID-CONFLICT。
        val sopConflicts = detectSopConflicts(materials)

        // 只把"有几何"的帧纳入成体；不同 transfer 支持状态不影响分组本身，
        // 不支持的帧仍可成组（几何保留），但 issue 中标记像素不可解码。
        val goodFrames = interpretations.flatMap { it.frames }
            .filter { it.geometry != null }
            .associateBy { it.frameId }

        val frameToSeries = interpretations.flatMap { interp ->
            val mat = materials.firstOrNull { it.stableId == interp.instanceStableId }
            interp.frames.map { f -> f.frameId to mat }
        }.toMap()

        val groups = goodFrames.values.groupBy { f ->
            val mat = frameToSeries[f.frameId]
            GroupKey(mat?.seriesInstanceUid, f.geometry!!.frameOfReferenceUid)
        }

        for ((key, framesInGroup) in groups) {
            candidates += buildForGroup(key, framesInGroup, frameToSeries, rules, atEventId, interpretations)
        }

        // 为冲突组附加 issue：候选 id 在上面已生成，这里在重算后的 issue 上直接体现。
        return candidates.map { c ->
            val conflictFrames = c.memberFrameIds.filter { fid ->
                sopConflicts.any { sc -> sc.frameIds.any { it.startsWith(fid.substringBefore('#')) } }
            }
            if (conflictFrames.isEmpty()) c
            else c.copy(issues = c.issues + GeometryEngine.issue(
                "uid_conflict_${c.candidateId}", Rules.UID_CONFLICT, "ERROR",
                "SOP Instance UID reused by distinct raw materials; both raw byte copies retained and neither merged away.",
                conflictFrames
            ))
        }
    }

    private data class SopConflict(val sopUid: String, val frameIds: List<String>)

    private fun detectSopConflicts(materials: List<InstanceMaterial>): List<SopConflict> =
        materials.filter { it.sopInstanceUid != null }
            .groupBy { it.sopInstanceUid }
            .filter { (_, list) ->
                list.size > 1 && list.map { it.pixelSha256 to it.tags.map { t -> t.rawValue } }.distinct().size > 1
            }
            .map { (sop, list) -> SopConflict(sop!!, list.map { "${it.stableId}#f0" }) }

    private fun buildForGroup(
        key: GroupKey,
        frames: List<FrameInterpretation>,
        frameToSeries: Map<String, InstanceMaterial?>,
        rules: RuleSet,
        atEventId: String?,
        allInterpretations: List<Interpretation>
    ): SeriesCandidate {
        val issues = mutableListOf<GeometryIssue>()

        // 1) 正交 / 单位长度检查（带容差）。非正交帧被排除出体，留在 excludedFrameIds。
        val (ortho, nonOrtho) = frames.partition { f ->
            val g = f.geometry!!
            val dotOk = abs(f.dirDot ?: g.rowDir.dot(g.colDir)) <= rules.orthogonalityTolerance
            val rowOk = abs((f.rowNorm ?: g.rowDir.norm()) - 1.0) <= rules.unitVectorTolerance
            val colOk = abs((f.colNorm ?: g.colDir.norm()) - 1.0) <= rules.unitVectorTolerance
            dotOk && rowOk && colOk
        }
        nonOrtho.forEach { f ->
            val g = f.geometry!!
            issues += GeometryEngine.issue(
                "nonortho_${f.frameId}", Rules.DIR_ORTHOGONAL, "ERROR",
                "Direction cosines fail orthogonality/unit-length tolerance of the active rule set; frame excluded from volume.",
                listOf(f.frameId),
                measured = "|row.col|=${f.dirDot} |row|=${f.rowNorm} |col|=${f.colNorm}",
                expected = "dot<=${rules.orthogonalityTolerance}, unit tol=${rules.unitVectorTolerance}"
            )
        }

        // 2) 不支持传输语法：几何仍成组，但像素不可解码 issue 已在解释层；此处确保分组中可见。
        val unsupported = ortho.filter { it.status == "UNSUPPORTED_TRANSFER_SYNTAX" }
        if (unsupported.isNotEmpty()) {
            issues += GeometryEngine.issue(
                "transfer_group_${key.forUid}_${key.seriesUid}", Rules.TRANSFER_SYNTAX, "WARN",
                "Group contains frames whose pixel bytes cannot be decoded; geometry still reviewed, pixels withheld.",
                unsupported.map { it.frameId }
            )
        }

        // 3) 法线：以第一帧法线为参考（同组 IOP 一致才是合法堆叠）。
        val referenceNormal = ortho.firstOrNull()?.geometry?.normal ?: Vec3.ZERO
        val iopInconsistent = ortho.filter {
            it.geometry != null && (it.geometry.normal - referenceNormal).norm() > rules.orthogonalityTolerance
        }
        if (iopInconsistent.isNotEmpty() && referenceNormal != Vec3.ZERO) {
            issues += GeometryEngine.issue(
                "iop_inconsistent_${key.forUid}", Rules.DIR_ORTHOGONAL, "WARN",
                "Frame orientations differ within one series/FrameOfReference group; kept together by UID but flagged.",
                iopInconsistent.map { it.frameId }
            )
        }
