package org.gsb.dicomreview.model

import kotlinx.serialization.Serializable
import org.gsb.dicomreview.dicom.DicomDataset
import org.gsb.dicomreview.dicom.DicomElement

@Serializable
data class Vec3(val x: Double, val y: Double, val z: Double) {
    operator fun minus(o: Vec3) = Vec3(x - o.x, y - o.y, z - o.z)
    operator fun plus(o: Vec3) = Vec3(x + o.x, y + o.y, z + o.z)
    operator fun times(s: Double) = Vec3(x * s, y * s, z * s)
    fun dot(o: Vec3) = x * o.x + y * o.y + z * o.z
    fun cross(o: Vec3) = Vec3(
        y * o.z - z * o.y,
        z * o.x - x * o.z,
        x * o.y - y * o.x
    )
    fun norm() = kotlin.math.sqrt(dot(this))
    fun normalized(): Vec3 = norm().let { if (it == 0.0) this else this * (1.0 / it) }
}

/**
 * A domain quantity. [originalText] is exactly what the file carried; [normalizedMm] exists only
 * when a conversion was possible. Unconvertible values stay in place with a diagnostic.
 */
@Serializable
data class Quantity(
    val originalText: String,
    val unit: String,
    val normalizedMm: Double? = null
) {
    companion object {
        fun mm(raw: String?) = if (raw == null) null else Quantity(raw, "mm", raw.trim().replace(" ", "").toDoubleOrNull())
        fun unitless(raw: String?) = if (raw == null) null else Quantity(raw, "1", raw.trim().replace(" ", "").toDoubleOrNull())
    }
}

@Serializable
data class OrientationFrame(
    val row: Vec3,
    val col: Vec3,
    val normal: Vec3,
    val orthogonal: Boolean,
    val rowNorm: Double,
    val colNorm: Double,
    val dot: Double
)

sealed interface GeometrySource {
    val evidence: List<EvidenceRef>

    @Serializable
    data class TopLevel(override val evidence: List<EvidenceRef>) : GeometrySource

    @Serializable
    data class FunctionalGroup(val frameIndex: Int, override val evidence: List<EvidenceRef>) : GeometrySource
}

@Serializable
data class EvidenceRef(
    val instanceId: String,
    val path: String,
    val tagLabel: String,
    val rawText: String,
    val frame: Int? = null
)

@Serializable
data class FrameGeometry(
    val frameNumber: Int,
    val instanceId: String,
    val sopInstanceUid: String?,
    val seriesInstanceUid: String?,
    val frameOfReferenceUid: String?,
    val instanceNumber: Int?,
    val position: Vec3?,
    val positionRaw: Quantity?,
    val orientation: OrientationFrame?,
    val orientationRaw: List<Quantity>?,
    val rowSpacingMm: Quantity?,
    val colSpacingMm: Quantity?,
    val source: String,
    val sourceFrameIndex: Int,
    val evidence: List<EvidenceRef>,
    val errors: List<String>,
    val warnings: List<String>,
    val usable: Boolean
)

@Serializable
data class RawTagValue(
    val tagLabel: String,
    val tagName: String,
    val vr: String,
    val rawText: String?,
    val path: String,
    val frame: Int? = null,
    val binary: Boolean = false
)

@Serializable
data class RawInstance(
    val instanceId: String,
    val batchId: String,
    val ordinalInBatch: Int,
    val fileName: String,
    val sopClassUid: String?,
    val sopInstanceUid: String?,
    val seriesInstanceUid: String?,
    val studyInstanceUid: String?,
    val frameOfReferenceUid: String?,
    val instanceNumber: Int?,
    val transferSyntaxUid: String?,
    val transferSyntaxSupported: Boolean,
    val mediaStored: Boolean,
    val mediaSopClassUid: String?,
    val fatalDiagnostic: String?,
    val rawTagsJson: String,
    val rawBytesHash: String,
    val rawBytesSize: Int,
    val pixelFrameCount: Int,
    val rows: Int?,
    val columns: Int?
)

@Serializable
data class StackFrame(
    val geometry: FrameGeometry,
    val tAlongNormal: Double,
    val order: Int
)

@Serializable
data class SeriesCandidate(
    val candidateId: String,
    val lineageKey: String,
    val kind: String,
    val frameOfReferenceUid: String?,
    val orientationKey: String,
    val frames: List<StackFrame>,
    val medianSpacingMm: Double?,
    val annotations: List<String>,
    val evidence: List<EvidenceRef>,
    val derivedAtEventId: String,
    val ruleVersion: String
)

@Serializable
data class UnlocatedInstance(
    val instanceId: String,
    val sopInstanceUid: String?,
    val reasons: List<String>,
    val evidence: List<EvidenceRef>
)

@Serializable
data class RuleFire(
    val fireId: String,
    val code: String,
    val severity: String,
    val message: String,
    val affectedInstanceIds: List<String>,
    val affectedCandidateLineageKeys: List<String>,
    val evidence: List<EvidenceRef>
)

@Serializable
data class GeometryResult(
    val candidates: List<SeriesCandidate>,
    val unlocated: List<UnlocatedInstance>,
    val ruleFires: List<RuleFire>,
    val parameters: GroupingParameters,
    val ruleVersion: String,
    val derivedAtEventId: String
)

@Serializable
data class GroupingParameters(
    val orthogonalityTolerance: Double = 0.01,
    val unitNormalTolerance: Double = 0.001,
    val duplicatePositionToleranceMm: Double = 0.01,
    val spacingJumpRatio: Double = 2.0,
    val parameterVersion: String = "params-v1"
)

fun flattenTags(dataset: DicomDataset, pathPrefix: String = ""): List<RawTagValue> =
    dataset.all().flatMap { elementToTags(it, pathPrefix) }

private fun elementToTags(element: DicomElement, path: String): List<RawTagValue> {
    val here = when (val v = element.value) {
        is org.gsb.dicomreview.dicom.DicomValue.Binary -> listOf(
            RawTagValue(element.tag.toString(), tagNameOf(element.tag), element.vr, null, path, binary = true)
        )
        is org.gsb.dicomreview.dicom.DicomValue.RawString -> listOf(
            RawTagValue(element.tag.toString(), tagNameOf(element.tag), element.vr, v.raw, path)
        )
        is org.gsb.dicomreview.dicom.DicomValue.Sequence -> {
            val nested = v.items.flatMapIndexed { idx, item ->
                item.all().flatMap { el -> elementToTags(el, "$path${element.tag}/[$idx]/") }
            }
            nested
        }
    }
    return here
}

fun tagNameOf(tag: org.gsb.dicomreview.dicom.Tag): String = when (tag.toString()) {
    "(0002,0010)" -> "TransferSyntaxUID"
    "(0008,0016)" -> "SOPClassUID"
    "(0008,0018)" -> "SOPInstanceUID"
    "(0020,000D)" -> "StudyInstanceUID"
    "(0020,000E)" -> "SeriesInstanceUID"
    "(0020,0013)" -> "InstanceNumber"
    "(0020,0032)" -> "ImagePositionPatient"
    "(0020,0037)" -> "ImageOrientationPatient"
    "(0020,0052)" -> "FrameOfReferenceUID"
    "(0028,0010)" -> "Rows"
    "(0028,0011)" -> "Columns"
    "(0028,0030)" -> "PixelSpacing"
    "(0028,0008)" -> "NumberOfFrames"
    "(5200,9229)" -> "SharedFunctionalGroupsSequence"
    "(5200,922E)" -> "PerFrameFunctionalGroupsSequence"
    "(0028,9110)" -> "PixelMeasuresSequence"
    "(0020,9113)" -> "PlanePositionSequence"
    "(0020,9116)" -> "PlaneOrientationSequence"
    "(0018,0050)" -> "SliceThickness"
    "(7FE0,0010)" -> "PixelData"
    else -> "PrivateOrUnknown"
}
