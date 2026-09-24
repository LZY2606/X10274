package gsbreview.derive

import gsbreview.domain.CandidateMember
import gsbreview.domain.Diagnostic
import gsbreview.domain.GeomFact
import gsbreview.domain.SlicePlane
import gsbreview.domain.VolumeCandidate

interface IdFactory {
    fun fact(
        frameKey: String, subjectKey: String, fieldName: String,
        rawValue: String, unit: String?, basis: String,
        numericJson: String?, valid: Boolean
    ): GeomFact
    fun diagnostic(
        code: String, severity: String, message: String,
        subjectKey: String?, frameKey: String?
    ): Diagnostic
    fun candidate(
        volumeKey: String, frameOfReferenceUid: String?, orientationHash: String?,
        status: String, reason: String, ordered: Boolean,
        members: List<CandidateMember>, planes: List<SlicePlane>,
        spacingResidualsMm: List<Double>
    ): VolumeCandidate
}
