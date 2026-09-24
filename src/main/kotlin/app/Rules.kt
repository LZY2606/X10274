package app

import kotlinx.serialization.json.*
import kotlin.math.abs

/** 规则参数 (ruleset 版本化)。所有长度量单位 mm。 */
data class RuleParams(
    val rulesetVersion: String,
    val orthTol: Double,
    val unitTol: Double,
    val dupTolMm: Double,
    val jumpTolMm: Double,
) {
    fun toJson() = buildJsonObject {
        put("rulesetVersion", rulesetVersion)
        put("orthTol", orthTol)
        put("unitTol", unitTol)
        put("dupTolMm", dupTolMm)
        put("jumpTolMm", jumpTolMm)
        put("unit", "mm")
    }
    companion object {
        val V1 = RuleParams("geom-rules/v1", 0.001, 0.01, 0.001, 0.25)
        fun fromJson(o: JsonObject): RuleParams = RuleParams(
            o["rulesetVersion"]!!.jsonPrimitive.content,
            o["orthTol"]!!.jsonPrimitive.double,
            o["unitTol"]!!.jsonPrimitive.double,
            o["dupTolMm"]!!.jsonPrimitive.double,
            o["jumpTolMm"]!!.jsonPrimitive.double)
    }
}

/** 派生层实例 (原始 tag 仍保留在导入层, 见 Store)。 */
data class DerivedInstance(
    val id: String,
    val relPath: String,
    val sopInstanceUid: String?,
    val seriesInstanceUid: String?,
    val frameOfReferenceUid: String?,
    val instanceNumberRaw: String?,
    val transferSyntaxUid: String?,
    val pixelSpacingRaw: String?,
    val frames: List<FrameGeom>,
    val diagnostics: List<Diag>,
    val pixelDecodable: Boolean,
)

data class FrameGeom(
    val frameIndex: Int,
    val position: Vec3?,
    val row: Vec3?,
    val col: Vec3?,
    val instanceId: String,
)

data class Diag(val code: String, val severity: String, val message: String, val detail: JsonObject = JsonObject(emptyMap()))

/** 排序后的成员: t 为切片法向投影坐标 (mm), residual 为相对中位间距的间距残差。 */
data class Member(
    val instanceId: String, val frameIndex: Int, val instanceNumberRaw: String?,
    val position: Vec3, val u: Double, val v: Double, val t: Double, val residualMm: Double)

data class CandidateOut(
    val key: String,
    val variant: String,
    val seriesInstanceUid: String?,
    val frameOfReferenceUid: String?,
    val members: List<Member>,
    val medianSpacingMm: Double?,
    val orthogonal: Boolean,
    val unitLength: Boolean,
    val rowColDot: Double,
    val events: List<JsonObject>,
    val diagnostics: List<Diag>,
    val dependsOn: Set<String>,
)

const val TAG_SOP = "0008,0018"
const val TAG_SERIES = "0020,000E"
const val TAG_FOR = "0020,0052"
const val TAG_INSTANCE_NO = "0020,0013"
const val TAG_IPP = "0020,0032"
const val TAG_IOP = "0020,0037"
const val TAG_PIXEL_SPACING = "0028,0030"
const val TAG_NUMBER_OF_FRAMES = "0028,0008"
const val TAG_PER_FRAME_FG = "5200,9230"

val SUPPORTED_TRANSFER_SYNTAXES = setOf(
    "1.2.840.10008.1.2",
    "1.2.840.10008.1.2.1",
)

fun jsonObj(block: JsonObjectBuilder.() -> Unit) = JsonObject(JsonObjectBuilder().apply(block).build())
private fun JsonObjectBuilder.putRefs(name: String, xs: Collection<String>) =
    put(name, JsonArray(xs.map { JsonPrimitive(it) }))

/**
 * 归组规则:
 * - InstanceNumber 不当空间排序真值, 仅用于一致性核对;
 * - 方向向量先检查正交与容差;
 * - 局部间距变化、重片与不同 FrameOfReference 产生候选分支, 不强合;
 * - 无法唯一决定时保留全部合法候选。
 */
fun groupIntoCandidates(instances: List<DerivedInstance>, params: RuleParams): List<CandidateOut> {
    val events = mutableListOf<JsonObject>()
    val diags = mutableListOf<Diag>()

    // UID 冲突: 同一 SOP UID 出现不同内容身份, 保留全部, 不覆盖。
    instances.groupBy { it.sopInstanceUid }.filter { it.key != null && it.value.size > 1 }.forEach { (uid, grp) ->
        val ev = jsonObj {
            put("kind", "UID_CONFLICT")
            put("sopInstanceUid", uid)
            putRefs("instances", grp.map { it.id })
            put("resolution", "keep-all-no-overwrite")
            put("basis", "duplicate (0008,0018) with distinct instance identity")
        }
        events += ev
        diags += Diag("UID_CONFLICT", "error",
            "SOP Instance UID $uid 出现 ${grp.size} 份不同材料, 全部保留", ev)
    }

    // transfer syntax: 不支持时只放弃像素解码, metadata 与几何继续参与。
    instances.filter { !it.pixelDecodable }.forEach { ins ->
        val ev = jsonObj {
            put("kind", "TRANSFER_SYNTAX_UNSUPPORTED")
            put("instanceId", ins.id)
            put("transferSyntaxUid", ins.transferSyntaxUid)
            put("action", "pixels-not-decoded-metadata-kept")
            putRefs("supported", SUPPORTED_TRANSFER_SYNTAXES)
        }
        events += ev
        diags += Diag("TRANSFER_SYNTAX_UNSUPPORTED", "warning",
            "transfer syntax ${ins.transferSyntaxUid} 不支持解码, 原始像素字节保留", ev)
    }

    // 缺失几何: 留在原位附诊断, 不参与空间候选, 不丢弃。
    val ungeometried = instances.filter { ins -> ins.frames.none { it.position != null && it.row != null && it.col != null } }
    ungeometried.forEach { ins ->
        val missing = buildList {
            if (!ins.frames.any { it.position != null }) add(TAG_IPP)
            if (!ins.frames.any { it.row != null && it.col != null }) add(TAG_IOP)
        }
        val ev = jsonObj {
            put("kind", "MISSING_GEOMETRY")
            put("instanceId", ins.id)
            put("missingTags", JsonArray(missing.map { JsonPrimitive(it) }))
            put("action", "excluded-from-spatial-candidates-kept-in-place")
        }
        events += ev
        diags += Diag("MISSING_GEOMETRY", "warning",
            "实例 ${ins.relPath} 缺失几何 tag $missing, 不纳入空间排序但保留", ev)
    }

    // 可空间排序的帧, 按 (Series, FrameOfReference) 分键: 不同 FoR 绝不强合。
    data class Slot(val ins: DerivedInstance, val frame: FrameGeom, val orient: Orientation)
    val slots = instances.flatMap { ins ->
        ins.frames.filter { it.position != null && it.row != null && it.col != null }
            .map { f -> Slot(ins, f, Orientation.from(f.row!!, f.col!!, params.orthTol, params.unitTol)) }
    }
    val byKey = slots.groupBy { (it.ins.seriesInstanceUid ?: "") + "|" + (it.ins.frameOfReferenceUid ?: "") }

    val forGroups = slots.map { it.ins.frameOfReferenceUid }.filterNotNull().distinct()
    if (forGroups.size > 1) {
        val ev = jsonObj {
            put("kind", "FRAME_OF_REFERENCE_SPLIT")
            putRefs("frameOfReferenceUids", forGroups)
            put("resolution", "separate-candidates-never-merged")
            put("basis", "(0020,0052) differs: no common coordinate basis")
        }
        events += ev
        diags += Diag("FRAME_OF_REFERENCE_SPLIT", "info",
            "检测到 ${forGroups.size} 个 FrameOfReference, 产生分支不强合", ev)
    }

    val result = mutableListOf<CandidateOut>()
    for ((key, grp) in byKey) {
        val seriesUid = grp.first().ins.seriesInstanceUid
        val forUid = grp.first().ins.frameOfReferenceUid
        // 取第一帧方向做基准 (各帧方向一致时即该 series 的平面)
        val baseOrient = grp.first().orient
        val n = baseOrient.normal
        val row = baseOrient.row; val col = baseOrient.col

        if (!baseOrient.orthogonal || !baseOrient.unitLength) {
            val ev = jsonObj {
                put("kind", "NON_ORTHOGONAL_ORIENTATION")
                put("candidateKey", key)
                put("rowDotCol", baseOrient.rowColDot)
                put("orthTol", params.orthTol)
                put("rowNorm", baseOrient.rowNorm)
                put("colNorm", baseOrient.colNorm)
                put("unitTol", params.unitTol)
                put("action", "flag-candidate-keep-derived-normal")
                put("basis", "|row . col| <= orthTol and |norm-1| <= unitTol")
            }
            events += ev
            diags += Diag("NON_ORTHOGONAL_ORIENTATION", "error",
                "方向向量非正交 (row·col=${"%.3f".format(baseOrient.rowColDot)}), 候选显式标记", ev)
        }

        data class P(val ins: DerivedInstance, val frame: FrameGeom, val pos: Vec3, val t: Double)
        val projected = grp.map { P(it.ins, it.frame, it.frame.position!!, it.frame.position!! dot n) }
            .sortedWith(compareBy({ it.t }, { it.ins.id }, { it.frame.frameIndex }))

        // InstanceNumber 不是空间排序真值: 检查与投影顺序是否矛盾, 只报不改。
        val ranks = projected.mapIndexed { idx, p -> idx to p }
        ranks.forEach { (idx, p) ->
            val inRaw = p.ins.instanceNumberRaw
            val inVal = inRaw?.trim()?.toIntOrNull()
            if (inVal != null) {
                val valByRank = ranks.map { it.second.ins.instanceNumberRaw?.trim()?.toIntOrNull() }
                if (valByRank != valByRank.sorted()) {
                    if (!events.any { (it["kind"] as? JsonPrimitive)?.content == "INSTANCE_NUMBER_NOT_SPATIAL_TRUTH" }) {
                        val ev = jsonObj {
                            put("kind", "INSTANCE_NUMBER_NOT_SPATIAL_TRUTH")
                            put("candidateKey", key)
                            putRefs("instanceNumbersInProjectionOrder",
                                projected.map { it.ins.instanceNumberRaw ?: "" })
                            put("rule", "projection on slice normal is the spatial truth")
                            put("action", "sort-by-projection-keep-instanceNumber-as-evidence")
                        }
                        events += ev
                        diags += Diag("INSTANCE_NUMBER_NOT_SPATIAL_TRUTH", "warning",
                            "InstanceNumber 与法向投影排序不一致, 以投影坐标排序", ev)
                    }
                }
            }
        }

        fun build(variant: String, chosen: List<P>): CandidateOut {
            val ts = chosen.map { it.t }
            val spacings = ts.zipWithNext { a, b -> b - a }
            val median = if (spacings.isNotEmpty()) Ds.median(spacings) else null
            val members = chosen.mapIndexed { idx, p ->
                val res = if (median != null && idx < ts.size - 1) (ts[idx + 1] - ts[idx]) - median else 0.0
                Member(p.ins.id, p.frame.frameIndex, p.ins.instanceNumberRaw, p.pos,
                    u = p.pos dot row, v = p.pos dot col, t = p.t, residualMm = res)
            }
            val candEvents = events.filter { ev ->
                val k = ev["candidateKey"]?.let { (it as JsonPrimitive).content }
                k == null || k == key
            }
            return CandidateOut(key, variant, seriesUid, forUid, members, median,
                baseOrient.orthogonal, baseOrient.unitLength, baseOrient.rowColDot,
                candEvents, diags.filter { d ->
                    val evInst = d.detail["instanceId"]?.let { (it as JsonPrimitive).content }
                    val evKey = d.detail["candidateKey"]?.let { (it as JsonPrimitive).content }
                    evKey == key || evInst in chosen.map { it.ins.id }.toSet()
                },
                dependsOn = chosen.map { it.ins.id }.toSet())
        }

        // 重片: |Δt| <= dupTol -> 保留全部合法候选 (全部保留 / 排除后者)。
        val dupPairs = projected.zipWithNext().filter { abs(it.second.t - it.first.t) <= params.dupTolMm }
        if (dupPairs.isNotEmpty()) {
            dupPairs.forEach { (a, b) ->
                val ev = jsonObj {
                    put("kind", "DUPLICATE_POSITION")
                    put("candidateKey", key)
                    put("tMm", a.t)
                    put("dupTolMm", params.dupTolMm)
                    putRefs("instances", listOf(a.ins.id, b.ins.id))
                    put("variants", JsonArray(listOf("keep-both", "drop-later")))
                    put("basis", "|t_i - t_j| <= dupTolMm; branch, do not force merge")
                }
                events += ev
                diags += Diag("DUPLICATE_POSITION", "warning",
                    "t=${a.t} mm 处出现重片, 产生候选分支", ev)
            }
            result += build("all-slices", projected)
            val laterIds = dupPairs.map { it.second.ins.id to it.second.frame.frameIndex }.toSet()
            val deduped = projected.filter { (it.ins.id to it.frame.frameIndex) !in laterIds }
            result += build("drop-later-duplicates", deduped)
            continue
        }

        // 局部间距跳变: |残差| > jumpTol -> 在跳变处分裂, 保留整体候选与两个合法分裂。
        val ts = projected.map { it.t }
        val spacings = ts.zipWithNext { a, b -> b - a }
        val median = if (spacings.isNotEmpty()) Ds.median(spacings) else null
        val jumpAt = if (median != null) spacings.indexOfFirst { abs(it - median) > params.jumpTolMm } else -1
        if (median != null && jumpAt >= 0) {
            val resAt = spacings[jumpAt] - median
            val ev = jsonObj {
                put("kind", "LOCAL_SPACING_JUMP")
                put("candidateKey", key)
                put("afterProjectionRank", jumpAt + 1)
                put("spacingMm", spacings[jumpAt])
                put("medianMm", median)
                put("residualMm", resAt)
                put("jumpTolMm", params.jumpTolMm)
                put("variants", JsonArray(listOf("all-slices", "split-before", "split-after")))
                put("basis", "local |spacing - median| > jumpTolMm; branch at the jump, do not rescale")
            }
            events += ev
            diags += Diag("LOCAL_SPACING_JUMP", "warning",
                "投影秩 ${jumpAt + 1} 后局部间距残差 ${"%.3f".format(resAt)} mm, 在跳变处分支", ev)
            result += build("all-slices", projected)
            result += build("split-before", projected.subList(0, jumpAt + 1))
            result += build("split-after", projected.subList(jumpAt + 1, projected.size))
            continue
        }

        result += build("all-slices", projected)
    }

    // 诊断汇总到实例级 (由 Store 落库时复用)
    return result
}
