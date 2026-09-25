package app

import kotlin.math.abs
import kotlin.math.sin

object CandidateKind {
    const val CONSISTENT = "CONSISTENT_SERIES"
    const val SPLIT = "SPLIT_SPACING_JUMP"
    const val DUP_VARIANT = "DUPLICATE_POSITION_VARIANT"
    const val FOR_FORK = "FRAME_OF_REFERENCE_FORK"
    const val MULTIFRAME = "MULTIFRAME_EXPANSION"
    const val QUARANTINE = "QUARANTINE_NON_ORTHOGONAL"
    const val UNGEOMETRIZED = "UNGEOMETRIZED_MISSING_GEOMETRY"
}

data class Candidate(
    val id: String,
    val kind: String,
    val seriesUid: String,
    val frameOfReferenceUid: String?,
    val members: List<String>,               // instance ids (or "id#fK" for frames), spatial order
    val projectionMm: Map<String, Double>,   // member -> t along normal, mm, LPS
    val spacingResidualsMm: Map<String, Double>,
    val normalLps: Vec3?,
    val diagnostics: List<Diag>,
    val paramKeysUsed: Set<String>
)

data class GroupEvent(val kind: String, val payload: Map<String, String>)

data class GroupingResult(val candidates: List<Candidate>, val events: List<GroupEvent>)

/**
 * Geometry grouping engine.
 * Rules: InstanceNumber is never spatial truth; orientation is checked for
 * orthonormality within tolerance first; spacing jumps, duplicate positions and
 * differing FrameOfReferenceUIDs fork into branches instead of forced merges.
 */
class GeometryEngine(private val params: Params) {

    fun group(instances: List<DerivedGeometry>): GroupingResult {
        val events = mutableListOf<GroupEvent>()
        val candidates = mutableListOf<Candidate>()
        val sorted = instances.sortedBy { it.instanceId }

        // UID conflict: same SOPInstanceUID with differing geometry -> both retained.
        sorted.groupBy { it.sopUid }.filter { it.key.isNotEmpty() }.forEach { (sop, group) ->
            if (group.size > 1) {
                val distinct = group.map { it.positionMm?.fmt() }.distinct()
                if (distinct.size > 1) {
                    events += GroupEvent(DiagKind.UID_CONFLICT, mapOf(
                        "sopInstanceUid" to sop,
                        "instances" to group.joinToString(",") { it.instanceId },
                        "positionsMmLps" to distinct.joinToString("|"),
                        "resolution" to "both retained; no silent merge"
                    ))
                }
            }
        }

        // Unsupported transfer syntax: metadata retained, pixel decode flagged.
        sorted.forEach { g ->
            g.diagnostics.filter { it.kind == DiagKind.UNSUPPORTED_TS }.forEach { d ->
                events += GroupEvent(DiagKind.UNSUPPORTED_TS, mapOf(
                    "instance" to g.instanceId,
                    "transferSyntax" to g.transferSyntax,
                    "resolution" to "metadata retained; pixel decode skipped"
                ))
            }
        }

        val bySeries = sorted.groupBy { it.seriesUid }
        var candSeq = 0
        fun nextId(seriesShort: String, kind: String): String = "cand-$seriesShort-$kind-${++candSeq}"

        bySeries.forEach { (seriesUid, members) ->
            val seriesShort = seriesUid.takeLast(4).ifEmpty { "none" }

            // FrameOfReference fork: never merge different FoR into one volume.
            val byFor = members.groupBy { it.frameOfReferenceUid }
            if (byFor.size > 1) {
                events += GroupEvent(DiagKind.FORK_FOR, mapOf(
                    "seriesUid" to seriesUid,
                    "frameOfReferenceUids" to byFor.keys.joinToString(","),
                    "resolution" to "separate candidate branches; not merged"
                ))
            }

            byFor.forEach { (forUid, forMembers) ->
                val missing = forMembers.filter { g -> g.diagnostics.any { it.kind == DiagKind.MISSING_GEOMETRY } }
                missing.forEach { g ->
                    events += GroupEvent(DiagKind.MISSING_GEOMETRY, mapOf(
                        "instance" to g.instanceId, "seriesUid" to seriesUid,
                        "resolution" to "kept in UNGEOMETRIZED candidate with diagnostic"
                    ))
                }
                if (missing.isNotEmpty()) {
                    candidates += Candidate(
                        id = nextId(seriesShort, CandidateKind.UNGEOMETRIZED),
                        kind = CandidateKind.UNGEOMETRIZED,
                        seriesUid = seriesUid, frameOfReferenceUid = forUid,
                        members = missing.map { it.instanceId },
                        projectionMm = emptyMap(), spacingResidualsMm = emptyMap(),
                        normalLps = null,
                        diagnostics = missing.flatMap { it.diagnostics.filter { d -> d.kind == DiagKind.MISSING_GEOMETRY } },
                        paramKeysUsed = emptySet()
                    )
                }

                var spatial = forMembers.filter { it.positionMm != null && it.rowDir != null && it.colDir != null }

                // Multiframe expansion: frames become slices via per-frame functional groups.
                val multiframe = spatial.filter { (it.numberOfFrames ?: 1) > 1 && it.framePositionsMm != null }
                multiframe.forEach { g ->
                    events += GroupEvent(DiagKind.MULTIFRAME, mapOf(
                        "instance" to g.instanceId,
                        "numberOfFrames" to (g.numberOfFrames ?: 0).toString(),
                        "resolution" to "frames expanded as slices from PerFrameFunctionalGroups"
                    ))
                }

                // Orientation orthonormality check FIRST, with tolerance.
                val orthTolSin = sin(Math.toRadians(params.orthogonalityToleranceDeg))
                val bad = spatial.filter { g ->
                    val r = g.rowDir!!; val c = g.colDir!!
                    abs(r.norm() - 1.0) > 1e-3 || abs(c.norm() - 1.0) > 1e-3 || abs(r.normalized().dot(c.normalized())) > orthTolSin
                }
                bad.forEach { g ->
                    val dev = Math.toDegrees(kotlin.math.asin(abs(g.rowDir!!.normalized().dot(g.colDir!!.normalized())).coerceAtMost(1.0)))
                    events += GroupEvent(DiagKind.NON_ORTHOGONAL, mapOf(
                        "instance" to g.instanceId,
                        "deviationDeg" to "%.4f".format(dev),
                        "toleranceDeg" to params.orthogonalityToleranceDeg.toString(),
                        "resolution" to "quarantined from spatial sort; raw orientation retained"
                    ))
                }
                if (bad.isNotEmpty()) {
                    candidates += Candidate(
                        id = nextId(seriesShort, CandidateKind.QUARANTINE),
                        kind = CandidateKind.QUARANTINE,
                        seriesUid = seriesUid, frameOfReferenceUid = forUid,
                        members = bad.map { it.instanceId },
                        projectionMm = emptyMap(), spacingResidualsMm = emptyMap(),
                        normalLps = null,
                        diagnostics = bad.map { Diag(DiagKind.NON_ORTHOGONAL, "orientation deviates beyond ${params.orthogonalityToleranceDeg} deg", "ImageOrientationPatient", it.tagsRawIop()) },
                        paramKeysUsed = setOf(RuleSet.PARAM_ORTH_TOL_DEG)
                    )
                }
                spatial = spatial.filter { it !in bad }
                if (spatial.isEmpty()) return@forEach

                // Normal from mean of row x col.
                val normal = spatial.map { it.rowDir!!.normalized().cross(it.colDir!!.normalized()) }
                    .reduce { a, b -> Vec3(a.x + b.x, a.y + b.y, a.z + b.z) }.normalized()

                // Slice units: single-frame instances + expanded multiframe frames.
                data class Slice(val ref: String, val pos: Vec3, val inst: DerivedGeometry)
                val slices = mutableListOf<Slice>()
                spatial.forEach { g ->
                    if ((g.numberOfFrames ?: 1) > 1 && g.framePositionsMm != null) {
                        g.framePositionsMm!!.forEachIndexed { i, p -> slices += Slice("${g.instanceId}#f${i + 1}", p, g) }
                    } else slices += Slice(g.instanceId, g.positionMm!!, g)
                }
                val tOf = { s: Slice -> s.pos.dot(normal) }
                val byT = slices.sortedBy(tOf)

                // InstanceNumber is not spatial truth: report disagreement, sort by projection.
                val inOrder = slices.sortedBy { it.inst.instanceNumber ?: Int.MAX_VALUE }.map { it.ref }
                val tOrder = byT.map { it.ref }
                if (inOrder != tOrder && slices.any { it.inst.instanceNumber != null }) {
                    events += GroupEvent(DiagKind.INSTANCE_NUMBER_IGNORED, mapOf(
                        "seriesUid" to seriesUid,
                        "instanceNumberOrder" to inOrder.joinToString(","),
                        "projectionOrder" to tOrder.joinToString(","),
                        "resolution" to "projection coordinate along slice normal used as spatial truth"
                    ))
                }

                // Duplicate positions: fork variants instead of dedup.
                val dupEps = params.duplicatePositionEpsilonMm
                val clusters = mutableListOf<MutableList<Slice>>()
                byT.forEach { s ->
                    val last = clusters.lastOrNull()
                    if (last != null && abs(tOf(s) - tOf(last.last())) <= dupEps) last += s else clusters += mutableListOf(s)
                }
                val dupClusters = clusters.filter { it.size > 1 }
                dupClusters.forEach { c ->
                    events += GroupEvent(DiagKind.DUPLICATE_POSITION, mapOf(
                        "seriesUid" to seriesUid,
                        "instances" to c.joinToString(",") { it.ref },
                        "positionMmLps" to "%.4f".format(tOf(c.first())),
                        "resolution" to "variant candidates forked; both slices retained"
                    ))
                }

                // Build variant slice-lists (vary one cluster at a time to bound count).
                val variants = mutableListOf<List<Slice>>()
                if (dupClusters.isEmpty()) {
                    variants += byT
                } else {
                    val cluster = dupClusters.first()
                    cluster.forEach { chosen ->
                        variants += clusters.flatMap { c -> if (c.size > 1) listOf(if (c === cluster) chosen else c.first()) else c }
                            .sortedBy(tOf)
                    }
                }

                variants.forEachIndexed { vi, variant ->
                    val ts = variant.map(tOf)
                    val steps = ts.zipWithNext { a, b -> b - a }
                    val median = if (steps.isEmpty()) 0.0 else steps.sorted()[steps.size / 2]
                    val residuals = mutableMapOf<String, Double>()
                    variant.forEachIndexed { i, s ->
                        if (i > 0) residuals[s.ref] = (ts[i] - ts[i - 1]) - median
                    }
                    val jumps = variant.filter { residuals[it.ref]?.let { r -> abs(r) > params.spacingResidualToleranceMm } == true }
                    val kind = when {
                        dupClusters.isNotEmpty() -> CandidateKind.DUP_VARIANT
                        else -> CandidateKind.CONSISTENT
                    }
                    val projMap = variant.mapIndexed { i, s -> s.ref to ts[i] }.toMap()
                    val paramKeys = mutableSetOf(RuleSet.PARAM_SPACING_TOL_MM, RuleSet.PARAM_ORTH_TOL_DEG)
                    if (dupClusters.isNotEmpty()) paramKeys += RuleSet.PARAM_DUP_EPS_MM
                    val baseId = nextId(seriesShort, kind)
                    candidates += Candidate(
                        id = baseId, kind = kind,
                        seriesUid = seriesUid, frameOfReferenceUid = forUid,
                        members = variant.map { it.ref },
                        projectionMm = projMap, spacingResidualsMm = residuals,
                        normalLps = normal,
                        diagnostics = emptyList(),
                        paramKeysUsed = paramKeys
                    )
                    events += GroupEvent("CANDIDATE_PROPOSED", mapOf(
                        "candidate" to baseId, "kind" to kind,
                        "members" to variant.joinToString(",") { it.ref },
                        "medianStepMm" to "%.4f".format(median)
                    ))
                    jumps.forEach { s ->
                        events += GroupEvent(DiagKind.SPACING_JUMP, mapOf(
                            "candidate" to baseId,
                            "at" to s.ref,
                            "residualMm" to "%.4f".format(residuals[s.ref] ?: 0.0),
                            "toleranceMm" to params.spacingResidualToleranceMm.toString(),
                            "resolution" to "split candidate forked at jump; whole-series candidate kept"
                        ))
                    }
                    if (jumps.isNotEmpty()) {
                        // Split at each jump boundary: multi-candidate, no forced merge.
                        val jumpIdx = variant.indices.filter { i -> i > 0 && variant[i] in jumps }
                        val bounds = listOf(0) + jumpIdx + listOf(variant.size)
                        bounds.zipWithNext { a, b ->
                            val part = variant.subList(a, b)
                            if (part.size >= 2 || variants.size == 1) {
                                val sid = nextId(seriesShort, CandidateKind.SPLIT)
                                candidates += Candidate(
                                    id = sid, kind = CandidateKind.SPLIT,
                                    seriesUid = seriesUid, frameOfReferenceUid = forUid,
                                    members = part.map { it.ref },
                                    projectionMm = part.map { it.ref to tOf(it) }.toMap(),
                                    spacingResidualsMm = emptyMap(),
                                    normalLps = normal, diagnostics = emptyList(),
                                    paramKeysUsed = setOf(RuleSet.PARAM_SPACING_TOL_MM)
                                )
                                events += GroupEvent("CANDIDATE_PROPOSED", mapOf(
                                    "candidate" to sid, "kind" to CandidateKind.SPLIT,
                                    "members" to part.joinToString(",") { it.ref },
                                    "parentCandidate" to baseId
                                ))
                            }
                        }
                    }
                }
            }
        }
        return GroupingResult(candidates, events)
    }

    private fun DerivedGeometry.tagsRawIop(): String? = null
}
