package gsb.core

import gsb.model.Candidate
import gsb.model.CandidateSet
import gsb.model.Diagnostic
import gsb.model.FrameGeometry
import gsb.model.Params
import gsb.model.Vec3
import gsb.model.sha256
import kotlin.math.abs

/**
 * 归组规则（ruleVersion 随算法语义变更递增）：
 *  - InstanceNumber 不当空间排序真值；一律按投影坐标排序。
 *  - 方向向量先检查正交与容差，再计算切片法向。
 *  - 局部间距变化、重片和不同 FrameOfReference 产生分支而不强合。
 */
object Grouping {
    const val RULE_VERSION = 1

    data class Result(val sets: List<CandidateSet>)

    fun group(frames: List<FrameGeometry>, params: Params): List<CandidateSet> {
        // 不同 FrameOfReference / Series 先分区，绝不强合
        return frames.groupBy { it.partitionKey }
            .toSortedMap()
            .map { (partition, fs) -> groupPartition(partition, fs, params) }
    }

    private fun groupPartition(partition: String, frames: List<FrameGeometry>, params: Params): CandidateSet {
        val diags = mutableListOf<Diagnostic>()
        val (unpositioned, positioned) = frames.partition { it.ippMm == null || it.iopRow == null || it.iopCol == null }

        // 方向正交性检查（先于法向计算）
        val flagged = mutableMapOf<String, MutableList<String>>()
        data class P(val frame: FrameGeometry, val axis: Vec3, val s: Double)
        val ps = mutableListOf<P>()
        var axisRef: Vec3? = null
        for (f in positioned.sortedBy { it.frameId }) {
            val row = f.iopRow!!
            val col = f.iopCol!!
            val dot = row.dot(col)
            if (abs(dot) > params.orthogonalityTolerance) {
                flagged.getOrPut(f.frameId) { mutableListOf() } += "NON_ORTHOGONAL"
                diags += Diagnostic(
                    "NON_ORTHOGONAL",
                    "方向向量不正交：row·col=%.6f 超出容差 %.6f".format(dot, params.orthogonalityTolerance),
                    "%.6f".format(dot),
                )
            }
            val axis = row.cross(col).let { if (it.norm() > 0) it.scale(1.0 / it.norm()) else Vec3(0.0, 0.0, 1.0) }
            if (axisRef == null) axisRef = axis
            val ref = axisRef!!
            if (abs(axis.dot(ref)) < 1.0 - params.axisClusterTolerance) {
                flagged.getOrPut(f.frameId) { mutableListOf() } += "AXIS_DIVERGENT"
                diags += Diagnostic("AXIS_DIVERGENT", "切片法向与分区基准不一致，保留为分支而非强合")
            }
            // 投影坐标：沿分区基准法向的投影，单位 mm
            ps += P(f, axis, f.ippMm!!.dot(ref))
        }

        // 按投影坐标排序（稳定：并列时按 frameId），绝不使用 InstanceNumber
        val sorted = ps.sortedWith(compareBy({ it.s }, { it.frame.frameId }))
        val sVals = sorted.map { it.s }
        val deltas = sVals.zipWithNext { a, b -> b - a }
        val median = deltas.sorted().let { if (it.isEmpty()) 0.0 else it[it.size / 2] }
        val residuals = deltas.map { it - median }

        // 重片检测
        val dupGroups = mutableListOf<MutableList<Int>>()
        sorted.forEachIndexed { i, p ->
            if (i > 0 && abs(p.s - sorted[i - 1].s) <= params.duplicatePositionEpsilonMm) {
                val g = dupGroups.lastOrNull()
                if (g != null && g.last() == i - 1) g += i else dupGroups += mutableListOf(i - 1, i)
            }
        }
        dupGroups.flatten().forEach { idx ->
            flagged.getOrPut(sorted[idx].frame.frameId) { mutableListOf() } += "DUPLICATE_POSITION"
        }
        if (dupGroups.isNotEmpty()) {
            diags += Diagnostic(
                "DUPLICATE_POSITION",
                "检测到 ${dupGroups.size} 组重复位置（容差 ${params.duplicatePositionEpsilonMm} mm），生成并列候选而非合并",
            )
        }

        // 局部间距跳变 → 分段为多个 run，不强合
        val splitAfter = BooleanArray(sorted.size)
        deltas.forEachIndexed { i, d ->
            if (median > 0 && abs(d) > median * params.spacingJumpFactor) splitAfter[i] = true
        }
        val runs = mutableListOf<List<Int>>()
        var cur = mutableListOf<Int>()
        sorted.indices.forEach { i ->
            cur += i
            if (splitAfter[i]) { runs += cur; cur = mutableListOf() }
        }
        if (cur.isNotEmpty()) runs += cur
        if (runs.size > 1) {
            diags += Diagnostic(
                "SPACING_JUMP",
                "局部间距跳变（中位数 %.4f mm，跳变因子 %.2f）：分为 ${runs.size} 个分支".format(median, params.spacingJumpFactor),
            )
            runs.drop(1).forEach { r -> r.forEach { idx -> flagged.getOrPut(sorted[idx].frame.frameId) { mutableListOf() } += "SPACING_JUMP" } }
        }

        val candidates = mutableListOf<Candidate>()
        // 每个 run 内若含重片组，为每组重片的每个成员生成一个并列候选
        for (run in runs) {
            val runSet = run.toSet()
            val runDups = dupGroups.map { g -> g.filter { it in runSet } }.filter { it.size > 1 }
            val variantCount = runDups.fold(1) { acc, g -> acc * g.size }.coerceAtMost(8)
            val combos = mutableListOf<List<Int>>()
            if (runDups.isEmpty()) {
                combos += run
            } else {
                // 笛卡尔组合（受 variantCount 上限保护）
                var acc: List<List<Int>> = listOf(emptyList())
                for (g in runDups) {
                    val next = mutableListOf<List<Int>>()
                    for (prefix in acc) for (choice in g) {
                        if (next.size < variantCount) next += prefix + choice
                    }
                    acc = next
                }
                val dupIdx = runDups.flatten().toSet()
                for (choice in acc) {
                    val chosen = choice.toSet()
                    combos += run.filter { it !in dupIdx || it in chosen }
                }
            }
            for (combo in combos) {
                val ids = combo.map { sorted[it].frame.frameId }
                val comboResiduals = combo.zipWithNext { a, b -> (sorted[b].s - sorted[a].s) - median }
                val flags = combo.flatMap { flagged[sorted[it].frame.frameId] ?: emptyList() }.distinct()
                val kind = when {
                    runDups.isNotEmpty() -> "duplicate-variant"
                    runs.size > 1 -> "volume-run"
                    else -> "volume"
                }
                val id = "cand-" + sha256(
                    "$partition|$kind|${ids.joinToString(",")}|r${RULE_VERSION}|p${params.version}"
                ).take(16)
                candidates += Candidate(
                    id = id,
                    kind = kind,
                    frameIds = ids,
                    residualsMm = comboResiduals,
                    flags = flags,
                    rationale = buildString {
                        append("按投影坐标排序（InstanceNumber 不作排序真值）")
                        if (runs.size > 1) append("；局部间距跳变分段 ${runs.indexOf(run) + 1}/${runs.size}")
                        if (runDups.isNotEmpty()) append("；重片并列候选")
                    },
                )
            }
        }
        if (unpositioned.isNotEmpty()) {
            val ids = unpositioned.sortedBy { it.frameId }.map { it.frameId }
            candidates += Candidate(
                id = "cand-" + sha256("$partition|unpositioned|${ids.joinToString(",")}|r${RULE_VERSION}|p${params.version}").take(16),
                kind = "unpositioned",
                frameIds = ids,
                flags = listOf("MISSING_GEOMETRY"),
                rationale = "缺失几何，保留为未定位分支，不参与空间排序",
            )
        }
        val sortedCandidates = candidates.sortedWith(compareBy({ it.kind }, { it.id }))
        val setId = "cs-" + sha256(
            "$partition|${sortedCandidates.joinToString(";") { it.id }}|r${RULE_VERSION}|p${params.version}"
        ).take(16)
        return CandidateSet(
            id = setId,
            partitionKey = partition,
            ruleVersion = RULE_VERSION,
            paramVersion = params.version,
            candidates = sortedCandidates,
            diagnostics = diags.distinctBy { it.code + it.message },
        )
    }
}
