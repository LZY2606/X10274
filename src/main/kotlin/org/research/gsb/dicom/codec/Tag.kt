package org.research.gsb.dicom.codec

/** DICOM data element tag as (group, element). */
@JvmInline
value class Tag(val code: Int) {
    constructor(group: Int, element: Int) : this((group shl 16) or (element and 0xFFFF))
    val group: Int get() = code ushr 16
    val element: Int get() = code and 0xFFFF
    override fun toString(): String = "(%04X,%04X)".format(group, element)
}

/** Standard tags used by the geometry calibration bench. */
object Tags {
    val TransferSyntaxUID = Tag(0x0002, 0x0010)
    val MediaStorageSOPClassUID = Tag(0x0002, 0x0002)
    val MediaStorageSOPInstanceUID = Tag(0x0002, 0x0003)

    val SOPClassUID = Tag(0x0008, 0x0016)
    val SOPInstanceUID = Tag(0x0008, 0x0018)
    val SpecificCharacterSet = Tag(0x0008, 0x0005)

    val StudyInstanceUID = Tag(0x0020, 0x000D)
    val SeriesInstanceUID = Tag(0x0020, 0x000E)
    val InstanceNumber = Tag(0x0020, 0x0013)
    val FrameOfReferenceUID = Tag(0x0020, 0x0052)
    val ImagePositionPatient = Tag(0x0020, 0x0032)
    val ImageOrientationPatient = Tag(0x0020, 0x0037)

    val Rows = Tag(0x0028, 0x0010)
    val Columns = Tag(0x0028, 0x0011)
    val PixelSpacing = Tag(0x0028, 0x0030)
    val SliceThickness = Tag(0x0018, 0x0050)
    val SpacingBetweenSlices = Tag(0x0018, 0x0088)
    val SamplesPerPixel = Tag(0x0028, 0x0002)
    val PhotometricInterpretation = Tag(0x0028, 0x0004)
    val BitsAllocated = Tag(0x0028, 0x0100)
    val PixelRepresentation = Tag(0x0028, 0x0103)
    val NumberOfFrames = Tag(0x0028, 0x0008)

    val SharedFunctionalGroupsSequence = Tag(0x5200, 0x9229)
    val PerFrameFunctionalGroupsSequence = Tag(0x5200, 0x9230)

    val PixelMeasuresSequence = Tag(0x0028, 0x9110)
    val PixelValueTransformationSequence = Tag(0x0028, 0x9140)
    val PlanePositionSequence = Tag(0x0020, 0x9113)
    val PlaneOrientationSequence = Tag(0x0020, 0x9116)
    val FrameContentSequence = Tag(0x0020, 0x9111)

    val PixelData = Tag(0x7FE0, 0x0010)
}
