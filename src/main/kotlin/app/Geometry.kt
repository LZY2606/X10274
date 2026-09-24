package app

import kotlin.math.abs
import kotlin.math.sqrt

/** 三维向量, 坐标基准: DICOM Patient Coordinate System (LPS), 单位 mm。 */
data class Vec3(val x: Double, val y: Double, val z: Double) {
    operator fun minus(o: Vec3) = Vec3(x - o.x, y - o.y, z - o.z)
    operator fun plus(o: Vec3) = Vec3(x + o.x, y + o.y, z + o.z)
    infix fun dot(o: Vec3) = x * o.x + y * o.y + z * o.z
    infix fun cross(o: Vec3) = Vec3(
        y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x)
    val norm get() = sqrt(this dot this)
    fun normalized() = norm.let { Vec3(x / it, y / it, z / it) }
    override fun toString() = "(${f(x)}, ${f(y)}, ${f(z)})"
    companion object { fun f(v: Double) = if (v == v.toLong().toDouble()) v.toLong().toString() else "%.4f".format(v) }
}

/** DICOM Decimal String: 原始表示保留在导入层, 派生层才解析; 无法转换留在原位并附诊断。 */
object Ds {
    fun parseDouble(raw: String): Double? = raw.trim().toDoubleOrNull()
    fun parseVec3(raw: String?): Triple<Double, Double, Double>? {
        if (raw == null) return null
        val p = raw.split("\\")
        if (p.size != 3) return null
        val (a, b, c) = p.map { parseDouble(it) }
        return if (a != null && b != null && c != null) Triple(a, b, c) else null
    }
    fun vec(raw: String?): Vec3? = parseVec3(raw)?.let { Vec3(it.first, it.second, it.third) }
    fun parseSix(raw: String?): Pair<Vec3, Vec3>? {
        if (raw == null) return null
        val p = raw.split("\\")
        if (p.size != 6) return null
        val n = p.map { parseDouble(it) }
        return if (n.all { it != null })
            Pair(Vec3(n[0]!!, n[1]!!, n[2]!!), Vec3(n[3]!!, n[4]!!, n[5]!!))
        else null
    }
    fun median(xs: List<Double>): Double {
        val s = xs.sorted()
        return if (s.size % 2 == 1) s[s.size / 2]
        else (s[s.size / 2 - 1] + s[s.size / 2]) / 2.0
    }
}

/** 方向向量先检查正交与容差; 非正交时仍给出叉积法向但显式标记。 */
data class Orientation(
    val row: Vec3, val col: Vec3, val normal: Vec3,
    val rowColDot: Double, val rowNorm: Double, val colNorm: Double,
    val orthogonal: Boolean, val unitLength: Boolean) {
    companion object {
        fun from(row: Vec3, col: Vec3, orthTol: Double, unitTol: Double): Orientation {
            val n = (row cross col).normalized()
            val dot = row dot col
            val rn = row.norm; val cn = col.norm
            return Orientation(row, col, n, dot, rn, cn,
                orthogonal = abs(dot) <= orthTol,
                unitLength = abs(rn - 1.0) <= unitTol && abs(cn - 1.0) <= unitTol)
        }
    }
}
