package app

import kotlin.math.abs
import kotlin.math.sqrt

data class Vec3(val x: Double, val y: Double, val z: Double) {
    operator fun minus(o: Vec3) = Vec3(x - o.x, y - o.y, z - o.z)
    fun dot(o: Vec3) = x * o.x + y * o.y + z * o.z
    fun cross(o: Vec3) = Vec3(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x)
    fun norm() = sqrt(dot(this))
    fun normalized(): Vec3 { val n = norm(); return if (n == 0.0) this else Vec3(x / n, y / n, z / n) }
}

/** Versioned rule parameters. Changing these creates a new rule_version. */
data class RuleParams(
    val version: Int,
    val orthoTol: Double = 0.01,          // max |row·col| for accepted orientation
    val spacingJumpTolMm: Double = 0.25,  // max residual vs local median gap
    val duplicatePosTolMm: Double = 0.01, // max |Δprojection| treated as duplicate
)

/** One spatially placeable frame: a whole single-frame instance or one frame of a multiframe object. */
data class FrameRef(
    val instanceId: String,
    val sopInstanceUid: String,
    val seriesInstanceUid: String,
    val frameOfReferenceUid: String?,
    val instanceNumber: Int?,
    val frameIndex: Int?,        // null for single-frame objects
    val position: Vec3,          // ImagePositionPatient, mm, patient coordinate basis (LPS)
    val rowCos: Vec3,
    val colCos: Vec3,
    val pixelSpacingRowMm: Double,
    val pixelSpacingColMm: Double,
)

data class Diagnostic(
    val kind: String,            // machine-readable event kind
    val message: String,
    val instanceIds: List<String>,
    val detail: Map<String, String> = emptyMap(),
)

data class PlacedFrame(
    val frame: FrameRef,
    val projectionMm: Double,    // dot(position, normal) — spatial truth, never InstanceNumber
)

data class CandidateSeries(
    val candidateKey: String,    // deterministic key: series|for|segment|variant
    val seriesInstanceUid: String,
    val frameOfReferenceUid: String,
    val frames: List<PlacedFrame>,           // sorted by projectionMm
    val normal: Vec3,
    val spacingsMm: List<Double>,            // gaps between consecutive projections
    val residualsMm: List<Double>,           // |gap - nominal| per gap
    val degraded: Boolean,                   // e.g. orientation non-orthogonal
    val branchReason: String?,               // why this is a branch (duplicate/spacing jump/…)
)

data class AnalysisResult(
    val candidates: List<CandidateSeries>,
    val diagnostics: List<Diagnostic>,
    val unplaced: List<String>,  // instance ids kept in place but excluded from spatial sort
)

object GeometryEngine {

    /** Group frames into reviewable series/volume candidates. Never merges across
     *  FrameOfReferenceUID; never trusts InstanceNumber for spatial ordering. */
    fun analyze(frames: List<FrameRef>, params: RuleParams): AnalysisResult {
        val diagnostics = mutableListOf<Diagnostic>()
        val candidates = mutableListOf<CandidateSeries>()

        // UID conflict: same SOPInstanceUID carried by two different instance records.
        frames.groupBy { it.sopInstanceUid }.filter { it.value.map { f -> f.instanceId }.toSet().size > 1 }
            .forEach { (sop, fs) ->
                diagnostics += Diagnostic("uid_conflict",
                    "SOPInstanceUID $sop carried by ${fs.map { it.instanceId }.toSet().size} distinct instances",
                    fs.map { it.instanceId }.distinct(), mapOf("sop_instance_uid" to sop))
            }

        // Partition by (series, frame of reference) — different FoR produce branches, never merges.
        val groups = frames.groupBy { it.seriesInstanceUid to (it.frameOfReferenceUid ?: "<none>") }
        val forCounts = frames.mapNotNull { it.frameOfReferenceUid }.toSet()
        if (forCounts.size > 1) {
            val bySeries = frames.groupBy { it.seriesInstanceUid }
            bySeries.forEach { (series, fs) ->
                val fors = fs.mapNotNull { it.frameOfReferenceUid }.toSet()
                if (fors.size > 1) diagnostics += Diagnostic("frame_of_reference_branch",
                    "Series $series spans ${fors.size} FrameOfReferenceUIDs; kept as separate branches",
                    fs.map { it.instanceId }.distinct(), mapOf("series_instance_uid" to series))
            }
        }

        for ((key, groupFrames) in groups) {
            val (seriesUid, forUid) = key
            // 1. Orientation first: orthogonality + tolerance, before any projection.
            val degraded = mutableSetOf<String>()
            for (f in groupFrames) {
                val d = f.rowCos.normalized().dot(f.colCos.normalized())
                if (abs(d) > params.orthoTol) {
                    degraded += f.instanceId
                    diagnostics += Diagnostic("orientation_non_orthogonal",
                        "row·col = %.4f exceeds ortho tolerance %.4f".format(d, params.orthoTol),
                        listOf(f.instanceId),
                        mapOf("row_dot_col" to "%.6f".format(d), "ortho_tol" to params.orthoTol.toString()))
                }
            }
            // 2. Projection onto slice normal; sort by projection (InstanceNumber is evidence only).
            val normal = groupFrames.first().let { it.rowCos.cross(it.colCos).normalized() }
            val placed = groupFrames.map { PlacedFrame(it, it.position.dot(normal)) }
                .sortedWith(compareBy({ it.projectionMm }, { it.frame.instanceId }))
            // InstanceNumber cross-check: report disagreement, never re-order by it.
            val byIN = groupFrames.filter { it.instanceNumber != null }.sortedBy { it.instanceNumber }
            if (byIN.size == groupFrames.size) {
                val projOrder = placed.map { it.frame.instanceId }
                val inOrder = byIN.map { it.instanceId }
                if (projOrder != inOrder) diagnostics += Diagnostic("instance_number_order_mismatch",
                    "InstanceNumber order disagrees with spatial projection order; projection kept as truth",
                    projOrder, mapOf("projection_order" to projOrder.joinToString(","),
                                     "instance_number_order" to inOrder.joinToString(",")))
            }
            // 3. Gaps, residuals vs local nominal (median gap).
            val gaps = placed.zipWithNext { a, b -> b.projectionMm - a.projectionMm }
            val nominal = if (gaps.isEmpty()) 0.0 else gaps.sorted()[gaps.size / 2]
            val residuals = gaps.map { abs(it - nominal) }

            // 4. Duplicate positions: keep every legal ordering as separate candidates.
            val dupPairs = placed.zipWithNext().filter { (a, b) -> abs(b.projectionMm - a.projectionMm) <= params.duplicatePosTolMm }
            if (dupPairs.isNotEmpty()) {
                diagnostics += Diagnostic("duplicate_position",
                    "${dupPairs.size} duplicate position(s) within %.3f mm".format(params.duplicatePosTolMm),
                    dupPairs.flatMap { (a, b) -> listOf(a.frame.instanceId, b.frame.instanceId) }.distinct(),
                    mapOf("dup_pos_tol_mm" to params.duplicatePosTolMm.toString()))
                // Both legal orders of each duplicated pair become branch candidates.
                candidates += candidate("$seriesUid|$forUid|dup-a", seriesUid, forUid, placed, normal, gaps, residuals,
                    degraded.any { id -> groupFrames.any { it.instanceId == id } }, "duplicate_position:order_a")
                val swapped = placed.toMutableList()
                for ((a, b) in dupPairs) {
                    val ia = swapped.indexOf(a); val ib = swapped.indexOf(b)
                    if (ia >= 0 && ib >= 0) { swapped[ia] = b; swapped[ib] = a }
                }
                candidates += candidate("$seriesUid|$forUid|dup-b", seriesUid, forUid, swapped, normal, gaps, residuals,
                    degraded.isNotEmpty(), "duplicate_position:order_b")
                continue
            }

            // 5. Local spacing jumps: split into segment branches instead of force-merging.
            val jumpIdx = residuals.indices.filter { residuals[it] > params.spacingJumpTolMm }
            if (jumpIdx.isNotEmpty()) {
                diagnostics += Diagnostic("spacing_jump",
                    "local spacing residual exceeds %.3f mm at gap(s) %s".format(params.spacingJumpTolMm, jumpIdx.joinToString(",")),
                    placed.flatMap { listOf(it.frame.instanceId) },
                    mapOf("residuals_mm" to residuals.joinToString(",") { "%.4f".format(it) },
                          "spacing_jump_tol_mm" to params.spacingJumpTolMm.toString()))
                var segStart = 0
                var segNo = 1
                val bounds = jumpIdx.map { it + 1 } + placed.size
                for (b in bounds) {
                    val seg = placed.subList(segStart, b)
                    if (seg.isNotEmpty()) {
                        val segGaps = seg.zipWithNext { a, c -> c.projectionMm - a.projectionMm }
                        val segNom = if (segGaps.isEmpty()) 0.0 else segGaps.sorted()[segGaps.size / 2]
                        candidates += candidate("$seriesUid|$forUid|seg$segNo", seriesUid, forUid, seg, normal,
                            segGaps, segGaps.map { abs(it - segNom) }, degraded.isNotEmpty(),
                            "spacing_jump:segment_$segNo")
                        segNo++
                    }
                    segStart = b
                }
                continue
            }

            candidates += candidate("$seriesUid|$forUid|main", seriesUid, forUid, placed, normal, gaps, residuals,
                degraded.isNotEmpty(), if (degraded.isNotEmpty()) "orientation_non_orthogonal" else null)
        }
        return AnalysisResult(candidates, diagnostics, emptyList())
    }

    private fun candidate(key: String, seriesUid: String, forUid: String, frames: List<PlacedFrame>,
                          normal: Vec3, gaps: List<Double>, residuals: List<Double>,
                          degraded: Boolean, branchReason: String?) =
        CandidateSeries(key, seriesUid, forUid, frames, normal, gaps, residuals, degraded, branchReason)
}

/** Extract placeable frames from a parsed DICOM instance; multiframe objects yield one FrameRef per frame. */
fun extractFrames(instanceId: String, dcm: DicomFile): Pair<List<FrameRef>, List<Diagnostic>> {
    val diags = mutableListOf<Diagnostic>()
    val sop = dcm.textOf(0x0008, 0x0018) ?: return emptyList<FrameRef>() to
        listOf(Diagnostic("missing_geometry", "no SOPInstanceUID", listOf(instanceId)))
    val series = dcm.textOf(0x0020, 0x000E) ?: "<no-series>"
    val forUid = dcm.textOf(0x0020, 0x0052)
    val inum = dcm.textOf(0x0020, 0x0013)?.toIntOrNull()

    fun vec(s: String?): Vec3? = s?.split("\\")?.mapNotNull { it.toDoubleOrNull() }
        ?.takeIf { it.size == 3 }?.let { Vec3(it[0], it[1], it[2]) }

    val iop = dcm.textOf(0x0020, 0x0037)?.split("\\")?.mapNotNull { it.toDoubleOrNull() }
    val ps = dcm.textOf(0x0028, 0x0030)?.split("\\")?.mapNotNull { it.toDoubleOrNull() }
    if (iop == null || iop.size != 6 || ps == null || ps.size < 2) {
        diags += Diagnostic("missing_geometry",
            "ImageOrientationPatient/PixelSpacing missing or unparsable; kept in place with original tags",
            listOf(instanceId))
        return emptyList<FrameRef>() to diags
    }
    val row = Vec3(iop[0], iop[1], iop[2]); val col = Vec3(iop[3], iop[4], iop[5])

    // Multiframe: per-frame plane positions from functional groups.
    val perFrame = dcm.find(0x5200, 0x9230)
    if (perFrame != null && perFrame.children.isNotEmpty()) {
        val frames = mutableListOf<FrameRef>()
        var idx = 0
        // children of PerFrameFunctionalGroupsSequence are the flattened tags of each item;
        // each item contains a PlanePositionSequence whose child is ImagePositionPatient.
        // The parser flattens items, so regroup by PlanePositionSequence occurrences.
        val positions = mutableListOf<Vec3>()
        collectPositions(perFrame.children, positions)
        for (p in positions) {
            idx++
            frames += FrameRef(instanceId, sop, series, forUid, inum, idx, p, row, col, ps[0], ps[1])
        }
        if (frames.isNotEmpty()) {
            diags += Diagnostic("multiframe_object",
                "enhanced object expanded to ${frames.size} frames via PerFrameFunctionalGroupsSequence",
                listOf(instanceId), mapOf("frames" to frames.size.toString()))
            return frames to diags
        }
    }
    val pos = vec(dcm.textOf(0x0020, 0x0032))
    if (pos == null) {
        diags += Diagnostic("missing_geometry",
            "ImagePositionPatient missing; kept in place with original tags", listOf(instanceId))
        return emptyList<FrameRef>() to diags
    }
    return listOf(FrameRef(instanceId, sop, series, forUid, inum, null, pos, row, col, ps[0], ps[1])) to diags
}

private fun collectPositions(tags: List<DicomTag>, out: MutableList<Vec3>) {
    for (t in tags) {
        if (t.group == 0x0020 && t.element == 0x0032) {
            val v = t.text.split("\\").mapNotNull { it.toDoubleOrNull() }
            if (v.size == 3) out += Vec3(v[0], v[1], v[2])
        }
        collectPositions(t.children, out)
    }
}
