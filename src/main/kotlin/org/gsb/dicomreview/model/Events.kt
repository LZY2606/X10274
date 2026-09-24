package org.gsb.dicomreview.model

import kotlinx.serialization.Serializable

@Serializable
data class Branch(
    val branchId: String,
    val name: String,
    val parentBranchId: String?,
    val forkedFromEventId: String?
)

@Serializable
sealed interface EventPayload {
    @Serializable
    data class Root(val note: String = "root") : EventPayload

    @Serializable
    data class MaterialImported(
        val batchId: String,
        val label: String,
        val instanceIds: List<String>,
        val sopRevisions: List<SopRevision>,
        val rolledBack: Boolean = false,
        val rollbackReason: String? = null
    ) : EventPayload

    @Serializable
    data class MaterialReinterpreted(
        val fromBatchId: String,
        val newBatchId: String,
        val label: String,
        val addedInstanceIds: List<String>,
        val revisedSopUids: List<String>,
        val retiredInstanceIds: List<String>
    ) : EventPayload

    @Serializable
    data class ParametersRevised(
        val oldVersion: String,
        val newVersion: String,
        val parameters: GroupingParameters
    ) : EventPayload

    @Serializable
    data class Decision(
        val candidateLineageKey: String,
        val candidateId: String,
        val verdict: String,
        val rationale: String
    ) : EventPayload

    @Serializable
    data class DecisionReverted(val targetDecisionEventId: String) : EventPayload

    @Serializable
    data class BranchForked(
        val newBranchId: String,
        val newBranchName: String,
        val fromEventId: String
    ) : EventPayload
}

@Serializable
data class SopRevision(
    val sopInstanceUid: String,
    val instanceId: String,
    val rawBytesHash: String,
    val revisionOrdinal: Int,
    val active: Boolean
)

@Serializable
data class RecordedEvent(
    val eventId: String,
    val branchId: String,
    val seq: Long,
    val type: String,
    val payloadJson: String,
    val parentEventId: String?,
    val clientHeadEventId: String?
)

@Serializable
data class DependencyEdge(val fromNode: String, val toNode: String)

@Serializable
data class InvalidationReport(
    val triggerEventId: String,
    val changedMaterialInstanceIds: List<String>,
    val changedSopUids: List<String>,
    val invalidatedCandidateIds: List<String>,
    val invalidatedValueIds: List<String>,
    val invalidatedRuleFireIds: List<String>,
    val retainedCandidateIds: List<String>,
    val superseded: List<SupersedePair>,
    val parameterScopeChanged: Boolean
)

@Serializable
data class SupersedePair(
    val oldCandidateId: String,
    val newCandidateId: String,
    val lineageKey: String,
    val reason: String
)
