package gsbreview.imports

import java.security.MessageDigest

object Ids {
    private const val SEP = " "

    fun sha256Hex(bytes: ByteArray): String {
        val d = MessageDigest.getInstance("SHA-256").digest(bytes)
        return d.joinToString("") { "%02x".format(it) }
    }

    fun contentId(prefix: String, vararg parts: Any): String {
        val material = parts.joinToString(SEP) { it.toString() }
        val h = sha256Hex(material.toByteArray(Charsets.UTF_8))
        return prefix + "_" + h.take(16)
    }

    fun sourceId(bytes: ByteArray, relPath: String): String =
        contentId("src", sha256Hex(bytes), relPath)

    fun subjectKey(sopInstanceUid: String?, sourceId: String): String =
        if (!sopInstanceUid.isNullOrBlank()) contentId("subj", "sop", sopInstanceUid)
        else contentId("subj", "src", sourceId)

    fun frameKey(subjectKey: String, frameNo: Int?): String =
        if (frameNo == null || frameNo <= 1) subjectKey + "#f1" else subjectKey + "#f" + frameNo

    fun interpretationId(ruleVersion: String, parameterFingerprint: String, batchId: String): String =
        contentId("interp", ruleVersion, parameterFingerprint, batchId)

    fun parameterSetId(fingerprint: String): String = contentId("params", fingerprint)

    fun factId(frameKey: String, fieldName: String, rawValue: String, basis: String,
               ruleVersion: String): String =
        contentId("fact", frameKey, fieldName, rawValue, basis, ruleVersion)

    fun diagnosticId(interpretationId: String, code: String, subjectKey: String?,
                     frameKey: String?, message: String): String =
        contentId("diag", interpretationId, code, subjectKey ?: "", frameKey ?: "", message)

    fun dependencyId(interpretationId: String, depType: String, depKey: String, scope: String): String =
        contentId("dep", interpretationId, depType, depKey, scope)

    fun candidateId(interpretationId: String, volumeKey: String, memberKeys: List<String>,
                    status: String): String =
        contentId("cand", interpretationId, volumeKey, memberKeys.joinToString(","), status)

    fun invalidationId(oldInterp: String, depType: String, depKey: String, scope: String): String =
        contentId("inv", oldInterp, depType, depKey, scope)

    fun eventId(branchId: String, type: String, payload: String, createdAt: String, seqHint: Int): String =
        contentId("evt", branchId, type, payload, createdAt, seqHint)

    fun batchId(caseId: String?, files: List<Pair<String, String>>, note: String, ts: String): String =
        contentId("batch", caseId ?: "", files.joinToString("|") { it.first + "@" + it.second.take(16) }, note, ts)

    fun branchId(name: String, parent: String?, forkAfter: Int?, ts: String): String =
        contentId("br", name, parent ?: "root", forkAfter?.toString() ?: "", ts)
}
