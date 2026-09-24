package org.research.gsb.dicom.model

/** Why a set of slices is or is not a valid volume candidate. */
enum class CandidateStatus {
    VOLUME,              // regular spacing, usable as a geometric volume
    AMBIGUOUS,           // multiple legal groupings possible
    DUPLICATE_POSITION,  // repeated slice location(s)
    SPACING_JUMP,        // local spacing discontinuity; branch kept open
    REVERSED_ORDER,      // sortable but descending positions
    NON_ORTHOGONAL,      // orientation cosines fail orthonormality tolerance
    MISSING_GEOMETRY,    // instances with no usable geometry
    MIXED_FRAME_REF,     // more than one FrameOfReference UID
    UID_CONFLICT,        // same UID with conflicting bytes/geometry
    UNSUPPORTED_PIXELS,  // transfer syntax pixels cannot be decoded
}

data class SeriesCandidate(
    val candidateId: String,
    val seriesUid: String,
    val frameOfReferenceUid: String?,
    val status: CandidateStatus,
    val sliceKeys: List<String>,          // projection-sorted (not InstanceNumber)
    val alternativeOrders: List<List<String>>,
    val nominalSpacingMm: Double?,
    val residuals: List<SpacingResidual>,
    val planes: List<SlicePlane>,
    val instanceNumberOrder: List<String>,
    val checks: List<GeometryCheck>,
    val diagnostics: List<String>,
    val accepted: Boolean = false,
    val rejected: Boolean = false,
)

/** A branch point where geometry could not be reduced to a single answer automatically. */
data class Ambiguity(
    val ambiguityId: String,
    val scope: String,
    val reasonCode: String,
    val description: String,
    val candidateIds: List<String>,
    val affectedSliceKeys: List<String>,
)
