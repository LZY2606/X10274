package org.research.gsb.dicom.geometry

import kotlin.math.abs
import kotlin.math.sqrt

object Vec {
    fun dot(a: DoubleArray, b: DoubleArray): Double =
        a[0] * b[0] + a[1] * b[1] + a[2] * b[2]

    fun cross(a: DoubleArray, b: DoubleArray): DoubleArray = doubleArrayOf(
        a[1] * b[2] - a[2] * b[1],
        a[2] * b[0] - a[0] * b[2],
        a[0] * b[1] - a[1] * b[0],
    )

    fun norm(a: DoubleArray): Double = sqrt(dot(a, a))

    fun normalize(a: DoubleArray): DoubleArray? {
        val n = norm(a)
        if (n < 1e-12) return null
        return doubleArrayOf(a[0] / n, a[1] / n, a[2] / n)
    }

    fun nearlyEqual(a: Double, b: Double, eps: Double): Boolean = abs(a - b) <= eps
}
