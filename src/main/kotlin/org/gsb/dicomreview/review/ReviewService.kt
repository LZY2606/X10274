package org.gsb.dicomreview.review

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.gsb.dicomreview.geom.GeometryExtractor
import org.gsb.dicomreview.geom.StableIds
import org.gsb.dicomreview.model.EventPayload
import org.gsb.dicomreview.model.InvalidationReport
import org.gsb.dicomreview.model.RecordedEvent
import org.gsb.dicomreview.model.SopRevision
import org.gsb.dicomreview.store.CounterexampleRow
import org.gsb.dicomreview.store.Store
import org.gsb.dicomreview.model.Branch

class ConcurrentModification(message: String) : RuntimeException(message)
class ImportRejected(message: String) : RuntimeException(message)

@Serializable
data class ExportBundle(
    val version: Int = 1,
    val branches: List<Branch>,
    val events: List<RecordedEvent>,
    val counterexamples: List<CounterexampleRow>,
    val materials: List<MaterialEntry>
)

@Serializable
data class MaterialEntry(
    val instanceId: String,
    val fileName: String,
    val bytesBase64: String
)

class ReviewService(val store: Store) {
    val interp = Interpreter(store)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = false }

    init { store.ensureMainBranch() }

    fun state(branchId: String = "br_main", asOfSeq: Long? = null): BranchState =
        interp.branchState(branchId, asOfSeq)

    private fun decodePayload(event: RecordedEvent): EventPayload =
        json.decodeFromString(event.payloadJson)

    /** Import a batch; any malformed member rolls back the whole batch (no half records). */
    fun importMaterials(
        files: List<Pair<String, ByteArray>>,
        label: String,
        branchId: String = "br_main",
        expectedHeadEventId: String? = null,
        clientBatchId: String? = null
    ): RecordedEvent = store.transaction {
        checkHead(branchId, expectedHeadEventId)
        // Parse every file first; one failure rejects the entire import.
        val parsed = files.mapIndexed { ordinal, (name, bytes) ->
            try {
                GeometryExtractor.extract("pending", ordinal, name, bytes)
            } catch (t: Throwable) {
                throw ImportRejected("batch '$label' rolled back: ${t.message}")
            }
        }
        val batchHash = StableIds.of("batch", label, parsed.joinToString("|") { it.raw.rawBytesHash + ":" + it.raw.fileName })
        if (store.batchExists(batchHash)) {
            return@transaction store.head(branchId)!!
        }
        val ord = store.nextBatchOrdinal()
        store.upsertBatch(batchHash, label, ord)
        parsed.forEachIndexed { idx, material ->
            val raw = material.raw.copy(batchId = batchHash, ordinalInBatch = idx)
            store.insertRaw(raw, files[idx].second)
            store.addBatchMember(batchHash, raw.instanceId, idx)
        }

        val before = interp.branchState(branchId)
        val sopRevisions = computeSopRevisions(before, parsed, batchHash)
        val seq = store.nextSeq(branchId)
        val head = store.head(branchId)
        val eventId = StableIds.of("evt", branchId, seq.toString(), batchHash)
        val payload = EventPayload.MaterialImported(
            batchId = batchHash,
            label = label,
            instanceIds = parsed.map { it.raw.instanceId },
            sopRevisions = sopRevisions
        )
        val event = RecordedEvent(eventId, branchId, seq, "MATERIAL_IMPORTED",
            json.encodeToString(payload), head?.eventId, expectedHeadEventId)
        store.appendEvent(event)
        val after = interp.branchState(branchId)
        val changedSops = sopRevisions.map { it.sopInstanceUid }.distinct()
        persistInvalidation(branchId, event, before, after,
            parsed.map { it.raw.instanceId }, changedSops, parameterScopeChanged = false)
        event
    }

    /** Re-interpret the same SOP UID from a new direct source: old bytes and branch stay readable. */
    fun reinterpret(
        files: List<Pair<String, ByteArray>>,
        label: String,
        branchId: String = "br_main",
        expectedHeadEventId: String? = null
    ): RecordedEvent = store.transaction {
        checkHead(branchId, expectedHeadEventId)
        val parsed = files.mapIndexed { i, (name, bytes) ->
            try { GeometryExtractor.extract("pending", i, name, bytes) }
            catch (t: Throwable) { throw ImportRejected("reinterpret '$label' rolled back: ${t.message}") }
        }
        val batchId = StableIds.of("batch", "reinterp", label, parsed.joinToString("|") { it.raw.rawBytesHash })
        store.upsertBatch(batchId, label, store.nextBatchOrdinal())
        parsed.forEachIndexed { idx, m ->
            val raw = m.raw.copy(batchId = batchId, ordinalInBatch = idx)
            store.insertRaw(raw, files[idx].second)
            store.addBatchMember(batchId, raw.instanceId, idx)
        }
        val before = interp.branchState(branchId)
        val revisedSopUids = parsed.mapNotNull { it.raw.sopInstanceUid }
            .filter { it in before.activeInstanceIdBySop }.distinct()
        val retiredIds = revisedSopUids.map { before.activeInstanceIdBySop.getValue(it) }
        val seq = store.nextSeq(branchId)
        val head = store.head(branchId)
        val eventId = StableIds.of("evt", branchId, seq.toString(), batchId)
        val payload = EventPayload.MaterialReinterpreted(
            fromBatchId = "active",
            newBatchId = batchId,
            label = label,
            addedInstanceIds = parsed.map { it.raw.instanceId },
            revisedSopUids = revisedSopUids,
            retiredInstanceIds = retiredIds
        )
        val event = RecordedEvent(eventId, branchId, seq, "MATERIAL_REINTERPRETED",
            json.encodeToString(payload), head?.eventId, expectedHeadEventId)
        store.appendEvent(event)
        val after = interp.branchState(branchId)
        persistInvalidation(branchId, event, before, after,
            parsed.map { it.raw.instanceId } + retiredIds, revisedSopUids, false)
        event
    }

    fun reviseParameters(
        params: org.gsb.dicomreview.model.GroupingParameters,
        branchId: String = "br_main",
        expectedHeadEventId: String? = null
    ): RecordedEvent = store.transaction {
        checkHead(branchId, expectedHeadEventId)
        val before = interp.branchState(branchId)
        val seq = store.nextSeq(branchId)
        val head = store.head(branchId)
        val newVersion = "params-${params.parameterVersion}@$seq"
        val eventId = StableIds.of("evt", branchId, seq.toString(), "params", newVersion)
        val payload = EventPayload.ParametersRevised(
            oldVersion = before.parameters.parameterVersion,
            newVersion = newVersion,
            parameters = params.copy(parameterVersion = newVersion)
        )
        val event = RecordedEvent(eventId, branchId, seq, "PARAMETERS_REVISED",
            json.encodeToString(payload), head?.eventId, expectedHeadEventId)
        store.appendEvent(event)
        val after = interp.branchState(branchId)
        persistInvalidation(branchId, event, before, after, emptyList(), emptyList(), true)
        event
    }

    fun decide(
        lineageKey: String,
        verdict: String,
        rationale: String,
        branchId: String = "br_main",
        expectedHeadEventId: String? = null,
        candidateId: String? = null
    ): RecordedEvent = store.transaction {
        checkHead(branchId, expectedHeadEventId)
        val state = interp.branchState(branchId)
        val resolvedCandidate = candidateId
            ?: state.geometry.candidates.firstOrNull { it.lineageKey == lineageKey }?.candidateId
            ?: throw ImportRejected("no candidate for lineage $lineageKey")
        val seq = store.nextSeq(branchId)
        val head = store.head(branchId)
        val eventId = StableIds.of("evt", branchId, seq.toString(), "decision", lineageKey, verdict)
        val payload = EventPayload.Decision(lineageKey, resolvedCandidate, verdict, rationale)
        val event = RecordedEvent(eventId, branchId, seq, "DECISION",
            json.encodeToString(payload), head?.eventId, expectedHeadEventId)
        store.appendEvent(event)
        event
    }

    fun revertDecision(targetEventId: String, branchId: String, expectedHeadEventId: String?): RecordedEvent =
        store.transaction {
            checkHead(branchId, expectedHeadEventId)
            val seq = store.nextSeq(branchId)
            val head = store.head(branchId)
            val eventId = StableIds.of("evt", branchId, seq.toString(), "revert", targetEventId)
            val payload = EventPayload.DecisionReverted(targetEventId)
            val event = RecordedEvent(eventId, branchId, seq, "DECISION_REVERTED",
                json.encodeToString(payload), head?.eventId, expectedHeadEventId)
            store.appendEvent(event)
            event
        }

    fun forkBranch(newName: String, fromBranchId: String, expectedHeadEventId: String): Pair<Branch, EventPayload.BranchForked> =
        store.transaction {
            val head = store.head(fromBranchId) ?: error("branch missing")
            if (head.eventId != expectedHeadEventId) {
                throw ConcurrentModification("branch $fromBranchId moved since you loaded it")
            }
            val newBranchId = StableIds.of("br", fromBranchId, head.eventId, newName)
            val branch = Branch(newBranchId, newName, fromBranchId, head.eventId)
            store.createBranch(branch)
            branch to EventPayload.BranchForked(newBranchId, newName, head.eventId)
        }

    private fun persistInvalidation(
        branchId: String,
        trigger: RecordedEvent,
        before: BranchState,
        after: BranchState,
        changedMaterials: List<String>,
        changedSops: List<String>,
        parameterScopeChanged: Boolean
    ) {
        val report: InvalidationReport = interp.computeInvalidation(
            branchId, before, after, trigger.eventId,
            changedMaterials.distinct(), changedSops.distinct(), parameterScopeChanged
        )
        store.saveInvalidation(
            json.encodeToString(report),
            StableIds.of("inv", trigger.eventId), branchId, trigger.eventId, trigger.seq
        )
    }

    private fun checkHead(branchId: String, expected: String?) {
        if (expected == null) return
        val head = store.head(branchId)
        if (head == null || head.eventId != expected) {
            throw ConcurrentModification(
                "version conflict: your base was $expected, current head is ${head?.eventId ?: "none"}"
            )
        }
    }

    private fun computeSopRevisions(
        before: BranchState,
        parsed: List<org.gsb.dicomreview.geom.ExtractedMaterial>,
        batchId: String
    ): List<SopRevision> = parsed.map { m ->
        val sop = m.raw.sopInstanceUid ?: m.raw.instanceId
        val previous = before.activeInstanceIdBySop[sop]
        val revisionOrdinal = before.events.count { ev ->
            val p = runCatching { decodePayload(ev) }.getOrNull()
            (p as? EventPayload.MaterialImported)?.sopRevisions?.any { it.sopInstanceUid == sop } == true ||
                (p as? EventPayload.MaterialReinterpreted)?.revisedSopUids?.contains(sop) == true
        } + 1
        SopRevision(sop, m.raw.instanceId, m.raw.rawBytesHash, revisionOrdinal, true)
    }

    fun exportBundle(): ByteArray {
        val materials = store.allInstances().mapNotNull { raw ->
            store.rawBytes(raw.instanceId)?.let { bytes ->
                MaterialEntry(raw.instanceId, raw.fileName, java.util.Base64.getEncoder().encodeToString(bytes))
            }
        }
        val bundle = ExportBundle(
            branches = store.listBranches(),
            events = store.listBranches().flatMap { store.eventsOnBranch(it.branchId) },
            counterexamples = store.listCounterexamples(),
            materials = materials
        )
        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zip ->
            zip.putNextEntry(ZipEntry("bundle.json"))
            zip.write(json.encodeToString(bundle).toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
        return baos.toByteArray()
    }

    fun importBundle(bytes: ByteArray) = store.transaction {
        val zip = ZipInputStream(ByteArrayInputStream(bytes))
        val entry = zip.nextEntry ?: throw ImportRejected("archive missing entries")
        check(entry.name == "bundle.json") { "unexpected archive member ${entry.name}" }
        val bundle = json.decodeFromString<ExportBundle>(zip.readBytes().toString(Charsets.UTF_8))
        bundle.materials.forEach { mat ->
            if (store.loadInstance(mat.instanceId) == null) {
                val rawBytes = java.util.Base64.getDecoder().decode(mat.bytesBase64)
                val extracted = GeometryExtractor.extract("restored", 0, mat.fileName, rawBytes)
                val raw = extracted.raw.copy(instanceId = mat.instanceId)
                store.insertRaw(raw, rawBytes)
            }
        }
        bundle.branches.sortedBy { if (it.parentBranchId == null) 0 else 1 }.forEach { b ->
            val exists = store.listBranches().any { it.branchId == b.branchId }
            if (!exists) store.createBranch(b)
        }
        bundle.events.sortedWith(compareBy({ it.branchId }, { it.seq })).forEach { ev ->
            if (store.event(ev.eventId) == null) {
                store.appendEvent(ev)
            }
        }
        bundle.counterexamples.forEach { c ->
            store.saveCounterexample(c.entryId, c.branchId, c.variant, c.title, c.accepted,
                c.baselineEventId, c.variantEventId, c.reportJson)
        }
    }
}
