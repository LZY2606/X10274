package org.gsb.dicomreview.geom

import java.security.MessageDigest

object StableIds {
    private fun sha(parts: List<String>): String {
        val md = MessageDigest.getInstance("SHA-256")
        parts.forEachIndexed { idx, part ->
            md.update(idx.toString().toByteArray(Charsets.UTF_8))
            md.update(byteArrayOf(0x1F))
            md.update(part.toByteArray(Charsets.UTF_8))
            md.update(byteArrayAt(part))
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun byteArrayAt(part: String): ByteArray = byteArrayOf(part.length.and(0xFF).toByte())

    fun of(prefix: String, vararg parts: String): String =
        prefix + "_" + sha(parts.toList()).take(24)

    fun sha256Bytes(bytes: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
