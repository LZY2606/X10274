package gsbreview.imports

import gsbreview.db.Store
import gsbreview.dicom.DicomParseException
import gsbreview.dicom.DicomReader
import gsbreview.dicom.FrameFunctionalGroup
import gsbreview.dicom.TransferSyntaxes
import java.sql.Connection
import java.time.Instant

data class IncomingFile(val relPath: String, val bytes: ByteArray, val mediaType: String = "application/dicom")

data class ImportResult(
    val batchId: String,
    val importedSources: List<String>,
    val newSubjects: List<String>,
    val updatedSubjects: List<String>,
    val uidConflicts: List<String>,
    val parseFailures: List<String>
)

class ImportService(private val store: Store) {

    fun importBatch(
        files: List<IncomingFile>,
        caseId: String?,
        note: String,
        stage: String = "initial",
        supersedes: Map<String, String> = emptyMap()
    ): ImportResult {
        val now = Instant.now().toString()
        val conn = store.connection
        conn.autoCommit = false
        try {
            val hashes = files.map { it.relPath to Ids.sha256Hex(it.bytes) }
            val batchId = Ids.batchId(caseId, hashes, note, now)
            store.insertImportBatch(batchId, caseId, now, note, stage)

            val imported = mutableListOf<String>()
            val newSubjects = mutableListOf<String>()
            val updatedSubjects = mutableListOf<String>()
            val uidConflicts = mutableListOf<String>()
            val failures = mutableListOf<String>()

            for (file in files) {
                val parsed = try {
                    DicomReader.parse(file.bytes)
                } catch (t: DicomParseException) {
                    failures += file.relPath + ": " + (t.message ?: "parse error")
                    continue
                }
                val sourceId = Ids.sourceId(file.bytes, file.relPath)
                if (!store.sourceExists(sourceId)) {
                    store.insertSource(
                        sourceId = sourceId,
                        batchId = batchId,
                        caseId = caseId,
                        relPath = file.relPath,
                        mediaType = file.mediaType,
                        sha = Ids.sha256Hex(file.bytes),
                        bytes = file.bytes,
                        size = file.bytes.size,
                        supersedes = supersedes[file.relPath],
                        importedAt = now,
                        stage = stage
                    )
                    store.replaceRawTags(sourceId, parsed.tags)
                }
                imported += sourceId

                val subjectKey = Ids.subjectKey(parsed.sopInstanceUid, sourceId)
                val existing = store.findSubjectBySopUid(parsed.sopInstanceUid)
                if (existing != null && existing != subjectKey) {
                    uidConflicts += "SOPInstanceUID ${parsed.sopInstanceUid} already present as $existing"
                }
                val tsInfo = TransferSyntaxes.lookup(parsed.transferSyntaxUid)
                val hasGeometry = parsed.frames.any {
                    it.imagePositionPatient != null && it.imageOrientationPatient != null
                }
                if (store.subjectExists(subjectKey)) {
                    store.updateSubjectCurrentSource(
                        subjectKey, sourceId, batchId, parsed.isMultiframe,
                        parsed.numberOfFrames, parsed.sopClassUid, parsed.transferSyntaxUid,
                        tsInfo?.supported ?: false, hasGeometry
                    )
                    updatedSubjects += subjectKey
                } else {
                    store.insertSubject(
                        subjectKey, sourceId, sourceId, batchId,
                        parsed.isMultiframe, parsed.numberOfFrames, parsed.sopClassUid,
                        parsed.transferSyntaxUid, tsInfo?.supported ?: false, hasGeometry
                    )
                    newSubjects += subjectKey
                }
                store.replaceFrames(subjectKey, parsed.frames)
            }

            if (failures.isNotEmpty()) {
                conn.rollback()
                throw BatchImportException(
                    "batch rolled back: ${failures.size} file(s) failed to parse; no partial records remain",
                    failures
                )
            }
            conn.commit()
            return ImportResult(batchId, imported, newSubjects, updatedSubjects, uidConflicts, failures)
        } catch (t: Throwable) {
            conn.rollback()
            throw t
        } finally {
            conn.autoCommit = true
        }
    }
}

class BatchImportException(message: String, val failures: List<String>) : RuntimeException(message)
