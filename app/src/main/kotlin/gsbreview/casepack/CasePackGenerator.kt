package gsbreview.casepack

import gsbreview.dicom.DicomWriter
import gsbreview.dicom.DicomWriter.FrameSpec
import gsbreview.dicom.DicomWriter.InstanceSpec
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.security.MessageDigest
import kotlin.io.path.writeText

object CasePackGenerator {
    private const val CASE_ID = "dicom-calibration-rig-v1"

    @JvmStatic
    fun main(args: Array<String>) {
        val out = Paths.get(args.getOrElse(0) { "cases" }).resolve(CASE_ID)
        Files.createDirectories(out)
        val files = LinkedHashMap<String, ByteArray>()

        val study = "1.3.6.1.4.1.99999.study.1"
        val seriesA = "1.3.6.1.4.1.99999.series.A"
        val seriesB = "1.3.6.1.4.1.99999.series.B"
        val forA = "1.3.6.1.4.1.99999.for.A"
        val forB = "1.3.6.1.4.1.99999.for.B"
        val axial = listOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0)
        val spacing = listOf(0.75, 0.75) // row\col in mm

        // Baseline ordered axial volume; InstanceNumbers intentionally shuffled to prove
        // InstanceNumber is never used as spatial truth.
        val positionsA = listOf(0.0, 5.0, 10.0, 15.0, 20.0, 30.0) // last gap = 2.0x -> local jump branch
        val shuffledInstance = listOf("6", "2", "1", "4", "3", "5")
        positionsA.forEachIndexed { i, z ->
            val name = "baseline/IM_%03d.dcm".format(i + 1)
            files[name] = DicomWriter.write(
                InstanceSpec(
                    sopInstanceUid = "1.3.6.1.4.1.99999.sop.A${i + 1}",
                    seriesInstanceUid = seriesA,
                    frameOfReferenceUid = forA,
                    instanceNumber = shuffledInstance[i],
                    imagePosition = listOf(-6.0, -6.0, z),
                    imageOrientation = axial,
                    pixelSpacing = spacing,
                    sliceThickness = 5.0
                )
            )
        }

        // Non-orthogonal orientation: row dot col = 0.1 (fails the 0.001 gate).
        files["boundary/nonorthogonal.dcm"] = DicomWriter.write(
            InstanceSpec(
                sopInstanceUid = "1.3.6.1.4.1.99999.sop.NONORTH",
                seriesInstanceUid = seriesB,
                frameOfReferenceUid = forA,
                instanceNumber = "101",
                imagePosition = listOf(0.0, 0.0, 100.0),
                imageOrientation = listOf(1.0, 0.0, 0.0, 0.1, 1.0, 0.0),
                pixelSpacing = spacing
            )
        )

        // Duplicate slice position (same coordinate as baseline slice #4, within tolerance).
        files["boundary/duplicate_position.dcm"] = DicomWriter.write(
            InstanceSpec(
                sopInstanceUid = "1.3.6.1.4.1.99999.sop.DUP",
                seriesInstanceUid = seriesA,
                frameOfReferenceUid = forA,
                instanceNumber = "201",
                imagePosition = listOf(-6.0, -6.0, 15.005),
                imageOrientation = axial,
                pixelSpacing = spacing
            )
        )

        // Multiframe object: three frames, second frame carries a local spacing jump.
        files["boundary/multiframe.dcm"] = DicomWriter.write(
            InstanceSpec(
                sopInstanceUid = "1.3.6.1.4.1.99999.sop.MF",
                seriesInstanceUid = "1.3.6.1.4.1.99999.series.MF",
                frameOfReferenceUid = "1.3.6.1.4.1.99999.for.MF",
                instanceNumber = "301",
                multiframe = true,
                frames = listOf(
                    FrameSpec(listOf(0.0, 0.0, 0.0), axial, spacing),
                    FrameSpec(listOf(0.0, 0.0, 4.0), axial, spacing),
                    FrameSpec(listOf(0.0, 0.0, 12.0), axial, spacing)
                )
            )
        )

        // UID conflict: identical SOPInstanceUID to baseline #1 but different bytes/path.
        files["boundary/uid_conflict.dcm"] = DicomWriter.write(
            InstanceSpec(
                sopInstanceUid = "1.3.6.1.4.1.99999.sop.A1",
                seriesInstanceUid = "1.3.6.1.4.1.99999.series.CONFLICT",
                frameOfReferenceUid = forB,
                instanceNumber = "401",
                imagePosition = listOf(50.0, 50.0, 0.0),
                imageOrientation = axial,
                pixelSpacing = spacing
            )
        )

        // Missing geometry: no IPP/IOP/PixelSpacing at all.
        files["boundary/missing_geometry.dcm"] = DicomWriter.write(
            InstanceSpec(
                sopInstanceUid = "1.3.6.1.4.1.99999.sop.NOGEOM",
                seriesInstanceUid = "1.3.6.1.4.1.99999.series.NOGEOM",
                frameOfReferenceUid = null,
                instanceNumber = "501",
                omitGeometry = true
            )
        )

        // Unsupported transfer syntax (JPEG2000 lossless): geometry must remain usable;
        // pixel decoding surfaces a diagnostic.
        files["boundary/unsupported_ts.dcm"] = DicomWriter.write(
            InstanceSpec(
                sopInstanceUid = "1.3.6.1.4.1.99999.sop.JP2K",
                seriesInstanceUid = "1.3.6.1.4.1.99999.series.JP2K",
                frameOfReferenceUid = "1.3.6.1.4.1.99999.for.JP2K",
                instanceNumber = "601",
                imagePosition = listOf(10.0, 10.0, 0.0),
                imageOrientation = axial,
                pixelSpacing = spacing,
                transferSyntaxUid = DicomWriter.TS_JPEG2000_LOSSLESS
            )
        )

        // Different FrameOfReference sharing the same spatial coordinates: never force-merge.
        files["boundary/different_for.dcm"] = DicomWriter.write(
            InstanceSpec(
                sopInstanceUid = "1.3.6.1.4.1.99999.sop.FORB",
                seriesInstanceUid = seriesA,
                frameOfReferenceUid = forB,
                instanceNumber = "701",
                imagePosition = listOf(-6.0, -6.0, 0.0),
                imageOrientation = axial,
                pixelSpacing = spacing
            )
        )

        for ((rel, bytes) in files) {
            val target = out.resolve(rel)
            Files.createDirectories(target.parent)
            Files.write(target, bytes)
        }

        val manifest = buildManifest(files)
        out.resolve("case-pack.json").writeText(manifest)
        out.resolve("replay.json").writeText(buildReplay())
        out.resolve("README.md").writeText(readme())
        println("wrote case pack to $out (${files.size} DICOM files)")
    }

    private fun sha256(b: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    private fun buildManifest(files: Map<String, ByteArray>): String {
        val entries = files.entries.joinToString(",\n") { (path, bytes) ->
            """  {"path":"$path","size":${bytes.size},"sha256":"${sha256(bytes)}"}"""
        }
        return """{
          |"caseId":"$CASE_ID",
          |"title":"DICOM 体系标定台：切片几何、FrameOfReference 与序列多解",
          |"domain":"dicom-volume-geometric-series-grouping",
          |"coordinateBasis":"DICOM patient coordinate system; positions in mm; direction cosines dimensionless",
          |"initialVersion":"v1 preserves SOP/Series/FrameOfReference UID, ImagePosition/Orientation, PixelSpacing, multiframe functional groups, transfer syntax and every original tag",
          |"preservedRawFields":[
          |  "SOPInstanceUID","SeriesInstanceUID","FrameOfReferenceUID",
          |  "ImagePositionPatient","ImageOrientationPatient","PixelSpacing",
          |  "PerFrameFunctionalGroupsSequence","SharedFunctionalGroupsSequence",
          |  "TransferSyntaxUID","all raw tags with original representation"
          |],
          |"scenarios":[
          |  {"key":"non_orthogonal","path":"boundary/nonorthogonal.dcm","expect":"rejected at orthogonality gate"},
          |  {"key":"duplicate_position","path":"boundary/duplicate_position.dcm","expect":"ambiguous_duplicate_position multi-candidate"},
          |  {"key":"local_spacing_jump","path":"baseline/IM_0006.dcm","expect":"branched_spacing_gap"},
          |  {"key":"multiframe","path":"boundary/multiframe.dcm","expect":"per-frame geometry with local jump branch"},
          |  {"key":"uid_conflict","path":"boundary/uid_conflict.dcm","expect":"subject key collision surfaced"},
          |  {"key":"missing_geometry","path":"boundary/missing_geometry.dcm","expect":"diagnostic, excluded from grouping"},
          |  {"key":"unsupported_transfer_syntax","path":"boundary/unsupported_ts.dcm","expect":"geometry preserved; pixel decoding unsupported"}
          |],
          |"files":[
          |$entries
          |  ]
          |}
          |""".trimMargin()
    }

    private fun buildReplay(): String = """{
      |"steps":[
      |  {"op":"import","stage":"initial","paths":["baseline","boundary"],"note":"initial import preserves all raw fields"},
      |  {"op":"interpret","trigger":"initial"},
      |  {"op":"revise_parameters","spacingJumpRatio":1.2,"note":"parameter revision; global derived layer invalidated"},
      |  {"op":"change_source","path":"baseline/IM_0003.dcm","note":"local-only source mutation; invalidation must be scoped"},
      |  {"op":"decide","candidate":"first-ordered-volume","decision":"accept","note":"first human decision"},
      |  {"op":"decide","candidate":"first-ordered-volume","decision":"reject","note":"conflicting human decision from a second window"}
      |]
      |}
      |""".trimMargin()

    private fun readme(): String = """
      |# DICOM 体系标定台：切片几何、FrameOfReference 与序列多解
      |
      |This is domain-specific offline material, not a generic table.
      |
      |Initial version preserves every raw field (UIDs, geometry, PixelSpacing, functional
      |groups, transfer syntax, original tags). The replay script then performs one parameter
      |revision, one local-only source change and one pair of conflicting human decisions.
      |
      |Fixed boundary cases:
      |- non-orthogonal direction vectors (orthogonality gate)
      |- duplicate slice position (all legal candidates retained)
      |- local spacing jump (branching, no forced merge)
      |- multiframe functional groups
      |- SOP UID conflict
      |- missing geometry (raw value stays in place with a diagnostic)
      |- unsupported transfer syntax (JPEG2000): geometry still readable
      |""".trimMargin()
}
