package dicomreview.derive

import dicomreview.model.FileExtraction
import dicomreview.model.FrameGeometry
import dicomreview.model.Vec3
import java.security.MessageDigest
import kotlin.math.abs

const val RULE_VERSION = "geom-rules-v1"

@kotlinx.serialization.Serializable
data class SpacingResidual(
    val fromFrameKey: String,
    val toFrameKey: String,
    val gapMm: Double,
    val nominalMm: Double,
    val residualMm: Double,
    val jumpRatio: Double,
    val localJump: Boolean
)

@kotlinx.serialization.Serializable
data class SeriesCandidate(
    val candidateId: String,
    val family: String,
    val frameOfReferenceUID: String?,
    val seriesUIDHint: String?,
    val variant: String, // contiguous | flagged-whole | duplicate-sheet
    val frameKeys: List<String>,
    val projectedCoordinates: List<Double>, // mm along slice normal, datum = FrameOfReference origin
    val coordinateDatum: String,
    val nominalSpacingMm: Double?,
    val residuals: List<SpacingResidual>,
    val duplicateSheets: List<List<String>>,
    val warnings: List<String>,
    val acceptedByDefault: Boolean
)

@kotlinx.serialization.Serializable
data class UngroupableFrame(
    val frameKey: String,
    val reasons: List<String>
)

@kotlinx.serialization.Serializable
data class SopConflict(
    val sopInstanceUID: String,
    val fileStableIds: List<String>,
    val sha256: List<String>
)

@kotlinx.serialization.Serializable
data class GroupingResult(
    val ruleVersion: String,
    val candidates: List<SeriesCandidate>,
    val ungroupable: List<UngroupableFrame>,
    val sopConflicts: List<SopConflict>,
    val warnings: List<String>
)

private data class Sheet(
    val frame: FrameGeometry, val fileId: String,
    val normal: Vec3, val t: Double
)

data class GroupingInput(
    val files: List<Pair<String, FileExtraction>>,
    val shaByFile: Map<String, String>,
    val params: ToleranceParams
)

object GroupingEngine {

    fun group(input: GroupingInput): GroupingResult {
        val params = input.params
        val warnings = mutableListOf<String>()
        val ungroupable = mutableListOf<UngroupableFrame>()
        val unsupportedByFile = input.files.associate { it.first to it.second.unsupportedTransferSyntax }

        val bySop = input.files.flatMap { it.second.frames }
            .filter { !it.sopInstanceUID.isNullOrBlank() }
            .groupBy { it.sopInstanceUID!! }
        val sopConflicts = bySop.mapNotNull { (sop, frames) ->
            val fileIds = frames.map { fileOf(it.frameKey) }.distinct().sorted()
            val hashes = fileIds.mapNotNull { input.shaByFile[it] }.distinct().sorted()
            if (hashes.size > 1) SopConflict(sop, fileIds, hashes) else null
        }.sortedBy { it.sopInstanceUID }
        if (sopConflicts.isNotEmpty())
            warnings += "UID_CONFLICT: ${sopConflicts.size} SOP Instance UID(s) map to different source bytes"

        val sheets = mutableListOf<Sheet>()
        for ((fileId, extraction) in input.files) {
            for (frame in extraction.frames) {
                if (frame.imagePosition == null || frame.imageOrientation == null) {
                    ungroupable += UngroupableFrame(frame.frameKey,
                        (frame.diagnostics.filter { it.startsWith("MISSING_GEOMETRY") || it.startsWith("UNCONVERTIBLE") }
                            + "NO_SPATIAL_GEOMETRY").distinct())
                    continue
                }
                val check = GeometryExtractor.checkOrientation(frame.imageOrientation, params)
                if (!check.valid) {
                    ungroupable += UngroupableFrame(frame.frameKey, check.reasons)
                    continue
                }
                val normal = GeometryExtractor.sliceNormal(frame.imageOrientation)
                sheets += Sheet(frame, fileId, normal, frame.imagePosition.dot(normal))
            }
        }

        // Families = FrameOfReferenceUID + parallel orientation. Different FoR never merge.
        val families = sheets.groupBy { s ->
            val f = s.frame
            val orientKey = f.imageOrientation!!.joinToString(",") { "%.4f".format(it) }
            "${f.frameOfReferenceUID ?: "<null-for>"}|$orientKey"
        }

        val candidates = mutableListOf<SeriesCandidate>()
        for ((familyKey, members) in families) {
            val sorted = members.sortedWith(compareBy<Sheet> { it.t }.thenBy { it.frame.frameKey })
            val fo = sorted.first().frame.frameOfReferenceUID
            val seriesHint = sorted.mapNotNull { it.frame.seriesUID }
                .groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
            val conflictingSops = sopConflicts.flatMap { it.fileStableIds }.toSet()

            // Cluster repeated positions into planes (tolerance in mm).
            val planes = mutableListOf<MutableList<Sheet>>()
            for (sheet in sorted) {
                val plane = planes.lastOrNull()
                if (plane != null && abs(plane.first().t - sheet.t) <= params.duplicatePositionToleranceMm)
                    plane += sheet
                else planes += mutableListOf(sheet)
            }
            val duplicatePlanes = planes.filter { it.size > 1 }
            if (duplicatePlanes.isNotEmpty())
                warnings += "DUPLICATE_POSITION: family '$familyKey' has ${duplicatePlanes.size} repeated-position plane(s)"

            val representatives = planes.map { it.minBy { s -> s.frame.frameKey } }
            val gaps = representatives.zipWithNext().map { it.second.t - it.first.t }
            val nominal = gaps.median()

            data class Gap(val a: Sheet, val b: Sheet, val gap: Double, val jump: Boolean)
            val gapRecords = representatives.zipWithNext().map { (a, b) ->
                val gap = b.t - a.t
                val ratio = if (nominal != null && nominal > 0) gap / nominal else 1.0
                val residual = if (nominal != null) gap - nominal else 0.0
                val jump = nominal != null && nominal > 0 &&
                    ((ratio >= params.localSpacingJumpRatio || ratio <= 1.0 / params.localSpacingJumpRatio) &&
                        abs(residual) >= params.localSpacingResidualMm)
                Gap(a, b, gap, jump)
            }
            val jumpIndexes = gapRecords.withIndex().filter { it.value.jump }.map { it.index }
            if (jumpIndexes.isNotEmpty())
                warnings += "LOCAL_SPACING_JUMP: family '$familyKey' split, never force-merged across gaps ${jumpIndexes.joinToString(",")}"

            fun residualsFor(keys: Collection<String>): List<SpacingResidual> = gapRecords
                .filter { it.a.frame.frameKey in keys && it.b.frame.frameKey in keys }
                .map {
                    SpacingResidual(
                        it.a.frame.frameKey, it.b.frame.frameKey,
                        r6(it.gap), r6(nominal ?: it.gap),
                        r6(if (nominal != null) it.gap - nominal else 0.0),
                        r6(if (nominal != null && nominal > 0) it.gap / nominal else 1.0),
                        it.jump
                    )
                }

            val baseWarnings = mutableListOf<String>()
            if (jumpIndexes.isNotEmpty())
                baseWarnings += "LOCAL_SPACING_JUMP: nominal=${r6(nominal ?: 0.0)}mm; gap ratio threshold ${params.localSpacingJumpRatio}, residual threshold ${params.localSpacingResidualMm}mm"
            if (duplicatePlanes.isNotEmpty())
                baseWarnings += "DUPLICATE_POSITION: repeated slices kept as alternatives, never averaged"
            if (sorted.any { it.frame.multiframe })
                baseWarnings += "MULTIFRAME: geometry taken via Shared/Per-Frame Functional Groups Sequences"
            if (sorted.any { it.fileId in conflictingSops })
                baseWarnings += "UID_CONFLICT: member bytes disagree despite shared SOP Instance UID"
            if (sorted.any { unsupportedByFile[it.fileId] == true })
                baseWarnings += "UNSUPPORTED_TRANSFER_SYNTAX: pixels not decoded; geometry read from preserved tags"

            val clean = jumpIndexes.isEmpty() && duplicatePlanes.isEmpty()

            // 1) Flagged whole family: always retained (accepted and rejected outcomes both kept later).
            candidates += SeriesCandidate(
                stableId("whole", familyKey, sorted.map { it.frame.frameKey }),
                familyKey, fo, seriesHint, "flagged-whole",
                sorted.map { it.frame.frameKey }, sorted.map { r6(it.t) },
                "FrameOfReference('${fo ?: "null"}') origin; axis = slice normal",
                nominal?.let(::r6), residualsFor(sorted.map { it.frame.frameKey }.toSet()),
                duplicatePlanes.map { p -> p.map { it.frame.frameKey } },
                baseWarnings + "FLAGGED_WHOLE: entire spatial family retained for review",
                acceptedByDefault = clean
            )

            // 2) Contiguous runs cut at jumps. Branching, no forced merge.
            val runs = mutableListOf<List<Sheet>>()
            var current = mutableListOf(representatives.first())
            gapRecords.forEachIndexed { i, g ->
                current += g.b
                if (i in jumpIndexes) { runs += expandRun(current, planes); current = mutableListOf(g.b) }
            }
            runs += expandRun(current, planes)
            runs.filter { it.size > 1 || clean }.forEachIndexed { idx, run ->
                val keys = run.map { it.frame.frameKey }
                candidates += SeriesCandidate(
                    stableId("run-$idx", familyKey, keys),
                    familyKey, fo, seriesHint, "contiguous",
                    keys, run.map { r6(it.t) },
                    "FrameOfReference('${fo ?: "null"}') origin; axis = slice normal",
                    nominal?.let(::r6), residualsFor(keys.toSet()),
                    duplicatePlanes.filter { p -> p.any { it.frame.frameKey in keys } }
                        .map { p -> p.map { it.frame.frameKey } },
                    baseWarnings.filter { !it.startsWith("LOCAL_SPACING_JUMP") }
                        .let { if (jumpIndexes.isNotEmpty()) it + "BRANCH_FROM_JUMP: contiguous subset ${idx + 1}/${runs.size}" else it },
                    acceptedByDefault = clean
                )
            }

            // 3) Duplicate-plane alternatives: every legal sheet preserved.
            duplicatePlanes.forEachIndexed { dupIdx, plane ->
                plane.forEachIndexed { altIdx, chosen ->
                    val altSheets = sorted.filter { s -> s !in plane || s === chosen }
                    val keys = altSheets.map { it.frame.frameKey }
                    candidates += SeriesCandidate(
                        stableId("dup-$dupIdx-alt-$altIdx", familyKey, keys),
                        familyKey, fo, seriesHint, "duplicate-sheet",
                        keys, altSheets.map { r6(it.t) },
                        "FrameOfReference('${fo ?: "null"}') origin; axis = slice normal",
                        nominal?.let(::r6), residualsFor(keys.toSet()),
                        listOf(plane.map { (if (it === chosen) "chosen:" else "alt:") + it.frame.frameKey }),
                        baseWarnings + "DUPLICATE_SHEET_ALTERNATIVE: one legal resolution of a repeated position",
                        acceptedByDefault = false
                    )
                }
            }
        }

        return GroupingResult(
            RULE_VERSION,
            candidates.sortedWith(compareBy({ it.family }, { it.variant }, { it.candidateId })),
            ungroupable.sortedBy { it.frameKey },
            sopConflicts,
            warnings.distinct()
        )
    }

    /** Expand a representative run to include all duplicate sheets on its planes. */
    private fun expandRun(reps: List<Sheet>, planes: List<List<Sheet>>): List<Sheet> {
        val repKeys = reps.map { it.frame.frameKey }.toSet()
        return planes.filter { plane -> plane.any { it.frame.frameKey in repKeys } }.flatten()
            .sortedWith(compareBy<Sheet> { it.t }.thenBy { it.frame.frameKey })
    }

    private fun List<Double>.median(): Double? = if (isEmpty()) null
    else sorted().let { if (it.size % 2 == 1) it[it.size / 2] else (it[it.size / 2 - 1] + it[it.size / 2]) / 2 }

    private fun r6(v: Double) = Math.round(v * 1e6) / 1e6

    private fun fileOf(frameKey: String) = frameKey.substringBefore("#f")

    private fun stableId(kind: String, family: String, frameKeys: List<String>): String {
        val basis = "$RULE_VERSION|$kind|$family|" + frameKeys.joinToString(",")
        val digest = MessageDigest.getInstance("SHA-256").digest(basis.toByteArray())
            .joinToString("") { "%02x".format(it) }.take(16)
        return "cand-$digest"
    }
}
