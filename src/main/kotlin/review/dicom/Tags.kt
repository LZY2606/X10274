package review.dicom

object Tags {
    fun t(g: Int, e: Int) = (g shl 16) or e

    val FILE_META_LENGTH = t(0x0002, 0x0000)
    val META_SOP_CLASS = t(0x0002, 0x0002)
    val META_SOP_INSTANCE = t(0x0002, 0x0003)
    val TRANSFER_SYNTAX = t(0x0002, 0x0010)
    val IMPL_CLASS = t(0x0002, 0x0012)

    val SOP_CLASS = t(0x0008, 0x0016)
    val SOP_INSTANCE = t(0x0008, 0x0018)
    val IMAGE_TYPE = t(0x0008, 0x0008)
    val STUDY_DATE = t(0x0008, 0x0020)
    val STUDY_DESCRIPTION = t(0x0008, 0x1030)
    val PATIENT_NAME = t(0x0010, 0x0010)
    val PATIENT_ID = t(0x0010, 0x0020)
    val IDENTITY_REMOVED = t(0x0012, 0x0062)
    val DEID_METHOD = t(0x0012, 0x0063)
    val SLICE_THICKNESS = t(0x0018, 0x0050)
    val SPACING_BETWEEN_SLICES = t(0x0018, 0x0088)
    val STUDY_UID = t(0x0020, 0x000D)
    val SERIES_UID = t(0x0020, 0x000E)
    val INSTANCE_NUMBER = t(0x0020, 0x0013)
    val IMAGE_POSITION = t(0x0020, 0x0032)
    val IMAGE_ORIENTATION = t(0x0020, 0x0037)
    val FRAME_OF_REFERENCE = t(0x0020, 0x0052)
    val SAMPLES_PER_PIXEL = t(0x0028, 0x0002)
    val PHOTOMETRIC = t(0x0028, 0x0004)
    val NUMBER_OF_FRAMES = t(0x0028, 0x0008)
    val ROWS = t(0x0028, 0x0010)
    val COLUMNS = t(0x0028, 0x0011)
    val PIXEL_SPACING = t(0x0028, 0x0030)
    val BITS_ALLOCATED = t(0x0028, 0x0100)
    val BITS_STORED = t(0x0028, 0x0101)
    val HIGH_BIT = t(0x0028, 0x0102)
    val PIXEL_REPRESENTATION = t(0x0028, 0x0103)

    val SHARED_FG = t(0x5200, 0x9229)
    val PERFRAME_FG = t(0x5200, 0x9230)
    val PIXEL_MEASURES = t(0x0028, 0x9110)
    val PLANE_POSITION = t(0x0020, 0x9113)
    val PLANE_ORIENTATION = t(0x0020, 0x9118)
    val DIMENSION_INDEX_VALUES = t(0x0020, 0x9157)
    val PIXEL_DATA = t(0x7FE0, 0x0010)

    val ITEM = t(0xFFFE, 0xE000)
    val ITEM_DELIM = t(0xFFFE, 0xE00D)
    val SEQ_DELIM = t(0xFFFE, 0xE0DD)

    val names = mapOf(
        FILE_META_LENGTH to "FileMetaInformationGroupLength",
        META_SOP_CLASS to "MediaStorageSOPClassUID",
        META_SOP_INSTANCE to "MediaStorageSOPInstanceUID",
        TRANSFER_SYNTAX to "TransferSyntaxUID",
        IMPL_CLASS to "ImplementationClassUID",
        SOP_CLASS to "SOPClassUID",
        SOP_INSTANCE to "SOPInstanceUID",
        IMAGE_TYPE to "ImageType",
        STUDY_DATE to "StudyDate",
        STUDY_DESCRIPTION to "StudyDescription",
        PATIENT_NAME to "PatientName",
        PATIENT_ID to "PatientID",
        IDENTITY_REMOVED to "PatientIdentityRemoved",
        DEID_METHOD to "DeidentificationMethodCodeSequence",
        SLICE_THICKNESS to "SliceThickness",
        SPACING_BETWEEN_SLICES to "SpacingBetweenSlices",
        STUDY_UID to "StudyInstanceUID",
        SERIES_UID to "SeriesInstanceUID",
        INSTANCE_NUMBER to "InstanceNumber",
        IMAGE_POSITION to "ImagePositionPatient",
        IMAGE_ORIENTATION to "ImageOrientationPatient",
        FRAME_OF_REFERENCE to "FrameOfReferenceUID",
        SAMPLES_PER_PIXEL to "SamplesPerPixel",
        PHOTOMETRIC to "PhotometricInterpretation",
        NUMBER_OF_FRAMES to "NumberOfFrames",
        ROWS to "Rows",
        COLUMNS to "Columns",
        PIXEL_SPACING to "PixelSpacing",
        BITS_ALLOCATED to "BitsAllocated",
        BITS_STORED to "BitsStored",
        HIGH_BIT to "HighBit",
        PIXEL_REPRESENTATION to "PixelRepresentation",
        SHARED_FG to "SharedFunctionalGroupsSequence",
        PERFRAME_FG to "PerFrameFunctionalGroupsSequence",
        PIXEL_MEASURES to "PixelMeasuresSequence",
        PLANE_POSITION to "PlanePositionSequence",
        PLANE_ORIENTATION to "PlaneOrientationSequence",
        DIMENSION_INDEX_VALUES to "DimensionIndexValues",
        PIXEL_DATA to "PixelData",
    )

    /** Tags encoded as SQ in implicit-VR datasets. */
    val sequenceTags = setOf(
        SHARED_FG, PERFRAME_FG, PIXEL_MEASURES, PLANE_POSITION, PLANE_ORIENTATION,
        t(0x0012, 0x0064), t(0x0020, 0x9222),
    )

    val longVr = setOf("OB", "OD", "OF", "OL", "OW", "SQ", "UC", "UN", "UR", "UT")

    fun name(tag: Int): String = names[tag] ?: "PrivateOrUnknown"
}
