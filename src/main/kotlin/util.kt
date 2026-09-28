package bench

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.security.MessageDigest
import java.time.Instant

val json = Json {
    prettyPrint = true
    ignoreUnknownKeys = true
    encodeDefaults = true
}

val jsonCompact = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

fun nowIso(): String = Instant.now().toString()

fun sha256Hex(s: String): String =
    MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

fun sha256Bytes(b: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

fun JsonObject.str(key: String): String? = (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.content

/** 以稳定 JSON 序列化参与哈希。 */
fun canonicalJson(o: Any): String = jsonCompact.encodeToString(kotlinx.serialization.serializer(), o)
