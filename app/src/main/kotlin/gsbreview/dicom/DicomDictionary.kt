package gsbreview.dicom

object DicomDictionary {
    private val names = mapOf(
        0x00020000 to ("File Meta Information Group Length" to "UL"),
        0x00020001 to ("File Meta Information Version" to "OB"),
        0x00020002 to ("MediaStorageSOPClassUID" to "UI"),
        0x00020003 to ("MediaStorageSOPInstanceUID" to "UI"),
        0x00020010 to ("TransferSyntaxUID" to "UI"),
        0x00020012 to ("ImplementationClassUID" to "UI"),
        0x00080005 to ("SpecificCharacterSet" to "CS"),
        0x00080008 to ("ImageType" to "CS"),
        0x00080016 to ("SOPClassUID" to "UI"),
        0x00080018 to ("SOPInstanceUID" to "UI"),
        0x00080060 to ("Modality" to "CS"),
        0x00100010 to ("PatientName" to "PN"),
        0x00100020 to ("PatientID" to "LO"),
        0x00180050 to ("SliceThickness" to "DS"),
        0x0020000D to ("StudyInstanceUID" to "UI"),
        0x0020000E to ("SeriesInstanceUID" to "UI"),
        0x00200011 to ("SeriesNumber" to "IS"),
        0x00200013 to ("InstanceNumber" to "IS"),
        0x00200032 to ("ImagePositionPatient" to "DS"),
        0x00200037 to ("ImageOrientationPatient" to "DS"),
        0x00200052 to ("FrameOfReferenceUID" to "UI"),
        0x00200060 to ("Laterality" to "CS"),
        0x00280002 to ("SamplesPerPixel" to "US"),
        0x00280004 to ("PhotometricInterpretation" to "CS"),
        0x00280008 to ("NumberOfFrames" to "IS"),
        0x00280010 to ("Rows" to "US"),
        0x00280011 to ("Columns" to "US"),
        0x00280030 to ("PixelSpacing" to "DS"),
        0x00280100 to ("BitsAllocated" to "US"),
        0x00280101 to ("BitsStored" to "US"),
        0x00280103 to ("PixelRepresentation" to "US"),
        0x52009229 to ("SharedFunctionalGroupsSequence" to "SQ"),
        0x52009230 to ("PerFrameFunctionalGroupsSequence" to "SQ"),
        0x00209113 to ("PlanePositionSequence" to "SQ"),
        0x00209116 to ("PlaneOrientationSequence" to "SQ"),
        0x00289110 to ("PixelMeasuresSequence" to "SQ"),
        0x7FE00010 to ("PixelData" to "OW")
    )

    fun name(tag: Int): String? = names[tag]?.first
    fun implicitVr(tag: Int): String = names[tag]?.second
        ?: if ((tag ushr 16) == 0xFFFE) "UN" else "LO"
}
