package app.dicom

/** Small DICOM data dictionary covering the tags used by the geometry bench. */
object DicomTags {
    fun h(group: Int, element: Int) = (group shl 16) or element

    val NAMES = mapOf(
        h(0x0002, 0x0002) to "MediaStorageSOPClassUID",
        h(0x0002, 0x0003) to "MediaStorageSOPInstanceUID",
        h(0x0002, 0x0010) to "TransferSyntaxUID",
        h(0x0002, 0x0012) to "ImplementationClassUID",
        h(0x0008, 0x0008) to "ImageType",
        h(0x0008, 0x0016) to "SOPClassUID",
        h(0x0008, 0x0018) to "SOPInstanceUID",
        h(0x0008, 0x0060) to "Modality",
        h(0x0010, 0x0010) to "PatientName",
        h(0x0010, 0x0020) to "PatientID",
        h(0x0018, 0x0050) to "SliceThickness",
        h(0x0018, 0x0088) to "SpacingBetweenSlices",
        h(0x0020, 0x000D) to "StudyInstanceUID",
        h(0x0020, 0x000E) to "SeriesInstanceUID",
        h(0x0020, 0x0013) to "InstanceNumber",
        h(0x0020, 0x0032) to "ImagePositionPatient",
        h(0x0020, 0x0037) to "ImageOrientationPatient",
        h(0x0020, 0x0052) to "FrameOfReferenceUID",
        h(0x0028, 0x0002) to "SamplesPerPixel",
        h(0x0028, 0x0004) to "PhotometricInterpretation",
        h(0x0028, 0x0008) to "NumberOfFrames",
        h(0x0028, 0x0010) to "Rows",
        h(0x0028, 0x0011) to "Columns",
        h(0x0028, 0x0030) to "PixelSpacing",
        h(0x0028, 0x0100) to "BitsAllocated",
        h(0x0028, 0x0101) to "BitsStored",
        h(0x0028, 0x0102) to "HighBit",
        h(0x0028, 0x0103) to "PixelRepresentation",
        h(0x5200, 0x9229) to "SharedFunctionalGroupsSequence",
        h(0x5200, 0x9230) to "PerFrameFunctionalGroupsSequence",
        h(0x0020, 0x9111) to "PlanePositionSequence",
        h(0x0020, 0x9113) to "PlaneOrientationSequence",
        h(0x0028, 0x9110) to "PixelMeasuresSequence",
        h(0x0020, 0x9056) to "StackID",
        h(0x0020, 0x9057) to "InStackPositionNumber",
        h(0x0020, 0x9228) to "DimensionIndexValues",
        h(0x7FE0, 0x0010) to "PixelData",
        h(0x0008, 0x1140) to "ReferencedImageSequence",
        h(0x0008, 0x1150) to "ReferencedSOPClassUID",
        h(0x0008, 0x1155) to "ReferencedSOPInstanceUID"
    )

    val VR = mapOf(
        h(0x0002, 0x0002) to "UI", h(0x0002, 0x0003) to "UI", h(0x0002, 0x0010) to "UI",
        h(0x0002, 0x0012) to "UI", h(0x0008, 0x0008) to "CS", h(0x0008, 0x0016) to "UI",
        h(0x0008, 0x0018) to "UI", h(0x0008, 0x0060) to "CS", h(0x0010, 0x0010) to "PN",
        h(0x0010, 0x0020) to "LO", h(0x0018, 0x0050) to "DS", h(0x0018, 0x0088) to "DS",
        h(0x0020, 0x000D) to "UI", h(0x0020, 0x000E) to "UI", h(0x0020, 0x0013) to "IS",
        h(0x0020, 0x0032) to "DS", h(0x0020, 0x0037) to "DS", h(0x0020, 0x0052) to "UI",
        h(0x0028, 0x0002) to "US", h(0x0028, 0x0004) to "CS", h(0x0028, 0x0008) to "IS",
        h(0x0028, 0x0010) to "US", h(0x0028, 0x0011) to "US", h(0x0028, 0x0030) to "DS",
        h(0x0028, 0x0100) to "US", h(0x0028, 0x0101) to "US", h(0x0028, 0x0102) to "US",
        h(0x0028, 0x0103) to "US", h(0x5200, 0x9229) to "SQ", h(0x5200, 0x9230) to "SQ",
        h(0x0020, 0x9111) to "SQ", h(0x0020, 0x9113) to "SQ", h(0x0028, 0x9110) to "SQ",
        h(0x0020, 0x9056) to "SH", h(0x0020, 0x9057) to "UL", h(0x0020, 0x9228) to "UL",
        h(0x7FE0, 0x0010) to "OW", h(0x0008, 0x1140) to "SQ", h(0x0008, 0x1150) to "UI",
        h(0x0008, 0x1155) to "UI"
    )

    fun name(tag: Int) = NAMES[tag] ?: ""
    fun vrOf(tag: Int) = VR[tag]
}
