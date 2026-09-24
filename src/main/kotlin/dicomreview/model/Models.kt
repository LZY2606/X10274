package dicomreview.model

import kotlinx.serialization.Serializable

/**
 * A single raw DICOM data element. The value is preserved as its original text
 * representation (numbers/strings verbatim; bulk values are summarised with a
 * byte-length note). Nothing is normalised here — normalisation only happens in
 * the derived layer.
 */
@Serializable
data class RawTag(
    val group: Int,
    val element: Int,
    val keyword: String,
    val vr: String,
    /** Original textual representation, e.g. "1.25\1.25" or "-1.0\0.0\0.0". */
    val rawValue: String,
    /** True when the value is bulk pixel/overlay data not embedded as text. */
    val bulk: Boolean = false,
    val bulkBytes: Long? = null,
    val path: String = ""
) {
    val tagHex: String get() = "(%04X,%04X)".format(group, element)
    fun matches(g: Int, e: Int) = group == g && element == e
}

@Serializable
data class ParsedFile(
    val relativePath: String,
    val mediaStorageSOPClassUID: String?,
    val transferSyntaxUID: String?,
    val tags: List<RawTag>,
    val pixelBulkBytes: Long,
    val parseWarnings: List<String> = emptyList()
) {
    fun find(g: Int, e: Int, path: String = ""): RawTag? =
        tags.firstOrNull { it.matches(g, e) && (path.isEmpty() || it.path == path) }
    fun findAll(g: Int, e: Int): List<RawTag> = tags.filter { it.matches(g, e) }
    fun keyword(kw: String): RawTag? = tags.firstOrNull { it.keyword == kw }
}

/** A normalised-but-still-sourced scalar with unit/datum metadata. */
@Serializable
data class Quantity(
    val value: Double,
    val unit: String,
    val sourceTag: String,
    val raw: String
)

@Serializable
data class Vec3(val x: Double, val y: Double, val z: Double) {
    operator fun minus(o: Vec3) = Vec3(x - o.x, y - o.y, z - o.z)
    operator fun plus(o: Vec3) = Vec3(x + o.x, y + o.y, z + o.z)
    operator fun times(s: Double) = Vec3(x * s, y * s, z * s)
    fun dot(o: Vec3) = x * o.x + y * o.y + z * o.z
    fun norm() = kotlin.math.sqrt(dot(this))
    fun normalized(): Vec3 = norm().let { if (it == 0.0) this else this * (1.0 / it) }
    override fun toString() = "%.6g,%.6g,%.6g".format(x, y, z)
}

/**
 * Frame-level extracted geometry. `missing` lists which mandatory attributes
 * could not be read; unconvertible values stay in `diagnostics` rather than
 * being dropped.
 */
@Serializable
data class FrameGeometry(
    val frameKey: String,            // stable per-file frame: "<fileStableId>#f<n>"
    val instanceNumberRaw: String?,  // preserved, NEVER used as spatial truth
    val imagePosition: Vec3?,        // mm in FrameOfReference coordinates
    val imageOrientation: List<Double>?, // 6 raw doubles (two row/col vectors)
    val rowSpacing: Quantity?,       // mm
    val colSpacing: Quantity?,       // mm
    val sliceThickness: Quantity?,   // mm (optional)
    val frameOfReferenceUID: String?,
    val sopClassUID: String?,
    val sopInstanceUID: String?,
    val seriesUID: String?,
    val multiframe: Boolean,
    val functionalGroupPath: String?, // e.g. "5200,9229/0/5200,9230/0"
    val missing: List<String>,
    val diagnostics: List<String>
)

/** Orthogonality / unit-vector check result for the direction cosines. */
@Serializable
data class OrientationCheck(
    val rowDotCol: Double,
    val rowNorm: Double,
    val colNorm: Double,
    val tolerance: Double,
    val orthogonal: Boolean,
    val unitLength: Boolean,
    val valid: Boolean,
    val reasons: List<String>
) {
    val sliceNormal: Vec3? = null
}
