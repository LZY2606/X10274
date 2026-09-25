# DICOM 体系标定台：切片几何、FrameOfReference 与序列多解

离线案例包，面向医学影像研究组的去标识 DICOM metadata + 像素文件归组审阅流程。

## 结构
- `baseline/` — 6 层轴位 CT 切片（z = 0..10 mm，步进 2.0 mm，LPS 患者坐标系）。
  SOP/Series/FrameOfReference UID、ImagePosition/Orientation、PixelSpacing、
  多帧 functional group、传输语法与原始 tag 文本全部保留。InstanceNumber 故意打乱，
  用于证明 InstanceNumber 不是空间排序真值。
- `counterexamples/` — 每个目录只改动一个直接来源：
  - `nonorthogonal-orientation/` 方向向量非正交（row·col = 0.087）
  - `duplicate-position/` 重复位置（z = 4.0 与邻片重合）
  - `spacing-jump/` 局部间距跳变（+0.5 mm）
  - `multiframe-object/` 多帧对象（PerFrameFunctionalGroupsSequence）
  - `uid-conflict/` SOPInstanceUID 冲突（同 UID 不同内容）
  - `missing-geometry/` 缺失 ImagePositionPatient
  - `unsupported-transfer-syntax/` JPEG2000 传输语法（不支持）
- `revisions/` — 一次参数修订（rules-v2.json）、一次只影响局部依赖的源数据改动
  （source-patch-local/）、一次互相冲突的人工决定（decisions-conflict.json）。
- `expected-events.json` — 机器可读取的判断依据：规则容差、每个场景应产生的事件
  与候选类型。
- `script.json` — 重放步骤：基线导入 → 七个反例 → 参数修订 → 局部源改动 → 冲突决定。

## 约定
- 所有几何量带单位/坐标基准：位置 mm（LPS）、PixelSpacing mm/px、容差 mm。
- 导入层保留原始表示（`.tags` 原文 + `.raw` 字节），派生层才做规范化；
  无法转换的值留在原位置并附诊断，不为画图丢弃。
- 归组原则见 `expected-events.json` 的 `principles`。
