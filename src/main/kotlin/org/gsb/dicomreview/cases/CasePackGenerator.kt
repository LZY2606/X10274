package org.gsb.dicomreview.cases

import java.nio.file.Files
import java.nio.file.Path
import org.gsb.dicomreview.dicom.DicomTags
import org.gsb.dicomreview.dicom.DicomWriter

/**
 * Offline, domain-specific case pack:
 *  "DICOM 体系标定台：切片几何、FrameOfReference 与序列多解"
 *
 * Every variant directory is a self-contained importable batch. Shared slices are byte identical
 * across baseline and variants, so re-imports preserve stable material identities; a single direct
 * source file changes per scenario.
 */
object CasePackGenerator {
    private const val STUDY = "1.2.826.0.1.534141.9999.0001"
    private const val SERIES = "1.2.826.0.1.534141.9999.0001.series01"
    private const val FOR = "1.2.826.0.1.534141.9999.0001.for01"

    data class SliceSpec(
        val file: String,
        val sop: String,
        val instanceNumber: Int,
        val position: Triple<Double, Double, Double>,
        val orientation: List<Double> = listOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0),
        val spacing: Pair<Double, Double> = 0.5 to 0.5,
        val forUid: String = FOR,
        val seriesUid: String = SERIES,
        val multiframe: Boolean = false,
        val omitGeometry: Boolean = false,
        val customTransferSyntax: String? = null,
        val encapsulatedPixel: Boolean = false,
        val badSpacingText: String? = null
    )

    fun generate(root: Path) {
        Files.createDirectories(root)

        // Baseline: 6 axial slices, regular 5 mm spacing, deliberately shuffled InstanceNumbers.
        val baseline = (0 until 6).map { k ->
            SliceSpec(
                file = "slice_%02d.dcm".format(k + 1),
                sop = "1.2.826.0.1.534141.9999.0001.sop.b%02d".format(k + 1),
                instanceNumber = listOf(6, 3, 1, 5, 2, 4)[k],
                position = Triple(0.0, 0.0, k * 5.0)
            )
        }
        writeVariant(root.resolve("00-baseline"), baseline,
            title = "基线：完整 tag、规则间距、InstanceNumber 与空间序故意不一致")

        // 1) direction non-orthogonal: one slice orientation row/col dot product far from zero.
        writeVariant(
            root.resolve("01-nonorthogonal-direction"),
            baseline.toMutableList().also {
                it[3] = it[3].copy(
                    orientation = listOf(1.0, 0.0, 0.0, 0.3, 0.9539, 0.0),
                    file = "slice_04_nonortho.dcm"
                )
            },
            title = "方向非正交：第 4 张方向向量被改为非正交"
        )

        // 2) duplicate position: slice 3 and 4 share the same Z (duplicate slice).
        writeVariant(
            root.resolve("02-duplicate-position"),
            baseline.toMutableList().also {
                it[3] = it[3].copy(position = Triple(0.0, 0.0, 10.0), file = "slice_04_dup.dcm")
            },
            title = "重复位置：第 4 张与第 3 张投影重合"
        )

        // 3) local spacing jump: last gap becomes 25 mm instead of 5 mm.
        writeVariant(
            root.resolve("03-local-spacing-jump"),
            baseline.toMutableList().also {
                it[5] = it[5].copy(position = Triple(0.0, 0.0, 50.0), file = "slice_06_gap.dcm")
            },
            title = "局部间距跳变：最后一段间距 5→45 mm"
        )

        // 4) multiframe object: one enhanced-MR style instance with per-frame functional groups.
        writeMultiframe(root.resolve("04-multiframe-functional-group"))

        // 5) UID conflict: two distinct materials carry the same SOP Instance UID.
        run {
            val dir = root.resolve("05-uid-conflict")
            val list = baseline.toMutableList()
            writeOne(dir, list[4], list[4].file)
            val conflicting = list[4].copy(
                position = Triple(0.0, 0.0, 22.0),
                file = "slice_05_uid_conflict_duplicate.dcm"
            )
            writeOne(dir, conflicting, conflicting.file)
            list.forEachIndexed { idx, spec -> if (idx != 4) writeOne(dir, spec, spec.file) }
            writeManifest(dir, "UID 冲突：两个不同字节的对象共享同一 SOP Instance UID")
        }

        // 6) missing geometry: one slice lacks IPP/IOP/PixelSpacing entirely.
        writeVariant(
            root.resolve("06-missing-geometry"),
            baseline.toMutableList().also {
                it[2] = it[2].copy(omitGeometry = true, file = "slice_03_no_geometry.dcm")
            },
            title = "缺失几何：第 3 张完全缺少 IPP/IOP/PixelSpacing"
        )

        // 7) unsupported transfer syntax: JPEG2000 encapsulated, metadata intact, pixels retained but not decoded.
        writeVariant(
            root.resolve("07-transfer-syntax-unsupported"),
            baseline.toMutableList().also {
                it[4] = it[4].copy(
                    customTransferSyntax = "1.2.840.10008.1.2.4.80",
                    encapsulatedPixel = true,
                    file = "slice_05_jpeg2000.dcm"
                )
            },
            title = "不支持的传输语法：JPEG2000 封装像素，仅解析元数据"
        )

        // 8) unconvertible value: malformed PixelSpacing text stays in place with diagnosis.
        writeVariant(
            root.resolve("08-unconvertible-spacing"),
            baseline.toMutableList().also {
                it[1] = it[1].copy(badSpacingText = "0.5/NA", file = "slice_02_bad_spacing.dcm")
            },
            title = "无法规范化的值：PixelSpacing 含非数值项，原值保留不用于画图"
        )

        writeManifestIndex(root)
    }

    private fun writeVariant(dir: Path, specs: List<SliceSpec>, title: String) {
        Files.createDirectories(dir)
        specs.forEach { writeOne(dir, it, it.file) }
        writeManifest(dir, title)
    }

    private fun writeOne(dir: Path, spec: SliceSpec, fileName: String) {
        val options = DicomWriter.WriteOptions(
            transferSyntaxUID = spec.customTransferSyntax ?: "1.2.840.10008.1.2.1",
            encapsulated = spec.encapsulatedPixel
        )
        val bytes = DicomWriter.write(options) {
            ui(DicomTags.SOPClassUID, "1.2.840.10008.5.1.4.1.1.2")
            ui(DicomTags.SOPInstanceUID, spec.sop)
            ui(DicomTags.StudyInstanceUID, STUDY)
            ui(DicomTags.SeriesInstanceUID, spec.seriesUid)
            ui(DicomTags.FrameOfReferenceUID, spec.forUid)
            is_(DicomTags.InstanceNumber, spec.instanceNumber.toString())
            us(DicomTags.Rows, 64)
            us(DicomTags.Columns, 64)
            if (!spec.omitGeometry) {
                val (x, y, z) = spec.position
                ds(DicomTags.ImagePositionPatient, "%.3f\\%.3f\\%.3f".format(x, y, z))
                ds(DicomTags.ImageOrientationPatient, spec.orientation.joinToString("\\") { "%.6f".format(it) })
                val spacingText = spec.badSpacingText
                    ?: "${"%.3f".format(spec.spacing.first)}\\${"%.3f".format(spec.spacing.second)}"
                ds(DicomTags.PixelSpacing, spacingText)
            }
            if (spec.encapsulatedPixel) {
                // fake JPEG2000 codestream fragment so the parser exercises encapsulated handling
                val frag = ByteArray(128) { (0x30 + (it % 80)).toByte() }
                System.arraycopy(byteArrayOf(0x00, 0x00, 0x00, 0x0C, 0x6A, 0x50, 0x20, 0x20), 0, frag, 0, 8)
                encapsulatedPixel(listOf(frag))
            } else {
                rawTag(DicomTags.PixelData, "OW", ByteArray(64 * 64) { ((it * 7) and 0xFF).toByte() })
            }
        }
        Files.write(dir.resolve(fileName), bytes)
    }

    private fun writeMultiframe(dir: Path) {
        Files.createDirectories(dir)
        val sharedSop = "1.2.826.0.1.534141.9999.0001.sop.mf01"
        val bytes = DicomWriter.write {
            ui(DicomTags.SOPClassUID, "1.2.840.10008.5.1.4.1.1.4.1")
            ui(DicomTags.SOPInstanceUID, sharedSop)
            ui(DicomTags.StudyInstanceUID, STUDY)
            ui(DicomTags.SeriesInstanceUID, SERIES)
            ui(DicomTags.FrameOfReferenceUID, FOR)
            is_(DicomTags.InstanceNumber, "99")
            us(DicomTags.Rows, 64); us(DicomTags.Columns, 64)
            is_(DicomTags.NumberOfFrames, "5")
            sequence(DicomTags.SharedFunctionalGroupsSequence) {
                item {
                    sequence(DicomTags.PixelMeasuresSequence) {
                        item { ds(DicomTags.PixelSpacing, "0.500\\0.500") }
                    }
                    sequence(DicomTags.PlaneOrientationSequence) {
                        item { ds(DicomTags.ImageOrientationPatient, "1\\0\\0\\0\\1\\0") }
                    }
                }
            }
            sequence(DicomTags.PerFrameFunctionalGroupsSequence) {
                (0 until 5).forEach { k ->
                    item {
                        sequence(DicomTags.PlanePositionSequence) {
                            item { ds(DicomTags.ImagePositionPatient, "0.000\\0.000\\%.3f".format(k * 5.0)) }
                        }
                    }
                }
            }
            rawTag(DicomTags.PixelData, "OW", ByteArray(64 * 64 * 5) { ((it * 3) and 0xFF).toByte() })
        }
        Files.write(dir.resolve("multiframe_5frames.dcm"), bytes)
        // add one classic single-frame slice in a different FrameOfReference (must never merge)
        val otherFor = FOR.replace("for01", "for02")
        writeOne(dir, SliceSpec(
            file = "other_for_slice.dcm",
            sop = "1.2.826.0.1.534141.9999.0001.sop.otherfor",
            instanceNumber = 1,
            position = Triple(0.0, 0.0, 0.0),
            forUid = otherFor,
            seriesUid = SERIES
        ), "other_for_slice.dcm")
        writeManifest(dir, "多帧对象（per-frame functional group）+ 不同 FrameOfReference 不强合")
    }

    private fun writeManifest(dir: Path, title: String) {
        val text = """# ${dir.fileName}

$title

导入方式：复核页“导入整批”，选择本目录。
"""
        Files.write(dir.resolve("CASE.md"), text.toByteArray())
    }

    private fun writeManifestIndex(root: Path) {
        val index = """# DICOM 体系标定台：切片几何、FrameOfReference 与序列多解（离线案例包）

按编号依次导入到独立分支，即可重放全部边界情形：

1. `00-baseline` — 完整基线（UID/IPP/IOP/PixelSpacing/functional group/传输语法/原 tag 保留）
2. `01-nonorthogonal-direction` — 方向非正交
3. `02-duplicate-position` — 重复位置（产生重片分支）
4. `03-local-spacing-jump` — 局部间距跳变（产生分段候选）
5. `04-multiframe-functional-group` — 多帧对象 + 不同 FoR 不强合
6. `05-uid-conflict` — SOP UID 冲突，不自动去重
7. `06-missing-geometry` — 缺失几何
8. `07-transfer-syntax-unsupported` — 不支持的传输语法（像素保留不臆造）
9. `08-unconvertible-spacing` — 无法规范化的值留在原位置

所有几何量都带单位/坐标基准；导入层保留原始表示，派生层才规范化。
"""
        Files.write(root.resolve("README.md"), index.toByteArray())
    }
}
