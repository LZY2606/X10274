package org.gsb.dicomreview.review

import kotlinx.serialization.json.Json
import org.gsb.dicomreview.geom.GeometryEngine
import org.gsb.dicomreview.geom.GeometryExtractor
import org.gsb.dicomreview.geom.StableIds
import org.gsb.dicomreview.model.Branch
import org.gsb.dicomreview.model.DependencyEdge
import org.gsb.dicomreview.model.EventPayload
import org.gsb.dicomreview.model.FrameGeometry
import org.gsb.dicomreview.model.GeometryResult
import org.gsb.dicomreview.model.GroupingParameters
import org.gsb.dicomreview.model.InvalidationReport
import org.gsb.dicomreview.model.RecordedEvent
import org.gsb.dicomreview.model.SeriesCandidate
import org.gsb.dicomreview.model.SopRevision
import org.gsb.dicomreview.model.SupersedePair
import org.gsb.dicomreview.store.Store

data class DecisionRecord(
    val eventId: String,
    val lineageKey: String,
    val candidateId: String,
    val verdict: String,
    val rationale: String,
    val active: Boolean
)

data class BranchState(
    val branchId: String,
    val head: RecordedEvent,
    val events: List<RecordedEvent>,
    val activeInstanceIdBySop: Map<String, String>,
    val activeInstanceIds: List<String>,
    val instancesById: Map<String, org.gsb.dicomreview.model.RawInstance>,
    val framesByInstanceId: Map<String, List<FrameGeometry>>,
    val parameters: GroupingParameters,
    val decisions: Map<String, DecisionRecord>,
    val geometry: GeometryResult,
    val invalidations: Map<String, InvalidationReport>,
    val lineageCurrentCandidate: Map<String, String>,
    val parameterEventId: String
)

class Interpreter(val store: Store) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun payload(event: RecordedEvent): EventPayload = json.decodeFromString(event.payloadJson)

    fun branchState(branchId: String, asOfSeq: Long? = null): BranchState {
        val branch = store.listBranches().first { it.branchId == branchId }
        val allEvents = eventPrefix(branch)
        val events = if (asOfSeq == null) allEvents else allEvents.filter { it.seq <= asOfSeq }
        val head = events.last()

        val activeBySop = linkedMapOf<String, String>()
        val retired = linkedSetOf<String>()
        var parameters = GroupingParameters()
        var parameterEventId = "evt_root"
        val decisions = linkedMapOf<String, DecisionRecord>()
        val batchOrdinals = linkedMapOf<String, Int>()

        events.forEach { ev ->
            when (val p = payload(ev)) {
                is EventPayload.MaterialImported -> {
                    p.sopRevisions.filter { it.active }.forEach { rev ->
                        activeBySop[rev.sopInstanceUid] = rev.instanceId
                    }
                    p.instanceIds.forEachIndexed { i, id -> batchOrdinals[id] = i }
                }
                is EventPayload.MaterialReinterpreted -> {
                    p.retiredInstanceIds.forEach { retired.add(it) }
                    activeBySop.filterValues { it in p.retiredInstanceIds }.keys.toList()
                        .forEach { sop ->
                            val replacement = p.addedInstanceIds.firstOrNull()
                            activeBySop[sop] = replacement ?: activeBySop.getValue(sop)
                        }
                    p.sopRevisions.filter { it.active }.forEach { rev ->
                        activeBySop[rev.sopInstanceUid] = rev.instanceId
                        retired.remove(rev.instanceId)
                    }
                }
                is EventPayload.ParametersRevised -> {
                    parameters = p.parameters; parameterEventId = ev.eventId
                }
                is EventPayload.Decision -> {
                    decisions[p.candidateLineageKey] = DecisionRecord(
                        ev.eventId, p.candidateLineageKey, p.candidateId, p.verdict, p.rationale, true
                    )
                }
                is EventPayload.DecisionReverted -> {
                    val target = events.firstOrNull { it.eventId == p.targetDecisionEventId }
                    if (target != null) {
                        val dp = payload(target) as EventPayload.Decision
                        decisions[dp.candidateLineageKey] = decisions[dp.candidateLineageKey]!!.copy(active = false)
                    }
                }
                else -> Unit
            }
        }

        val rawById = store.allInstances().associateBy { it.instanceId }
        val activeMaterialIds = activeBySop.values.distinct().filter { it in rawById }
        val frames = linkedMapOf<String, List<FrameGeometry>>()
        activeMaterialIds.forEach { id ->
            val raw = rawById.getValue(id)
            val bytes = store.rawBytes(id)!!
            val extracted = GeometryExtractor.extract(raw.batchId, raw.ordinalInBatch, raw.fileName, bytes)
            frames[id] = extracted.frames
        }

        val derivedAt = head.eventId
        val geometry = GeometryEngine.evaluate(
            frames.values.flatten(),
            rawById.filterKeys { it in activeMaterialIds },
            parameters,
            derivedAt
        )

        val invalidations = store.listInvalidations(branchId)
            .map { (_, js) -> json.decodeFromString<InvalidationReport>(js) }
            .filter { rep -> rep.triggerEventId in events.map { it.eventId } }
            .associateBy { it.triggerEventId }

        return BranchState(
            branchId = branchId,
            head = head,
            events = events,
            activeInstanceIdBySop = activeBySop,
            activeInstanceIds = activeMaterialIds,
            instancesById = rawById,
            framesByInstanceId = frames,
            parameters = parameters,
            decisions = decisions,
            geometry = geometry,
            invalidations = invalidations,
            lineageCurrentCandidate = geometry.candidates.associate { it.lineageKey to it.candidateId },
            parameterEventId = parameterEventId
        )
    }

    /** Branch event lists start with the parent branch prefix (the fork point), then own events. */
    private fun eventPrefix(branch: Branch): List<RecordedEvent> {
        val own = store.eventsOnBranch(branch.branchId)
        if (branch.parentBranchId == null) return own
        val parent = store.listBranches().first { it.branchId == branch.parentBranchId }
        val parentPrefix = eventPrefix(parent)
        val forkPoint = parentPrefix.indexOfFirst { it.eventId == branch.forkedFromEventId }
        return if (forkPoint < 0) parentPrefix + own else parentPrefix.take(forkPoint + 1) + own
    }

    /**
     * Recompute derived candidates for the state after [eventId] and compare with the snapshot after
     * its parent. Only dependencies reachable from changed material/parameter nodes are invalidated;
     * untouched candidates keep identical stable identities.
     */
    fun computeInvalidation(
        branchId: String,
        beforeState: BranchState,
        afterState: BranchState,
        triggerEventId: String,
        changedInstanceIds: List<String>,
        changedSopUids: List<String>,
        parameterScopeChanged: Boolean
    ): InvalidationReport {
        val beforeById = beforeState.geometry.candidates.associateBy { it.candidateId }
        val afterById = afterState.geometry.candidates.associateBy { it.candidateId }

        val edges = dependencyEdges(afterState)
        val changedNodes = changedInstanceIds.map { "material:$it" } +
            if (parameterScopeChanged) listOf("parameters:${afterState.parameterEventId}") else emptyList()
        val reachableCandidates = forwardReach(edges, changedNodes)
            .filter { it.startsWith("candidate:") }.map { it.removePrefix("candidate:") }.toSet()

        val invalidated = (beforeById.keys - afterById.keys).filter { it in reachableCandidates || parameterScopeChanged }
        val retained = afterState.geometry.candidates.filter { it.candidateId in beforeById }.map { it.candidateId }

        // lineage based supersession within affected scope
        val oldByLineage = beforeById.values.associateBy { it.lineageKey }
        val newByLineage = afterById.values.associateBy { it.lineageKey }
        val superseded = newByLineage.mapNotNull { (lineage, new) ->
            val old = oldByLineage[lineage]
            if (old != null && old.candidateId != new.candidateId &&
                (new.candidateId in reachableCandidates || old.candidateId in reachableCandidates || parameterScopeChanged)) {
                SupersedePair(old.candidateId, new.candidateId, lineage,
                    if (parameterScopeChanged) "parameters-revised" else "source-reinterpreted")
            } else null
        }

        val invalidatedValues = reachableCandidates.filter { it.startsWith("value:") }
        val invalidatedRuleIds = afterState.geometry.ruleFires
            .filter { fire -> fire.affectedInstanceIds.any { it in changedInstanceIds } }
            .map { it.fireId }

        return InvalidationReport(
            triggerEventId = triggerEventId,
            changedMaterialInstanceIds = changedInstanceIds,
            changedSopUids = changedSopUids,
            invalidatedCandidateIds = invalidated,
            invalidatedValueIds = invalidatedValues,
            invalidatedRuleFireIds = invalidatedRuleIds,
            retainedCandidateIds = retained,
            superseded = superseded,
            parameterScopeChanged = parameterScopeChanged
        )
    }

    fun dependencyEdges(state: BranchState): List<DependencyEdge> {
        val edges = mutableListOf<DependencyEdge>()
        val paramNode = "parameters:${state.parameterEventId}"
        state.geometry.candidates.forEach { cand ->
            val candidateNode = "candidate:${cand.candidateId}"
            cand.frames.map { it.geometry.instanceId }.distinct().forEach { id ->
                edges += DependencyEdge("material:$id", candidateNode)
            }
            edges += DependencyEdge(paramNode, candidateNode)
            cand.frames.forEach { sf ->
                val valueId = StableIds.of("value", cand.candidateId, sf.geometry.instanceId, sf.geometry.frameNumber.toString())
                edges += DependencyEdge(candidateNode, "value:$valueId")
            }
        }
        return edges
    }

    private fun forwardReach(edges: List<DependencyEdge>, seeds: List<String>): Set<String> {
        val adjacency = edges.groupBy { it.fromNode }.mapValues { e -> e.value.map { it.toNode } }
        val seen = mutableSetOf<String>()
        val queue = ArrayDeque(seeds)
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            if (!seen.add(node)) continue
            adjacency[node]?.forEach { queue.add(it) }
        }
        return seen
    }
}
