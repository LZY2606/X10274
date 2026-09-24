package dgeom

import kotlinx.serialization.json.*
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.sqrt

/**
 * Geometry grouping engine. Rules (machine-readable in casepack/expected/expectations.json):
 *  R1: InstanceNumber is never the spatial sort truth; projection onto the slice normal is.
 *  R2: orientation vectors are checked for unit-norm and orthogonality against a tolerance first.
 *  R3: local spacing jumps, duplicate positions and differing FrameOfReferenceUIDs produce
 *      separate branch candidates; members are never force-merged.
 */
object Engine {

    data class Result(val candidates: List<Candidate>, val events: List<DiagEvent>)

    data class DiagEvent(
        val type: String,
        val severity: String,
        val message: String,
        val refs: JsonObject,
    )

    data class Candidate(
        val id: String,
        val kind: String,
        val seriesUid: String,
        val forUid: String?,
        val axis: Vec3?,
        val origin: Vec3?,
        val members: JsonArray,
        val diagnostics: JsonArray,
        val detail: JsonObject,
    )

    private class Geom(
        val inst: InstanceRec,
        val frameIndex: Int?,
        val row: Vec3,
        val col: Vec3,
        val pos: Vec3,
    )

    fun interpret(instances: List<InstanceRec>, params: Params, interpId: String): Result {
        val events = mutableListOf<DiagEvent>()
        val candidates = mutableListOf<Candidate>()
        var candCounter = 0
        fun candId(kind: String, series: String) =
            "cand:" + sha1("$interpId|$series|$kind|${candCounter++}").take(12)

        // R3: FrameOfReference is part of the group key — different FoR never merges.
        val groups = instances.groupBy { it.seriesUid to (it.forUid ?: "") }
        val seriesForCount = instances.groupBy { it.seriesUid }.mapValues { e -> e.value.map { it.forUid }.toSet().size }
        for ((series, count) in seriesForCount) {
            if (count > 1) events.add(
                DiagEvent(
                    "frame_of_reference_split", "branch",
                    "序列 $series 含 $count 个不同 FrameOfReferenceUID，产生分支而不强合",
                    buildJsonObject { put("seriesUid", series); put("forCount", count) }
                )
            )
        }

        for ((key, members) in groups.toSortedMap(compareBy({ it.first }, { it.second }))) {
            val (seriesUid, forUidRaw) = key
            val forUid = forUidRaw.ifEmpty { null }
            val multiframe = members.filter { it.functionalGroups?.get("PerFrameFunctionalGroups") is JsonArray }
            val singleFrame = members - multiframe.toSet()

            for (mf in multiframe) {
                val r = expandMultiframe(mf, params, events)
                if (r != null) candidates += r.copy(id = candId("multiframe_volume", seriesUid))
            }

            val geoms = mutableListOf<Geom>()
            val ungroupable = mutableListOf<InstanceRec>()
            for (inst in singleFrame.sortedBy { it.id }) {
                val g = parseGeom(inst, params, events)
                if (g == null) ungroupable += inst else geoms += g
            }
            if (ungroupable.isNotEmpty()) {
                events.add(
                    DiagEvent(
                        "missing_geometry", "error",
                        "序列 $seriesUid 有 ${ungroupable.size} 个实例缺失/无法解析几何，原 tag 保留，不参与空间归组",
                        buildJsonObject {
                            put("seriesUid", seriesUid)
                            putJsonArray("instanceIds") { ungroupable.forEach { add(it.id) } }
                        }
                    )
                )
                candidates += Candidate(
                    candId("ungroupable", seriesUid), "ungroupable", seriesUid, forUid, null, null,
                    JsonArray(ungroupable.map { inst ->
                        buildJsonObject {
                            put("instanceId", inst.id)
                            put("instanceNumber", inst.instanceNumber?.let { JsonPrimitive(it) } ?: JsonNull)
                            putJsonArray("diagnostics") { inst.diagnostics.forEach { add(it) } }
                        }
                    }),
                    JsonArray(ungroupable.flatMap { it.diagnostics }.distinct().map { JsonPrimitive(it) }),
                    buildJsonObject { put("reason", "missing_or_unparsable_geometry"); put("unit", "mm"); put("basis", "DICOM-LPS") }
                )
            }
            if (geoms.isNotEmpty()) groupGeoms(geoms, seriesUid, forUid, params, events, candidates) { k, s -> candId(k, s) }
        }
        return Result(candidates, events)
    }

    private fun parseGeom(inst: InstanceRec, params: Params, events: MutableList<DiagEvent>): Geom? {
        val iop = inst.rawTags.tagDoubles(Tags.ImageOrientationPatient)
        val ipp = inst.rawTags.tagDoubles(Tags.ImagePositionPatient)
        if (iop == null || iop.size != 6 || ipp == null || ipp.size != 3) return null
        val rowRaw = Vec3(iop[0], iop[1], iop[2])
        val colRaw = Vec3(iop[3], iop[4], iop[5])
        val rowN = rowRaw.normalized()
        val colN = colRaw.normalized()
        // R2: unit-norm and orthogonality checks come first, against tolerance.
        if (abs(rowRaw.norm() - 1.0) > params.unitNormTolerance || abs(colRaw.norm() - 1.0) > params.unitNormTolerance) {
            events.add(DiagEvent(
                "orientation_not_unit", "warning",
                "实例 ${inst.id} 方向向量非单位向量（|row|=${"%.6f".format(rowRaw.norm())}, |col|=${"%.6f".format(colRaw.norm())}），归一化后继续",
                buildJsonObject {
                    put("instanceId", inst.id)
                    put("rowNorm", rowRaw.norm()); put("colNorm", colRaw.norm())
                    put("unitNormTolerance", params.unitNormTolerance)
                }
            ))
        }
        val dot = abs(rowN.dot(colN))
        val devDeg = Math.toDegrees(asin(dot.coerceIn(0.0, 1.0)))
        if (devDeg > params.orthogonalityToleranceDeg) {
            events.add(DiagEvent(
                "orientation_nonorthogonal", "error",
                "实例 ${inst.id} 方向向量非正交，偏差 ${"%.4f".format(devDeg)}° 超过容差 ${params.orthogonalityToleranceDeg}°",
                buildJsonObject {
                    put("instanceId", inst.id)
                    put("deviationDeg", devDeg)
                    put("toleranceDeg", params.orthogonalityToleranceDeg)
                    put("row", rowRaw.toJson()); put("col", colRaw.toJson())
                }
            ))
        }
        return Geom(inst, null, rowN, colN, Vec3(ipp[0], ipp[1], ipp[2]))
    }

    private fun expandMultiframe(inst: InstanceRec, params: Params, events: MutableList<DiagEvent>): Candidate? {
        val fg = inst.functionalGroups ?: return null
        val frames = fg["PerFrameFunctionalGroups"] as? JsonArray ?: return null
        val shared = (fg["SharedFunctionalGroups"] as? JsonObject)
        val iop = shared?.let { (it["PlaneOrientationSequence"] as? JsonObject)?.tagDoubles("ImageOrientationPatient") }
            ?: inst.rawTags.tagDoubles(Tags.ImageOrientationPatient) ?: return null
        val row = Vec3(iop[0], iop[1], iop[2]).normalized()
        val col = Vec3(iop[3], iop[4], iop[5]).normalized()
        val normal = row.cross(col).normalized()
        val members = mutableListOf<JsonObject>()
        frames.forEachIndexed { idx, f ->
            val pos = ((f as? JsonObject)?.get("PlanePositionSequence") as? JsonObject)
                ?.tagDoubles("ImagePositionPatient")
            members.add(buildJsonObject {
                put("instanceId", inst.id)
                put("frameIndex", idx)
                if (pos != null && pos.size == 3) {
                    val p = Vec3(pos[0], pos[1], pos[2])
                    put("projectionMm", p.dot(normal))
                }
                put("instanceNumber", inst.instanceNumber?.let { JsonPrimitive(it) } ?: JsonNull)
            })
        }
        events.add(DiagEvent(
            "multiframe_expanded", "info",
            "多帧对象 ${inst.id} 经 PerFrameFunctionalGroups 展开为 ${frames.size} 帧",
            buildJsonObject { put("instanceId", inst.id); put("frames", frames.size) }
        ))
        return Candidate(
            "", "multiframe_volume", inst.seriesUid, inst.forUid, normal,
            null, JsonArray(members),
            buildJsonArray { if (inst.transferSyntax !in SUPPORTED_TRANSFER_SYNTAXES) add("pixel_preview_unavailable") },
            buildJsonObject { put("frames", frames.size); put("unit", "mm"); put("basis", "DICOM-LPS") }
        )
    }

    private fun groupGeoms(
        geoms: List<Geom>, seriesUid: String, forUid: String?,
        params: Params, events: MutableList<DiagEvent>, candidates: MutableList<Candidate>,
        idGen: (String, String) -> String,
    ) {
        val axis = geoms.first().row.cross(geoms.first().col).normalized()
        // R1: sort truth is the spatial projection, never InstanceNumber.
        val sorted = geoms.sortedBy { it.pos.dot(axis) }
        val byInstNum = geoms.filter { it.inst.instanceNumber != null }.sortedBy { it.inst.instanceNumber }
        if (byInstNum.size >= 2 && byInstNum.map { it.inst.id } != sorted.filter { it.inst.instanceNumber != null }.map { it.inst.id }) {
            events.add(DiagEvent(
                "instance_number_not_spatial_truth", "info",
                "序列 $seriesUid 的 InstanceNumber 顺序与空间投影顺序不一致，采用投影排序",
                buildJsonObject {
                    put("seriesUid", seriesUid)
                    putJsonArray("instanceNumberOrder") { byInstNum.forEach { add(it.inst.id) } }
                    putJsonArray("projectionOrder") { sorted.forEach { add(it.inst.id) } }
                }
            ))
        }
        // Axis consistency across the group.
        for (g in sorted.drop(1)) {
            val n = g.row.cross(g.col).normalized()
            val dev = Math.toDegrees(asin(n.cross(axis).norm().coerceIn(0.0, 1.0)))
            if (dev > params.orthogonalityToleranceDeg) {
                events.add(DiagEvent(
                    "orientation_inconsistent", "warning",
                    "实例 ${g.inst.id} 法向与组轴偏差 ${"%.4f".format(dev)}°",
                    buildJsonObject { put("instanceId", g.inst.id); put("deviationDeg", dev) }
                ))
            }
        }
        val projs = sorted.map { it.pos.dot(axis) }
        val diffs = projs.zipWithNext { a, b -> b - a }
        val nonZero = diffs.map { abs(it) }.filter { it >= params.duplicateEpsilonMm }
        val nominal = if (nonZero.isEmpty()) 0.0 else nonZero.sorted()[nonZero.size / 2]

        // Transfer syntax support is metadata-level: pixels may be unavailable, tags stay.
        val unsupported = sorted.filter { it.inst.transferSyntax !in SUPPORTED_TRANSFER_SYNTAXES }
        if (unsupported.isNotEmpty()) {
            events.add(DiagEvent(
                "transfer_syntax_unsupported", "warning",
                "序列 $seriesUid 含 ${unsupported.size} 个实例的传输语法不受支持（像素不可预览，metadata 保留）",
                buildJsonObject {
                    put("seriesUid", seriesUid)
                    putJsonArray("instanceIds") { unsupported.forEach { add(it.inst.id) } }
                    putJsonArray("transferSyntaxes") { unsupported.mapNotNull { it.inst.transferSyntax }.distinct().forEach { add(it) } }
                }
            ))
        }

        fun memberJson(idx: Int, residual: Double?): JsonObject {
            val g = sorted[idx]
            return buildJsonObject {
                put("instanceId", g.inst.id)
                put("sortIndex", idx)
                put("projectionMm", projs[idx])
                if (residual != null) put("residualMm", residual)
                put("instanceNumber", g.inst.instanceNumber?.let { JsonPrimitive(it) } ?: JsonNull)
                put("transferSyntax", g.inst.transferSyntax?.let { JsonPrimitive(it) } ?: JsonNull)
            }
        }
        fun diagArray(): JsonArray = buildJsonArray {
            if (unsupported.isNotEmpty()) add("pixel_preview_unavailable")
        }
        val detailBase = buildJsonObject {
            put("unit", "mm"); put("basis", "DICOM-LPS")
            put("nominalSpacingMm", nominal)
            put("sortedBy", "projection")
        }

        // R3a: duplicate positions -> branch candidates, no forced merge.
        val dupSets = mutableListOf<List<Int>>()
        run {
            var i = 0
            while (i < sorted.size) {
                var j = i
                while (j + 1 < sorted.size && abs(projs[j + 1] - projs[i]) < params.duplicateEpsilonMm) j++
                if (j > i) dupSets += (i..j).toList()
                i = j + 1
            }
        }
        if (dupSets.isNotEmpty()) {
            events.add(DiagEvent(
                "duplicate_position", "branch",
                "序列 $seriesUid 检出 ${dupSets.size} 组重复位置（容差 ${params.duplicateEpsilonMm} mm），产生分支而不强合",
                buildJsonObject {
                    put("seriesUid", seriesUid)
                    putJsonArray("sets") {
                        dupSets.forEach { s -> add(JsonArray(s.map { JsonPrimitive(sorted[it].inst.id) })) }
                    }
                    put("epsilonMm", params.duplicateEpsilonMm)
                }
            ))
            candidates += Candidate(
                idGen("duplicate_flagged", seriesUid), "duplicate_flagged", seriesUid, forUid, axis,
                sorted.first().pos,
                JsonArray(sorted.indices.map { memberJson(it, if (it == 0) null else diffs[it - 1] - nominal) }),
                buildJsonArray { add("contains_duplicate_positions"); if (unsupported.isNotEmpty()) add("pixel_preview_unavailable") },
                detailBase
            )
            for (set in dupSets) {
                for (drop in set.take(4)) {
                    val keep = sorted.indices.filter { it !in set || it == drop }
                    candidates += Candidate(
                        idGen("duplicate_branch", seriesUid), "duplicate_branch", seriesUid, forUid, axis,
                        sorted[keep.first()].pos,
                        JsonArray(keep.map { memberJson(it, null) }),
                        buildJsonArray { add("keeps_instance:" + sorted[drop].inst.id) },
                        detailBase
                    )
                }
            }
            return
        }

        // R3b: local spacing jumps -> split into segment candidates, no forced merge.
        val jumpAfter = diffs.indices.filter { nominal > 0 && abs(diffs[it]) > params.spacingJumpFactor * nominal }
        if (jumpAfter.isNotEmpty()) {
            events.add(DiagEvent(
                "spacing_jump", "branch",
                "序列 $seriesUid 在 ${jumpAfter.size} 处出现局部间距跳变（标称 ${"%.4f".format(nominal)} mm），产生分支而不强合",
                buildJsonObject {
                    put("seriesUid", seriesUid)
                    put("nominalSpacingMm", nominal)
                    put("jumpFactor", params.spacingJumpFactor)
                    putJsonArray("jumps") {
                        jumpAfter.forEach { j ->
                            add(buildJsonObject {
                                put("afterInstanceId", sorted[j].inst.id)
                                put("gapMm", diffs[j])
                                put("residualMm", diffs[j] - nominal)
                            })
                        }
                    }
                }
            ))
            candidates += Candidate(
                idGen("spacing_jump_flagged", seriesUid), "spacing_jump_flagged", seriesUid, forUid, axis,
                sorted.first().pos,
                JsonArray(sorted.indices.map { memberJson(it, if (it == 0) null else diffs[it - 1] - nominal) }),
                buildJsonArray { add("contains_spacing_jump"); if (unsupported.isNotEmpty()) add("pixel_preview_unavailable") },
                detailBase
            )
            val bounds = mutableListOf<Int>()
            bounds += 0
            jumpAfter.forEach { bounds += it + 1 }
            bounds += sorted.size
            for (w in bounds.zipWithNext()) {
                val seg = (w.first until w.second).toList()
                if (seg.isEmpty()) continue
                val segDiffs = seg.zipWithNext { a, b -> projs[b] - projs[a] }
                candidates += Candidate(
                    idGen("series_volume", seriesUid), "series_volume", seriesUid, forUid, axis,
                    sorted[seg.first()].pos,
                    JsonArray(seg.mapIndexed { k, idx -> memberJson(idx, if (k == 0) null else segDiffs[k - 1] - nominal) }),
                    diagArray(), detailBase
                )
            }
            return
        }

        // Clean run: single series_volume with per-gap residuals.
        candidates += Candidate(
            idGen("series_volume", seriesUid), "series_volume", seriesUid, forUid, axis,
            sorted.first().pos,
            JsonArray(sorted.indices.map { memberJson(it, if (it == 0) null else diffs[it - 1] - nominal) }),
            diagArray(), detailBase
        )
    }
}
