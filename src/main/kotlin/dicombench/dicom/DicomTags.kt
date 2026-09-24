package dicombench.dicom

/** Minimal DICOM data dictionary: tag -> default VR, used for implicit-VR parsing. */
object DicomTags {
    const val MediaStorageSOPClassUID = 0x00020002
    const val MediaStorageSOPInstanceUID = 0x00020003
    const val TransferSyntaxUID = 0x00020010
    const val ImplementationClassUID = 0x00020012

    const val ImageType = 0x00080008
    const val SOPClassUID = 0x00080016
    const val SOPInstanceUID = 0x00080018
    const val StudyDate = 0x00080020
    const val SeriesDescription = 0x0008103E
    const val Manufacturer = 0x00080070

    const val SamplesPerPixel = 0x00280002
    const val PhotometricInterpretation = 0x00280004
    const val Rows = 0x00280010
    const val Columns = 0x00280011
    const val PixelSpacing = 0x00280030
    const val BitsAllocated = 0x00280100
    const val PixelRepresentation = 0x00280103

    const val StudyInstanceUID = 0x0020000D
    const val SeriesInstanceUID = 0x0020000E
    const val InstanceNumber = 0x00200013
    const val ImagePositionPatient = 0x00200032
    const val ImageOrientationPatient = 0x00200037
    const val FrameOfReferenceUID = 0x00200052

    const val NumberOfFrames = 0x00280008
    const val SharedFunctionalGroupsSequence = 0x52009229
    const val PerFrameFunctionalGroupsSequence = 0x52009230
    const val PixelMeasuresSequence = 0x00289110
    const val PlanePositionSequence = 0x00209113
    const val PlaneOrientationSequence = 0x00209116
    const val FrameContentSequence = 0x00209111
    const val DimensionIndexValues = 0x00209157

    const val PixelData = 0x7FE00010

    val vrByTag: Map<Int, String> = buildMap {
        put(MediaStorageSOPClassUID, "UI"); put(MediaStorageSOPInstanceUID, "UI")
        put(TransferSyntaxUID, "UI"); put(ImplementationClassUID, "UI")
        put(ImageType, "CS"); put(SOPClassUID, "UI"); put(SOPInstanceUID, "UI")
        put(StudyDate, "DA"); put(SeriesDescription, "LO"); put(Manufacturer, "LO")
        put(SamplesPerPixel, "US"); put(PhotometricInterpretation, "CS")
        put(Rows, "US"); put(Columns, "US"); put(PixelSpacing, "DS")
        put(BitsAllocated, "US"); put(PixelRepresentation, "US")
        put(StudyInstanceUID, "UI"); put(SeriesInstanceUID, "UI")
        put(InstanceNumber, "IS"); put(ImagePositionPatient, "DS")
        put(ImageOrientationPatient, "DS"); put(FrameOfReferenceUID, "UI")
        put(NumberOfFrames, "IS")
        put(SharedFunctionalGroupsSequence, "SQ"); put(PerFrameFunctionalGroupsSequence, "SQ")
        put(PixelMeasuresSequence, "SQ"); put(PlanePositionSequence, "SQ")
        put(PlaneOrientationSequence, "SQ"); put(FrameContentSequence, "SQ")
        put(DimensionIndexValues, "UL"); put(PixelData, "OW")
    }
}
