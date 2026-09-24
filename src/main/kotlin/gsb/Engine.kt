package gsb

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Derivation rule parameters. A new version is created on every revision;
 * old versions stay readable and are referenced by existing derivations.
 */
data class RuleParams(
    val version: Int,
    val orthogonalityTolerance: Double = 1e-3,       // |row . col|, unitless
    val duplicatePositionEpsilonMm: Double = 0.01,   // mm along normal
    val spacingResidualToleranceMm: Double = 0.6,    // mm
    val supportedTransferSyntaxes: List<String> = listOf(
        "1.2.840.10008.1.2",      // Implicit VR Little Endian
        "1.2.840.10008.1.2.1",    // Explicit VR Little Endian
        "1.2.840.10008.1.2.2",    // Explicit VR Big Endian
    ),
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("version", version)
        put("orthogonality_tolerance", orthogonalityTolerance)
        put("duplicate_position_epsilon_mm", duplicatePositionEpsilonMm)
        put("spacing_residual_tolerance_mm", spacingResidualToleranceMm)
        put("supported_transfer_syntaxes", JsonArray(supportedTransferSyntaxes.map { JsonPrimitive(it) }))
    }

    companion object {
        fun fromJson(o: JsonObject): RuleParams = RuleParams(
            version = (o["version"] as JsonPrimitive).content.toInt(),
            orthogonalityTolerance = (o["orthogonality_tolerance"] as JsonPrimitive).content.toDouble(),
            duplicatePositionEpsilonMm = (o["duplicate_position_epsilon_mm"] as JsonPrimitive).content.toDouble(),
            spacingResidualToleranceMm = (o["spacing_residual_tolerance_mm"] as JsonPrimitive).content.toDouble(),
            supportedTransferSyntaxes = (o["supported_transfer_syntaxes"] as JsonArray).map { it.jsonPrimitiveContent() },
        )
    }
}

private fun JsonElement.jsonPrimitiveContent(): String = (this as JsonPrimitive).content

data class CandidateSlice(
    val sliceId: String,
    val instanceId: String,
    val frameIndex: Int?,
    val position: Vec3,
    val projectionMm: Double,
    val spacingResidualMm: Double?,
    val instanceNumber: Int?,
)

data class CandidateGeometry(
    val origin: Vec3,
    val normal: Vec3,
    val row: Vec3,
    val col: Vec3,
    val spacingMedianMm: Double,
    val spacingMinMm: Double,
    val spacingMaxMm: Double,
) {
    fun toJson(): JsonObject = buildJsonObject {
        fun v(name: String, v: Vec3) = put(name, buildJsonArray {
            add(JsonPrimitive(v.x)); add(JsonPrimitive(v.y)); add(JsonPrimitive(v.z))
        })
        v("origin_mm_lps", origin); v("normal", normal); v("row", row); v("col", col)
        put("spacing_median_mm", spacingMedianMm)
        put("spacing_min_mm", spacingMinMm)
        put("spacing_max_mm", spacingMaxMm)
    }

    companion object {
        fun fromJson(o: JsonObject): CandidateGeometry {
            fun v(name: String): Vec3 {
                val a = o[name] as JsonArray
                return Vec3(
                    a[0].jsonPrimitiveContent().toDouble(),
                    a[1].jsonPrimitiveContent().toDouble(),
                    a[2].jsonPrimitiveContent().toDouble(),
                )
            }
            return CandidateGeometry(
                origin = v("origin_mm_lps"), normal = v("normal"), row = v("row"), col = v("col"),
                spacingMedianMm = (o["spacing_median_mm"] as JsonPrimitive).content.toDouble(),
                spacingMinMm = (o["spacing_min_mm"] as JsonPrimitive).content.toDouble(),
                spacingMaxMm = (o["spacing_max_mm"] as JsonPrimitive).content.toDouble(),
            )
        }
    }
}

data class Candidate(
    val id: String,
    val seriesUid: String,
    val forUid: String,
    val branch: String,
    val rank: Int,
    val slices: List<CandidateSlice>,
    val geometry: CandidateGeometry,
    val note: String,
)

data class EngineDiagnostic(
    val code: String,
    val instanceId: String?,
    val tag: String?,
    val rawValue: String?,
    val message: String,
)

data class DerivationOutput(
    val candidates: List<Candidate>,
    val diagnostics: List<EngineDiagnostic>,
    val unplacedInstanceIds: List<String>,
)

/**
 * Grouping rules:
 *  - InstanceNumber is never a spatial sort key; slices order by projection on
 *    the slice normal.
 *  - Direction cosines are checked for orthogonality against a tolerance first.
 *  - Local spacing jumps, duplicate positions and differing FrameOfReference
 *    UIDs produce branch candidates; nothing is force-merged. When no unique
 *    decision is possible, every legal candidate is kept.
 */
object DerivationEngine {

    fun derive(derivationId: String, instances: List<ImportedInstance>, params: RuleParams): DerivationOutput {
        val diagnostics = mutableListOf<EngineDiagnostic>()
        val candidates = mutableListOf<Candidate>()
        val unplaced = mutableListOf<String>()

        // UID conflict: same SOPInstanceUID carrying different content.
        val bySop = instances.filter { it.sopUid.isNotEmpty() }.groupBy { it.sopUid }
        for ((sop, group) in bySop) {
            if (group.size > 1) {
                diagnostics += EngineDiagnostic(
                    Diag.UID_CONFLICT, null, "SOPInstanceUID", sop,
                    "SOPInstanceUID $sop is carried by ${group.size} distinct instances " +
                        group.joinToString(", ") { it.id } + "; both are kept, no silent merge",
                )
            }
        }

        // Import-level diagnostics become derivation diagnostics as well.
        for (inst in instances) {
            for (d in inst.diagnostics) {
                diagnostics += EngineDiagnostic(d.code, inst.id, d.tag, d.rawValue, d.message)
            }
            if (inst.slices.isEmpty()) unplaced += inst.id
        }

        data class Group(val seriesUid: String, val forUid: String, val slices: List<SliceGeom>)

        val groups = instances
            .flatMap { it.slices }
            .groupBy { slice ->
                val inst = instances.first { it.id == slice.instanceId }
                inst.seriesUid to (inst.forUid ?: "(none)")
            }
            .map { (key, slices) -> Group(key.first, key.second, slices.sortedWith(compareBy({ it.projection }, { it.sliceId }))) }
            .sortedWith(compareBy({ it.seriesUid }, { it.forUid }))

        // FrameOfReference split notice (never merged across FoR).
        val seriesToFors = groups.groupBy { it.seriesUid }.mapValues { e -> e.value.map { it.forUid }.toSet() }
        for ((series, fors) in seriesToFors) {
            if (fors.size > 1) {
                diagnostics += EngineDiagnostic(
                    Diag.FRAME_OF_REFERENCE_SPLIT, null, "FrameOfReferenceUID", series,
                    "series $series spans ${fors.size} FrameOfReference UIDs; kept as separate candidate groups",
                )
            }
        }

        for (group in groups) {
            candidates += candidatesForGroup(derivationId, group.seriesUid, group.forUid, group.slices, params, diagnostics)
        }

        return DerivationOutput(candidates, diagnostics, unplaced.distinct())
    }

    private fun candidatesForGroup(
        derivationId: String,
        seriesUid: String,
        forUid: String,
        sortedSlices: List<SliceGeom>,
        params: RuleParams,
        diagnostics: MutableList<EngineDiagnostic>,
    ): List<Candidate> {
        val out = mutableListOf<Candidate>()
        if (sortedSlices.isEmpty()) return out

        // 1. Orthogonality first, with tolerance.
        val nonOrtho = sortedSlices.filter { it.orthogonalityError > params.orthogonalityTolerance }
        for (s in nonOrtho) {
            diagnostics += EngineDiagnostic(
                Diag.NON_ORTHOGONAL_ORIENTATION, s.instanceId, "ImageOrientationPatient",
                "%.6f".format(s.orthogonalityError),
                "slice ${s.sliceId} direction cosines deviate from orthogonal by " +
                    "%.6f".format(s.orthogonalityError) + " (tolerance ${params.orthogonalityTolerance})",
            )
        }

        // 2. InstanceNumber is evidence, not truth: record when it disagrees with spatial order.
        val byInstanceNumber = sortedSlices
            .filter { it.instanceNumber != null }
            .sortedBy { it.instanceNumber }
        if (byInstanceNumber.size == sortedSlices.size &&
            byInstanceNumber.map { it.sliceId } != sortedSlices.map { it.sliceId }
        ) {
            diagnostics += EngineDiagnostic(
                Diag.INSTANCE_NUMBER_IGNORED, null, "InstanceNumber", null,
                "InstanceNumber order disagrees with spatial projection order in series $seriesUid; " +
                    "spatial projection is used as ordering truth",
            )
        }

        // 3. Duplicate positions.
        val dupGroups = mutableListOf<List<SliceGeom>>()
        run {
            var i = 0
            while (i < sortedSlices.size) {
                val cluster = mutableListOf(sortedSlices[i])
                var j = i + 1
                while (j < sortedSlices.size &&
                    kotlin.math.abs(sortedSlices[j].projection - sortedSlices[i].projection) <= params.duplicatePositionEpsilonMm
                ) {
                    cluster += sortedSlices[j]; j++
                }
                if (cluster.size > 1) dupGroups += cluster
                i = if (cluster.size > 1) j else i + 1
            }
        }
        for (cluster in dupGroups) {
            diagnostics += EngineDiagnostic(
                Diag.DUPLICATE_POSITION, null, "ImagePositionPatient",
                "%.4f".format(cluster.first().projection),
                "duplicate slice positions at projection %.4f mm: %s".format(
                    cluster.first().projection, cluster.joinToString(", ") { it.sliceId }),
            )
        }

        // 4. Local spacing jumps.
        val projections = sortedSlices.map { it.projection }
        val stats = spacingStats(projections)
        val jumpAfter = stats.residualsMm.mapIndexedNotNull { idx, r ->
            if (kotlin.math.abs(r) > params.spacingResidualToleranceMm) idx else null
        }
        for (idx in jumpAfter) {
            diagnostics += EngineDiagnostic(
                Diag.SPACING_JUMP, sortedSlices[idx].instanceId, "ImagePositionPatient",
                "%.4f".format(stats.residualsMm[idx]),
                "local spacing jump of %.4f mm after slice %s (tolerance %.4f mm)".format(
                    stats.residualsMm[idx], sortedSlices[idx].sliceId, params.spacingResidualToleranceMm),
            )
        }

        val multiframe = sortedSlices.any { it.frameIndex != null }

        fun buildCandidate(branch: String, rank: Int, slices: List<SliceGeom>, note: String): Candidate {
            val projs = slices.map { it.projection }
            val st = spacingStats(projs)
            val candSlices = slices.mapIndexed { i, s ->
                CandidateSlice(
                    sliceId = s.sliceId,
                    instanceId = s.instanceId,
                    frameIndex = s.frameIndex,
                    position = s.position,
                    projectionMm = s.projection,
                    spacingResidualMm = if (i < st.residualsMm.size) st.residualsMm[i] else null,
                    instanceNumber = s.instanceNumber,
                )
            }
            val ref = slices.first()
            return Candidate(
                id = Ids.candidate(derivationId, seriesUid, forUid, branch, rank, slices.map { it.sliceId }),
                seriesUid = seriesUid,
                forUid = forUid,
                branch = branch,
                rank = rank,
                slices = candSlices,
                geometry = CandidateGeometry(
                    origin = ref.position, normal = ref.normal, row = ref.row, col = ref.col,
                    spacingMedianMm = st.medianMm, spacingMinMm = st.minMm, spacingMaxMm = st.maxMm,
                ),
                note = note,
            )
        }

        val clean = nonOrtho.isEmpty() && dupGroups.isEmpty() && jumpAfter.isEmpty()
        if (clean) {
            val branch = if (multiframe) "multiframe" else "baseline"
            out += buildCandidate(branch, 0, sortedSlices,
                if (multiframe) "multi-frame object expanded from functional groups; frames ordered by projection"
                else "uniform geometry; ordered by spatial projection (InstanceNumber not used)")
            return out
        }

        // Anomalies: keep every legal candidate, never force-merge.
        var rank = 0
        if (jumpAfter.isNotEmpty()) {
            // Split at each jump: segments + the merged variant.
            val bounds = mutableListOf(0)
            jumpAfter.forEach { bounds += it + 1 }
            bounds += sortedSlices.size
            for (b in 0 until bounds.size - 1) {
                val seg = sortedSlices.subList(bounds[b], bounds[b + 1])
                if (seg.isNotEmpty()) {
                    out += buildCandidate("spacing-jump", rank++, seg.toList(),
                        "segment ${b + 1} after splitting at local spacing jump(s); not merged with neighbours")
                }
            }
            out += buildCandidate("spacing-jump", rank++, sortedSlices,
                "merged variant retaining the spacing jump; flagged, kept as a legal alternative")
        }
        if (dupGroups.isNotEmpty()) {
            // One variant per deduplication choice (bounded: per cluster, keep each member once).
            val choices = dupGroups.map { cluster -> cluster.map { it.sliceId } }
            val combos = cartesian(choices)
            for (combo in combos) {
                val keep = combo.toSet()
                val slices = sortedSlices.filter { s ->
                    val cluster = dupGroups.firstOrNull { c -> c.any { it.sliceId == s.sliceId } }
                    cluster == null || s.sliceId in keep
                }
                out += buildCandidate("duplicate-position", rank++, slices,
                    "deduplication choice keeping ${combo.joinToString(", ")}")
            }
        }
        if (nonOrtho.isNotEmpty()) {
            val bad = nonOrtho.map { it.sliceId }.toSet()
            out += buildCandidate("non-orthogonal", rank++, sortedSlices.filter { it.sliceId !in bad },
                "non-orthogonal slice(s) excluded: ${bad.joinToString(", ")}")
            out += buildCandidate("non-orthogonal", rank++, sortedSlices,
                "non-orthogonal slice(s) kept as-is: ${bad.joinToString(", ")}")
        }
        return out
    }

    private fun cartesian(choices: List<List<String>>): List<List<String>> {
        var acc: List<List<String>> = listOf(emptyList())
        for (choice in choices) {
            acc = acc.flatMap { prefix -> choice.map { prefix + it } }
            if (acc.size > 16) break // bound combinatorics; case pack stays small
        }
        return acc
    }
}
