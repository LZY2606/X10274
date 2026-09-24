package org.research.gsb.dicom.geometry

import org.research.gsb.dicom.model.Ambiguity
import org.research.gsb.dicom.model.CandidateStatus
import org.research.gsb.dicom.model.GeometryCheck
import org.research.gsb.dicom.model.ResidualKind
import org.research.gsb.dicom.model.SeriesCandidate
import org.research.gsb.dicom.model.Severity
import org.research.gsb.dicom.model.SliceGeometry
import org.research.gsb.dicom.model.SlicePlane
import org.research.gsb.dicom.model.SpacingResidual
import kotlin.math.abs
import kotlin.math.sign

data class GroupingResult(
    val candidates: List<SeriesCandidate>,
    val ambiguities: List<Ambiguity>,
    val ungrouped: List<String>,
    val checks: List<GeometryCheck>,
)

/**
 * Builds reviewable series/volume candidates purely from spatial geometry.
 *
 * Rules (mandatory domain semantics):
 *  - InstanceNumber is never treated as spatial ground truth.
 *  - Orientation cosines are checked for orthogonality/norm tolerances first.
 *  - Local spacing changes, duplicate slices and different FrameOfReference UIDs create
 *    branches; slices are never force-merged into one volume.
 *  - When geometry is not unique, every legal candidate is retained.
 */
class GeometryGroupingEngine(private val rules: GroupingRules) {

    fun group(
        slices: List<SliceGeometry>,
        uidConflicts: Map<String, String> = emptyMap(),
        unsupportedPixelInstances: Set<String> = emptySet(),
    ): GroupingResult {
        val checks = mutableListOf<GeometryCheck>()
        val candidates = mutableListOf<SeriesCandidate>()
        val ambiguities = mutableListOf<Ambiguity>()
        val ungrouped = mutableListOf<String>()

        // 1) Different FrameOfReference UIDs never merge: group first on (series, FoR).
        val buckets = slices.groupBy { it.seriesUid to (it.frameOfReferenceUid ?: "∅NONE") }

        for ((bucketKey, bucketSlices) in buckets) {
            val seriesUid = bucketKey.first
            val forUid = bucketKey.first.let { bucketSlices.firstOrNull()?.frameOfReferenceUid }

            val orthoBad = bucketSlices.filter { sl ->
                sl.orientation != null && !sl.orientation.isOrthonormal
            }
            val missing = bucketSlices.filter { !sl.locatable }

            // 2) UID conflict instances stay in their own flagged branch.
            val conflictKeys = bucketSlices.filter { uidConflicts.containsKey(it.sopInstanceUid) }
                .map { it.instanceKey }

            val usable = bucketSlices.filter { it.locatable && it.instanceKey !in conflictKeys }

            // 3) Reject/branch non-orthogonal instead of projecting on bad normals.
            val usableOrtho = usable.filter { sl ->
                sl.orientation == null || sl.orientation.isOrthonormal
            }
            val nonOrtho = (orthoBad.map { it.instanceKey } +
                usable.filter { it.orientation != null && !it.orientation.isOrthonormal }
                    .map { it.instanceKey }).distinct()

            if (nonOrtho.isNotEmpty()) {
                candidates += candidate(
                    seriesUid, forUid, CandidateStatus.NON_ORTHOGONAL,
                    nonOrtho, "Non-orthogonal orientation cosines", checks,
                )
                ambiguities += Ambiguity(
                    ambiguityId = "amb-${stableSuffix(seriesUid, "nonortho")}",
                    scope = seriesUid,
                    reasonCode = "NON_ORTHOGONAL",
                    description = "Slices failed orientation orthogonality/unit-length checks; " +
                        "projection suppressed until corrected.",
                    candidateIds = candidates.map { it.candidateId },
                    affectedSliceKeys = nonOrtho,
                )
            }

            if (missing.isNotEmpty()) {
                val keys = missing.map { it.instanceKey }
                candidates += candidate(seriesUid, forUid, CandidateStatus.MISSING_GEOMETRY,
                    keys, "Missing ImagePosition/ImageOrientation geometry", checks)
                ungrouped += keys
            }

            if (conflictKeys.isNotEmpty()) {
                candidates += candidate(seriesUid, forUid, CandidateStatus.UID_CONFLICT,
                    conflictKeys, "Same SOP UID with conflicting source bytes", checks)
            }

            if (usableOrtho.isNotEmpty()) {
                buildGeometricCandidates(seriesUid, forUid, usableOrtho, ambiguities, candidates, checks)
            }

            val unsupported = bucketSlices
                .filter { unsupportedPixelInstances.contains(it.sopInstanceUid) }
                .map { it.instanceKey }
            if (unsupported.isNotEmpty()) {
                candidates += candidate(seriesUid, forUid, CandidateStatus.UNSUPPORTED_PIXELS,
                    unsupported, "Transfer syntax not decodable; metadata retained", checks)
            }
        }

        return GroupingResult(candidates, ambiguities.distinctBy { it.ambiguityId }, ungrouped, checks)
    }

    private fun buildGeometricCandidates(
        seriesUid: String,
        forUid: String?,
        slices: List<SliceGeometry>,
        ambiguities: MutableList<Ambiguity>,
        candidates: MutableList<SeriesCandidate>,
        checks: MutableList<GeometryCheck>,
    ) {
        // Choose a reference normal from the first slice (all share orientation in a valid series).
        val refNormal = slices.firstNotNullOfOrNull { it.orientation?.normal }
        val refPos = slices.firstNotNullOfOrNull { it.position?.normalized }
        if (refNormal == null || refPos == null) return

        fun proj(sl: SliceGeometry): Double {
            val p = sl.position!!.normalized!!
            return Vec.dot(p, refNormal)
        }

        // Spatial ordering along normal — explicitly NOT InstanceNumber ordering.
        val sortedAsc = slices.sortedBy { proj(it) }
        val keysAsc = sortedAsc.map { it.instanceKey }
        val projections = sortedAsc.map { proj(it) }

        val eps = rules.positionEpsilonMm
        val duplicateGroups = mutableListOf<List<String>>()
        var dupRun = mutableListOf(sortedAsc[0].instanceKey)
        for (i in 1..sortedAsc.lastIndex) {
            if (abs(projections[i] - projections[i - 1]) <= eps) {
                dupRun += sortedAsc[i].instanceKey
            } else {
                if (dupRun.size > 1) duplicateGroups += dupRun.toList()
                dupRun = mutableListOf(sortedAsc[i].instanceKey)
            }
        }
        if (dupRun.size > 1) duplicateGroups += dupRun.toList()

        // Residuals between adjacent spatially ordered slices.
        val gaps = (1..projections.lastIndex).map { projections[it] - projections[it - 1] }
        val positiveGaps = gaps.filter { it > eps }
        val nominal = positiveGaps.takeIf { it.isNotEmpty() }?.let { pg ->
            pg.sorted().let { it[it.size / 2] } // robust median nominal spacing
        }
        val residuals = mutableListOf<SpacingResidual>()
        var jumpCount = 0
        for (i in 1..sortedAsc.lastIndex) {
            val gap = projections[i] - projections[i - 1]
            val kind = when {
                abs(gap) <= eps -> ResidualKind.DUPLICATE
                nominal != null && abs(gap - nominal) >
                    (rules.spacingAbsoluteToleranceMm + rules.spacingRelativeTolerance * abs(nominal)) -> {
                    jumpCount++; ResidualKind.JUMP
                }
                gap < 0 -> ResidualKind.REVERSED
                else -> ResidualKind.REGULAR
            }
            residuals += SpacingResidual(
                fromSlice = sortedAsc[i - 1].instanceKey,
                toSlice = sortedAsc[i].instanceKey,
                gapMm = gap,
                expectedMm = nominal,
                residualMm = if (nominal != null) gap - nominal else null,
                kind = kind,
            )
        }

        val planes = sortedAsc.map { sl ->
            SlicePlane(
                sliceKey = sl.instanceKey,
                positionMm = sl.position?.normalized,
                normalMm = sl.orientation?.normal,
                projectionAlongNormal = proj(sl),
                instanceNumber = sl.instanceNumberRaw,
            )
        }
        val instanceOrder = slices.sortedBy { it.instanceNumberRaw?.toIntOrNull() ?: Int.MAX_VALUE }
            .map { it.instanceKey }

        val duplicateKeys = duplicateGroups.flatten().distinct()
        val status = when {
            duplicateKeys.isNotEmpty() && jumpCount > 0 -> CandidateStatus.AMBIGUOUS
            duplicateKeys.isNotEmpty() -> CandidateStatus.DUPLICATE_POSITION
            jumpCount > 0 -> CandidateStatus.SPACING_JUMP
            gaps.any { it < -eps } -> CandidateStatus.REVERSED_ORDER
            else -> CandidateStatus.VOLUME
        }

        val alternativeOrders = mutableListOf<List<String>>()
        if (instanceOrder != keysAsc) alternativeOrders += instanceOrder
        // Descending spatial order is a legal alternate interpretation for display.
        alternativeOrders += keysAsc.reversed()

        val primary = SeriesCandidate(
            candidateId = "cand-${stableSuffix(seriesUid, "asc-${slices.first().instanceKey}")}",
            seriesUid = seriesUid,
            frameOfReferenceUid = forUid,
            status = status,
            sliceKeys = keysAsc,
            alternativeOrders = alternativeOrders,
            nominalSpacingMm = nominal,
            residuals = residuals,
            planes = planes,
            instanceNumberOrder = instanceOrder,
            checks = checks.toList(),
            diagnostics = buildList {
                if (duplicateGroups.isNotEmpty()) add("Duplicate slice positions: " +
                    duplicateGroups.joinToString { it.joinToString("≈") })
                if (jumpCount > 0) add("$jumpCount local spacing jump(s) exceed tolerances; branches retained")
                if (instanceOrder != keysAsc) add("InstanceNumber order differs from spatial projection order")
            },
        )
        candidates += primary

        if (duplicateKeys.isNotEmpty() || jumpCount > 0) {
            ambiguities += Ambiguity(
                ambiguityId = "amb-${stableSuffix(seriesUid, "spacing")}",
                scope = seriesUid,
                reasonCode = if (duplicateKeys.isNotEmpty()) "DUPLICATE_OR_JUMP" else "SPACING_JUMP",
                description = when {
                    duplicateKeys.isNotEmpty() && jumpCount > 0 ->
                        "Duplicate positions and local spacing jumps detected; more than one legal grouping exists."
                    duplicateKeys.isNotEmpty() ->
                        "Repeated slice locations: keep duplicate slices rather than collapsing them."
                    else -> "Local spacing discontinuity detected; grouping is branched, not force-merged."
                },
                candidateIds = listOf(primary.candidateId) + branchCandidatesFor(
                    seriesUid, forUid, keysAsc, projections, duplicateGroups, nominal, planes, instanceOrder, checks
                ).map { it.candidateId },
                affectedSliceKeys = if (duplicateKeys.isNotEmpty()) duplicateKeys
                    else residuals.filter { it.kind == ResidualKind.JUMP }.flatMap { listOf(it.fromSlice, it.toSlice) },
            )
            candidates += branchCandidatesFor(
                seriesUid, forUid, keysAsc, projections, duplicateGroups, nominal, planes, instanceOrder, checks
            )
        }

        // Mixed FrameOfReference within one nominal series gets an explicit ambiguity too.
        val forSet = slices.mapNotNull { it.frameOfReferenceUid }.distinct()
        if (forSet.size > 1) {
            ambiguities += Ambiguity(
                ambiguityId = "amb-${stableSuffix(seriesUid, "for")}",
                scope = seriesUid,
                reasonCode = "MIXED_FRAME_OF_REFERENCE",
                description = "Slices reference ${forSet.size} FrameOfReference UIDs; never merged into one volume.",
                candidateIds = candidates.map { it.candidateId },
                affectedSliceKeys = slices.map { it.instanceKey },
            )
        }
    }

    private fun branchCandidatesFor(
        seriesUid: String,
        forUid: String?,
        keysAsc: List<String>,
        projections: List<Double>,
        duplicateGroups: List<List<String>>,
        nominal: Double?,
        planes: List<SlicePlane>,
        instanceOrder: List<String>,
        checks: List<GeometryCheck>,
    ): List<SeriesCandidate> {
        // One legal alternate: drop nothing but split at spacing jumps into contiguous runs.
        val eps = rules.positionEpsilonMm
        val runs = mutableListOf<MutableList<String>>(mutableListOf(keysAsc.first()))
        for (i in 1..keysAsc.lastIndex) {
            val gap = projections[i] - projections[i - 1]
            val isJump = nominal != null && abs(gap - nominal) >
                (rules.spacingAbsoluteToleranceMm + rules.spacingRelativeTolerance * abs(nominal))
            if (isJump && gap > eps) runs += mutableListOf(keysAsc[i]) else runs.last() += keysAsc[i]
        }
        return runs.filter { it.size > 1 || runs.size > 1 }.mapIndexed { idx, runKeys ->
            SeriesCandidate(
                candidateId = "cand-${stableSuffix(seriesUid, "run$idx")}",
                seriesUid = seriesUid,
                frameOfReferenceUid = forUid,
                status = CandidateStatus.SPACING_JUMP,
                sliceKeys = runKeys,
                alternativeOrders = listOf(instanceOrder.filter { it in runKeys.toSet() }),
                nominalSpacingMm = nominal,
                residuals = emptyList(),
                planes = planes.filter { it.sliceKey in runKeys.toSet() },
                instanceNumberOrder = instanceOrder.filter { it in runKeys.toSet() },
                checks = checks,
                diagnostics = listOf("Contiguous run #$idx split at a local spacing jump"),
            )
        }
    }

    private fun candidate(
        seriesUid: String,
        forUid: String?,
        status: CandidateStatus,
        sliceKeys: List<String>,
        reason: String,
        checks: List<GeometryCheck>,
    ): SeriesCandidate {
        checks += GeometryCheck(status.name, Severity.WARNING, reason)
        return SeriesCandidate(
            candidateId = "cand-${stableSuffix(seriesUid, status.name.lowercase() + "-" + sliceKeys.firstOrNull().hashCode())}",
            seriesUid = seriesUid,
            frameOfReferenceUid = forUid,
            status = status,
            sliceKeys = sliceKeys,
            alternativeOrders = emptyList(),
            nominalSpacingMm = null,
            residuals = emptyList(),
            planes = emptyList(),
            instanceNumberOrder = emptyList(),
            checks = checks,
            diagnostics = listOf(reason),
        )
    }

    private fun stableSuffix(vararg parts: String): String {
        var h = 1122968701L
        for (p in parts) for (c in p) {
            h = h xor c.code.toLong()
            h *= 0x100000001b3L
        }
        return java.lang.Long.toHexString(h and 0xFFFFFFFFL)
    }
}
