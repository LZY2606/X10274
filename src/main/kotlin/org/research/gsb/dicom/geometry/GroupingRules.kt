package org.research.gsb.dicom.geometry

/** Versioned geometry parameters. Changing any value produces a new rule version and the
 *  dependency layer invalidates conclusions derived under the old version. */
data class GroupingRules(
    val versionId: String,
    val orthogonalityTolerance: Double = 1e-3,
    val unitNormTolerance: Double = 1e-3,
    val positionEpsilonMm: Double = 0.05,
    val spacingRelativeTolerance: Double = 0.10,
    val spacingAbsoluteToleranceMm: Double = 0.25,
) {
    fun isSameEffective(other: GroupingRules): Boolean =
        orthogonalityTolerance == other.orthogonalityTolerance &&
            unitNormTolerance == other.unitNormTolerance &&
            positionEpsilonMm == other.positionEpsilonMm &&
            spacingRelativeTolerance == other.spacingRelativeTolerance &&
            spacingAbsoluteToleranceMm == other.spacingAbsoluteToleranceMm
}
