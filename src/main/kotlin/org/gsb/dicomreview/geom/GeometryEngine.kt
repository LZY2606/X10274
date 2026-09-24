package org.gsb.dicomreview.geom

import kotlin.math.abs
import org.gsb.dicomreview.model.EvidenceRef
import org.gsb.dicomreview.model.FrameGeometry
import org.gsb.dicomreview.model.GeometryResult
import org.gsb.dicomreview.model.GroupingParameters
import org.gsb.dicomreview.model.RuleFire
import org.gsb.dicomreview.model.SeriesCandidate
import org.gsb.dicomreview.model.StackFrame
import org.gsb.dicomreview.model.UnlocatedInstance
import org.gsb.dicomreview.model.Vec3

/**
 * Versioned pure grouping rules.
 *
 *  - InstanceNumber is never used as spatial truth.
 *  - Direction vectors are checked for orthogonality and unit length before any stacking.
 *  - Local spacing changes, duplicate slices and different FrameOfReference UIDs branch; they are
 *    never silently merged. All legal candidates are retained when the geometry is ambiguous.
 */
object GeometryEngine {
    const val RULE_VERSION = "geom-rules-v1"

    fun evaluate(
        frames: List<FrameGeometry>,
        rawById: Map<String, org.gsb.dicomreview.model.RawInstance>,
        params: GroupingParameters,
        derivedAtEventId: String
    ): GeometryResult {
        val ruleFires = mutableListOf<RuleFire>()
        val unlocated = mutableListOf<UnlocatedInstance>()
        val candidates = mutableListOf<SeriesCandidate>()

        // 1) UID conflicts: same SOP Instance UID, more than one distinct material identity live.
        val activeFramesBySop = frames.groupBy { it.sopInstanceUid }
        val conflictedSops = activeFramesBySop
            .filter { (sop, list) -> sop != null && list.map { it.instanceId }.distinct().size > 1 }
            .keys
        val conflictInstanceIds = frames.filter { it.sopInstanceUid in conflictedSops }.map { it.instanceId }.distinct()
        if (conflictedSops.isNotEmpty()) {
            ruleFires += RuleFire(
                StableIds.of("fire", derivedAtEventId, "UID_CONFLICT", conflictedSops.filterNotNull().sorted().joinToString(",")),
                "UID_CONFLICT", "ERROR",
                "same SOPInstanceUID carried by ${conflictInstanceIds.size} distinct materials; no automatic dedup",
                conflictInstanceIds,
                emptyList(),
                frames.filter { it.instanceId in conflictInstanceIds }.flatMap { it.evidence }.distinct()
            )
        }

        // 2) Exclude unusable frames with a recorded reason.
        val excluded = frames.filter { fg ->
            !fg.usable || fg.orientation?.orthogonal != true
        }
        excluded.groupBy { it.instanceId }.forEach { (instanceId, fs) ->
            val reasons = fs.flatMap { it.errors + it.warnings }.distinct()
            val fatal = rawById[instanceId]?.fatalDiagnostic
            unlocated += UnlocatedInstance(
                instanceId,
                rawById[instanceId]?.sopInstanceUid,
                (if (fatal != null) listOf(fatal) else emptyList()) + reasons,
                fs.flatMap { it.evidence }.distinct()
            )
            if (fs.any { it.orientation?.orthogonal == false }) {
                ruleFires += RuleFire(
                    StableIds.of("fire", derivedAtEventId, "NONORTHOGONAL", instanceId),
                    "DIRECTION_NONORTHOGONAL", "ERROR",
                    "orientation vectors outside tolerance (dot/unit norms); instance not stacked",
                    listOf(instanceId), emptyList(), fs.flatMap { it.evidence }.distinct()
                )
            }
        }

        val usable = frames.filter {
            it.usable && it.orientation?.orthogonal == true && it.instanceId !in conflictInstanceIds
        }

        // 3) Different FrameOfReference UIDs never merge.
        val byFor = usable.groupBy { it.frameOfReferenceUid ?: "__NO_FOR__" }

        byFor.forEach { (forKey, forFrames) ->
            val buckets = bucketByOrientation(forFrames, params)
            buckets.forEachIndexed { bucketIndex, bucket ->
                val orientationKey = StableIds.of(
                    "ori", forKey,
                    "%.4f".format(bucket.first().orientation!!.normal.x),
                    "%.4f".format(bucket.first().orientation!!.normal.y),
                    "%.4f".format(bucket.first().orientation!!.normal.z)
                )
                val built = buildStackCandidates(
                    bucket, forKey.ifBlank { null }, orientationKey,
                    params, derivedAtEventId, ruleFires
                )
                candidates += built
            }
        }

        // UID conflicts stay visible as suspect singleton stacks so reviewers can resolve by hand.
        frames.filter { it.instanceId in conflictInstanceIds && it.usable }.groupBy { it.instanceId }.forEach { (iid, fs) ->
            candidates += suspectCandidate(
                fs, derivedAtEventId, "UID_CONFLICT_SINGLETON",
                "UID_CONFLICT", forLabel = fs.first().frameOfReferenceUid
            )
        }

        // Fatal / unparseable and missing-geometry materials stay visible as unlocated.
        rawById.values.filter { it.instanceId !in frames.map { f -> f.instanceId } }.forEach { raw ->
            unlocated += UnlocatedInstance(
                raw.instanceId, raw.sopInstanceUid,
                listOf(raw.fatalDiagnostic ?: "MISSING_GEOMETRY: no parseable frame geometry"),
                emptyList()
            )
        }

        return GeometryResult(
            candidates = candidates.sortedWith(compareBy({ it.frameOfReferenceUid ?: "" }, { it.lineageKey }, { it.kind })),
            unlocated = unlocated.sortedBy { it.instanceId },
            ruleFires = ruleFires.sortedBy { it.code + it.fireId },
            parameters = params,
            ruleVersion = RULE_VERSION,
            derivedAtEventId = derivedAtEventId
        )
    }

    private fun bucketByOrientation(
        frames: List<FrameGeometry>, params: GroupingParameters
    ): List<List<FrameGeometry>> {
        val buckets = mutableListOf<MutableList<FrameGeometry>>()
        frames.sortedBy { it.instanceId + "#" + it.frameNumber }.forEach { fg ->
            val normal = fg.orientation!!.normal
            val existing = buckets.indexOfFirst { bucket ->
                val ref = bucket.first().orientation!!.normal
                val d = abs(normal.dot(ref))
                d >= 1.0 - params.orthogonalityTolerance
            }
            if (existing >= 0) buckets[existing].add(fg) else buckets.add(mutableListOf(fg))
        }
        return buckets
    }

    private fun buildStackCandidates(
        frames: List<FrameGeometry>,
        forUid: String?,
        orientationKey: String,
        params: GroupingParameters,
        eventId: String,
        ruleFires: MutableList<RuleFire>
    ): List<SeriesCandidate> {
        val representative = frames.minWith(compareBy({ it.instanceId }, { it.frameNumber }))
        val normal = representative.orientation!!.normal
        // Spatial truth: project ImagePositionPatient onto the slice normal. InstanceNumber is ignored.
        val sorted = frames.sortedWith(compareBy({ it.position!!.dot(normal) }, { it.instanceId }, { it.frameNumber }))
        val stack = sorted.mapIndexed { idx, fg ->
            StackFrame(fg, fg.position!!.dot(normal), idx)
        }

        val gaps = stack.zipWithNext().map { (a, b) -> b.tAlongNormal - a.tAlongNormal }
        val positiveGaps = gaps.filter { it > params.duplicatePositionToleranceMm }
        val median = positiveGaps.takeIf { it.isNotEmpty() }?.let { medianOf(it) }

        val dupGroups = groupDuplicateClusters(stack, params.duplicatePositionToleranceMm)
        val jumpIndices = gaps.mapIndexedNotNull { i, g ->
            if (median != null && g > params.spacingJumpRatio * median) i else null
        }

        dupGroups.flatMap { cluster ->
            cluster.map { it.geometry.instanceId }
        }.distinct().takeIf { it.isNotEmpty() }?.let { ids ->
            ruleFires += RuleFire(
                StableIds.of("fire", eventId, "DUPLICATE", ids.sorted().joinToString(","), orientationKey),
                "DUPLICATE_POSITION", "WARNING",
                "${dupGroups.sumOf { it.size }} frames share projection within ${params.duplicatePositionToleranceMm} mm; candidates branch",
                ids, listOf(seriesLineageSeed(stack, forUid, orientationKey)),
                clusterEvidence(stack)
            )
        }
        if (jumpIndices.isNotEmpty()) {
            ruleFires += RuleFire(
                StableIds.of("fire", eventId, "SPACING_JUMP", jumpIndices.joinToString(","), orientationKey),
                "LOCAL_SPACING_JUMP", "WARNING",
                "local gap exceeds ${params.spacingJumpRatio}x median spacing ${median}mm; segmented candidates emitted",
                stack.map { it.geometry.instanceId }.distinct(),
                listOf(seriesLineageSeed(stack, forUid, orientationKey)),
                clusterEvidence(stack)
            )
        }

        val out = mutableListOf<SeriesCandidate>()
        val seriesUid = stack.map { it.geometry.seriesInstanceUid }.filterNotNull().groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
        val baseAnnotations = buildList {
            if (dupGroups.isNotEmpty()) add("DUPLICATE_POSITIONS_PRESENT")
            if (jumpIndices.isNotEmpty()) add("LOCAL_SPACING_JUMP_PRESENT")
            if (stack.map { it.geometry.source }.any { s -> s == "PER_FRAME_FUNCTIONAL_GROUP" }) add("MULTIFRAME_OBJECT")
            if (stack.map { it.geometry.instanceId }.distinct().size > 1) add("MULTI_INSTANCE_STACK")
        }

        // Candidate A: full reading, duplicates kept as separate slices in arrival order.
        out += makeCandidate(
            stack, forUid, orientationKey, kind = "FULL_WITH_DUPLICATES",
            annotations = baseAnnotations + if (dupGroups.isNotEmpty()) listOf("AMBIGUOUS:REQUIRES_REVIEW") else emptyList(),
            eventId = eventId, seriesUid = seriesUid,
            reasonTag = "union"
        )

        // Candidate B..: one reading per duplicate choice group (select first member by stable order).
        dupGroups.forEachIndexed { gi, cluster ->
            val chosen = cluster.minWith(compareBy({ it.geometry.instanceId }, { it.geometry.frameNumber }))
            val reduced = stack.filterNot { sf -> cluster.contains(sf) && sf != chosen }
                .mapIndexed { i, sf -> sf.copy(order = i) }
            out += makeCandidate(
                reduced, forUid, orientationKey, kind = "DUPLICATE_RESOLUTION_${gi}",
                annotations = listOf("DUPLICATE_RESOLVED:KEPT=${chosen.geometry.instanceId}#${chosen.geometry.frameNumber}"),
                eventId = eventId, seriesUid = seriesUid,
                reasonTag = "dup$gi"
            )
        }

        // Candidate C..: segments split at local spacing jumps.
        if (jumpIndices.isNotEmpty()) {
            val cutSet = jumpIndices.toSet()
            val segments = mutableListOf<MutableList<StackFrame>>(mutableListOf(stack.first()))
            stack.drop(1).forEachIndexed { i, sf ->
                if (i in cutSet) segments.add(mutableListOf(sf)) else segments.last().add(sf)
            }
            segments.forEachIndexed { si, seg ->
                val reindexed = seg.mapIndexed { i, sf -> sf.copy(order = i) }
                out += makeCandidate(
                    reindexed, forUid, orientationKey, kind = "SPACING_SEGMENT_$si",
                    annotations = listOf("SPACING_SEGMENT:$si", "SEGMENT_OF_SPLIT_STACK"),
                    eventId = eventId, seriesUid = seriesUid,
                    reasonTag = "seg$si"
                )
            }
        }

        return out
    }

    private fun suspectCandidate(
        frames: List<FrameGeometry>, eventId: String, kind: String, annotation: String, forLabel: String?
    ): SeriesCandidate {
        val normal = frames.first().orientation?.normal ?: Vec3(0.0, 0.0, 1.0)
        val stack = frames.sortedBy { it.position?.dot(normal) ?: 0.0 }.mapIndexed { i, fg ->
            StackFrame(fg, fg.position?.dot(normal) ?: 0.0, i)
        }
        return makeCandidate(stack, forLabel, StableIds.of("ori", forLabel ?: "none", kind), kind,
            listOf(annotation), eventId,
            stack.map { it.geometry.seriesInstanceUid }.filterNotNull().firstOrNull(), "suspect")
    }

    private fun makeCandidate(
        stack: List<StackFrame>,
        forUid: String?,
        orientationKey: String,
        kind: String,
        annotations: List<String>,
        eventId: String,
        seriesUid: String?,
        reasonTag: String
    ): SeriesCandidate {
        val lineage = seriesLineageSeed(stack, forUid, orientationKey)
        val gaps = stack.zipWithNext().map { (a, b) -> b.tAlongNormal - a.tAlongNormal }.filter { it > 0.0 }
        val medianSpacing = gaps.takeIf { it.isNotEmpty() }?.let { medianOf(it) }
        val orderedIds = stack.map { "${it.geometry.instanceId}#${it.geometry.frameNumber}@%.4f".format(it.tAlongNormal) }
        val candidateId = StableIds.of("cand", lineage, kind, reasonTag, orderedIds.joinToString("|"))
        val evidence = stack.flatMap { it.geometry.evidence }.distinct()
        return SeriesCandidate(
            candidateId = candidateId,
            lineageKey = lineage,
            kind = kind,
            frameOfReferenceUid = forUid,
            orientationKey = orientationKey,
            frames = stack,
            medianSpacingMm = medianSpacing,
            annotations = annotations.distinct(),
            evidence = evidence,
            derivedAtEventId = eventId,
            ruleVersion = RULE_VERSION
        )
    }

    private fun seriesLineageSeed(stack: List<StackFrame>, forUid: String?, orientationKey: String): String {
        val seriesUid = stack.map { it.geometry.seriesInstanceUid }.filterNotNull().sorted().distinct().joinToString(",")
        return StableIds.of("line", forUid ?: "__NO_FOR__", orientationKey, seriesUid.ifBlank { "no-series-uid" })
    }

    private fun clusterEvidence(stack: List<StackFrame>): List<EvidenceRef> =
        stack.flatMap { it.geometry.evidence }.distinct()

    private fun groupDuplicateClusters(stack: List<StackFrame>, tolMm: Double): List<List<StackFrame>> {
        val groups = mutableListOf<MutableList<StackFrame>>()
        stack.forEach { sf ->
            val g = groups.firstOrNull { grp -> abs(grp.first().tAlongNormal - sf.tAlongNormal) <= tolMm }
            if (g == null) groups.add(mutableListOf(sf)) else g.add(sf)
        }
        return groups.filter { it.size > 1 }
    }

    private fun medianOf(values: List<Double>): Double {
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2.0
    }
}
