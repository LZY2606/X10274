package org.research.gsb.dicom.geometry

import org.research.gsb.dicom.model.CoordinateReference
import org.research.gsb.dicom.model.GeometryCheck
import org.research.gsb.dicom.model.Orientation
import org.research.gsb.dicom.model.Severity
import org.research.gsb.dicom.model.Vector3
import org.research.gsb.dicom.model.VectorKind
import kotlin.math.abs

/** Validates ImageOrientationPatient row/column cosines before any projection is used. */
object OrientationInterpreter {

    fun interpret(rawRow: List<String>?, rawCol: List<String>?, tolerance: Double): Orientation {
        val checks = mutableListOf<GeometryCheck>()
        val row = Vector3.patientMm(rawRow ?: emptyList(), VectorKind.ORIENTATION_ROW, tolerance)
        val col = Vector3.patientMm(rawCol ?: emptyList(), VectorKind.ORIENTATION_COLUMN, tolerance)
        row.diagnostics.forEach { checks += GeometryCheck("ROW_COMPONENT", Severity.ERROR, it) }
        col.diagnostics.forEach { checks += GeometryCheck("COL_COMPONENT", Severity.ERROR, it) }

        val rv = row.normalized
        val cv = col.normalized
        var rowNorm: Double? = null
        var colNorm: Double? = null
        var dot: Double? = null
        var normal: DoubleArray? = null

        if (rv != null && cv != null) {
            rowNorm = Vec.norm(rv)
            colNorm = Vec.norm(cv)
            if (abs(rowNorm - 1.0) > tolerance) {
                checks += GeometryCheck("ROW_NOT_UNIT", Severity.ERROR,
                    "Row cosine norm=$rowNorm deviates from 1 by ${abs(rowNorm - 1.0)} > tolerance $tolerance",
                    rowNorm, 1.0 + tolerance)
            }
            if (abs(colNorm - 1.0) > tolerance) {
                checks += GeometryCheck("COL_NOT_UNIT", Severity.ERROR,
                    "Column cosine norm=$colNorm deviates from 1 by ${abs(colNorm - 1.0)} > tolerance $tolerance",
                    colNorm, 1.0 + tolerance)
            }
            dot = Vec.dot(rv, cv)
            if (abs(dot) > tolerance) {
                checks += GeometryCheck("NON_ORTHOGONAL", Severity.ERROR,
                    "Row·column=$dot exceeds orthogonality tolerance $tolerance", dot, tolerance)
            }
            val crossNorm = Vec.norm(Vec.cross(rv, cv))
            if (crossNorm < 1e-9) {
                checks += GeometryCheck("ZERO_NORMAL", Severity.ERROR,
                    "Row and column are parallel; slice normal is undefined")
            } else {
                normal = Vec.normalize(Vec.cross(rv, cv))
            }
            if (checks.none { it.severity == Severity.ERROR }) {
                checks += GeometryCheck("ORTHO_OK", Severity.INFO,
                    "Orientation orthonormal within tolerance $tolerance", abs(dot), tolerance)
            }
        } else {
            checks += GeometryCheck("ORIENTATION_MISSING", Severity.ERROR,
                "ImageOrientationPatient missing or unparsable")
        }
        return Orientation(row, col, rowNorm, colNorm, dot, normal, checks)
    }

    fun position(raw: List<String>?): Vector3 =
        Vector3.patientMm(raw ?: emptyList(), VectorKind.POSITION, 0.0).let { v ->
            if (v.normalized == null) v.copy(reference = CoordinateReference.PATIENT_MM) else v
        }
}
