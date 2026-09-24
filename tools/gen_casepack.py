#!/usr/bin/env python3
"""离线案例包生成器: DICOM 体系标定台——切片几何、FrameOfReference 与序列多解。

去标识合成材料: 只保留 DICOM metadata 字符串与占位像素字节。
每个变体相对 baseline 只改动一个直接来源。
"""
import json, os, struct, shutil

ROOT = os.path.join(os.path.dirname(__file__), "..", "casepack")
STUDY = "1.2.826.0.1.3680043.9.7433.1"
SERIES = STUDY + ".1"
FOR = STUDY + ".0.1"
FOR2 = STUDY + ".0.2"
TS_LEI = "1.2.840.10008.1.2.1"
TS_JP2 = "1.2.840.10008.1.2.4.90"

def sop(n): return f"{SERIES}.{n}"

def write_pixel(path, seed):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    # 16x16 单帧占位像素, 16-bit; 内容仅用于哈希区分, 不构成真实影像
    rows = []
    for y in range(16):
        for x in range(16):
            rows.append((seed * 73 + x * 17 + y * 31) & 0xFFFF)
    with open(path, "wb") as f:
        f.write(struct.pack("<" + "H" * len(rows), *rows))

def tags(sop_uid, z, *, ipp=True, iop=(1.0,0,0,0,1,0), inst_no=None,
         for_uid=FOR, ts=TS_LEI, series=SERIES, spacing=0.703125):
    t = {
        "0008,0005": "ISO_IR 100",
        "0008,0016": "1.2.840.10008.5.1.4.1.1.2",
        "0008,0018": sop_uid,
        "0008,0060": "CT",
        "0020,000D": STUDY,
        "0020,000E": series,
        "0020,0013": str(inst_no if inst_no is not None else int(sop_uid.split(".")[-1])),
        "0020,0052": for_uid,
        "0028,0010": "16",
        "0028,0011": "16",
        "0028,0030": f"{spacing}\\{spacing}",
        "0018,0050": "1.0",
    }
    if ipp:
        t["0020,0032"] = f"-120.0\\-120.0\\{z:.1f}"
        t["0020,0037"] = "\\".join(str(v) for v in iop)
    return t

def inst_obj(t, ts=TS_LEI, pixel="px/0001.raw", frames=None):
    o = {"transferSyntaxUID": ts, "pixelFile": pixel}
    if frames is not None:
        o["frames"] = frames  # 多帧 functional group (Per-Frame PlanePosition/PlaneOrientation)
    o["tags"] = t
    return o

def write_case(case_id, files):
    """files: dict relpath -> obj; pixel bytes generated deterministically per file."""
    d = os.path.join(ROOT, case_id)
    shutil.rmtree(d, ignore_errors=True)
    for rel, obj in files.items():
        p = os.path.join(d, rel)
        os.makedirs(os.path.dirname(p), exist_ok=True)
        px = obj.pop("_pxseed", None)
        if px is not None:
            pxrel = obj["pixelFile"]
            write_pixel(os.path.join(d, pxrel), px)
        with open(p, "w", encoding="utf-8") as f:
            json.dump(obj, f, ensure_ascii=False, indent=1, sort_keys=True)

def slice_files(zs, case_id, *, inst_numbers=None, tag_overrides=None,
                for_uids=None, tss=None, drop_geom=(), iops=None, seed0=100):
    files = {}
    for k, z in enumerate(zs, start=1):
        uid = sop(k)
        ipp = k not in drop_geom
        kw = {}
        if iops and k in iops: kw["iop"] = iops[k]
        if for_uids and k in for_uids: kw["for_uid"] = for_uids[k]
        if inst_numbers is not None: kw["inst_no"] = inst_numbers[k-1]
        t = tags(uid, z, ipp=ipp, **kw)
        ts = tss.get(k, TS_LEI) if tss else TS_LEI
        obj = inst_obj(t, ts=ts, pixel=f"px/{k:04d}.raw")
        obj["_pxseed"] = seed0 + k
        if tag_overrides and k in tag_overrides:
            t.update(tag_overrides[k])
        files[f"inst/{k:04d}.json"] = obj
    return files

def main():
    if os.path.exists(ROOT): shutil.rmtree(ROOT)
    os.makedirs(ROOT)

    # 1) baseline: 8 张等间距 1.0mm 轴位切片
    write_case("baseline", slice_files([float(z) for z in range(8)], "baseline"))

    # 2) 方向非正交: row·col = 0.2 > 容差
    write_case("nonorthogonal", slice_files(
        [float(z) for z in range(8)], "nonorthogonal",
        iops={k: (1,0,0,0.2,1,0) for k in range(1,9)}, seed0=200))

    # 3) 重复位置: 第 4、5 张 t=3.0 重片
    write_case("duplicate-position", slice_files(
        [0,1,2,3,3,4,5,6], "duplicate-position", seed0=300))

    # 4) 局部间距跳变: z=4->5.8, 残差 +0.8mm
    write_case("spacing-jump", slice_files(
        [0,1,2,3,4,5.8,6.8,7.8], "spacing-jump", seed0=400))

    # 5) 多帧对象: 单个 SOP, 6 帧 functional group, 帧 z=0..5
    mf_tags = {
        "0008,0016": "1.2.840.10008.5.1.4.1.1.2.1",
        "0008,0018": SERIES + ".mf1",
        "0008,0060": "CT",
        "0020,000D": STUDY, "0020,000E": SERIES,
        "0020,0052": FOR,
        "0028,0008": "6",
        "0028,0010": "16", "0028,0011": "16",
        "0028,0030": "0.703125\\0.703125",
        "5200,9230": {"sq": [
            {"0020,9113": {"sq": [{"0020,0032": f"-120.0\\-120.0\\{z:.1f}",
                                   "0020,0037": "1\\0\\0\\0\\1\\0"}]}}
            for z in range(6)]},
    }
    mf = inst_obj(mf_tags, pixel="px/frame.raw", frames=[{"index": z+1,
        "planePositionSequence": [{"0020,0032": f"-120.0\\-120.0\\{z:.1f}"}],
        "planeOrientationSequence": [{"0020,0037": "1\\0\\0\\0\\1\\0"}]} for z in range(6)])
    mf["_pxseed"] = 500
    write_case("multiframe", {"inst/0001.json": mf})

    # 6) UID 冲突: 第 9 个文件复用第 3 张 SOP UID, 内容与位置均不同
    files6 = slice_files([float(z) for z in range(8)], "uid-conflict", seed0=600)
    t9 = tags(sop(3), 9.0)
    o9 = inst_obj(t9, pixel="px/0009.raw"); o9["_pxseed"] = 699
    files6["inst/0009.json"] = o9
    write_case("uid-conflict", files6)

    # 7) 缺失几何: 第 5 张无 IPP/IOP
    write_case("missing-geometry", slice_files(
        [float(z) for z in range(8)], "missing-geometry",
        drop_geom={5}, seed0=700))

    # 8) transfer syntax 不支持: 第 2 张为 JPEG2000 像素
    write_case("unsupported-transfer-syntax", slice_files(
        [float(z) for z in range(8)], "unsupported-transfer-syntax",
        tss={2: TS_JP2}, seed0=800))

    # 9) 不同 FrameOfReference: 末张属于 FOR2, 必须分支而不强合
    write_case("frame-of-reference-split", slice_files(
        [float(z) for z in range(8)], "frame-of-reference-split",
        for_uids={8: FOR2}, seed0=900))

    # 10) InstanceNumber 与空间顺序不一致 (仅此一个直接来源不同)
    write_case("instance-number-scrambled", slice_files(
        [float(z) for z in range(8)], "instance-number-scrambled",
        inst_numbers=[3,7,1,5,2,8,4,6], seed0=1000))

    # 11) 回放用局部源数据修订: 只替换 baseline 第 4 张 (位置 3.0->3.06, 像素变更)
    t_amend = tags(sop(4), 3.06, inst_no=4)
    oa = inst_obj(t_amend, pixel="px/0004.raw"); oa["_pxseed"] = 1140
    write_case("amend-baseline", {"inst/0004.json": oa})

    # 12) 损坏导入 (事务回滚测试专用)
    bd = os.path.join(ROOT, "broken"); os.makedirs(bd, exist_ok=True)
    with open(os.path.join(bd, "bad.json"), "w") as f:
        f.write('{ "transferSyntaxUID": "1.2.840.10008.1.2.1", "tags": { broken ')

    manifest = {
        "caseId": "dicom-geometry-calibration-bench",
        "title": "DICOM 体系标定台: 切片几何、FrameOfReference 与序列多解",
        "coordinateBasis": "DICOM Patient Coordinate System (LPS), unit mm",
        "rulesetV1": {"orthTol": 0.001, "unitTol": 0.01,
                      "dupTolMm": 0.001, "jumpTolMm": 0.25},
        "cases": [
            {"id": "baseline", "dir": "baseline", "title": "基线: 8 张等间距轴位切片",
             "expect": {"slices": 8, "candidateVariants": 1, "medianSpacingMm": 1.0, "events": []}},
            {"id": "nonorthogonal", "dir": "nonorthogonal", "expect": {"slices": 8,
                "candidateVariants": 1, "events": ["NON_ORTHOGONAL_ORIENTATION"]}},
            {"id": "duplicate-position", "dir": "duplicate-position",
             "expect": {"slices": 8, "candidateVariants": 2,
                        "events": ["DUPLICATE_POSITION"]}},
            {"id": "spacing-jump", "dir": "spacing-jump",
             "expect": {"slices": 8, "candidateVariants": 3,
                        "events": ["LOCAL_SPACING_JUMP"]}},
            {"id": "multiframe", "dir": "multiframe",
             "expect": {"slices": 6, "candidateVariants": 1, "events": [], "multiFrame": True}},
            {"id": "uid-conflict", "dir": "uid-conflict",
             "expect": {"slices": 9, "candidateVariants": 1,
                        "events": ["UID_CONFLICT"]}},
            {"id": "missing-geometry", "dir": "missing-geometry",
             "expect": {"slices": 7, "candidateVariants": 1,
                        "events": ["MISSING_GEOMETRY"]}},
            {"id": "unsupported-transfer-syntax", "dir": "unsupported-transfer-syntax",
             "expect": {"slices": 8, "candidateVariants": 1,
                        "events": ["TRANSFER_SYNTAX_UNSUPPORTED"]}},
            {"id": "frame-of-reference-split", "dir": "frame-of-reference-split",
             "expect": {"slices": 8, "candidateVariants": 2,
                        "events": ["FRAME_OF_REFERENCE_SPLIT"]}},
            {"id": "instance-number-scrambled", "dir": "instance-number-scrambled",
             "expect": {"slices": 8, "candidateVariants": 1,
                        "events": ["INSTANCE_NUMBER_NOT_SPATIAL_TRUTH"]}},
        ],
        "replay": [
            {"step": 1, "action": "importCase", "caseId": "baseline",
             "expectCandidates": 1},
            {"step": 2, "action": "decide", "caseId": "baseline",
             "decision": "accepted"},
            {"step": 3, "action": "importCase", "caseId": "spacing-jump",
             "expectCandidates": 3},
            {"step": 4, "action": "reviseParams", "jumpTolMm": 0.9,
             "expectInvalidatedOnly": ["spacing-jump"]},
            {"step": 5, "action": "amendSource", "caseId": "baseline",
             "dir": "amend-baseline", "expectInvalidatedOnly": ["baseline"]},
            {"step": 6, "action": "conflictingDecisions",
             "caseIdA": "baseline", "caseIdB": "spacing-jump"},
            {"step": 7, "action": "forkAtHistoryEvent"},
            {"step": 8, "action": "exportDeleteReimport",
             "expect": "identity-order-provenance-preserved"},
        ],
    }
    with open(os.path.join(ROOT, "manifest.json"), "w", encoding="utf-8") as f:
        json.dump(manifest, f, ensure_ascii=False, indent=1)
    print("casepack written to", os.path.abspath(ROOT))

if __name__ == "__main__":
    main()
