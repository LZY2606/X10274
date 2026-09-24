package gsbreview.derive

import gsbreview.db.SourceMeta
import gsbreview.db.Store
import gsbreview.db.SubjectRow
import gsbreview.domain.CandidateMember
import gsbreview.domain.Diagnostic
import gsbreview.domain.GeomFact
import gsbreview.domain.InterpretationView
import gsbreview.domain.InvalidationView
import gsbreview.domain.SlicePlane
import gsbreview.domain.VolumeCandidate
import gsbreview.dicom.DicomReader
import gsbreview.dicom.FrameFunctionalGroup
import gsbreview.imports.Ids
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class DeterministicIdFactory(private val interpretationId: String, private val params: GeometryParams) : IdFactory {
    override fun fact(frameKey: String, subjectKey: String, fieldName: String, rawValue: String,
                      unit: String?, basis: String, numericJson: String?, valid: Boolean): GeomFact {
        val id = Ids.factId(frameKey, fieldName, rawValue, basis, params.ruleVersion)
        return GeomFact(id, frameKey, subjectKey, fieldName, rawValue, unit, basis,
            numericJson?.let { Json.parseToJsonElement(it) as? JsonObject ?: JsonPrimitive(numericJson) }, valid)
    }

    override fun diagnostic(code: String, severity: String, message: String,
                            subjectKey: String?, frameKey: String?): Diagnostic {
        val id = Ids.diagnosticId(interpretationId, code, subjectKey, frameKey, message)
        return Diagnostic(id, code, severity, message, subjectKey, frameKey, null, null)
    }

    override fun candidate(volumeKey: String, frameOfReferenceUid: String?, orientationHash: String?,
                           status: String, reason: String, ordered: Boolean, members: List<CandidateMember>,
                           planes: List<SlicePlane>, spacingResidualsMm: List<Double>): VolumeCandidate {
        val id = Ids.candidateId(interpretationId, volumeKey, members.map { it.frameKey }, status)
        return VolumeCandidate(id, volumeKey, frameOfReferenceUid, orientationHash, status, reason,
            ordered, members.size, members, planes, spacingResidualsMm)
    }
}

class DeriveService(private val store: Store) {
    private val json = Json { ignoreUnknownKeys = true }

    fun runInterpretation(
        params: GeometryParams,
        batchId: String,
        caseId: String?,
        trigger: String
    ): InterpretationView {
        val parameterFingerprint = params.fingerprint()
        val parameterSetId = Ids.parameterSetId(parameterFingerprint)
        val interpretationId = Ids.interpretationId(params.ruleVersion, parameterFingerprint, batchId)
        if (store.interpretationExists(interpretationId)) return loadInterpretation(interpretationId)

        val now = java.time.Instant.now().toString()
        store.ensureParameterSet(parameterSetId, parameterFingerprint, now)

        val subjects = store.allSubjects()
        val inputs = subjects.flatMap { frameInputs(it) }
        val factory = DeterministicIdFactory(interpretationId, params)
        val result = GeometryEngine.analyze(inputs, params, factory)

        val prev = store.latestInterpretation()
        val invalidations = if (prev != null) computeInvalidations(prev, interpretationId, params, inputs, store) else emptyList()

        store.persistInterpretation(
            interpretationId = interpretationId,
            ruleVersion = params.ruleVersion,
            parameterSetId = parameterSetId,
            batchId = batchId,
            caseId = caseId,
            createdAt = now,
            trigger = trigger,
            facts = result.facts,
            candidates = result.candidates,
            diagnostics = result.diagnostics,
            dependencies = buildDependencies(interpretationId, inputs, params),
            invalidations = invalidations
        )
        return loadInterpretation(interpretationId)
    }

    private fun frameInputs(subject: SubjectRow): List<FrameInput> {
        val bytes = store.sourceBytes(subject.currentSourceId)
        val parsed = DicomReader.parse(bytes)
        val tagsByPath = parsed.tags.associateBy { it.path }
        val tagLookup = { leaf: String ->
            parsed.tags.firstOrNull { it.path == leaf }?.rawRepr
                ?: parsed.tags.filter { it.path.endsWith(".$leaf") }
                    .minByOrNull { it.path.count { c -> c == '.' } }?.rawRepr
        }
        return parsed.frames.map { fg: FrameFunctionalGroup ->
            val frameKey = Ids.frameKey(subject.subjectKey, fg.frameNo)
            if (!parsed.isMultiframe) {
                FrameInput(
                    frameKey = frameKey,
                    subjectKey = subject.subjectKey,
                    instanceNumber = parsed.instanceNumber,
                    frameOfReferenceUid = parsed.frameOfReferenceUid,
                    group = fg,
                    rawPresent = GeometryRawPresence(
                        ippPresent = tagLookup("(0020,0032)") != null,
                        iopPresent = tagLookup("(0020,0037)") != null,
                        pixelSpacingPresent = tagLookup("(0028,0030)") != null
                    )
                )
            } else {
                val ippLeaf = "(0020,0032)"; val iopLeaf = "(0020,0037)"; val psLeaf = "(0028,0030)"
                val ippPresent = parsed.tags.any {
                    it.path.startsWith("(5200,9230)[${fg.frameNo - 1}].") && it.path.endsWith(ippLeaf)
                }
                val iopPresent = parsed.tags.any {
                    it.path.startsWith("(5200,9230)[${fg.frameNo - 1}].") && it.path.endsWith(iopLeaf)
                }
                val psPresent = parsed.tags.any {
                    it.path.startsWith("(5200,9230)[${fg.frameNo - 1}].") && it.path.endsWith(psLeaf)
                }
                FrameInput(
                    frameKey = frameKey,
                    subjectKey = subject.subjectKey,
                    instanceNumber = parsed.instanceNumber,
                    frameOfReferenceUid = parsed.frameOfReferenceUid,
                    group = fg,
                    rawPresent = GeometryRawPresence(ippPresent, iopPresent, psPresent)
                )
            }
        }
    }

    private fun buildDependencies(
        interpretationId: String,
        inputs: List<FrameInput>,
        params: GeometryParams
    ): List<gsbreview.db.DependencyRow> {
        val deps = mutableListOf<gsbreview.db.DependencyRow>()
        inputs.forEach { input ->
            listOf(
                "ImagePositionPatient" to "raw_tag",
                "ImageOrientationPatient" to "raw_tag",
                "PixelSpacing" to "raw_tag",
                "FrameOfReferenceUID" to "raw_tag",
                "InstanceNumber" to "raw_tag"
            ).forEach { (field, type) ->
                deps += gsbreview.db.DependencyRow(
                    dependencyId = Ids.dependencyId(
                        interpretationId, type, input.frameKey + ":" + field,
                        """{"frame":"${input.frameKey}","field":"$field"}"""
                    ),
                    interpretationId = interpretationId,
                    depType = type,
                    depKey = input.frameKey + ":" + field,
                    scopeJson = """{"frame":"${input.frameKey}","field":"$field"}"""
                )
            }
            deps += gsbreview.db.DependencyRow(
                dependencyId = Ids.dependencyId(
                    interpretationId, "source_bytes", input.frameKey,
                    """{"frame":"${input.frameKey}"}"""
                ),
                interpretationId = interpretationId,
                depType = "source_bytes",
                depKey = input.frameKey,
                scopeJson = """{"frame":"${input.frameKey}"}"""
            )
            deps += gsbreview.db.DependencyRow(
                dependencyId = Ids.dependencyId(
                    interpretationId, "frame_of_reference", input.frameOfReferenceUid ?: "null",
                    """{"frame":"${input.frameKey}","for":"${input.frameOfReferenceUid ?: ""}"}"""
                ),
                interpretationId = interpretationId,
                depType = "frame_of_reference",
                depKey = input.frameOfReferenceUid ?: "null",
                scopeJson = """{"frame":"${input.frameKey}","for":"${input.frameOfReferenceUid ?: ""}"}"""
            )
        }
        deps += gsbreview.db.DependencyRow(
            dependencyId = Ids.dependencyId(interpretationId, "rule_params", params.fingerprint(),
                """{"params":${JsonPrimitive(params.fingerprint())}}"""),
            interpretationId = interpretationId,
            depType = "rule_params",
            depKey = params.fingerprint(),
            scopeJson = """{"rule_version":"${params.ruleVersion}","fingerprint":${JsonPrimitive(params.fingerprint())}}"""
        )
        return deps
    }

    private fun computeInvalidations(
        oldInterpretationId: String,
        newInterpretationId: String,
        newParams: GeometryParams,
        currentInputs: List<FrameInput>,
        store: Store
    ): List<gsbreview.db.InvalidationRow> {
        val rows = mutableListOf<gsbreview.db.InvalidationRow>()
        val now = java.time.Instant.now().toString()
        val oldParams = store.parameterFingerprintForInterpretation(oldInterpretationId)
        if (oldParams != newParams.fingerprint()) {
            val scope = JsonObject(mapOf(
                "old_fingerprint" to JsonPrimitive(oldParams),
                "new_fingerprint" to JsonPrimitive(newParams.fingerprint()),
                "impacted" to JsonPrimitive("all-derived-facts-candidates")
            ))
            rows += gsbreview.db.InvalidationRow(
                invalidationId = Ids.invalidationId(oldInterpretationId, "rule_params",
                    newParams.fingerprint(), scope.toString()),
                oldInterpretationId = oldInterpretationId,
                newInterpretationId = newInterpretationId,
                reason = "parameter/rule revision; dependency graph marks the derived layer stale",
                depType = "rule_params",
                depKey = newParams.fingerprint(),
                scopeJson = scope.toString(),
                createdAt = now
            )
        }
        // Source-level invalidations are keyed per frame/field so a single changed file
        // only invalidates the local dependency subtree.
        val changed = store.changedSourcesSinceInterpretation(oldInterpretationId)
        for (sourceId in changed) {
            val meta: SourceMeta? = store.sourceMeta(sourceId)
            rows += gsbreview.db.InvalidationRow(
                invalidationId = Ids.invalidationId(oldInterpretationId, "source_bytes",
                    sourceId, """{"source":"$sourceId","rel_path":"${meta?.relPath ?: ""}"}"""),
                oldInterpretationId = oldInterpretationId,
                newInterpretationId = newInterpretationId,
                reason = "source file changed: only facts/candidates depending on ${meta?.relPath ?: sourceId} are invalidated",
                depType = "source_bytes",
                depKey = sourceId,
                scopeJson = """{"source":"$sourceId","rel_path":"${meta?.relPath ?: ""}","stage":"${meta?.stage ?: ""}"}""",
                createdAt = now
            )
        }
        return rows
    }

    fun loadInterpretation(interpretationId: String): InterpretationView =
        store.loadInterpretation(interpretationId)
}
