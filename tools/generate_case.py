#!/usr/bin/env python3
"""生成 DICOM 体系标定台离线案例包（去标识化合成数据，非真实患者数据）。

输出到 src/main/resources/cases/dicom-geometry-bench/：
  baseline/    8 个几何一致的切片（InstanceNumber 故意打乱）
  variants/    7 个边界文件：方向非正交 / 重复位置 / 局部间距跳变(序列B) /
               多帧对象 / UID 冲突 / 缺失几何 / transfer syntax 不支持
  amendments/  1 个局部源数据修订（修正序列B末片间距）
  case.json    叙事化分阶段场景
  archive/expectations.json  机器可读的判定预期（反例档案）
"""
import json, base64, hashlib, os, shutil

ROOT = "src/main/resources/cases/dicom-geometry-bench"
UID = "1.2.826.0.1.3680043.9.7433"
FOR_UID = UID + ".1000"
SERIES_A = UID + ".10"   # 基线序列
SERIES_B = UID + ".20"   # 间距跳变序列
SERIES_C = UID + ".30"   # 多帧对象
SERIES_D = UID + ".40"   # 不支持的传输语法
TS_EXPLICIT_LE = "1.2.840.10008.1.2.1"
TS_JPEG2000 = "1.2.840.10008.1.2.4.90"  # 本系统不支持的像素编码

def tag(g, e, vr, value=None, items=None):
    t = {"g": g, "e": e, "vr": vr}
    if value is not None:
        t["value"] = value
    if items is not None:
        t["items"] = items
    return t

def pixel(seed: str) -> str:
    data = b""
    i = 0
    while len(data) < 64:
        data += hashlib.sha256(f"{seed}:{i}".encode()).digest()
        i += 1
    return base64.b64encode(data[:64]).decode()

def instance(file_name, sop, series, ipp, iop="1\\0\\0\\0\\1\\0",
             spacing="0.703125\\0.703125", inst_num=1, ts=TS_EXPLICIT_LE,
             for_uid=FOR_UID, extra=None, omit=(), seed=None, amends=None):
    tags = [
        tag("0008", "0018", "UI", sop),          # SOPInstanceUID
        tag("0020", "000E", "UI", series),       # SeriesInstanceUID
        tag("0020", "0052", "UI", for_uid),      # FrameOfReferenceUID
        tag("0020", "0011", "IS", str(100)),     # SeriesNumber
        tag("0020", "0013", "IS", str(inst_num)),# InstanceNumber
        tag("0028", "0010", "US", "4"),          # Rows
        tag("0028", "0011", "US", "4"),          # Columns
    ]
    if spacing is not None:
        tags.append(tag("0028", "0030", "DS", spacing))
    if ipp is not None:
        tags.append(tag("0020", "0032", "DS", ipp))
    if iop is not None:
        tags.append(tag("0020", "0037", "DS", iop))
    if extra:
        tags.extend(extra)
    tags = [t for t in tags if (t["g"], t["e"]) not in omit]
    obj = {
        "fileName": file_name,
        "transferSyntaxUid": ts,
        "pixelDataBase64": pixel(seed or file_name),
        "tags": tags,
    }
    if amends:
        obj["amends"] = amends
    return obj

def write(path, obj):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8") as f:
        json.dump(obj, f, ensure_ascii=False, indent=2)
        f.write("\n")

def main():
    shutil.rmtree(ROOT, ignore_errors=True)
    # ---- baseline: z = 0..17.5 step 2.5mm, InstanceNumber 故意打乱 ----
    shuffled = [7, 2, 8, 1, 5, 3, 6, 4]
    baseline_files = []
    for i in range(8):
        z = i * 2.5
        name = f"baseline/inst-{i+1:02d}.json"
        obj = instance(
            f"inst-{i+1:02d}.dcm", f"{UID}.1.{i+1}", SERIES_A,
            ipp=f"-90\\-90\\{z}", inst_num=shuffled[i], seed=f"baseline-{i}")
        write(os.path.join(ROOT, name), obj)
        baseline_files.append(name)

    # ---- variants ----
    v = {}
    v["non-orthogonal"] = "variants/non-orthogonal.json"
    write(os.path.join(ROOT, v["non-orthogonal"]), instance(
        "non-orthogonal.dcm", f"{UID}.1.9", SERIES_A,
        ipp="-90\\-90\\20", iop="1\\0\\0\\0.2\\0.979795897\\0", inst_num=9))
    v["duplicate-position"] = "variants/duplicate-position.json"
    write(os.path.join(ROOT, v["duplicate-position"]), instance(
        "duplicate-position.dcm", f"{UID}.1.10", SERIES_A,
        ipp="-90\\-90\\7.5", inst_num=10))
    spacing_files = []
    for i, z in enumerate([0, 2.5, 5.0, 12.5]):
        name = f"variants/spacing-jump-{i+1:02d}.json"
        write(os.path.join(ROOT, name), instance(
            f"spacing-jump-{i+1:02d}.dcm", f"{UID}.2.{i+1}", SERIES_B,
            ipp=f"-90\\-90\\{z}", inst_num=i + 1, seed=f"jump-{i}"))
        spacing_files.append(name)
    # 多帧对象：3 帧，几何只在 PerFrameFunctionalGroups 中
    frames_fg = []
    for k, z in enumerate([20.0, 22.5, 25.0]):
        frames_fg.append([
            tag("0020", "9113", "SQ", items=[[tag("0020", "0032", "DS", f"-90\\-90\\{z}")]]),
            tag("0020", "9116", "SQ", items=[[tag("0020", "0037", "DS", "1\\0\\0\\0\\1\\0")]]),
        ])
    v["multiframe"] = "variants/multiframe.json"
    write(os.path.join(ROOT, v["multiframe"]), instance(
        "multiframe.dcm", f"{UID}.3.1", SERIES_C, ipp=None, iop=None,
        inst_num=1, extra=[
            tag("0028", "0008", "IS", "3"),
            tag("5200", "9230", "SQ", items=frames_fg),
        ]))
    # UID 冲突：与 inst-01 相同 SOPInstanceUID，不同像素内容
    v["uid-conflict"] = "variants/uid-conflict.json"
    write(os.path.join(ROOT, v["uid-conflict"]), instance(
        "uid-conflict.dcm", f"{UID}.1.1", SERIES_A,
        ipp="-90\\-90\\0", inst_num=1, seed="conflicting-pixels"))
    # 缺失几何
    v["missing-geometry"] = "variants/missing-geometry.json"
    write(os.path.join(ROOT, v["missing-geometry"]), instance(
        "missing-geometry.dcm", f"{UID}.1.11", SERIES_A,
        ipp=None, iop=None, inst_num=11))
    # 不支持的传输语法（像素不可解码，元数据保留）
    v["unsupported-ts"] = "variants/unsupported-ts.json"
    write(os.path.join(ROOT, v["unsupported-ts"]), instance(
        "unsupported-ts.dcm", f"{UID}.4.1", SERIES_D,
        ipp="-90\\-90\\0", inst_num=1, ts=TS_JPEG2000))

    # ---- amendment：修正 spacing-jump-04 的位置 12.5 -> 7.5 ----
    amend = "amendments/spacing-jump-04-v2.json"
    write(os.path.join(ROOT, amend), instance(
        "spacing-jump-04-v2.dcm", f"{UID}.2.4", SERIES_B,
        ipp="-90\\-90\\7.5", inst_num=4, seed="jump-3-v2",
        amends="spacing-jump-04.json"))

    case = {
        "caseId": "dicom-geometry-bench",
        "title": "DICOM 体系标定台：切片几何、FrameOfReference 与序列多解",
        "narrative": "医学影像研究组导入去标识的 DICOM metadata 与像素文件，按空间几何组成可复核的 series/volume 候选。",
        "coordinateBasis": "DICOM 患者坐标系（LPS），单位 mm；基准由 FrameOfReferenceUID 标识",
        "stages": [
            {"id": "stage-0-baseline", "kind": "import",
             "label": "初始导入：基线序列 A（8 片，InstanceNumber 已打乱）",
             "files": baseline_files},
            {"id": "stage-1-variants", "kind": "import",
             "label": "触发边界组合：非正交/重片/间距跳变/多帧/UID冲突/缺几何/TS不支持",
             "files": [v["non-orthogonal"], v["duplicate-position"]] + spacing_files +
                      [v["multiframe"], v["uid-conflict"], v["missing-geometry"], v["unsupported-ts"]]},
            {"id": "stage-2-param", "kind": "paramRevision",
             "label": "参数修订：方向正交容差 0.001 → 0.3",
             "params": {"orthogonalityTolerance": 0.3}},
            {"id": "stage-3-amend", "kind": "import",
             "label": "局部源数据改动：修正序列 B 末片位置 12.5 → 7.5",
             "files": [amend]},
            {"id": "stage-4-adjudicate", "kind": "manual",
             "label": "人工裁定：对候选做接受/否定（可在两个窗口制造版本冲突）"},
        ],
    }
    write(os.path.join(ROOT, "case.json"), case)

    expectations = {
        "principles": [
            "InstanceNumber 不当空间排序真值",
            "方向向量先检查正交与容差",
            "局部间距变化、重片和不同 FrameOfReference 产生分支而不强合",
        ],
        "diagnostics": [
            {"file": v["non-orthogonal"], "code": "NON_ORTHOGONAL", "stage": "stage-1-variants"},
            {"file": v["duplicate-position"], "code": "DUPLICATE_POSITION", "stage": "stage-1-variants"},
            {"file": "variants/spacing-jump-04.json", "code": "SPACING_JUMP", "stage": "stage-1-variants"},
            {"file": v["multiframe"], "code": "MULTI_FRAME_EXPANDED", "stage": "stage-1-variants"},
            {"file": v["uid-conflict"], "code": "UID_CONFLICT", "stage": "stage-1-variants"},
            {"file": v["missing-geometry"], "code": "MISSING_GEOMETRY", "stage": "stage-1-variants"},
            {"file": v["unsupported-ts"], "code": "UNSUPPORTED_TRANSFER_SYNTAX", "stage": "stage-1-variants"},
        ],
        "invalidation": [
            {"stage": "stage-2-param",
             "affectedPartitions": [f"{SERIES_A}|{FOR_UID}"],
             "unaffectedPartitions": [f"{SERIES_B}|{FOR_UID}", f"{SERIES_C}|{FOR_UID}", f"{SERIES_D}|{FOR_UID}"]},
            {"stage": "stage-3-amend",
             "affectedPartitions": [f"{SERIES_B}|{FOR_UID}"],
             "unaffectedPartitions": [f"{SERIES_A}|{FOR_UID}", f"{SERIES_C}|{FOR_UID}"]},
        ],
        "baselineOrder": {
            "partition": f"{SERIES_A}|{FOR_UID}",
            "projectionCoordinatesMm": [0.0, 2.5, 5.0, 7.5, 10.0, 12.5, 15.0, 17.5],
            "note": "按投影坐标排序；InstanceNumber [7,2,8,1,5,3,6,4] 不得作为排序真值",
        },
    }
    write(os.path.join(ROOT, "archive", "expectations.json"), expectations)
    print("case package generated at", ROOT)

if __name__ == "__main__":
    main()
