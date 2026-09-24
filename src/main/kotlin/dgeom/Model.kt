package dgeom

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.MessageDigest

val JsonFmt = Json { ignoreUnknownKeys = true; encodeDefaults = true }
val JsonPretty = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }

fun sha256Hex(data: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }

fun sha256Hex(text: String): String = sha256Hex(text.toByteArray(Charsets.UTF_8))

/** 稳定身份：由内容寻址，导出/删除/重导入后保持不变。 */
fun shortId(prefix: String, vararg parts: String?): String =
    prefix + "_" + sha256Hex(parts.joinToString("|") { it ?: "∅" }).substring(0, 16)

/** 原始 tag 的逐字节表示（导入时保留原表示，派生层才做规范化）。 */
@Serializable
data class RawTag(
    val tag: String,                               // "(0020,0032)"
    val vr: String,
    val values: List<String> = emptyList(),        // 原始字符串分量
    val items: List<Map<String, RawTag>>? = null,  // SQ 嵌套（多帧 functional group）
    val binaryLength: Int? = null,                 // OB/OW/UN 等二进制（原字节在 BLOB 中保留）
)

@Serializable
data class Diagnostic(
    val code: String,       // 机器可读：unsupported_transfer_syntax / unconvertible_value / ...
    val message: String,
    val tag: String? = null,
)

/** 一份导入材料（去标识 DICOM 文件）。原始字节存 BLOB，身份=内容哈希。 */
@Serializable
data class Material(
    val id: String,                 // mat_<hash16>
    val hash: String,               // 原始字节完整 sha256
    val fileName: String,
    val transferSyntax: String?,
    val tags: Map<String, RawTag>,  // 原 tag 全量保留
    val diagnostics: List<Diagnostic> = emptyList(),
    val importedSeq: Int = 0,
)
