package org.gsb.dicomreview.web

import kotlinx.serialization.json.Json
import org.gsb.dicomreview.model.SeriesCandidate
import org.gsb.dicomreview.model.StackFrame
import org.gsb.dicomreview.review.BranchState
import org.gsb.dicomreview.web.SvgView

object ReviewHtml {
    private val json = Json { prettyPrint = true }

    @kotlinx.serialization.Serializable
    private data class TagRow(val tagLabel: String, val tagName: String, val vr: String,
                              val rawText: String?, val path: String, val frame: Int? = null,
                              val binary: Boolean = false)

    fun render(
        state: BranchState,
        branchId: String,
        asOf: Long?,
        candidateLineage: String?,
        frameIdx: Int,
        message: String?
    ): String {
        val candidates = state.geometry.candidates
        val selected: SeriesCandidate? = candidates.firstOrNull { it.lineageKey == candidateLineage }
            ?: candidates.firstOrNull()
        val selectedFrame = selected?.frames?.getOrNull(frameIdx.coerceAtMost((selected.frames.size - 1).coerceAtLeast(0)))
        val headId = state.head.eventId

        return """<!doctype html>
<html lang='zh'><head><meta charset='utf-8'><title>DICOM 几何复核台</title>
<style>
 body{font-family:ui-sans-serif,-apple-system,'PingFang SC',sans-serif;margin:0;background:#f4f5f7;color:#1f2933}
 header{background:#10233f;color:#fff;padding:10px 18px}
 h1{font-size:17px;margin:0}
 .wrap{display:grid;grid-template-columns:300px 1fr 420px;gap:12px;padding:12px}
 .card{background:#fff;border:1px solid #d6dae0;border-radius:8px;padding:12px;margin-bottom:12px}
 h2{font-size:14px;margin:0 0 8px;color:#10233f}
 table{border-collapse:collapse;width:100%;font-size:12px}
 th,td{border-bottom:1px solid #e5e8ec;padding:4px 6px;text-align:left;vertical-align:top}
 .cand{display:block;padding:8px;border:1px solid #d6dae0;border-radius:6px;margin-bottom:6px;text-decoration:none;color:#1f2933}
 .cand.active{border-color:#2980b9;background:#eef6ff}
 .badge{display:inline-block;font-size:11px;padding:1px 6px;border-radius:10px;background:#e4e7eb;margin-right:4px}
 .badge.warn{background:#fdecc8;color:#7a4f01}.badge.err{background:#fbdcdc;color:#8a1f1f}
 .badge.ok{background:#d8f0e0;color:#1e6b3a}
 button{padding:6px 10px;border:1px solid #2980b9;background:#2980b9;color:#fff;border-radius:5px;cursor:pointer}
 button.ghost{background:#fff;color:#2980b9}button.danger{background:#c0392b;border-color:#c0392b}
 input,select,textarea{font-size:12px;padding:4px;margin:2px 0;width:100%;box-sizing:border-box}
 .row2{display:grid;grid-template-columns:1fr 1fr;gap:8px}
 .muted{color:#66788a;font-size:11px}
 .timeline li{font-size:12px;margin-bottom:3px}
 .pill{font-family:ui-monospace,monospace;font-size:10px;background:#eef1f5;padding:1px 4px;border-radius:3px}
 svg.panel-svg{width:100%;height:auto;border:1px solid #e5e8ec;border-radius:6px;background:#fff}
 .framebar a{display:inline-block;padding:3px 7px;margin:2px;border:1px solid #cbd2d9;border-radius:4px;font-size:11px;text-decoration:none;color:#1f2933}
 .framebar a.sel{background:#2980b9;color:#fff}
 details summary{cursor:pointer;font-size:12px;font-weight:600}
 pre{font-size:10.5px;background:#0f172a;color:#e2e8f0;padding:8px;border-radius:6px;overflow:auto;max-height:300px}
</style></head><body>
<header><h1>DICOM 体系标定台：切片几何 · FrameOfReference · 序列多解</h1>
<div class='muted' style='color:#bcd0ee'>分支 <span class='pill'>${esc(branchId)}</span> · HEAD <span class='pill' data-id='head-event-id'>${esc(headId)}</span>${if (asOf != null) " · as-of seq=$asOf（历史视图）" else ""}</div></header>
<div class='wrap'>
 <div>
  ${branchCard(state, branchId, headId)}
  ${timelineCard(state, branchId, asOf)}
  ${candidateListCard(state, selected, branchId, asOf)}
 </div>
 <div>
  ${if (message != null) "<div class='card' data-id='flash'>${esc(message)}</div>" else ""}
  ${importCard(branchId, headId)}
  ${paramsCard(state, branchId, headId)}
  ${invalidationsCard(state)}
  ${unlocatedCard(state)}
  ${ruleFiresCard(state)}
 </div>
 <div>
  ${candidateDetailCard(state, selected, selectedFrame, frameIdx, branchId, asOf, headId)}
  ${tagEvidenceCard(state, selectedFrame, selected)}
 </div>
</div></body></html>"""
    }

    private fun branchCard(state: BranchState, branchId: String, headId: String): String {
        val opts = state.let { listOf(it.branchId) }
        val branches = state.events.map { it.branchId }.distinct()
        return """<div class='card'><h2>分支 / Branch</h2>
        <div class='muted'>当前: <span class='pill'>${esc(branchId)}</span></div>
        <form action='/fork' method='post' style='margin-top:6px'>
          <input type='hidden' name='branch' value='${esc(branchId)}'/>
          <input type='hidden' name='baseHead' value='${esc(headId)}'/>
          <input name='name' placeholder='新分支名（保留当前依赖版本）' required/>
          <button type='submit' class='ghost'>从此 HEAD 新建分支</button>
        </form>
        <div class='muted' style='margin-top:6px'>所有分支：</div>
        <div>${branches.joinToString { b -> "<a class='badge' href='/review?branch=${esc(b)}'>${esc(b)}</a>" }}</div>
        </div>"""
    }

    private fun timelineCard(state: BranchState, branchId: String, asOf: Long?): String = """
    <div class='card'><h2>事件时间线（回到旧事件恢复当时结论）</h2>
    <ol class='timeline' start='0'>${state.events.joinToString("") { ev ->
        val mark = if (asOf != null && ev.seq == asOf) " ✅" else ""
        "<li data-id='timeline-${esc(ev.eventId)}'><a href='/review?branch=${esc(branchId)}&asOf=${ev.seq}'>#${ev.seq} ${esc(ev.type)}$mark</a><div class='pill'>${esc(ev.eventId)}</div></li>"
    }}</ol></div>"""

    private fun candidateListCard(state: BranchState, selected: SeriesCandidate?, branchId: String, asOf: Long?): String {
        val asOfPart = if (asOf != null) "&asOf=$asOf" else ""
        return "<div class='card'><h2>序列/体数据候选（${state.geometry.candidates.size} 个合法候选均保留）</h2>" +
            state.geometry.candidates.joinToString("") { cand ->
                val decision = state.decisions[cand.lineageKey]?.takeIf { it.active }
                val decisionBadge = when (decision?.verdict) {
                    "ACCEPT" -> "<span class='badge ok'>已采纳</span>"
                    "REJECT" -> "<span class='badge err'>已否定</span>"
                    else -> "<span class='badge'>待裁定</span>"
                }
                val ann = cand.annotations.joinToString("") { a ->
                    val cls = if (a.contains("JUMP") || a.contains("DUPLICATE")) "warn" else ""
                    "<span class='badge $cls'>${esc(a)}</span>"
                }
                val active = if (selected?.lineageKey == cand.lineageKey) "cand active" else "cand"
                "<a class='$active' data-id='candidate-${esc(cand.candidateId)}' " +
                    "href='/review?branch=${esc(branchId)}$asOfPart&candidate=${java.net.URLEncoder.encode(cand.lineageKey, "UTF-8")}'>" +
                    "<div><b>${esc(cand.kind)}</b> $decisionBadge</div>" +
                    "<div class='muted'>${cand.frames.size} 帧 · FoR ${esc(cand.frameOfReferenceUid ?: "∅")} · 中位间距 ${cand.medianSpacingMm?.let { "%.3f mm".format(it) } ?: "—"}</div>" +
                    "<div style='margin-top:4px'>$ann</div></a>"
            } + "</div>"
    }

    private fun importCard(branchId: String, headId: String): String = """
    <div class='card'><h2>导入去标识 DICOM（原始 tag/像素文件保留；整批失败即回滚）</h2>
    <form action='/import' method='post' enctype='multipart/form-data'>
      <input type='hidden' name='branch' value='${esc(branchId)}'/>
      <input type='hidden' name='baseHead' value='${esc(headId)}'/>
      <div class='row2'><input name='label' placeholder='批次标签（如 baseline）' required/>
        <input type='file' name='file' webkitdirectory directory multiple required/></div>
      <button type='submit'>导入整批（事务）</button>
    </form>
    <form action='/reinterpret' method='post' enctype='multipart/form-data' style='margin-top:8px'>
      <input type='hidden' name='branch' value='${esc(branchId)}'/>
      <input type='hidden' name='baseHead' value='${esc(headId)}'/>
      <div class='row2'><input name='label' placeholder='重新解释来源（局部改动）' required/>
        <input type='file' name='file' multiple required/></div>
      <button type='submit' class='ghost'>对同一 SOP 重新解释（新分支材料，旧字节保留）</button>
    </form>
    <div style='margin-top:8px'>
      <a href='/export'><button type='button' class='ghost'>导出归档 (zip)</button></a>
    </div>
    <form action='/importBundle' method='post' enctype='multipart/form-data' style='margin-top:6px'>
      <div class='row2'><input type='file' name='bundle' accept='.zip' required/><button type='submit' class='ghost'>导入归档</button></div>
    </form></div>"""

    private fun paramsCard(state: BranchState, branchId: String, headId: String): String {
        val p = state.parameters
        return """<div class='card'><h2>推导参数（修订生成新事件并按依赖范围失效）</h2>
        <form action='/params' method='post'>
          <input type='hidden' name='branch' value='${esc(branchId)}'/>
          <input type='hidden' name='baseHead' value='${esc(headId)}'/>
          <div class='row2'>
            <label>方向正交容差 (|row·col|)<input name='orthogonalityTolerance' value='${p.orthogonalityTolerance}' step='0.001'/></label>
            <label>单位法向容差<input name='unitNormalTolerance' value='${p.unitNormalTolerance}' step='0.0005'/></label>
            <label>重片位置容差 (mm)<input name='duplicatePositionToleranceMm' value='${p.duplicatePositionToleranceMm}' step='0.005'/></label>
            <label>间距跳变倍率<input name='spacingJumpRatio' value='${p.spacingJumpRatio}' step='0.1'/></label>
          </div>
          <div class='muted'>当前规则版本 <span class='pill'>${esc(state.geometry.ruleVersion)}</span> · 参数 <span class='pill'>${esc(p.parameterVersion)}</span></div>
          <button type='submit'>修订参数（只失效受影响候选）</button>
        </form></div>"""
    }

    private fun invalidationsCard(state: BranchState): String = """
    <div class='card'><h2>失效范围（按依赖传播，绝不全局静默替换）</h2>
    ${if (state.invalidations.isEmpty()) "<div class='muted'>尚无修订；初始结论由 ${esc(state.geometry.derivedAtEventId)} 派生。</div>" else
        state.invalidations.values.joinToString("") { rep ->
            val scope = if (rep.parameterScopeChanged) "参数版本变化" else "直接来源改动"
            "<details data-id='invalidation-${esc(rep.triggerEventId)}'><summary><span class='badge warn'>$scope</span>${esc(rep.triggerEventId)}</summary>" +
            "<table><tr><th>改变材料</th><td>${rep.changedMaterialInstanceIds.joinToString { esc(it) }.ifBlank { "—" }}</td></tr>" +
            "<tr><th>改变 SOP</th><td>${rep.changedSopUids.joinToString { esc(it) }.ifBlank { "—" }}</td></tr>" +
            "<tr><th>失效候选</th><td>${rep.invalidatedCandidateIds.joinToString { esc(it) }.ifBlank { "无" }}</td></tr>" +
            "<tr><th>保留候选（身份不变）</th><td>${rep.retainedCandidateIds.joinToString { esc(it) }.ifBlank { "无" }}</td></tr>" +
            "<tr><th>替换链</th><td>${rep.superseded.joinToString { "${esc(it.oldCandidateId)} → ${esc(it.newCandidateId)} (${esc(it.reason)})" }.ifBlank { "无" }}</td></tr>" +
            "</table></details>"
        }}
    </div>"""

    private fun unlocatedCard(state: BranchState): String = """
    <div class='card'><h2>缺失几何 / 不支持传输语法（原值保留，附诊断，不丢弃）</h2>
    ${if (state.geometry.unlocated.isEmpty()) "<div class='muted'>无</div>" else
        "<table><tr><th>实例</th><th>SOP</th><th>诊断</th></tr>" +
        state.geometry.unlocated.joinToString("") { u ->
            "<tr data-id='unlocated-${esc(u.instanceId)}'><td class='pill'>${esc(u.instanceId)}</td><td>${esc(u.sopInstanceUid ?: "∅")}</td>" +
            "<td>${u.reasons.joinToString("<br/>") { esc(it) }}</td></tr>"
        } + "</table>"}
    </div>"""

    private fun ruleFiresCard(state: BranchState): String = """
    <div class='card'><h2>机器可读判定事件（规则触发）</h2>
    ${if (state.geometry.ruleFires.isEmpty()) "<div class='muted'>无触发；当前几何在容差内。</div>" else
        state.geometry.ruleFires.joinToString("") { f ->
            val cls = if (f.severity == "ERROR") "err" else "warn"
            "<details data-id='fire-${esc(f.fireId)}'><summary><span class='badge $cls'>${esc(f.code)}</span>${esc(f.message)}</summary>" +
            "<pre>" + esc(Json.encodeToString(org.gsb.dicomreview.model.RuleFire.serializer(), f)) + "</pre></details>"
        }}
    </div>"""

    private fun candidateDetailCard(
        state: BranchState,
        selected: SeriesCandidate?,
        selectedFrame: StackFrame?,
        frameIdx: Int,
        branchId: String,
        asOf: Long?,
        headId: String
    ): String {
        if (selected == null || selectedFrame == null) {
            return "<div class='card'><h2>候选详情</h2><div class='muted'>当前没有任何可复核候选，请先导入案例。</div></div>"
        }
        val asOfPart = if (asOf != null) "&asOf=$asOf" else ""
        val frameBar = selected.frames.indices.joinToString(" ") { i ->
            val cls = if (i == frameIdx) "framebar sel" else "framebar"
            "<a class='$cls' data-id='frame-tab-$i' href='/review?branch=${esc(branchId)}$asOfPart&candidate=${java.net.URLEncoder.encode(selected.lineageKey, "UTF-8")}&frame=$i'>#${i + 1}</a>"
        }
        val decision = state.decisions[selected.lineageKey]?.takeIf { it.active }
        val decisionPanel = if (decision == null) """
          <form action='/decide' method='post'>
            <input type='hidden' name='branch' value='${esc(branchId)}'/>
            <input type='hidden' name='baseHead' value='${esc(headId)}'/>
            <input type='hidden' name='lineage' value='${esc(selected.lineageKey)}'/>
            <input type='hidden' name='candidateId' value='${esc(selected.candidateId)}'/>
            <textarea name='rationale' placeholder='裁定依据（机器与人工都可追溯）'></textarea>
            <button name='verdict' value='ACCEPT'>采纳</button>
            <button name='verdict' value='REJECT' class='danger'>否定</button>
          </form>""" else """
          <div data-id='decision-${esc(selected.lineageKey)}'><span class='badge ${if (decision.verdict == "ACCEPT") "ok" else "err"}'>${if (decision.verdict == "ACCEPT") "已采纳" else "已否定"}</span>
          ${esc(decision.rationale)} <span class='pill'>${esc(decision.eventId)}</span></div>
          <form action='/revert' method='post' style='margin-top:6px'>
            <input type='hidden' name='branch' value='${esc(branchId)}'/>
            <input type='hidden' name='baseHead' value='${esc(headId)}'/>
            <input type='hidden' name='targetEventId' value='${esc(decision.eventId)}'/>
            <button class='ghost'>撤销该裁定（回到前一分支）</button>
          </form>"""

        val sf = selectedFrame.geometry
        val spacingGaps = selected.frames.zipWithNext().map { (a, b) -> b.tAlongNormal - a.tAlongNormal }
        val gapRows = if (spacingGaps.isEmpty()) "" else
            "<tr><th>逐段间距</th><td data-unit='mm'>" + spacingGaps.joinToString(" / ") { "%.3f".format(it) } + " mm</td></tr>"

        return """<div class='card'><h2>候选复核 <span class='pill' data-id='candidate-detail-id'>${esc(selected.candidateId)}</span></h2>
        <div class='muted'>lineage <span class='pill'>${esc(selected.lineageKey)}</span> · kind ${esc(selected.kind)} · 规则 ${esc(selected.ruleVersion)} · 派生自 ${esc(selected.derivedAtEventId)}</div>
        <div class='framebar' style='margin:8px 0'>$frameBar</div>
        ${SvgView.slicePlane(selected, selectedFrame)}
        ${SvgView.spatialProjection(selected)}
        ${SvgView.spacingResiduals(selected)}
        <table style='margin-top:8px'>
          <tr><th>FoR 基准</th><td data-id='candidate-for' data-unit='uid'>${esc(selected.frameOfReferenceUid ?: "∅")}</td></tr>
          <tr><th>切片位置 (mm，患者坐标)</th><td data-id='frame-position' data-unit='patient-mm' data-frame='${selectedFrame.geometry.frameNumber}'>${"%.3f / %.3f / %.3f".format(sf.position!!.x, sf.position.y, sf.position.z)}</td></tr>
          <tr><th>法向投影 t</th><td data-id='frame-t' data-unit='mm'>${"%.4f".format(selectedFrame.tAlongNormal)} mm（空间排序真值，非 InstanceNumber）</td></tr>
          <tr><th>InstanceNumber（仅存档）</th><td>${sf.instanceNumber ?: "∅"}（不作为排序依据）</td></tr>
          <tr><th>像素间距 row/col</th><td data-id='frame-spacing' data-unit='mm'>${sf.rowSpacingMm?.let { "${it.originalText} mm" } ?: "∅"} / ${sf.colSpacingMm?.let { "${it.originalText} mm" } ?: "∅"}</td></tr>
          <tr><th>正交检查</th><td data-id='frame-ortho'>row·col=${"%.5f".format(sf.orientation!!.dot)}; |row|=${"%.5f".format(sf.orientation.rowNorm)}; |col|=${"%.5f".format(sf.orientation.colNorm)} → ${if (sf.orientation.orthogonal) "通过" else "非正交→分支/排除"}</td></tr>
          <tr><th>中位间距</th><td data-id='median-spacing' data-unit='mm'>${selected.medianSpacingMm?.let { "%.4f mm".format(it) } ?: "—"}</td></tr>
          $gapRows
          <tr><th>标注</th><td>${selected.annotations.joinToString { "<span class='badge'>${esc(it)}</span>" }}</td></tr>
          <tr><th>来源实例</th><td>${selected.frames.joinToString { "<span class='pill'>${esc(it.geometry.instanceId)}#${it.geometry.frameNumber}</span>" }}</td></tr>
        </table>
        <div style='margin-top:10px'>$decisionPanel</div>
        </div>"""
    }

    private fun tagEvidenceCard(state: BranchState, frame: StackFrame?, selected: SeriesCandidate?): String {
        if (frame == null) return ""
        val instanceId = frame.geometry.instanceId
        val raw = state.instancesById[instanceId] ?: return ""
        val tags: List<TagRow> = try {
            Json.decodeFromString(raw.rawTagsJson)
        } catch (t: Throwable) { emptyList() }
        val relevantPaths = frame.geometry.evidence.map { it.path }.toSet()
        val rows = tags.filter { tr ->
            tr.path in relevantPaths || tr.tagName in setOf(
                "SOPInstanceUID", "SeriesInstanceUID", "StudyInstanceUID", "FrameOfReferenceUID",
                "InstanceNumber", "TransferSyntaxUID", "NumberOfFrames", "SharedFunctionalGroupsSequence",
                "PerFrameFunctionalGroupsSequence", "PixelData"
            )
        }.distinctBy { it.path + it.tagLabel }
        return """<div class='card'><h2>每张图像的原始 tag 证据 <span class='pill'>${esc(instanceId)}</span></h2>
        <div class='muted'>传输语法 <span class='pill' data-id='ts-${esc(instanceId)}'>${esc(raw.transferSyntaxUid ?: "∅")}</span>
        ${if (raw.transferSyntaxSupported) "" else "<span class='badge err'>不支持：像素保留、不臆造</span>"}
        ${if (raw.fatalDiagnostic != null) "<div class='badge err'>${esc(raw.fatalDiagnostic)}</div>" else ""}
        </div>
        <table><tr><th>路径</th><th>Tag</th><th>VR</th><th>原始表示（导入时不改写）</th></tr>
        ${rows.joinToString("") { tr ->
            val emphasized = if (tr.path in relevantPaths) "style='background:#eef6ff'" else ""
            val value = if (tr.binary) "<span class='muted'>&lt;binary ${tr.tagName} retained&gt;</span>" else esc(tr.rawText ?: "")
            "<tr $emphasized data-id='tag-${esc(instanceId)}-${esc(tr.path.replace(Regex("[^A-Za-z0-9]"), "_"))}'>" +
              "<td class='pill'>${esc(tr.path)}</td><td>${esc(tr.tagLabel)} ${esc(tr.tagName)}</td><td>${esc(tr.vr)}</td><td>$value</td></tr>"
        }}
        </table></div>"""
    }

    private fun esc(s: String?): String = escapeHtml(s)
}
