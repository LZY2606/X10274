package dicomlab

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.abs

/** One reviewable slice: a single-frame instance, or one frame of a multi-frame object. */
data class Slice(
    val instanceId: String,
    val sliceKey: String,
    val seriesUid: String?,
    val forUid: String?,
    val transferSyntax: String?,
    val instanceNumber: Int?,
    val position: Vec3?,
    val row: Vec3?,
    val col: Vec3?,
    val pixelSpacing: Pair<Double, Double>?,
    val isFrame: Boolean
)

data class GroupingResult(
    val candidates: List<CandidateRec>,
    val diagnostics: List<DiagnosticRec>,
    val basis: JudgementBasis
)

/**
 * Geometry-first grouping. Rules of judgement (also emitted as a machine-readable JudgementBasis):
 *  - InstanceNumber is never the spatial sort truth; slices sort by projection onto the stack normal.
 *  - Orientation vectors are checked for orthonormality within tolerance BEFORE any projection.
 *  - Local spacing jumps, duplicate positions and differing FrameOfReferenceUIDs produce
 *    parallel candidate branches; nothing is force-merged and no legal candidate is dropped.
 */
class Grouper(private val params: RuleParams, private val interpretationId: String) {

    fun group(instances: List<InstanceRec>): GroupingResult {
        val diagnostics = mutableListOf<DiagnosticRec>()
        val slices = mutableListOf<Slice>()

        // Stage 1: expand multi-frame objects into per-frame slices (raw object kept untouched).
        for (inst in instances) {
            if (inst.multiframeJson != null) {
                val frames = expandMultiframe(inst, diagnostics)
                slices += frames
                diagnostics += diag(
                    inst.id, "MULTIFRAME_OBJECT",
                    "multi-frame object expanded into ${frames.size} frame slices; original functional groups preserved",
                    """{"frames":${frames.size},"sliceKey":"${inst.sliceKey}"}"""
                )
            } else {
                slices += sliceFrom(
                    inst.id, inst.sliceKey, inst.seriesInstanceUid, inst.frameOfReferenceUid,
                    inst.transferSyntaxUid, inst.instanceNumber,
                    inst.imagePositionPatient, inst.imageOrientationPatient, inst.pixelSpacing,
                    isFrame = false, diagnostics = diagnostics, ownerId = inst.id
                )
            }
        }

        // Stage 2: per-slice evidence checks. Unsupported transfer syntax limits impact to that
        // instance only: raw tags stay, the slice is excluded from spatial candidates.
        val supported = params.supportedTransferSyntaxes.toSet()
        val usable = mutableListOf<Slice>()
        for (s in slices) {
            if (s.transferSyntax != null && s.transferSyntax !in supported) {
                diagnostics += diag(
                    s.instanceId, "UNSUPPORTED_TRANSFER_SYNTAX",
                    "transfer syntax ${s.transferSyntax} not supported; metadata kept, slice excluded from volume candidates",
                    """{"transferSyntax":"${s.transferSyntax}","sliceKey":"${s.sliceKey}"}"""
                )
                continue
            }
            if (s.position == null || s.row == null || s.col == null) {
                diagnostics += diag(
                    s.instanceId, "MISSING_GEOMETRY",
                    "ImagePositionPatient/ImageOrientationPatient missing or unconvertible; raw value left in place",
                    """{"sliceKey":"${s.sliceKey}"}"""
                )
                continue
            }
            // Orientation is validated BEFORE it is used for any projection.
            val issues = Geometry.orientationIssues(s.row, s.col, params)
            if (issues.isNotEmpty()) {
                diagnostics += diag(
                    s.instanceId, "NON_ORTHOGONAL",
                    "orientation vectors fail orthonormality within tolerance: ${issues.joinToString("; ")}",
                    """{"sliceKey":"${s.sliceKey}","orthogonalityTolerance":${params.orthogonalityTolerance},"normTolerance":${params.normTolerance}}"""
                )
            }
            usable += s
        }

        // Stage 3: UID conflicts — same SOPInstanceUID carrying contradictory identity/geometry.
        val bySop = usable.filter { it.sliceKey.contains('#').not() }.groupBy { it.sliceKey }
        for ((sop, group) in bySop) {
            if (group.size > 1) {
                val distinct = group.map { Triple(it.seriesUid, it.forUid, it.position) }.distinct()
                if (distinct.size > 1) {
                    diagnostics += diag(
                        null, "UID_CONFLICT",
                        "SOPInstanceUID $sop appears ${group.size} times with conflicting series/position; all copies retained",
                        """{"sopInstanceUid":"$sop","instances":${group.map { "\"${it.instanceId}\"" }}}"""
                    )
                }
            }
        }

        // Stage 4: branch by SeriesInstanceUID × FrameOfReferenceUID — never merged across FoR.
        val candidates = mutableListOf<CandidateRec>()
        val branchDescriptions = mutableListOf<String>()
        val groups = usable.groupBy { (it.seriesUid ?: "∅") to (it.forUid ?: "∅") }.toSortedMap()
        var sortIndex = 0
        for ((key, groupSlices) in groups) {
            val (seriesUid, forUid) = key
            val normal = Geometry.normal(groupSlices.first().row!!, groupSlices.first().col!!)
            // Spatial sort truth = projection onto the normal, in mm. InstanceNumber is ignored here.
            val sorted = groupSlices.sortedBy { Geometry.projection(it.position!!, normal) }
            val projections = sorted.map { Geometry.projection(it.position!!, normal) }
            val deltas = projections.zipWithNext { a, b -> b - a }
            val positive = deltas.filter { it > 0 }
            val median = if (positive.isEmpty()) 0.0 else median(positive)

            val boundaries = mutableSetOf<Int>() // index i means a break BEFORE sorted[i]
            for (i in 1 until sorted.size) {
                val d = projections[i] - projections[i - 1]
                if (abs(d) <= params.duplicateToleranceMm) {
                    diagnostics += diag(
                        sorted[i].instanceId, "DUPLICATE_POSITION",
                        "duplicate spatial position (Δ=${"%.4f".format(d)} mm) between ${sorted[i - 1].sliceKey} and ${sorted[i].sliceKey}; branch created, not merged",
                        """{"a":"${sorted[i - 1].sliceKey}","b":"${sorted[i].sliceKey}","deltaMm":$d,"toleranceMm":${params.duplicateToleranceMm}}"""
                    )
                    boundaries += i
                } else if (median > 0 && (d > params.spacingJumpFactor * median || d < median / params.spacingJumpFactor)) {
                    diagnostics += diag(
                        sorted[i].instanceId, "SPACING_JUMP",
                        "local spacing jump ${"%.4f".format(d)} mm vs median ${"%.4f".format(median)} mm at ${sorted[i].sliceKey}; branch created, not merged",
                        """{"sliceKey":"${sorted[i].sliceKey}","deltaMm":$d,"medianMm":$median,"jumpFactor":${params.spacingJumpFactor}}"""
                    )
                    boundaries += i
                }
            }

            val groupKey = "$seriesUid|$forUid"
            // Candidate A: the whole group as one series candidate (always kept).
            candidates += buildCandidate("series", "$groupKey|all", sorted, projections, median, sortIndex++)
            branchDescriptions += "$groupKey:all(${sorted.size} slices)"
            // Candidate B…: legal split branches at every duplicate/jump boundary.
            if (boundaries.isNotEmpty()) {
                val segments = mutableListOf<Pair<Int, Int>>() // [from, until)
                var from = 0
                for (b in boundaries.sorted()) { segments += from to b; from = b }
                segments += from to sorted.size
                segments.filter { it.second - it.first >= 1 }.forEachIndexed { segIdx, (f, u) ->
                    val sub = sorted.subList(f, u)
                    val subProj = projections.subList(f, u)
                    candidates += buildCandidate("volume", "$groupKey|split#$segIdx", sub, subProj, median, sortIndex++)
                    branchDescriptions += "$groupKey:split#$segIdx(${sub.size} slices)"
                }
            }
        }

        val basis = JudgementBasis(
            orthogonalityTolerance = params.orthogonalityTolerance,
            duplicateToleranceMm = params.duplicateToleranceMm,
            spacingJumpFactor = params.spacingJumpFactor,
            branches = branchDescriptions,
            diagnostics = diagnostics.map { it.code }
        )
        return GroupingResult(candidates, diagnostics, basis)
    }

    private fun buildCandidate(
        kind: String, branchKey: String, slices: List<Slice>, projections: List<Double>,
        medianSpacing: Double, sortIndex: Int
    ): CandidateRec {
        val id = stableId("cand", interpretationId, branchKey, slices.joinToString(",") { it.instanceId })
        val label = "${kind}:${branchKey} (${slices.size} slices)"
        val members = slices.mapIndexed { i, s ->
            val residual = if (i == 0) 0.0 else (projections[i] - projections[i - 1]) - medianSpacing
            MemberRec(id, s.instanceId, projections[i], residual, i)
        }
        return CandidateRec(id, interpretationId, kind, branchKey, label, "proposed", sortIndex, members)
    }

    private fun expandMultiframe(inst: InstanceRec, diagnostics: MutableList<DiagnosticRec>): List<Slice> {
        val mf = try {
            Json.parseToJsonElement(inst.multiframeJson!!).jsonObject
        } catch (e: Exception) {
            diagnostics += diag(inst.id, "UNCONVERTIBLE_VALUE", "multiframe functional groups unparseable; raw JSON left in place", """{"tag":"PerFrameFunctionalGroupsSequence"}""")
            return emptyList()
        }
        val shared = mf["shared"]?.jsonObject ?: JsonObject(emptyMap())
        val perFrame = mf["perFrame"]?.jsonArray ?: return emptyList()
        return perFrame.mapIndexedNotNull { idx, frameEl ->
            val frame = frameEl.jsonObject
            fun tag(name: String): String? =
                frame[name]?.jsonPrimitive?.content ?: shared[name]?.jsonPrimitive?.content
            sliceFrom(
                inst.id, "${inst.sliceKey}#frame-${idx + 1}", inst.seriesInstanceUid, inst.frameOfReferenceUid,
                inst.transferSyntaxUid, inst.instanceNumber,
                tag("imagePositionPatient"), tag("imageOrientationPatient") ?: inst.imageOrientationPatient,
                tag("pixelSpacing") ?: inst.pixelSpacing,
                isFrame = true, diagnostics = diagnostics, ownerId = inst.id
            )
        }
    }

    private fun sliceFrom(
        instanceId: String, sliceKey: String, seriesUid: String?, forUid: String?,
        transferSyntax: String?, instanceNumber: Int?,
        ipp: String?, iop: String?, ps: String?,
        isFrame: Boolean, diagnostics: MutableList<DiagnosticRec>, ownerId: String
    ): Slice {
        var position: Vec3? = null
        var row: Vec3? = null
        var col: Vec3? = null
        var spacing: Pair<Double, Double>? = null
        if (ipp != null) {
            position = Geometry.parseVec3(ipp)
            if (position == null) diagnostics += diag(ownerId, "UNCONVERTIBLE_VALUE", "ImagePositionPatient '$ipp' unconvertible; raw value left in place", """{"tag":"ImagePositionPatient","raw":"$ipp"}""")
        }
        if (iop != null) {
            val v6 = Geometry.parseVec6(iop)
            if (v6 == null) {
                diagnostics += diag(ownerId, "UNCONVERTIBLE_VALUE", "ImageOrientationPatient '$iop' unconvertible; raw value left in place", """{"tag":"ImageOrientationPatient","raw":"$iop"}""")
            } else { row = v6.first; col = v6.second }
        }
        if (ps != null) {
            spacing = Geometry.parsePair(ps)
            if (spacing == null) diagnostics += diag(ownerId, "UNCONVERTIBLE_VALUE", "PixelSpacing '$ps' unconvertible; raw value left in place", """{"tag":"PixelSpacing","raw":"$ps"}""")
        }
        return Slice(instanceId, sliceKey, seriesUid, forUid, transferSyntax, instanceNumber, position, row, col, spacing, isFrame)
    }

    private fun diag(instanceId: String?, code: String, message: String, detailJson: String) =
        DiagnosticRec(stableId("diag", interpretationId, instanceId, code, detailJson), interpretationId, instanceId, code, message, detailJson)

    companion object {
        fun median(values: List<Double>): Double {
            val s = values.sorted()
            return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2.0
        }
    }
}
