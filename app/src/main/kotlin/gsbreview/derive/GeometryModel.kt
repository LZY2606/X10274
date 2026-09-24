package gsbreview.derive

import gsbreview.domain.CandidateMember
import gsbreview.domain.Diagnostic
import gsbreview.domain.FrameGeometry
import gsbreview.domain.GeomFact
import gsbreview.domain.SlicePlane
import gsbreview.domain.Vec3
import gsbreview.domain.VolumeCandidate
import gsbreview.dicom.FrameFunctionalGroup
import kotlin.math.abs

data class GeometryParams(
    val ruleVersion: String,
    val orthogonalityTolerance: Double = 0.001,
    val duplicatePositionToleranceMm: Double = 0.01,
    val spacingJumpRatio: Double = 1.5,
    val spacingResidualToleranceMm: Double = 0.5
) {
    fun fingerprint(): String =
        "rv=$ruleVersion|ort=$orthogonalityTolerance|dup=$duplicatePositionToleranceMm|" +
            "jump=$spacingJumpRatio|res=$spacingResidualToleranceMm"
}

data class FrameInput(
    val frameKey: String,
    val subjectKey: String,
    val instanceNumber: String?,
    val frameOfReferenceUid: String?,
    val group: FrameFunctionalGroup?,
    val rawPresent: GeometryRawPresence
)

data class GeometryRawPresence(
    val ippPresent: Boolean,
    val iopPresent: Boolean,
    val pixelSpacingPresent: Boolean
)

data class GeometryResult(
    val geometries: List<FrameGeometry>,
    val facts: List<GeomFact>,
    val candidates: List<VolumeCandidate>,
    val diagnostics: List<Diagnostic>
)

object GeometryEngine {

    fun analyze(inputs: List<FrameInput>, params: GeometryParams,
                idFactory: IdFactory): GeometryResult {
        val geometries = inputs.map { frameGeometry(it, params, idFactory) }
        val facts = buildFacts(inputs, geometries, params, idFactory)
        val diagnostics = mutableListOf<Diagnostic>()
        geometries.forEach { g ->
            g.problems.forEach { code ->
                diagnostics += idFactory.diagnostic(
                    code = code,
                    severity = if (code.startsWith("MISSING")) "warning" else "error",
                    message = diagnosticMessage(code),
                    subjectKey = g.subjectKey,
                    frameKey = g.frameKey
                )
            }
            if (g.orthogonalityError != null && g.orthogonalityError > params.orthogonalityTolerance) {
                diagnostics += idFactory.diagnostic(
                    code = "ORTHOGONALITY_OUT_OF_TOLERANCE",
                    severity = "error",
                    message = "row/column direction dot product error ${g.orthogonalityError} " +
                        "exceeds tolerance ${params.orthogonalityTolerance} (dimensionless, patient basis)",
                    subjectKey = g.subjectKey,
                    frameKey = g.frameKey
                )
            }
        }
        val candidates = groupCandidates(geometries, params, idFactory, diagnostics)
        return GeometryResult(geometries, facts, candidates, diagnostics)
    }

    private fun frameGeometry(input: FrameInput, params: GeometryParams, idFactory: IdFactory): FrameGeometry {
        val problems = mutableListOf<String>()
        val g = input.group
        if (!input.rawPresent.ippPresent) problems += "MISSING_IMAGE_POSITION_PATIENT"
        if (!input.rawPresent.iopPresent) problems += "MISSING_IMAGE_ORIENTATION_PATIENT"
        if (!input.rawPresent.pixelSpacingPresent) problems += "MISSING_PIXEL_SPACING"
        if (input.rawPresent.ippPresent && (g?.imagePositionPatient == null || g.imagePositionPatient.size != 3))
            problems += "UNPARSEABLE_IMAGE_POSITION_PATIENT"
        if (input.rawPresent.iopPresent && (g?.imageOrientationPatient == null || g.imageOrientationPatient.size != 6))
            problems += "UNPARSEABLE_IMAGE_ORIENTATION_PATIENT"
        if (input.rawPresent.pixelSpacingPresent && (g?.pixelSpacing == null || g.pixelSpacing.size < 2))
            problems += "UNPARSEABLE_PIXEL_SPACING"

        val ipp = g?.imagePositionPatient?.takeIf { it.size == 3 }?.let { Vec3(it[0], it[1], it[2]) }
        val iop = g?.imageOrientationPatient?.takeIf { it.size == 6 }
        var normal: List<Double>? = null
        var orthogonal = true
        var orthError: Double? = null
        var sliceCoord: Double? = null
        if (iop != null) {
            val row = iop.subList(0, 3)
            val col = iop.subList(3, 6)
            val n = Vec3.cross(row, col)
            val norm = n.norm()
            orthogonal = abs(row.zip(col).sumOf { it.first * it.second }) <= params.orthogonalityTolerance &&
                abs(norm - 1.0) <= params.orthogonalityTolerance
            orthError = maxOf(abs(row.zip(col).sumOf { it.first * it.second }), abs(norm - 1.0))
            if (norm > 0) normal = (n * (1.0 / norm)).asList()
            if (normal != null && ipp != null) {
                sliceCoord = ipp.dot(Vec3(normal[0], normal[1], normal[2]))
            }
        }
        return FrameGeometry(
            frameKey = input.frameKey,
            subjectKey = input.subjectKey,
            instanceNumber = input.instanceNumber,
            imagePositionPatient = ipp,
            rowCosines = iop?.subList(0, 3),
            columnCosines = iop?.subList(3, 6),
            pixelSpacingRowMm = g?.pixelSpacing?.getOrNull(1),
            pixelSpacingColMm = g?.pixelSpacing?.getOrNull(0),
            frameOfReferenceUID = input.frameOfReferenceUid,
            normal = normal,
            sliceCoordinate = sliceCoord,
            orthogonal = orthogonal,
            orthogonalityError = orthError,
            unitBasis = "DICOM patient coordinate system (mm); direction cosines are dimensionless",
            problems = problems
        )
    }

    private fun buildFacts(
        inputs: List<FrameInput>,
        geometries: List<FrameGeometry>,
        params: GeometryParams,
        idFactory: IdFactory
    ): List<GeomFact> {
        val facts = mutableListOf<GeomFact>()
        fun add(frameKey: String, subjectKey: String, field: String, raw: String?,
                unit: String?, basis: String, numericJson: String?, present: Boolean) {
            val valid = present && !raw.isNullOrBlank()
            facts += idFactory.fact(
                frameKey = frameKey,
                subjectKey = subjectKey,
                fieldName = field,
                rawValue = raw ?: "<absent>",
                unit = unit,
                basis = basis,
                numericJson = numericJson,
                valid = valid
            )
        }
        inputs.zip(geometries).forEach { (input, geo) ->
            val g = input.group
            add(geo.frameKey, geo.subjectKey, "ImagePositionPatient",
                g?.imagePositionPatient?.joinToString("\\") { fmt(it) },
                "mm (DICOM patient coordinate system)",
                input.group?.positionTagPath ?: "0020,0032",
                g?.imagePositionPatient?.let { listJson(it) },
                input.rawPresent.ippPresent)
            add(geo.frameKey, geo.subjectKey, "ImageOrientationPatient",
                g?.imageOrientationPatient?.joinToString("\\") { fmt(it) },
                "dimensionless direction cosines (patient basis)",
                "0020,0037",
                g?.imageOrientationPatient?.let { listJson(it) },
                input.rawPresent.iopPresent)
            add(geo.frameKey, geo.subjectKey, "PixelSpacing",
                g?.pixelSpacing?.joinToString("\\") { fmt(it) },
                "mm (row spacing \\ column spacing)",
                "0028,0030",
                g?.pixelSpacing?.let { listJson(it) },
                input.rawPresent.pixelSpacingPresent)
            add(geo.frameKey, geo.subjectKey, "FrameOfReferenceUID",
                geo.frameOfReferenceUID, null, "0020,0052",
                null, geo.frameOfReferenceUID != null)
            add(geo.frameKey, geo.subjectKey, "InstanceNumber",
                geo.instanceNumber, "advisory tag only, never spatial truth",
                "0020,0013", null, geo.instanceNumber != null)
            geo.normal?.let {
                add(geo.frameKey, geo.subjectKey, "DerivedNormal",
                    it.joinToString("\\") { v -> fmt(v) },
                    "dimensionless unit vector (patient basis)",
                    "derived: cross(ImageOrientationPatient[0..2],[3..5]) normalized",
                    listJson(it), true)
            }
            geo.sliceCoordinate?.let {
                add(geo.frameKey, geo.subjectKey, "SliceCoordinate",
                    fmt(it), "mm projection along normal (patient basis)",
                    "derived: dot(ImagePositionPatient, normal)",
                    kotlinx.serialization.json.JsonPrimitive(it).toString(), true)
            }
        }
        return facts
    }

    private fun groupCandidates(
        geometries: List<FrameGeometry>,
        params: GeometryParams,
        idFactory: IdFactory,
        diagnostics: MutableList<Diagnostic>
    ): List<VolumeCandidate> {
        val usable = geometries.filter {
            it.imagePositionPatient != null && it.rowCosines != null &&
                it.columnCosines != null && it.normal != null && it.sliceCoordinate != null
        }
        val unusable = geometries - usable.toSet()
        unusable.forEach { g ->
            diagnostics += idFactory.diagnostic(
                code = "EXCLUDED_FROM_VOLUME_GROUPING",
                severity = "warning",
                message = "frame ${g.frameKey} cannot be placed in patient space; kept as ungrouped evidence",
                subjectKey = g.subjectKey,
                frameKey = g.frameKey
            )
        }

        // Different FrameOfReferenceUIDs branch and are never force-merged.
        val byFoR = usable.groupBy { it.frameOfReferenceUID ?: "<null-frame-of-reference>" }
        val candidates = mutableListOf<VolumeCandidate>()
        for ((forKey, group) in byFoR) {
            // Same orientation hash within a FrameOfReference.
            val byOrientation = group.groupBy { orientationHash(it, params.orthogonalityTolerance) }
            for ((oriHash, samePlane) in byOrientation) {
                val nonOrth = samePlane.filter { !it.orthogonal }
                val orth = samePlane.filter { it.orthogonal }
                nonOrth.forEach { g ->
                    diagnostics += idFactory.diagnostic(
                        code = "NON_ORTHOGONAL_DIRECTION_EXCLUDED",
                        severity = "error",
                        message = "frame ${g.frameKey} orientation fails orthogonality check; not sorted, retained as evidence",
                        subjectKey = g.subjectKey,
                        frameKey = g.frameKey
                    )
                    candidates += singletonCandidate(
                        idFactory, g, forKey, oriHash,
                        status = "rejected_non_orthogonal",
                        reason = "direction vectors failed orthogonality/tolerance gate"
                    )
                }
                candidates += buildOrderedCandidates(orth, forKey, oriHash, params, idFactory, diagnostics)
            }
        }
        return candidates
    }

    private fun buildOrderedCandidates(
        frames: List<FrameGeometry>,
        forKey: String,
        oriHash: String,
        params: GeometryParams,
        idFactory: IdFactory,
        diagnostics: MutableList<Diagnostic>
    ): List<VolumeCandidate> {
        if (frames.isEmpty()) return emptyList()
        if (frames.size == 1) {
            return listOf(singletonCandidate(idFactory, frames[0], forKey, oriHash,
                status = "single_frame", reason = "only one spatially valid frame in this branch"))
        }
        val normal = frames[0].normal!!
        val sorted = frames.sortedBy { it.sliceCoordinate!! }
        // Duplicate positions (within tolerance) form explicit duplicate branches and are
        // never resolved by averaging; both legal choices are retained.
        val duplicateClusters = clusterDuplicates(sorted, params.duplicatePositionToleranceMm)
        val result = mutableListOf<VolumeCandidate>()
        if (duplicateClusters.values.any { it.size > 1 }) {
            val dupFrames = duplicateClusters.values.filter { it.size > 1 }
            dupFrames.flatten().forEach { g ->
                diagnostics += idFactory.diagnostic(
                    code = "DUPLICATE_SLICE_POSITION",
                    severity = "warning",
                    message = "frame ${g.frameKey} shares slice coordinate within " +
                        "${params.duplicatePositionToleranceMm} mm; emitting one candidate per legal choice",
                    subjectKey = g.subjectKey,
                    frameKey = g.frameKey
                )
            }
            // Emit one candidate per choice inside each duplicated coordinate.
            val choices = dupFrames.map { cluster -> cluster.map { listOf(it) } }
            val uniques = duplicateClusters.values.filter { it.size == 1 }.flatten()
            val combos = cartesian(choices)
            combos.forEachIndexed { idx, picked ->
                val members = (uniques + picked.flatten()).sortedBy { it.sliceCoordinate!! }
                result += makeCandidate(
                    idFactory, members, forKey, oriHash,
                    status = "ambiguous_duplicate_position",
                    reason = "duplicate slice position; candidate ${idx + 1}/${combos.size} is a legal choice awaiting review",
                    params = params, normal = normal
                )
            }
            // Segments separated from the duplicated cluster by local spacing jumps are still
            // detected below on the full set; emit gap-branched candidates too.
        }

        // Local spacing jumps split the ordered set into separate candidates (branches),
        // rather than forcing one volume with a fabricated interpolation.
        val gapGroups = splitAtGaps(sorted, params.spacingJumpRatio, idFactory, diagnostics)
        gapGroups.forEach { seg ->
            if (seg.size == 1 && duplicateClusters[seg[0].sliceCoordinate]?.let { it.size > 1 } == true) return@forEach
            result += makeCandidate(
                idFactory, seg, forKey, oriHash,
                status = if (gapGroups.size > 1) "branched_spacing_gap" else "ordered_volume",
                reason = if (gapGroups.size > 1)
                    "local spacing jump above ratio ${params.spacingJumpRatio} created a separate branch"
                else "monotonic spacing within tolerance; spatial order is from geometry not InstanceNumber",
                params = params, normal = normal
            )
        }
        return dedupeCandidates(result)
    }

    private fun makeCandidate(
        idFactory: IdFactory,
        ordered: List<FrameGeometry>,
        forKey: String,
        oriHash: String,
        status: String,
        reason: String,
        params: GeometryParams,
        normal: List<Double>
    ): VolumeCandidate {
        val coords = ordered.map { it.sliceCoordinate!! }
        val gaps = coords.zipWithNext { a, b -> b - a }
        val median = gaps.sorted().let { it[it.size / 2] }
        val residuals = gaps.map { kotlin.math.abs(it - median) }
        val members = ordered.mapIndexed { index, g ->
            val expectedResidual = if (index == 0) null else residuals[index - 1]
            val instOrder = g.instanceNumber?.trim()?.toIntOrNull()
            val instMatches = instOrder?.let {
                val others = ordered.mapNotNull { it.instanceNumber?.trim()?.toIntOrNull() }
                if (others.size != ordered.size) null
                else others.sorted()[index] == it
            }
            CandidateMember(
                ord = index,
                frameKey = g.frameKey,
                subjectKey = g.subjectKey,
                instanceNumber = g.instanceNumber,
                position = g.imagePositionPatient!!.asList(),
                spatialCoordinateMm = g.sliceCoordinate,
                spacingResidualMm = expectedResidual,
                instanceNumberMatchesSpatialOrder = instMatches
            )
        }
        val planes = ordered.map {
            SlicePlane(
                frameKey = it.frameKey,
                origin = it.imagePositionPatient!!.asList(),
                row = it.rowCosines!!,
                col = it.columnCosines!!,
                normal = it.normal!!
            )
        }
        val volumeKey = "for=${forKey}|ori=${oriHash}|coords=${coords.joinToString(",") { fmt(it) }}"
        return idFactory.candidate(
            volumeKey = volumeKey,
            frameOfReferenceUid = if (forKey == "<null-frame-of-reference>") null else forKey,
            orientationHash = oriHash,
            status = status,
            reason = reason,
            ordered = true,
            members = members,
            planes = planes,
            spacingResidualsMm = listOf(0.0) + residuals
        )
    }

    private fun singletonCandidate(
        idFactory: IdFactory,
        g: FrameGeometry,
        forKey: String,
        oriHash: String,
        status: String,
        reason: String
    ): VolumeCandidate = idFactory.candidate(
        volumeKey = "for=${forKey}|ori=${oriHash}|single=${g.frameKey}",
        frameOfReferenceUid = if (forKey == "<null-frame-of-reference>") null else forKey,
        orientationHash = oriHash,
        status = status,
        reason = reason,
        ordered = false,
        members = listOf(
            CandidateMember(0, g.frameKey, g.subjectKey, g.instanceNumber,
                g.imagePositionPatient?.asList(), g.sliceCoordinate, null, null)
        ),
        planes = if (g.imagePositionPatient != null && g.rowCosines != null &&
            g.columnCosines != null && g.normal != null) {
            listOf(SlicePlane(g.frameKey, g.imagePositionPatient.asList(),
                g.rowCosines, g.columnCosines, g.normal))
        } else emptyList(),
        spacingResidualsMm = emptyList()
    )

    private fun clusterDuplicates(
        sorted: List<FrameGeometry>, tolMm: Double
    ): Map<Double, List<FrameGeometry>> {
        val clusters = LinkedHashMap<Double, MutableList<FrameGeometry>>()
        for (g in sorted) {
            val c = g.sliceCoordinate!!
            val key = clusters.keys.firstOrNull { abs(it - c) <= tolMm }
            if (key != null) clusters.getValue(key) += g else clusters[c] = mutableListOf(g)
        }
        return clusters
    }

    private fun splitAtGaps(
        sorted: List<FrameGeometry>,
        jumpRatio: Double,
        idFactory: IdFactory,
        diagnostics: MutableList<Diagnostic>
    ): List<List<FrameGeometry>> {
        if (sorted.size < 2) return listOf(sorted)
        val gaps = sorted.zipWithNext { a, b -> b.sliceCoordinate!! - a.sliceCoordinate!! }
        val positive = gaps.filter { it > 0 }.sorted()
        if (positive.isEmpty()) return listOf(sorted)
        val median = positive.let { it[it.size / 2] }
        val segments = mutableListOf<MutableList<FrameGeometry>>(mutableListOf(sorted.first()))
        for (i in gaps.indices) {
            val ratio = if (median != 0.0) gaps[i] / median else Double.POSITIVE_INFINITY
            if (ratio > jumpRatio) {
                diagnostics += idFactory.diagnostic(
                    code = "LOCAL_SPACING_JUMP",
                    severity = "warning",
                    message = "gap between ${sorted[i].frameKey} and ${sorted[i + 1].frameKey} " +
                        "is ${fmt(gaps[i])} mm, ${fmt(ratio)}x the median spacing ${fmt(median)} mm " +
                        "(limit ${jumpRatio}x); branching instead of merging",
                    subjectKey = sorted[i + 1].subjectKey,
                    frameKey = sorted[i + 1].frameKey
                )
                segments += mutableListOf(sorted[i + 1])
            } else {
                segments.last() += sorted[i + 1]
            }
        }
        return segments
    }

    private fun orientationHash(g: FrameGeometry, tol: Double): String {
        if (g.rowCosines == null || g.columnCosines == null) return "no-ior"
        val bucket = { v: Double ->
            val b = Math.round(v / tol) * tol
            if (abs(b) < tol) 0.0 else b
        }
        val q = (g.rowCosines + g.columnCosines).map { bucket(it) }
        return q.joinToString(",") { fmt(it) }
    }

    private fun cartesian(lists: List<List<List<FrameGeometry>>>): List<List<List<FrameGeometry>>> {
        if (lists.isEmpty()) return listOf(emptyList())
        var acc: List<List<List<FrameGeometry>>> = listOf(emptyList())
        for (choices in lists) {
            acc = acc.flatMap { prefix -> choices.map { prefix + it } }
        }
        return acc
    }

    private fun dedupeCandidates(list: List<VolumeCandidate>): List<VolumeCandidate> {
        val seen = LinkedHashMap<String, VolumeCandidate>()
        for (c in list) {
            val key = c.members.joinToString("|") { it.frameKey } + "#" + c.status
            if (key !in seen) seen[key] = c
        }
        return seen.values.toList()
    }

    private fun diagnosticMessage(code: String): String = when (code) {
        "MISSING_IMAGE_POSITION_PATIENT" -> "ImagePositionPatient (0020,0032) absent; spatial position cannot be derived"
        "MISSING_IMAGE_ORIENTATION_PATIENT" -> "ImageOrientationPatient (0020,0037) absent; slice plane cannot be derived"
        "MISSING_PIXEL_SPACING" -> "PixelSpacing (0028,0030) absent; in-plane measure basis unavailable"
        "UNPARSEABLE_IMAGE_POSITION_PATIENT" -> "ImagePositionPatient present but not exactly 3 numeric components; raw value retained"
        "UNPARSEABLE_IMAGE_ORIENTATION_PATIENT" -> "ImageOrientationPatient present but not exactly 6 numeric components; raw value retained"
        "UNPARSEABLE_PIXEL_SPACING" -> "PixelSpacing present but not >=2 numeric components; raw value retained"
        else -> code
    }

    private fun fmt(v: Double): String {
        if (v == v.toLong().toDouble()) return v.toLong().toString()
        return String.format(java.util.Locale.US, "%.6f", v).trimEnd('0').trimEnd('.')
    }

    private fun listJson(values: List<Double>) =
        "[" + values.joinToString(",") { fmt(it) } + "]"
}
