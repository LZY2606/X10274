package gsb

import java.security.MessageDigest

/** Stable, content-derived identities. Ids survive export / delete / re-import. */
object Ids {
    fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    fun sha256(text: String): String = sha256(text.toByteArray(Charsets.UTF_8))

    fun material(raw: ByteArray): String = "mat-" + sha256(raw).take(16)

    fun instance(rawJson: String): String = "inst-" + sha256(rawJson).take(16)

    fun slice(instanceId: String, frameIndex: Int?): String =
        if (frameIndex == null) "slice:$instanceId" else "slice:$instanceId#f$frameIndex"

    fun derivation(name: String, parentId: String?, seq: Long): String =
        "drv-" + sha256("$name|${parentId ?: "-"}|$seq").take(12)

    fun candidate(
        derivationId: String,
        seriesUid: String,
        forUid: String,
        branch: String,
        rank: Int,
        memberSliceIds: List<String>,
    ): String = "cand-" + sha256(
        listOf(derivationId, seriesUid, forUid, branch, rank.toString(), memberSliceIds.joinToString(","))
            .joinToString("|")
    ).take(16)

    fun diagnostic(derivationId: String, code: String, instanceId: String?, tag: String?): String =
        "dia-" + sha256("$derivationId|$code|${instanceId ?: "-"}|${tag ?: "-"}").take(16)

    fun adjudication(derivationId: String, candidateId: String, seq: Long): String =
        "adj-" + sha256("$derivationId|$candidateId|$seq").take(16)

    fun eventUid(seq: Long): String = "evt-%06d".format(seq)

    /** Identity attached to every displayed value in the review UI. */
    fun valueIdentity(ownerId: String, tag: String): String = "val:$ownerId:$tag"
}
