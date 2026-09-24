package dicomreview.dicom

object Tags {
    const val TransferSyntaxUID = 0x00020010
    const val MediaStorageSOPClassUID = 0x00020002
    const val MediaStorageSOPInstanceUID = 0x00020003
    const val SpecificCharacterSet = 0x00080005
    const val SOPClassUID = 0x00080016
    const val SOPInstanceUID = 0x00080018
    const val InstanceNumber = 0x00200013
    const val SeriesInstanceUID = 0x0020000E
    const val FrameOfReferenceUID = 0x00200052
    const val ImagePositionPatient = 0x00200032
    const val ImageOrientationPatient = 0x00200037
    const val PixelSpacing = 0x00280030
    const val SliceThickness = 0x00180050
    const val Rows = 0x00280010
    const val Columns = 0x00280011
    const val NumberOfFrames = 0x00280008
    const val PixelData = 0x7FE00010
    const val SharedFunctionalGroupsSequence = 0x52009229
    const val PerFrameFunctionalGroupsSequence = 0x52009230
    const val PlanePositionSequence = 0x00209113
    const val PlaneOrientationSequence = 0x00209116
    const val PixelMeasuresSequence = 0x00289110
    const val ImagePositionPatientF = 0x00200032 // inside PlanePositionSequence
    const val ImageOrientationPatientF = 0x00200037
    const val PixelSpacingF = 0x00280030
    const val SliceThicknessF = 0x00180050
    const val FrameOfReferenceUIDF = 0x00200052
}

private val KEYWORDS = mapOf(
    0x00020002 to "MediaStorageSOPClassUID",
    0x00020003 to "MediaStorageSOPInstanceUID",
    0x00020010 to "TransferSyntaxUID",
    0x00080005 to "SpecificCharacterSet",
    0x00080016 to "SOPClassUID",
    0x00080018 to "SOPInstanceUID",
    0x00180050 to "SliceThickness",
    0x0020000E to "SeriesInstanceUID",
    0x00200013 to "InstanceNumber",
    0x00200032 to "ImagePositionPatient",
    0x00200037 to "ImageOrientationPatient",
    0x00200052 to "FrameOfReferenceUID",
    0x00280008 to "NumberOfFrames",
    0x00280010 to "Rows",
    0x00280011 to "Columns",
    0x00280030 to "PixelSpacing",
    0x7FE00010 to "PixelData",
    0x52009229 to "SharedFunctionalGroupsSequence",
    0x52009230 to "PerFrameFunctionalGroupsSequence",
    0x00209113 to "PlanePositionSequence",
    0x00209116 to "PlaneOrientationSequence",
    0x00289110 to "PixelMeasuresSequence"
)

fun keywordOf(tag: Int): String = KEYWORDS[tag]
    ?: "Tag%04X%04X".format(tag ushr 16, tag and 0xFFFF)
