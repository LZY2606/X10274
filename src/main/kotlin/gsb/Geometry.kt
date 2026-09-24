package gsb

import kotlin.math.abs
import kotlin.math.sqrt

/** 3D vector in millimetres, DICOM patient (LPS) basis. */
data class Vec3(val x: Double, val y: Double, val z: Double) {
    operator fun plus(o: Vec3) = Vec3(x + o.x, y + o.y, z + o.z)
    operator fun minus(o: Vec3) = Vec3(x - o.x, y - o.y, z - o.z)
    operator fun times(s: Double) = Vec3(x * s, y * s, z * s)
    fun dot(o: Vec3) = x * o.x + y * o.y + z * o.z
    fun cross(o: Vec3) = Vec3(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x)
    fun norm() = sqrt(x * x + y * y + z * z)
    fun normalized(): Vec3 {
        val n = norm()
        return if (n == 0.0) Vec3(0.0, 0.0, 0.0) else Vec3(x / n, y / n, z / n)
    }

    companion object {
        val ZERO = Vec3(0.0, 0.0, 0.0)
    }
}

/**
 * Geometry of one slice (a single-frame instance, or one frame of a multi-frame
 * object expanded from its functional groups). All quantities carry mm and the
 * LPS coordinate basis; normalization happens only in this derived layer.
 */
data class SliceGeom(
    val sliceId: String,
    val instanceId: String,
    val frameIndex: Int?,
    val position: Vec3,          // ImagePositionPatient, mm, LPS
    val row: Vec3,               // ImageOrientationPatient row cosine (unit)
    val col: Vec3,               // ImageOrientationPatient col cosine (unit)
    val spacingRow: Double,      // PixelSpacing[0], mm
    val spacingCol: Double,      // PixelSpacing[1], mm
    val rows: Int,
    val cols: Int,
    val instanceNumber: Int?,    // evidence only; never a spatial sort key
) {
    val normal: Vec3 get() = row.cross(col).normalized()

    /** Spatial projection coordinate along the slice normal, in mm. */
    val projection: Double get() = position.dot(normal)

    /** |row . col| of the normalized direction cosines; 0 means perfectly orthogonal. */
    val orthogonalityError: Double get() = abs(row.normalized().dot(col.normalized()))

    /** In-plane corners (mm, LPS): top-left, top-right, bottom-right, bottom-left. */
    fun corners(): List<Vec3> {
        val w = spacingCol * cols
        val h = spacingRow * rows
        return listOf(
            position,
            position + row * w,
            position + row * w + col * h,
            position + col * h,
        )
    }
}

data class SpacingStats(
    val medianMm: Double,
    val minMm: Double,
    val maxMm: Double,
    /** residual[i] = gap(i -> i+1) - median, mm; length = slices - 1 */
    val residualsMm: List<Double>,
)

fun spacingStats(sortedProjections: List<Double>): SpacingStats {
    if (sortedProjections.size < 2) return SpacingStats(0.0, 0.0, 0.0, emptyList())
    val gaps = sortedProjections.zipWithNext { a, b -> b - a }
    val sorted = gaps.sorted()
    val median = if (sorted.size % 2 == 1) sorted[sorted.size / 2]
    else (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2.0
    return SpacingStats(
        medianMm = median,
        minMm = gaps.min(),
        maxMm = gaps.max(),
        residualsMm = gaps.map { it - median },
    )
}
