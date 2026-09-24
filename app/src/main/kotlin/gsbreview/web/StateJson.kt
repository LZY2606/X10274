package gsbreview.web

import gsbreview.AppService
import gsbreview.events.ReviewState
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

fun stateJson(app: AppService, branchId: String, asOf: Long?): JsonObject {
    val state = app.state(branchId, asOf)
    val interp = state.interpretation
    val candidates = app.projection.decorateCandidates(state)
    return JsonObject(mapOf(
        "branchId" to JsonPrimitive(state.branchId),
        "headSeq" to JsonPrimitive(state.asOfSeq),
        "mainBranchId" to JsonPrimitive(app.eventStore.mainBranchId),
        "interpretation" to if (interp == null) JsonNull else JsonObject(mapOf(
            "interpretationId" to JsonPrimitive(interp.interpretationId),
            "ruleVersion" to JsonPrimitive(interp.ruleVersion),
            "parameterSetId" to JsonPrimitive(interp.parameterSetId),
            "batchId" to JsonPrimitive(interp.batchId),
            "caseId" to (interp.caseId?.let(::JsonPrimitive) ?: JsonNull),
            "trigger" to JsonPrimitive(interp.trigger),
            "status" to JsonPrimitive(interp.status),
            "diagnostics" to JsonArray(interp.diagnostics.map { d ->
                JsonObject(mapOf(
                    "diagnosticId" to JsonPrimitive(d.diagnosticId),
                    "code" to JsonPrimitive(d.code),
                    "severity" to JsonPrimitive(d.severity),
                    "message" to JsonPrimitive(d.message),
                    "subjectKey" to (d.subjectKey?.let(::JsonPrimitive) ?: JsonNull),
                    "frameKey" to (d.frameKey?.let(::JsonPrimitive) ?: JsonNull)
                ))
            }),
            "invalidations" to JsonArray(interp.invalidations.map { iv ->
                JsonObject(mapOf(
                    "oldInterpretationId" to JsonPrimitive(iv.oldInterpretationId),
                    "newInterpretationId" to (iv.newInterpretationId?.let(::JsonPrimitive) ?: JsonNull),
                    "reason" to JsonPrimitive(iv.reason),
                    "depType" to JsonPrimitive(iv.depType),
                    "depKey" to JsonPrimitive(iv.depKey),
                    "scope" to iv.scope
                ))
            })
        )),
        "facts" to JsonArray((interp?.facts ?: emptyList()).map { f ->
            JsonObject(mapOf(
                "factId" to JsonPrimitive(f.factId),
                "frameKey" to JsonPrimitive(f.frameKey),
                "subjectKey" to JsonPrimitive(f.subjectKey),
                "fieldName" to JsonPrimitive(f.fieldName),
                "rawValue" to JsonPrimitive(f.rawValue),
                "unit" to (f.unit?.let(::JsonPrimitive) ?: JsonNull),
                "basis" to JsonPrimitive(f.basis),
                "numeric" to (f.numeric ?: JsonNull),
                "valid" to JsonPrimitive(f.valid)
            ))
        }),
        "candidates" to JsonArray(candidates.map { c ->
            JsonObject(mapOf(
                "candidateId" to JsonPrimitive(c.candidateId),
                "volumeKey" to JsonPrimitive(c.volumeKey),
                "frameOfReferenceUID" to (c.frameOfReferenceUID?.let(::JsonPrimitive) ?: JsonNull),
                "orientationHash" to (c.orientationHash?.let(::JsonPrimitive) ?: JsonNull),
                "status" to JsonPrimitive(c.status),
                "reason" to JsonPrimitive(c.reason),
                "ordered" to JsonPrimitive(c.ordered),
                "memberCount" to JsonPrimitive(c.memberCount),
                "accepted" to JsonPrimitive(c.candidateId in state.acceptedCandidateIds),
                "rejected" to JsonPrimitive(c.candidateId in state.rejectedCandidateIds),
                "members" to JsonArray(c.members.map { m ->
                    JsonObject(mapOf(
                        "ord" to JsonPrimitive(m.ord),
                        "frameKey" to JsonPrimitive(m.frameKey),
                        "subjectKey" to JsonPrimitive(m.subjectKey),
                        "instanceNumber" to (m.instanceNumber?.let(::JsonPrimitive) ?: JsonNull),
                        "position" to JsonArray((m.position ?: emptyList()).map { JsonPrimitive(it) }),
                        "spatialCoordinateMm" to (m.spatialCoordinateMm?.let { JsonPrimitive(it) } ?: JsonNull),
                        "spacingResidualMm" to (m.spacingResidualMm?.let { JsonPrimitive(it) } ?: JsonNull),
                        "instanceNumberMatchesSpatialOrder" to
                            (m.instanceNumberMatchesSpatialOrder?.let { JsonPrimitive(it) } ?: JsonNull)
                    ))
                }),
                "planes" to JsonArray(c.planes.map { p ->
                    JsonObject(mapOf(
                        "frameKey" to JsonPrimitive(p.frameKey),
                        "origin" to JsonArray(p.origin.map { JsonPrimitive(it) }),
                        "row" to JsonArray(p.row.map { JsonPrimitive(it) }),
                        "col" to JsonArray(p.col.map { JsonPrimitive(it) }),
                        "normal" to JsonArray(p.normal.map { JsonPrimitive(it) })
                    ))
                }),
                "spacingResidualsMm" to JsonArray(c.spacingResidualsMm.map { JsonPrimitive(it) })
            ))
        }),
        "subjects" to JsonArray(app.subjects().map { s ->
            val ts = app.transferSyntaxInfo(s.transferSyntaxUid)
            JsonObject(mapOf(
                "subjectKey" to JsonPrimitive(s.subjectKey),
                "sourceId" to JsonPrimitive(s.currentSourceId),
                "multiframe" to JsonPrimitive(s.multiframe),
                "nFrames" to JsonPrimitive(s.nFrames),
                "sopClassUid" to (s.sopClassUid?.let(::JsonPrimitive) ?: JsonNull),
                "transferSyntaxUid" to (s.transferSyntaxUid?.let(::JsonPrimitive) ?: JsonNull),
                "transferSyntaxSupported" to JsonPrimitive(s.transferSyntaxSupported),
                "transferSyntaxName" to (ts?.name?.let(::JsonPrimitive) ?: JsonNull),
                "pixelDecodable" to JsonPrimitive(ts?.pixelDecodable ?: false),
                "hasGeometry" to JsonPrimitive(s.hasGeometry)
            ))
        }),
        "events" to JsonArray(state.visibleEvents.map { ev ->
            @Suppress("UNCHECKED_CAST")
            JsonObject(mapOf(
                "seq" to JsonPrimitive(ev["seq"] as Long),
                "eventId" to JsonPrimitive(ev["eventId"] as String),
                "type" to JsonPrimitive(ev["type"] as String),
                "actor" to JsonPrimitive(ev["actor"] as String),
                "createdAt" to JsonPrimitive(ev["createdAt"] as String),
                "basisEventSeq" to ((ev["basisEventSeq"] as Long?)?.let { JsonPrimitive(it) } ?: JsonNull),
                "payload" to (ev["payload"] as? kotlinx.serialization.json.JsonElement ?: JsonNull)
            ))
        }),
        "invalidationNotes" to JsonArray(state.invalidationNotes.map(::JsonPrimitive)),
        "parameterOverrides" to (state.parameterOverrides ?: JsonNull)
    ))
}
