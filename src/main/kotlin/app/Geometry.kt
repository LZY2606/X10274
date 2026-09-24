package app

import kotlin.math.abs
import kotlin.math.sqrt

/** 3D vector in the DICOM Reference Coordinate System (RCS), unit mm. */
data class Vec3(val x: Double, val y: Double, val z: Double) {
    operator fun minus(o: Vec3) = Vec3(x - o.x, y - o.y, z - o.z)
    operator fun plus(o: Vec3) = Vec3(x + o.x, y + o.y, z + o.z)
    operator fun times(s: Double) = Vec3(x * s, y * s, z * s)
    infix fun dot(o: Vec3) = x * o.x + y * o.y + z * o.z
    infix fun cross(o: Vec3) = Vec3(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x)
    fun norm() = sqrt(this dot this)
    fun normalized(): Vec3 = this * (1.0 / norm())
    companion object {
        fun of(v: List<Double>) = Vec3(v[0], v[1], v[2])
    }
}

/** Tunable derivation parameters. Changing them creates a new interpretation. */
@kotlinx.serialization.Serializable
data class Params(
    val orthoTol: Double = 1e-3,      // dimensionless, |row·col| tolerance after normalization
    val spacingTolFrac: Double = 0.25, // fraction of median spacing tolerated as residual, unit mm-based
    val dupTolMm: Double = 0.01        // mm, positions closer than this are duplicates
) {
    companion object { const val VERSION = "geom-rules/1" }
}

/** Machine-readable diagnostic attached to derived objects. */
@kotlinx.serialization.Serializable
data class Diagnostic(
    val kind: String,
    val message: String,
    val unit: String? = null,   // e.g. "mm", "dimensionless"
    val basis: String? = null,  // e.g. "RCS" (DICOM Reference Coordinate System)
    val value: Double? = null,
    val tolerance: Double? = null
)

/** One derived slice (single-frame instance or one frame of a multi-frame object). */
@kotlinx.serialization.Serializable
data class Slice(
    val sourceId: String,
    val sopInstanceUid: String,
    val frameIndex: Int? = null,
    val position: Vec3? = null,       // mm, RCS
    val row: Vec3? = null,            // direction cosine, dimensionless
    val col: Vec3? = null,
    val normal: Vec3? = null,
    val projection: Double? = null,   // mm along normal
    val pixelSpacingMm: List<Double>? = null,
    val diagnostics: List<Diagnostic> = emptyList()
)

@kotlinx.serialization.Serializable
data class Candidate(
    val id: String,
    val groupKey: String,          // seriesInstanceUid + frameOfReferenceUid
    val branchReason: String,      // BASELINE, SPLIT_AT_SPACING_JUMP, DUPLICATE_POSITION_*, NON_ORTHOGONAL_QUARANTINE, ...
    val memberRefs: List<String>,  // sourceId[#frame]
    val orderedSlices: List<Slice>,
    val residualsMm: List<Double>, // spacing residual per consecutive pair, unit mm
    val medianSpacingMm: Double?,
    val status: String = "PROPOSED" // PROPOSED | ACCEPTED | REJECTED
)

@kotlinx.serialization.Serializable
data class GroupingResult(
    val candidates: List<Candidate>,
    val events: List<GeometryEvent>
)

/** Machine-readable judgment event produced by derivation. */
@kotlinx.serialization.Serializable
data class GeometryEvent(
    val kind: String,                 // NON_ORTHOGONAL_ORIENTATION, DUPLICATE_POSITION, SPACING_JUMP, ...
    val status: String,               // BRANCHED | QUARANTINED | EXCLUDED | NOTED
    val inputRefs: List<String>,      // source ids / tag paths
    val affectedObjects: List<String>,
    val detail: String,
    val unit: String? = null,
    val basis: String? = null
)

object Geometry {

    val SUPPORTED_TRANSFER_SYNTAXES = setOf(
        "1.2.840.10008.1.2",    // Implicit VR Little Endian
        "1.2.840.10008.1.2.1",  // Explicit VR Little Endian
        "1.2.840.10008.1.2.2"   // Explicit VR Big Endian
    )

    data class OrientationCheck(
        val orthogonal: Boolean,
        val rowNorm: Double,
        val colNorm: Double,
        val dot: Double,
        val normal: Vec3
    )

    /** Rule: orientation vectors are checked for orthonormality within tolerance BEFORE any sorting. */
    fun checkOrientation(iop: List<Double>, tol: Double): OrientationCheck {
        val row = Vec3.of(iop.subList(0, 3))
        val col = Vec3.of(iop.subList(3, 6))
        val rn = row.norm()
        val cn = col.norm()
        val r = row.normalized()
        val c = col.normalized()
        val d = r dot c
        return OrientationCheck(abs(d) <= tol && abs(rn - 1.0) <= tol && abs(cn - 1.0) <= tol, rn, cn, d, r cross c)
    }

    /** Derive slices from one raw instance. Raw values stay in the source; this is the normalized layer. */
    fun deriveSlices(sourceId: String, inst: RawInstance, params: Params): Pair<List<Slice>, List<GeometryEvent>> {
        val events = mutableListOf<GeometryEvent>()
        if (inst.transferSyntaxUid !in SUPPORTED_TRANSFER_SYNTAXES) {
            events += GeometryEvent(
                kind = "UNSUPPORTED_TRANSFER_SYNTAX",
                status = "EXCLUDED",
                inputRefs = listOf("$sourceId:0002,0010"),
                affectedObjects = listOf(inst.sopInstanceUid),
                detail = "TransferSyntax ${inst.transferSyntaxUid} not decodable; raw tags retained, excluded from grouping"
            )
            return emptyList<Slice>() to events
        }
        val frames = inst.frames
        val slices = mutableListOf<Slice>()
        if (frames != null && frames.isNotEmpty()) {
            events += GeometryEvent(
                kind = "MULTI_FRAME_OBJECT",
                status = "NOTED",
                inputRefs = listOf("$sourceId:5200,9230"),
                affectedObjects = listOf(inst.sopInstanceUid),
                detail = "Multi-frame object expanded into ${frames.size} per-frame functional group slices"
            )
            frames.forEachIndexed { i, fg ->
                slices += deriveOne(sourceId, inst.sopInstanceUid, i, fg.imagePositionPatient,
                    fg.imageOrientationPatient ?: inst.imageOrientationPatient, inst.pixelSpacing, params, events)
            }
        } else {
            slices += deriveOne(sourceId, inst.sopInstanceUid, null, inst.imagePositionPatient,
                inst.imageOrientationPatient, inst.pixelSpacing, params, events)
        }
        return slices to events
    }

    private fun deriveOne(
        sourceId: String, sopUid: String, frameIndex: Int?,
        ipp: List<Double>?, iop: List<Double>?, spacing: List<Double>?,
        params: Params, events: MutableList<GeometryEvent>
    ): Slice {
        val diags = mutableListOf<Diagnostic>()
        var row: Vec3? = null
        var col: Vec3? = null
        var normal: Vec3? = null
        if (iop == null || iop.size != 6) {
            diags += Diagnostic("MISSING_GEOMETRY", "ImageOrientationPatient absent or malformed", basis = "RCS")
        } else {
            val chk = checkOrientation(iop, params.orthoTol)
            row = Vec3.of(iop.subList(0, 3)).normalized()
            col = Vec3.of(iop.subList(3, 6)).normalized()
            normal = chk.normal
            if (!chk.orthogonal) {
                diags += Diagnostic(
                    "NON_ORTHOGONAL_ORIENTATION",
                    "row·col=${"%.6f".format(chk.dot)} exceeds orthoTol",
                    unit = "dimensionless", value = chk.dot, tolerance = params.orthoTol
                )
                events += GeometryEvent(
                    kind = "NON_ORTHOGONAL_ORIENTATION",
                    status = "QUARANTINED",
                    inputRefs = listOf("$sourceId:0020,0037"),
                    affectedObjects = listOf(sopUid),
                    detail = "Orientation not orthonormal within tol=${params.orthoTol}; quarantined into its own branch",
                    unit = "dimensionless"
                )
            }
        }
        var pos: Vec3? = null
        var proj: Double? = null
        if (ipp == null || ipp.size != 3) {
            diags += Diagnostic("MISSING_GEOMETRY", "ImagePositionPatient absent or malformed", unit = "mm", basis = "RCS")
        } else {
            pos = Vec3.of(ipp)
            if (normal != null) proj = pos dot normal
        }
        if (diags.any { it.kind == "MISSING_GEOMETRY" }) {
            events += GeometryEvent(
                kind = "MISSING_GEOMETRY",
                status = "QUARANTINED",
                inputRefs = listOf(sourceId),
                affectedObjects = listOf(sopUid),
                detail = "Missing spatial geometry; kept unpositioned, never sorted by InstanceNumber",
                unit = "mm", basis = "RCS"
            )
        }
        return Slice(sourceId, sopUid, frameIndex, pos, row, col, normal, proj, spacing, diags)
    }

    /**
     * Group slices into series/volume candidates.
     * Rules: InstanceNumber is never the spatial sort truth; local spacing changes, duplicate
     * positions and differing FrameOfReference produce branches, never forced merges.
     */
    fun group(
        sources: List<Pair<String, RawInstance>>,
        params: Params,
        interpretationId: String
    ): GroupingResult {
        val events = mutableListOf<GeometryEvent>()
        // UID conflict: identical SOPInstanceUID carried by sources with different content
        val bySop = sources.groupBy { it.second.sopInstanceUid }
        val conflicted = mutableSetOf<String>()
        for ((sop, list) in bySop) {
            val distinct = list.map { it.first }.distinct()
            if (distinct.size > 1) {
                conflicted += sop
                events += GeometryEvent(
                    kind = "UID_CONFLICT",
                    status = "BRANCHED",
                    inputRefs = list.map { it.first },
                    affectedObjects = listOf(sop),
                    detail = "SOPInstanceUID $sop arrives with ${distinct.size} distinct payloads; both retained, no overwrite"
                )
            }
        }

        val allSlices = mutableListOf<Slice>()
        for ((id, inst) in sources) {
            val (slices, evs) = deriveSlices(id, inst, params)
            allSlices += slices
            events += evs
        }

        // Group key: FrameOfReference + Series. Different FoR => different branch, never merged.
        val grouped = allSlices.groupBy {
            val inst = sources.first { s -> s.first == it.sourceId }.second
            "${inst.seriesInstanceUid}|${inst.frameOfReferenceUid ?: "NO_FOR"}"
        }

        val candidates = mutableListOf<Candidate>()
        for ((key, slices) in grouped.toSortedMap()) {
            val healthy = slices.filter { s -> s.diagnostics.none { it.kind == "NON_ORTHOGONAL_ORIENTATION" } }
            val quarantined = slices.filter { s -> s.diagnostics.any { it.kind == "NON_ORTHOGONAL_ORIENTATION" } }
            if (quarantined.isNotEmpty()) {
                candidates += makeCandidate(interpretationId, key, "NON_ORTHOGONAL_QUARANTINE", quarantined, params)
            }
            val positioned = healthy.filter { it.projection != null }
            val unpositioned = healthy.filter { it.projection == null }
            if (unpositioned.isNotEmpty()) {
                candidates += makeCandidate(interpretationId, key, "UNPOSITIONED_MISSING_GEOMETRY", unpositioned, params)
            }
            if (positioned.isEmpty()) continue
            // Sort by spatial projection, NOT InstanceNumber.
            val sorted = positioned.sortedWith(compareBy({ it.projection!! }, { it.sourceId }, { it.frameIndex ?: -1 }))
            candidates += buildBranches(interpretationId, key, sorted, params, events)
        }
        return GroupingResult(candidates.sortedBy { it.id }, events)
    }

    private fun buildBranches(
        interpretationId: String, key: String, sorted: List<Slice>,
        params: Params, events: MutableList<GeometryEvent>
    ): Candidate {
        val n = sorted.size
        val spacings = (0 until n - 1).map { sorted[it + 1].projection!! - sorted[it].projection!! }
        val median = if (spacings.isEmpty()) null else spacings.sorted().let { it[it.size / 2] }

        // Duplicate positions -> branch, keep both variants.
        val dupIdx = spacings.indices.filter { kotlin.math.abs(spacings[it]) <= params.dupTolMm }
        if (dupIdx.isNotEmpty()) {
            val refs = dupIdx.map { "${sorted[it].sourceId}~${sorted[it + 1].sourceId}" }
            events += GeometryEvent(
                kind = "DUPLICATE_POSITION",
                status = "BRANCHED",
                inputRefs = refs,
                affectedObjects = refs,
                detail = "Positions closer than dupTol=${params.dupTolMm} mm; kept as duplicate branch, not merged",
                unit = "mm", basis = "RCS"
            )
            return makeCandidate(interpretationId, key, "DUPLICATE_POSITION_BRANCH", sorted, params, median)
        }

        // Local spacing jump -> split branch, both candidates retained.
        if (median != null && median > 0) {
            val jumpIdx = spacings.indices.filter {
                kotlin.math.abs(spacings[it] - median) > params.spacingTolFrac * median
            }
            if (jumpIdx.isNotEmpty()) {
                val cut = jumpIdx.first()
                events += GeometryEvent(
                    kind = "SPACING_JUMP",
                    status = "BRANCHED",
                    inputRefs = listOf(sorted[cut].sourceId, sorted[cut + 1].sourceId),
                    affectedObjects = sorted.map { it.sourceId },
                    detail = "Local spacing ${"%.3f".format(spacings[cut])} mm deviates from median ${"%.3f".format(median)} mm; split candidates retained",
                    unit = "mm", basis = "RCS"
                )
                return makeCandidate(interpretationId, key, "SPLIT_AT_SPACING_JUMP@${cut + 1}", sorted, params, median)
            }
        }
        return makeCandidate(interpretationId, key, "BASELINE", sorted, params, median)
    }

    private fun makeCandidate(
        interpretationId: String, key: String, reason: String,
        slices: List<Slice>, params: Params, medianOverride: Double? = null
    ): Candidate {
        val refs = slices.map { s -> s.sourceId + (s.frameIndex?.let { "#$it" } ?: "") }
        val projections = slices.mapNotNull { it.projection }
        val residuals = projections.zipWithNext { a, b -> b - a }.let { diffs ->
            val med = medianOverride ?: diffs.sorted().let { if (it.isEmpty()) null else it[it.size / 2] }
            if (med == null) emptyList() else diffs.map { it - med }
        }
        val median = medianOverride ?: projections.zipWithNext { a, b -> b - a }
            .sorted().let { if (it.isEmpty()) null else it[it.size / 2] }
        val id = "cand-" + sha256("$interpretationId|$key|$reason|${refs.joinToString(",")}").take(12)
        return Candidate(id, key, reason, refs, slices, residuals, median)
    }

    fun sha256(s: String): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        return md.digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
