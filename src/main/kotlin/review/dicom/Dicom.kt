package review.dicom

/** DICOM tag packed as 0xGGGGEeee. */
object Tags {
    const val FileMetaInformationVersion = 0x00020001
    const val MediaStorageSOPClassUID = 0x00020002
    const val MediaStorageSOPInstanceUID = 0x00020003
    const val TransferSyntaxUID = 0x00020010
    const val ImplementationClassUID = 0x00020012

    const val SpecificCharacterSet = 0x00080005
    const val SOPClassUID = 0x00080016
    const val SOPInstanceUID = 0x00080018
    const val StudyDate = 0x00080020
    const val Modality = 0x00080060
    const val PatientName = 0x00100010
    const val PatientID = 0x00100020
    const val StudyInstanceUID = 0x0020000D
    const val SeriesInstanceUID = 0x0020000E
    const val InstanceNumber = 0x00200013
    const val FrameOfReferenceUID = 0x00200052
    const val ImagePositionPatient = 0x00200032
    const val ImageOrientationPatient = 0x00200037

    const val SamplesPerPixel = 0x00280002
    const val PhotometricInterpretation = 0x00280004
    const val NumberOfFrames = 0x00280008
    const val Rows = 0x00280010
    const val Columns = 0x00280011
    const val PixelSpacing = 0x00280030
    const val BitsAllocated = 0x00280100
    const val BitsStored = 0x00280101
    const val HighBit = 0x00280102
    const val PixelRepresentation = 0x00280103

    const val SliceThickness = 0x00180050
    const val SpacingBetweenSlices = 0x00180088

    const val SharedFunctionalGroupsSequence = 0x52009229
    const val PerFrameFunctionalGroupsSequence = 0x52009230
    const val PixelMeasuresSequence = 0x00289110
    const val PlanePositionSequence = 0x00209113
    const val PlaneOrientationSequence = 0x00209116

    const val PixelData = 0x7FE00010
    const val Item = 0xFFFEE000
    const val ItemDelimitationItem = 0xFFFEE00D
    const val SequenceDelimitationItem = 0xFFFEE0DD

    private val names = mapOf(
        FileMetaInformationVersion to "File Meta Information Version",
        MediaStorageSOPClassUID to "Media Storage SOP Class UID",
        MediaStorageSOPInstanceUID to "Media Storage SOP Instance UID",
        TransferSyntaxUID to "Transfer Syntax UID",
        ImplementationClassUID to "Implementation Class UID",
        SOPClassUID to "SOP Class UID", SOPInstanceUID to "SOP Instance UID",
        StudyDate to "Study Date", Modality to "Modality",
        PatientName to "Patient's Name", PatientID to "Patient ID",
        StudyInstanceUID to "Study Instance UID", SeriesInstanceUID to "Series Instance UID",
        InstanceNumber to "Instance Number", FrameOfReferenceUID to "Frame of Reference UID",
        ImagePositionPatient to "Image Position (Patient)",
        ImageOrientationPatient to "Image Orientation (Patient)",
        SamplesPerPixel to "Samples per Pixel", PhotometricInterpretation to "Photometric Interpretation",
        NumberOfFrames to "Number of Frames", Rows to "Rows", Columns to "Columns",
        PixelSpacing to "Pixel Spacing", BitsAllocated to "Bits Allocated", BitsStored to "Bits Stored",
        HighBit to "High Bit", PixelRepresentation to "Pixel Representation",
        SliceThickness to "Slice Thickness", SpacingBetweenSlices to "Spacing Between Slices",
        SharedFunctionalGroupsSequence to "Shared Functional Groups Sequence",
        PerFrameFunctionalGroupsSequence to "Per-Frame Functional Groups Sequence",
        PixelMeasuresSequence to "Pixel Measures Sequence",
        PlanePositionSequence to "Plane Position Sequence",
        PlaneOrientationSequence to "Plane Orientation Sequence",
        PixelData to "Pixel Data", SpecificCharacterSet to "Specific Character Set"
    )
    fun name(tag: Int): String = names[tag] ?: "Unknown"
    fun hex(tag: Int): String {
        val g = tag ushr 16
        val e = tag and 0xFFFF
        return "(%04X,%04X)".format(g, e)
    }
    /** Implicit-VR fallback dictionary used for the implicit little-endian transfer syntax. */
    fun implicitVr(tag: Int): String = when (tag) {
        TransferSyntaxUID, MediaStorageSOPClassUID, MediaStorageSOPInstanceUID,
        ImplementationClassUID, SOPClassUID, SOPInstanceUID, StudyInstanceUID,
        SeriesInstanceUID, FrameOfReferenceUID -> "UI"
        Modality, PhotometricInterpretation -> "CS"
        PatientName, PatientID, StudyDate, InstanceNumber, SpecificCharacterSet -> "LO"
        ImagePositionPatient, ImageOrientationPatient, PixelSpacing -> "DS"
        SamplesPerPixel, Rows, Columns, BitsAllocated, BitsStored, HighBit,
        PixelRepresentation -> "US"
        NumberOfFrames, SpacingBetweenSlices, SliceThickness -> "IS"
        SharedFunctionalGroupsSequence, PerFrameFunctionalGroupsSequence,
        PixelMeasuresSequence, PlanePositionSequence, PlaneOrientationSequence -> "SQ"
        PixelData -> "OW"
        FileMetaInformationVersion -> "OB"
        else -> "UN"
    }
}

class DicomParseException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

data class RawElement(
    val tag: Int,
    val vr: String,
    val rawValue: String,
    val byteOffset: Long,
    val byteEnd: Long,
    val seqPath: String = "",
    val items: List<DicomDataset> = emptyList(),
    val pixel: PixelEncoding? = null,
)

data class PixelEncoding(
    val encapsulated: Boolean,
    val frameCount: Int,
    val decodable: Boolean,
    val reason: String?,
    /** native: per-frame offsets/lengths inside the file; encapsulated: fragment offsets/lengths. */
    val parts: List<LongRange> = emptyList(),
)

data class DicomDataset(val elements: List<RawElement>) {
    fun first(tag: Int): RawElement? = elements.firstOrNull { it.tag == tag }
    fun all(tag: Int): List<RawElement> = elements.filter { it.tag == tag }
}

data class ParsedDicom(
    val dataset: DicomDataset,
    val transferSyntaxUid: String,
    val transferSyntaxSupported: Boolean,
    val pixelEncapsulated: Boolean,
) {
    fun first(tag: Int) = dataset.first(tag)
}
