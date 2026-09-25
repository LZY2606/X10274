package app.domain

import app.dicom.DicomReader

/**
 * Deterministic, content-addressed identities. Same inputs always yield the same id,
 * so export, wipe and re-import preserve identity, order and provenance.
 */
object Ids {
    fun hash(vararg parts: String): String =
        DicomReader.sha256Hex(parts.joinToString("|").toByteArray(Charsets.UTF_8))

    fun eventId(type: String, branchId: String, prevEventId: String?, payloadJson: String): String =
        hash("event", type, branchId, prevEventId ?: "root", payloadJson)

    fun nodeId(frameKey: String, ruleVersion: String, paramVersion: Int): String =
        hash("node", frameKey, ruleVersion, paramVersion.toString())

    fun setId(kind: String, seriesUid: String, frameOfReferenceUid: String): String =
        hash("set", kind, seriesUid, frameOfReferenceUid)

    fun candidateId(setId: String, structureKey: String): String =
        hash("cand", setId, structureKey)

    fun derivationId(ruleVersion: String, paramVersion: Int, parentDerivationId: String?, roots: List<String>): String =
        hash("deriv", ruleVersion, paramVersion.toString(), parentDerivationId ?: "root", roots.sorted().joinToString(","))

    fun decisionId(branchId: String, targetSetId: String, action: String, candidateId: String?, baseEventId: String, note: String): String =
        hash("dec", branchId, targetSetId, action, candidateId ?: "-", baseEventId, note)

    fun bucketId(reasonCode: String, frameKeys: List<String>): String =
        hash("bucket", reasonCode, frameKeys.sorted().joinToString(","))

    fun batchId(fileShas: List<String>): String =
        hash("batch", fileShas.sorted().joinToString(","))
}
