package gsbreview

import gsbreview.db.RawTagView
import gsbreview.db.Store
import gsbreview.db.SubjectRow
import gsbreview.db.rawTagsForSubject
import gsbreview.derive.DeriveService
import gsbreview.derive.GeometryParams
import gsbreview.domain.InterpretationView
import gsbreview.dicom.TransferSyntaxes
import gsbreview.events.EventStore
import gsbreview.events.ReviewProjection
import gsbreview.events.ReviewState
import gsbreview.events.VersionConflict
import gsbreview.imports.ImportService
import gsbreview.imports.IncomingFile

class AppService(
    val store: Store,
    val ruleVersion: String = "geometry-rules-2026-09-24"
) {
    val eventStore = EventStore(store)
    private val importService = ImportService(store)
    private val deriveService = DeriveService(store)
    val projection = ReviewProjection(store, eventStore)

    fun defaultParams(overrides: Map<String, Double> = emptyMap()) = GeometryParams(
        ruleVersion = ruleVersion,
        orthogonalityTolerance = overrides["orthogonalityTolerance"] ?: 0.001,
        duplicatePositionToleranceMm = overrides["duplicatePositionToleranceMm"] ?: 0.01,
        spacingJumpRatio = overrides["spacingJumpRatio"] ?: 1.5,
        spacingResidualToleranceMm = overrides["spacingResidualToleranceMm"] ?: 0.5
    )

    data class ImportOutcome(
        val batchId: String,
        val interpretationId: String,
        val eventSeq: Long,
        val uidConflicts: List<String>,
        val state: ReviewState
    )

    fun importAndInterpret(
        files: List<IncomingFile>,
        caseId: String?,
        note: String,
        stage: String = "initial",
        actor: String = "reviewer",
        branchId: String = eventStore.mainBranchId,
        baseSeq: Long = eventStore.headSeq(eventStore.mainBranchId)
    ): ImportOutcome {
        val imported = importService.importBatch(files, caseId, note, stage)
        val view = deriveService.runInterpretation(defaultParams(), imported.batchId, caseId,
            trigger = "import:$note")
        val payload = buildString {
            append("{")
            append("\"batchId\":\"").append(imported.batchId).append("\",")
            append("\"interpretationId\":\"").append(view.interpretationId).append("\",")
            append("\"caseId\":").append(caseId?.let { "\"$it\"" } ?: "null").append(",")
            append("\"stage\":\"").append(stage).append("\",")
            append("\"uidConflicts\":[").append(imported.uidConflicts.joinToString(",") { "\"${escape(it)}\"" })
            .append("]}")
        }
        val append = eventStore.append(branchId, "import_committed", payload, actor, baseSeq)
        if (!append.ok) {
            throw VersionConflict(append.conflict!!.message, append.conflict)
        }
        return ImportOutcome(imported.batchId, view.interpretationId, append.event!!.eventSeq,
            imported.uidConflicts, projection.stateAt(branchId))
    }

    fun reviseParameters(
        overrides: Map<String, Double>,
        note: String,
        actor: String,
        branchId: String,
        baseSeq: Long,
        batchId: String = currentBatchId()
    ): Long {
        val stateBefore = projection.stateAt(branchId)
        val params = defaultParams(overrides)
        val view = deriveService.runInterpretation(params, batchId, stateBefore.interpretation?.caseId,
            trigger = "parameter-revision:$note")
        val payload =
            """{"parameterSetId":"${view.parameterSetId}","interpretationId":"${view.interpretationId}",
            |"overrides":${overridesJson(overrides)},"note":"${escape(note)}",
            |"previousInterpretationId":"${stateBefore.interpretation?.interpretationId}"}"""
                .trimMargin().replace("\n", "")
        val res = eventStore.append(branchId, "parameter_revision", payload, actor, baseSeq)
        if (!res.ok) throw VersionConflict(res.conflict!!.message, res.conflict)
        return res.event!!.eventSeq
    }

    fun recordSourceChange(
        files: List<IncomingFile>,
        caseId: String?,
        note: String,
        actor: String,
        branchId: String,
        baseSeq: Long
    ): Long {
        val stateBefore = projection.stateAt(branchId)
        val imported = importService.importBatch(files, caseId, note, stage = "source-revision")
        val view = deriveService.runInterpretation(defaultParams(), imported.batchId, caseId,
            trigger = "source-change:$note")
        val payload =
            """{"batchId":"${imported.batchId}","interpretationId":"${view.interpretationId}",
            |"changedPaths":[${files.joinToString(",") { "\"${escape(it.relPath)}\"" }}],
            |"previousInterpretationId":"${stateBefore.interpretation?.interpretationId}",
            |"note":"${escape(note)}"}""".trimMargin().replace("\n", "")
        val res = eventStore.append(branchId, "source_changed", payload, actor, baseSeq)
        if (!res.ok) throw VersionConflict(res.conflict!!.message, res.conflict)
        return res.event!!.eventSeq
    }

    fun decide(
        candidateId: String,
        accept: Boolean,
        note: String?,
        actor: String,
        branchId: String,
        baseSeq: Long
    ): Long {
        val state = projection.stateAt(branchId)
        val candidate = state.interpretation?.candidates?.firstOrNull { it.candidateId == candidateId }
            ?: error("candidate $candidateId is not a legal conclusion at the current branch state")
        val type = if (accept) "candidate_accepted" else "candidate_rejected"
        val payload =
            """{"candidateId":"${candidate.candidateId}",
            |"interpretationId":"${state.interpretation!!.interpretationId}",
            |"volumeKey":"${escape(candidate.volumeKey)}","note":"${escape(note ?: "")}"}"""
                .trimMargin().replace("\n", "")
        val res = eventStore.append(branchId, type, payload, actor, baseSeq)
        if (!res.ok) throw VersionConflict(res.conflict!!.message, res.conflict)
        return res.event!!.eventSeq
    }

    fun undoLastDecision(actor: String, branchId: String, baseSeq: Long, targetSeq: Long): Long {
        val res = eventStore.undo(branchId, actor, baseSeq, targetSeq)
        if (!res.ok) throw VersionConflict(res.conflict!!.message, res.conflict!!)
        return res.event!!.eventSeq
    }

    fun forkBranch(name: String, fromBranch: String, atSeq: Long): String =
        eventStore.createBranch(name, fromBranch, atSeq)

    fun currentBatchId(): String = store.batches().lastOrNull()?.batchId
        ?: error("no import batch yet")

    fun subjects(): List<SubjectRow> = store.allSubjects()
    fun tags(subjectKey: String): List<RawTagView> = store.rawTagsForSubject(subjectKey)
    fun interpretation(id: String): InterpretationView = deriveService.loadInterpretation(id)
    fun state(branchId: String = eventStore.mainBranchId, asOf: Long? = null): ReviewState =
        projection.stateAt(branchId, asOf)

    fun transferSyntaxInfo(uid: String?) = TransferSyntaxes.lookup(uid)

    private fun overridesJson(overrides: Map<String, Double>) =
        "{" + overrides.entries.joinToString(",") { "\"${it.key}\":${it.value}" } + "}"

    private fun escape(s: String) =
        s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ")
}
