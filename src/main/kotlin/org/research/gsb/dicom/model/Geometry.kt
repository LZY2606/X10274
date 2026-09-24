package org.research.gsb.dicom.model

import org.research.gsb.dicom.codec.Tag

/**
 * A 3-vector as interpreted from a DS attribute. Components keep the original textual
 * representation in [rawComponents]; [normalized] holds the parsed values plus the
 * coordinate reference (DICOM patient coordinate system, millimetres).
 */
data class Vector3(
    val rawComponents: List<String>,
    val normalized: DoubleArray?,
    val reference: CoordinateReference,
    val diagnostics: List<String> = emptyList(),
) {
    companion object {
        fun patientMm(
            raw: List<String>,
            kind: VectorKind,
            tolerance: Double,
        ): Vector3 {
            val diags = mutableListOf<String>()
            if (raw.size != 3) diags += "Expected 3 components for $kind, found ${raw.size}"
            val parsed = raw.map { it.trim().toDoubleOrNull() }
            if (parsed.any { it == null }) diags += "Non-numeric component in $kind: ${raw.joinToString("\\\\")}"
            val values = if (parsed.all { it != null }) parsed.requireNoNulls().toDoubleArray() else null
            return Vector3(raw, values, CoordinateReference.PATIENT_MM, diags)
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Vector3) return false
        return rawComponents == other.rawComponents &&
            normalized.contentEquals(other.normalized) &&
            reference == other.reference && diagnostics == other.diagnostics
    }

    override fun hashCode(): Int {
        var result = rawComponents.hashCode()
        result = 31 * result + (normalized?.contentHashCode() ?: 0)
        result = 31 * result + reference.hashCode()
        result = 31 * result + diagnostics.hashCode()
        return result
    }
}

enum class VectorKind { POSITION, ORIENTATION_ROW, ORIENTATION_COLUMN, SPACING }

data class CoordinateReference(val frame: String, val unit: String, val system: String) {
    companion object {
        val PATIENT_MM = CoordinateReference(frame = "FrameOfReferenceUID", unit = "mm", system = "DICOM patient (LPH+)")
        val UNITLESS = CoordinateReference(frame = "-", unit = "", system = "none")
    }
}

/** Row/column cosine pair plus all geometric checks relative to active tolerances. */
data class Orientation(
    val rowCosines: Vector3,
    val colCosines: Vector3,
    val rowNorm: Double?,
    val colNorm: Double?,
    val dotProduct: Double?,
    val normal: DoubleArray?,
    val checks: List<GeometryCheck>,
) {
    val isOrthonormal: Boolean get() = checks.all { it.severity != Severity.ERROR } &&
        checks.any { it.code == "ORTHO_OK" }
}

data class GeometryCheck(
    val code: String,
    val severity: Severity,
    val message: String,
    val value: Double? = null,
    val limit: Double? = null,
)

enum class Severity { INFO, WARNING, ERROR }

/**
 * One locatable slice, either a classic single-frame instance or one frame of a
 * multi-frame object. [instanceKey] is stable across re-imports, [frameIndex] is
 * 0-based (-1 for classic instances).
 */
data class SliceGeometry(
    val instanceKey: String,
    val sopInstanceUid: String,
    val seriesUid: String,
    val frameOfReferenceUid: String?,
    val frameIndex: Int,
    val instanceNumberRaw: String?,
    val position: Vector3?,
    val orientation: Orientation?,
    val rowSpacingMm: String?,
    val colSpacingMm: String?,
    val sliceThicknessRaw: String?,
    val spacingBetweenSlicesRaw: String?,
    val pixelRows: Int?,
    val pixelColumns: Int?,
    val evidence: List<TagEvidence>,
    val diagnostics: List<String>,
) {
    val locatable: Boolean get() = position?.normalized != null && orientation?.normal != null
}

data class TagEvidence(
    val tag: String,
    val name: String,
    val vr: String,
    val rawValue: String,
    val source: String, // "INSTANCE" | "SHARED_FG" | "PERFRAME_FG[n]"
    val frameIndex: Int,
)

/** Residual between expected and observed slice spacing along the normal. */
data class SpacingResidual(
    val fromSlice: String,
    val toSlice: String,
    val gapMm: Double,
    val expectedMm: Double?,
    val residualMm: Double?,
    val kind: ResidualKind,
)

enum class ResidualKind { REGULAR, JUMP, DUPLICATE, REVERSED, FIRST, UNLOCATABLE }

data class SlicePlane(
    val sliceKey: String,
    val positionMm: DoubleArray?,
    val normalMm: DoubleArray?,
    val projectionAlongNormal: Double?,
    val instanceNumber: String?,
) {
    override fun equals(other: Any?): Boolean = other is SlicePlane && other.sliceKey == sliceKey
    override fun hashCode(): Int = sliceKey.hashCode()
}
