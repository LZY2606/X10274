package org.gsb.dicomreview.dicom

import java.io.ByteArrayOutputStream

data class Tag(val group: Int, val element: Int) {
    override fun toString(): String = "(%04X,%04X)".format(group, element)
}

sealed class DicomValue {
    data class RawString(val raw: String, val vr: String, val representation: String) : DicomValue()
    data class Sequence(val items: List<DicomDataset>) : DicomValue()
    class Binary(val bytes: ByteArray, val vr: String) : DicomValue() {
        override fun equals(other: Any?): Boolean = other is Binary && bytes.contentEquals(other.bytes) && vr == other.vr
        override fun hashCode(): Int = bytes.contentHashCode() * 31 + vr.hashCode()
    }
}

class DicomDataset(elements: List<DicomElement> = emptyList()) {
    private val byTag: Map<Tag, DicomElement> = elements.associateBy { it.tag }
    val elements: List<DicomElement> = elements

    operator fun get(tag: Tag): DicomElement? = byTag[tag]
    fun contains(tag: Tag): Boolean = byTag.containsKey(tag)
    fun all(): List<DicomElement> = elements
}

data class DicomElement(val tag: Tag, val vr: String, val value: DicomValue) {
    fun strings(): List<String> = when (val v = value) {
        is DicomValue.RawString -> v.raw.split("\\")
        else -> emptyList()
    }
    fun singleString(): String? = strings().firstOrNull()?.takeIf { it.isNotEmpty() }
    fun intOrNull(): Int? = singleString()?.trim()?.toIntOrNull()
    fun doubleOrNull(): Double? = singleString()?.trim()?.replace(" ", "")?.toDoubleOrNull()
    fun doublesOrNull(): List<Double>? =
        strings().map { it.trim().replace(" ", "").toDoubleOrNull() ?: return null }
    fun sequenceItems(): List<DicomDataset> = (value as? DicomValue.Sequence)?.items ?: emptyList()
}

data class ParsedDicom(
    val dataset: DicomDataset,
    val transferSyntaxUID: String?,
    val explicitVR: Boolean,
    val bigEndian: Boolean,
    val pixelEncapsulated: Boolean,
    val parseDiagnostic: String?
)

class DicomParseException(message: String) : RuntimeException(message)

object DicomTags {
    val TransferSyntaxUID = Tag(0x0002, 0x0010)
    val SOPClassUID = Tag(0x0008, 0x0016)
    val SOPInstanceUID = Tag(0x0008, 0x0018)
    val StudyInstanceUID = Tag(0x0020, 0x000D)
    val SeriesInstanceUID = Tag(0x0020, 0x000E)
    val InstanceNumber = Tag(0x0020, 0x0013)
    val ImagePositionPatient = Tag(0x0020, 0x0032)
    val ImageOrientationPatient = Tag(0x0020, 0x0037)
    val FrameOfReferenceUID = Tag(0x0020, 0x0052)
    val Rows = Tag(0x0028, 0x0010)
    val Columns = Tag(0x0028, 0x0011)
    val PixelSpacing = Tag(0x0028, 0x0030)
    val PixelData = Tag(0x7FE0, 0x0010)
    val NumberOfFrames = Tag(0x0028, 0x0008)
    val SharedFunctionalGroupsSequence = Tag(0x5200, 0x9229)
    val PerFrameFunctionalGroupsSequence = Tag(0x5200, 0x922E)
    val PixelMeasuresSequence = Tag(0x0028, 0x9110)
    val PlanePositionSequence = Tag(0x0020, 0x9113)
    val PlaneOrientationSequence = Tag(0x0020, 0x9116)
    val ImagePositionPatientItem = ImagePositionPatient
    val ImageOrientationPatientItem = ImageOrientationPatient
    val PixelSpacingItem = PixelSpacing
    val SliceThickness = Tag(0x0018, 0x0050)
}
