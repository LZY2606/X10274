package org.research.gsb.dicom.store

import kotlinx.serialization.Serializable

@Serializable
data class ImportedInstanceRecord(
    val slotId: String,
    val instanceKey: String,
    val relativePath: String,
    val sopInstanceUid: String,
    val seriesInstanceUid: String,
    val studyInstanceUid: String?,
    val frameOfReferenceUid: String?,
    val transferSyntaxUid: String,
    val transferSyntaxDecodable: Boolean,
    val multiFrame: Boolean,
    val frameCount: Int,
    val sha256: String,
    val parseStatus: String,
    val diagnostics: List<String> = emptyList(),
)

@Serializable
data class SourceChange(
    val slotId: String,
    val previousSha256: String?,
    val nextSha256: String,
    val reason: String,
)

@Serializable
data class InvalidationScope(
    val kind: String,                 // LOCAL | GLOBAL_RULE
    val affectedInstanceKeys: List<String> = emptyList(),
    val affectedCandidateIds: List<String> = emptyList(),
    val affectedSeriesUids: List<String> = emptyList(),
    val note: String = "",
)

@Serializable
sealed class EventPayload {
    @Serializable
    data class SourceImport(
        val batchId: String,
        val label: String,
        val instances: List<ImportedInstanceRecord>,
        val rollbackOnError: Boolean = true,
    ) : EventPayload()

    @Serializable
    data class RuleRevision(
        val previousRuleVersionId: String?,
        val nextRuleVersionId: String,
        val parameters: Map<String, Double>,
        val rationale: String,
    ) : EventPayload()

    @Serializable
    data class SourcePatch(
        val label: String,
        val changes: List<SourceChange>,
        val scope: InvalidationScope,
    ) : EventPayload()

    @Serializable
    data class Decision(
        val action: String,             // ACCEPT | REJECT | ADJUST
        val candidateId: String,
        val seriesUid: String,
        val comment: String = "",
        val parameterOverrides: Map<String, Double> = emptyMap(),
        val baseEventId: String,
    ) : EventPayload()

    @Serializable
    data class ConflictRejected(
        val attempted: Decision,
        val serverHeadEventId: String,
        val otherActor: String,
        val diffSummary: String,
    ) : EventPayload()
}

@Serializable
data class StoredEvent(
    val eventId: String,
    val branchId: String,
    val parentEventId: String?,
    val seq: Long,
    val actor: String,
    val createdAt: String,
    val ruleVersionId: String,
    val type: String,
    val payload: EventPayload,
    val sha256: String,
)
