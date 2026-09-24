#!/usr/bin/env python3
"""Generate the offline case package: 'DICOM 体系标定台: 切片几何、FrameOfReference 与序列多解'.

Writes real DICOM PS3.10 files (Explicit VR Little Endian dataset) plus a
machine-readable manifest (case.json) describing the baseline, the single-source
variants and the scripted scenario (param revision, local source amendment,
conflicting human decisions).
"""
import json, os, struct, hashlib, shutil

ROOT = os.path.join(os.path.dirname(__file__), "..", "cases", "dicom-geometry-calibration-bench")

EXPLICIT_LE = "1.2.840.10008.1.2.1"
JPEG2000 = "1.2.840.10008.1.2.4.90"
CT_IMAGE_STORAGE = "1.2.840.10008.5.1.4.1.1.2"
ENHANCED_CT_STORAGE = "1.2.840.10008.5.1.4.1.1.2.1"
IMPL_CLASS = "1.2.826.0.1.3680043.9.7433.1"

def vr_explicit(tag, vr, value_bytes):
    g, e = tag
    out = struct.pack("<HH", g, e) + vr.encode()
    if vr in ("OB", "OW", "OF", "SQ", "UT", "UN"):
        out += b"\x00\x00" + struct.pack("<I", len(value_bytes))
    else:
        out += struct.pack("<H", len(value_bytes))
    return out + value_bytes

def ui(tag, s):  return vr_explicit(tag, "UI", s.encode() + b"\x00" * (len(s) % 2 == 1 and 1 or 0))
def _pad(b, n):  return b + b" " * ((n - len(b)) % n if len(b) % 2 else 0)
def str_vr(tag, vr, s):
    b = s.encode()
    if len(b) % 2: b += b"\x00" if vr == "UI" else b" "
    return vr_explicit(tag, vr, b)
def us(tag, *vals): return vr_explicit(tag, "US", struct.pack("<" + "H"*len(vals), *vals))
def ul(tag, *vals): return vr_explicit(tag, "UL", struct.pack("<" + "I"*len(vals), *vals))
def sq(tag, items_bytes):
    body = b"".join(items_bytes)
    return vr_explicit(tag, "SQ", body)
def item(content): return struct.pack("<HHI", 0xFFFE, 0xE000, len(content)) + content

def pixel_data(seed, rows=8, cols=8):
    vals = [(seed * 37 + i * 11) % 2048 for i in range(rows * cols)]
    return vr_explicit((0x7FE0, 0x0010), "OW", struct.pack("<" + "H"*len(vals), *vals))

def dicom_file(sop_uid, series_uid, for_uid, instance_no, ipp, iop, pix_spacing,
               transfer=EXPLICIT_LE, sop_class=CT_IMAGE_STORAGE, extra=b"", seed=1,
               include_geometry=True, pixel_seed=None):
    meta = b""
    meta += ui((0x0002, 0x0001), "\x00\x01")
    meta += str_vr((0x0002, 0x0002), "UI", sop_class)
    meta += str_vr((0x0002, 0x0003), "UI", sop_uid)
    meta += str_vr((0x0002, 0x0010), "UI", transfer)
    meta += str_vr((0x0002, 0x0012), "UI", IMPL_CLASS)
    meta = ul((0x0002, 0x0000), len(meta)) + meta
    ds = b""
    ds += str_vr((0x0008, 0x0016), "UI", sop_class)
    ds += str_vr((0x0008, 0x0018), "UI", sop_uid)
    ds += str_vr((0x0008, 0x0020), "DA", "20240115")
    ds += str_vr((0x0010, 0x0010), "PN", "ANON^RESEARCH")
    ds += str_vr((0x0010, 0x0020), "LO", "ANON-001")
    ds += str_vr((0x0020, 0x000D), "UI", "1.2.826.0.1.3680043.9.7433.2.1")
    ds += str_vr((0x0020, 0x000E), "UI", series_uid)
    if for_uid:
        ds += str_vr((0x0020, 0x0052), "UI", for_uid)
    if instance_no is not None:
        ds += str_vr((0x0020, 0x0013), "IS", str(instance_no))
    if include_geometry:
        ds += str_vr((0x0020, 0x0032), "DS", "\\".join(f"{v:.6f}" for v in ipp))
        ds += str_vr((0x0020, 0x0037), "DS", "\\".join(f"{v:.6f}" for v in iop))
        ds += str_vr((0x0028, 0x0030), "DS", "\\".join(f"{v:.6f}" for v in pix_spacing))
    ds += us((0x0028, 0x0010), 8) + us((0x0028, 0x0011), 8)
    ds += us((0x0028, 0x0100), 16) + us((0x0028, 0x0101), 12) + us((0x0028, 0x0102), 11) + us((0x0028, 0x0103), 0)
    ds += str_vr((0x0028, 0x0004), "CS", "MONOCHROME2")
    ds += extra
    ds += pixel_data(pixel_seed if pixel_seed is not None else seed)
    return b"\x00" * 128 + b"DICM" + meta + ds

def multiframe_extra(positions):
    frames = b""
    for p in positions:
        pps = sq((0x0020, 0x9113), [item(str_vr((0x0020, 0x0032), "DS", "\\".join(f"{v:.6f}" for v in p)))])
        frames += item(pps)
    ds = str_vr((0x0028, 0x0008), "IS", str(len(positions)))
    ds += sq((0x5200, 0x9230), [frames[i:i+1] for i in range(0)])  # placeholder replaced below
    return ds

def write(path, content):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "wb") as f: f.write(content)

def main():
    if os.path.exists(ROOT): shutil.rmtree(ROOT)
    os.makedirs(ROOT)
    S = "1.2.826.0.1.3680043.9.7433.10"   # series uid base
    F = "1.2.826.0.1.3680043.9.7433.20"   # frame of reference base
    U = "1.2.826.0.1.3680043.9.7433.30"   # sop uid base
    IOP = [1, 0, 0, 0, 1, 0]
    PS = [0.703125, 0.703125]

    # ---- baseline: 6 slices, 1.0 mm spacing, consistent geometry ----
    for i in range(6):
        write(f"{ROOT}/baseline/slice_{i+1:02d}.dcm",
              dicom_file(f"{U}.1.{i+1}", f"{S}.1", f"{F}.1", i + 1,
                         [-90.0, -120.0, -50.0 + i * 1.0], IOP, PS, seed=10 + i))

    # ---- variants: each changes exactly one direct source ----
    # 1. orientation non-orthogonal: row·col = 0.2 (beyond tolerance)
    iop_bad = [1, 0, 0, 0.2, 0.98, 0]
    for i in range(4):
        write(f"{ROOT}/variants/orientation_non_orthogonal/slice_{i+1:02d}.dcm",
              dicom_file(f"{U}.2.{i+1}", f"{S}.2", f"{F}.1", i + 1,
                         [-90.0, -120.0, -50.0 + i * 1.0], iop_bad, PS, seed=20 + i))

    # 2. duplicate position: slices 2 and 3 share the same ImagePositionPatient
    for i in range(4):
        z = -50.0 + (i if i < 2 else i - 1) * 1.0   # i=1,2 -> same z
        write(f"{ROOT}/variants/duplicate_position/slice_{i+1:02d}.dcm",
              dicom_file(f"{U}.3.{i+1}", f"{S}.3", f"{F}.1", i + 1,
                         [-90.0, -120.0, z], IOP, PS, seed=30 + i))

    # 3. local spacing jump: gaps 1,1,3,1 mm
    zs, z = [], -50.0
    for gap in [0, 1, 1, 3, 1]:
        z += gap; zs.append(z)
    for i in range(5):
        write(f"{ROOT}/variants/spacing_jump/slice_{i+1:02d}.dcm",
              dicom_file(f"{U}.4.{i+1}", f"{S}.4", f"{F}.1", i + 1,
                         [-90.0, -120.0, zs[i]], IOP, PS, seed=40 + i))

    # 4. multiframe object: 3 frames via PerFrameFunctionalGroupsSequence
    frames = b""
    for k, zz in enumerate([-50.0, -49.0, -48.0]):
        pps = sq((0x0020, 0x9113), [item(str_vr((0x0020, 0x0032), "DS",
                 "\\".join(f"{v:.6f}" for v in [-90.0, -120.0, zz])))])
        frames += item(pps)
    extra = str_vr((0x0028, 0x0008), "IS", "3") + sq((0x5200, 0x9230), [frames])
    write(f"{ROOT}/variants/multiframe_object/enhanced_01.dcm",
          dicom_file(f"{U}.5.1", f"{S}.5", f"{F}.1", 1, [-90.0, -120.0, -50.0], IOP, PS,
                     sop_class=ENHANCED_CT_STORAGE, extra=extra, seed=50))

    # 5. uid conflict: same SOPInstanceUID, different pixel content; and one
    #    series uid reused across two different FrameOfReferenceUIDs
    for i in range(2):
        write(f"{ROOT}/variants/uid_conflict/a_{i+1}.dcm",
              dicom_file(f"{U}.6.1", f"{S}.6", f"{F}.1", i + 1,
                         [-90.0, -120.0, -50.0 - i], IOP, PS, pixel_seed=61 + i))
    write(f"{ROOT}/variants/uid_conflict/b_1.dcm",
          dicom_file(f"{U}.6.9", f"{S}.6", f"{F}.2", 1,
                     [-90.0, -120.0, -50.0], IOP, PS, seed=63))

    # 6. missing geometry: no IPP / IOP / PixelSpacing
    write(f"{ROOT}/variants/missing_geometry/slice_01.dcm",
          dicom_file(f"{U}.7.1", f"{S}.7", f"{F}.1", 1, None, None, None,
                     include_geometry=False, seed=70))

    # 7. unsupported transfer syntax: JPEG 2000 (encapsulated), raw preserved
    write(f"{ROOT}/variants/unsupported_transfer_syntax/slice_01.dcm",
          dicom_file(f"{U}.8.1", f"{S}.8", f"{F}.1", 1,
                     [-90.0, -120.0, -50.0], IOP, PS, transfer=JPEG2000, seed=80))

    # ---- scenario step 3: local source amendment (replacement for baseline slice 3) ----
    write(f"{ROOT}/scenario/03_source_amend/slice_03_v2.dcm",
          dicom_file(f"{U}.1.3", f"{S}.1", f"{F}.1", 3,
                     [-90.0, -120.0, -48.25], IOP, PS, seed=99))

    manifest = {
        "case_id": "dicom-geometry-calibration-bench",
        "title": "DICOM 体系标定台：切片几何、FrameOfReference 与序列多解",
        "narrative": "医学影像研究组导入去标识的 DICOM metadata 与像素文件，按空间几何组成可复核的 series/volume 候选",
        "baseline": {
            "dir": "baseline",
            "series_instance_uid": f"{S}.1",
            "frame_of_reference_uid": f"{F}.1",
            "slices": 6, "spacing_mm": 1.0,
            "pixel_spacing_mm": PS,
            "preserved": ["SOPInstanceUID", "SeriesInstanceUID", "FrameOfReferenceUID",
                           "ImagePositionPatient", "ImageOrientationPatient", "PixelSpacing",
                           "PerFrameFunctionalGroupsSequence", "TransferSyntaxUID", "raw_tags"]
        },
        "variants": [
            {"dir": "variants/orientation_non_orthogonal", "kind": "orientation_non_orthogonal",
             "changed_source": "ImageOrientationPatient", "expect": "row·col = 0.196 > ortho_tol"},
            {"dir": "variants/duplicate_position", "kind": "duplicate_position",
             "changed_source": "ImagePositionPatient", "expect": "two frames share projection coordinate"},
            {"dir": "variants/spacing_jump", "kind": "spacing_jump",
             "changed_source": "ImagePositionPatient", "expect": "local gap 3.0mm vs nominal 1.0mm"},
            {"dir": "variants/multiframe_object", "kind": "multiframe_object",
             "changed_source": "PerFrameFunctionalGroupsSequence", "expect": "3 frames from one object"},
            {"dir": "variants/uid_conflict", "kind": "uid_conflict",
             "changed_source": "SOPInstanceUID/FrameOfReferenceUID",
             "expect": "same SOP uid different pixels; same series uid across two FoR"},
            {"dir": "variants/missing_geometry", "kind": "missing_geometry",
             "changed_source": "ImagePositionPatient/ImageOrientationPatient/PixelSpacing",
             "expect": "instance kept in place with diagnostic, excluded from spatial sort"},
            {"dir": "variants/unsupported_transfer_syntax", "kind": "unsupported_transfer_syntax",
             "changed_source": "TransferSyntaxUID", "expect": "raw bytes preserved, pixel decode skipped"}
        ],
        "scenario": [
            {"step": 1, "kind": "baseline_import", "dir": "baseline"},
            {"step": 2, "kind": "param_revision",
             "change": {"spacing_jump_tol_mm": [0.25, 2.5]},
             "expect": "invalidation limited to candidates whose residuals depend on spacing tolerance"},
            {"step": 3, "kind": "source_amend", "dir": "scenario/03_source_amend",
             "amends_sop_instance_uid": f"{U}.1.3",
             "expect": "only candidates containing that instance are invalidated"},
            {"step": 4, "kind": "conflicting_decisions",
             "expect": "second submitter sees version conflict with both diffs"}
        ],
        "rules": {
            "instance_number_not_spatial_truth": True,
            "orientation_orthogonality_checked_first": True,
            "ortho_tol": 0.01,
            "spacing_jump_tol_mm": 0.25,
            "duplicate_position_tol_mm": 0.01,
            "branches_not_merges": ["spacing_jump", "duplicate_position", "frame_of_reference_mismatch"]
        }
    }
    write(f"{ROOT}/case.json", json.dumps(manifest, ensure_ascii=False, indent=2).encode())
    print("case package written to", os.path.abspath(ROOT))

if __name__ == "__main__":
    main()
