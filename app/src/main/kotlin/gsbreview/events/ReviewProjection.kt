package gsbreview.events

import gsbreview.db.Store
import gsbreview.domain.InterpretationView
import gsbreview.domain.VolumeCandidate
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

data class CandidateDecision(
    val candidateId: String,
    val interpretationId: String,
    val decision: String,
    val eventSeq: Long,
    val note: String?
)

data class ReviewState(
    val branchId: String,
    val asOfSeq: Long,
    val interpretation: InterpretationView?,
    val decisions: Map<String, CandidateDecision>,
    val acceptedCandidateIds: Set<String>,
    val rejectedCandidateIds: Set<String>,
    val visibleEvents: List<Map<String, Any?>>,
    val parameterOverrides: JsonObject?,
    val invalidationNotes: List<String>
)

class ReviewProjection(private val store: Store, private val eventStore: EventStore) {
    private val json = Json { ignoreUnknownKeys = true }

    fun stateAt(branchId: String, atOrBeforeSeq: Long? = null): ReviewState {
        val branch = eventStore.branchState(branchId, atOrBeforeSeq = null)
        val cutoff = atOrBeforeSeq ?: branch.headSeq
        val events = branch.events.filter { it.eventSeq <= cutoff }

        var currentInterpretationId: String? = null
        var parameterOverrides: JsonObject? = null
        val decisions = LinkedHashMap<String, CandidateDecision>()
        val notes = mutableListOf<String>()

        for (e in events) {
            val payload = runCatching { json.parseToJsonElement(e.payloadJson) as JsonObject }.getOrNull()
            when (e.eventType) {
                "import_committed" -> {
                    currentInterpretationId = payload?.get("interpretationId")?.jsonPrimitive?.content
                }
                "interpretation_created" -> {
                    currentInterpretationId = payload?.get("interpretationId")?.jsonPrimitive?.content
                }
                "parameter_revision" -> {
                    currentInterpretationId = payload?.get("interpretationId")?.jsonPrimitive?.content
                    parameterOverrides = payload
                    notes += "seq ${e.eventSeq}: parameter revision -> $currentInterpretationId; " +
                        "older conclusions remain readable"
                }
                "source_changed" -> {
                    currentInterpretationId = payload?.get("interpretationId")?.jsonPrimitive?.content
                    notes += "seq ${e.eventSeq}: local source change -> $currentInterpretationId; " +
                        "invalidations are scoped via dependencies"
                }
                "candidate_accepted" -> {
                    val cid = payload?.get("candidateId")?.jsonPrimitive?.content ?: continue
                    val iid = payload["interpretationId"]?.jsonPrimitive?.content ?: continue
                    decisions[cid] = CandidateDecision(cid, iid, "accepted", e.eventSeq,
                        payload["note"]?.jsonPrimitive?.content)
                }
                "candidate_rejected" -> {
                    val cid = payload?.get("candidateId")?.jsonPrimitive?.content ?: continue
                    val iid = payload?.get("interpretationId")?.jsonPrimitive?.content ?: continue
                    decisions[cid] = CandidateDecision(cid, iid, "rejected", e.eventSeq,
                        payload["note"]?.jsonPrimitive?.content)
                }
                "candidate_accept_revoked", "candidate_reject_revoked" -> {
                    val cid = payload?.get("candidateId")?.jsonPrimitive?.content
                    if (cid != null) decisions.remove(cid)
                }
                "parameter_revision_reverted" -> {
                    // Recompute visible interp from prior events: simply remove the override effect
                    // by scanning backwards is avoided; replay semantics: revert appends an event
                    // that points back; find the previous interp.
                    val previous = events.takeWhile { it.eventSeq < e.eventSeq }
                        .lastOrNull { it.eventType in setOf("interpretation_created", "import_committed", "source_changed") }
                    if (previous != null) {
                        currentInterpretationId = runCatching {
                            (json.parseToJsonElement(previous.payloadJson) as JsonObject)
                                ["interpretationId"]?.jsonPrimitive?.content
                        }.getOrNull() ?: currentInterpretationId
                    }
                    parameterOverrides = null
                }
            }
        }

        val interpretation = currentInterpretationId?.let { id ->
            runCatching { store.loadInterpretation(id) }.getOrNull()
        }
        val visibleEvents = events.map { e ->
            mapOf(
                "seq" to e.eventSeq,
                "eventId" to e.eventId,
                "type" to e.eventType,
                "actor" to e.actor,
                "createdAt" to e.createdAt,
                "basisEventSeq" to e.basisEventSeq,
                "payload" to (runCatching { json.parseToJsonElement(e.payloadJson) }.getOrNull()
                    ?: JsonObject(emptyMap()))
            )
        }
        return ReviewState(
            branchId = branchId,
            asOfSeq = cutoff,
            interpretation = interpretation,
            decisions = decisions,
            acceptedCandidateIds = decisions.filterValues { it.decision == "accepted" }.keys,
            rejectedCandidateIds = decisions.filterValues { it.decision == "rejected" }.keys,
            visibleEvents = visibleEvents,
            parameterOverrides = parameterOverrides,
            invalidationNotes = notes
        )
    }

    fun decorateCandidates(state: ReviewState): List<VolumeCandidate> {
        val interp = state.interpretation ?: return emptyList()
        return interp.candidates.map { c ->
            val d = state.decisions[c.candidateId]
            c.copy(accepted = d?.decision == "accepted")
        }
    }
}
