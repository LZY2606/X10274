package org.gsb.dicomreview.web

import kotlin.math.max
import kotlin.math.min
import org.gsb.dicomreview.model.SeriesCandidate
import org.gsb.dicomreview.model.StackFrame
import org.gsb.dicomreview.review.BranchState

object SvgView {
    private const val W = 420
    private const val H = 260

    fun spatialProjection(candidate: SeriesCandidate): String {
        if (candidate.frames.isEmpty()) return "<svg data-id='${candidate.candidateId}-projection' width='$W' height='$H'></svg>"
        val first = candidate.frames.first().geometry
        val normal = first.orientation!!.normal
        val points = candidate.frames.map { it.geometry.position!! }
        val px = points.map { it.x }; val py = points.map { it.y }; val pz = points.map { it.z }
        val (minX, maxX) = bounds(px); val (minY, maxY) = bounds(py); val (minZ, maxZ) = bounds(pz)
        val spanX = max(1e-6, maxX - minX); val spanY = max(1e-6, maxY - minY); val spanZ = max(1e-6, maxZ - minZ)
        val span = max(max(spanX, spanY), spanZ)
        fun tx(v: Double) = (40 + (v - minX) / span * (W - 80))
        fun ty(v: Double) = (H - 40 - (v - minZ) / span * (H - 80))
        val pathD = points.joinToString(" ") { p -> "${"%.1f".format(tx(p.x))},${"%.1f".format(ty(p.z))}" }
        val circles = candidate.frames.joinToString("\n") { sf ->
            val p = sf.geometry.position!!
            val cx = "%.1f".format(tx(p.x)); val cy = "%.1f".format(ty(p.z))
            val dvId = stableValueId(candidate, sf, "projected-xz")
            "<circle data-id='$dvId' data-unit='patient-mm' cx='$cx' cy='$cy' r='4' class='slice-dot'/>" +
                "<text x='$cx' y='${"%.1f".format(ty(p.z) - 8)}' font-size='9' text-anchor='middle'>#${sf.order + 1}</text>"
        }
        return """
        <svg data-id='${candidate.candidateId}-projection' viewBox='0 0 $W $H' class='panel-svg' role='img'
             aria-label='spatial projection X/Z patient coordinates millimetres'>
          <rect x='0' y='0' width='$W' height='$H' fill='#fff' stroke='#ccc'/>
          <text x='10' y='18' font-size='11'>空间投影 X/Z (mm，患者坐标系，基准 FoR=${candidate.frameOfReferenceUid ?: "∅"})</text>
          <polyline points='$pathD' fill='none' stroke='#888' stroke-dasharray='3 3'/>
          $circles
          <text x='10' y='${H - 8}' font-size='10'>n=(${"%.3f".format(normal.x)}, ${"%.3f".format(normal.y)}, ${"%.3f".format(normal.z)})</text>
        </svg>
        """.trimIndent()
    }

    fun spacingResiduals(candidate: SeriesCandidate): String {
        val gaps = candidate.frames.zipWithNext().map { (a, b) -> b.tAlongNormal - a.tAlongNormal }
        if (gaps.isEmpty()) return "<svg data-id='${candidate.candidateId}-residuals' width='$W' height='$H'></svg>"
        val median = gaps.sorted().let { if (it.size % 2 == 1) it[it.size / 2] else (it[it.size / 2 - 1] + it[it.size / 2]) / 2 }
        val maxAbs = max(1e-6, gaps.maxOf { kotlin.math.abs(it - median) })
        val barW = (W - 100) / gaps.size
        val bars = gaps.mapIndexed { idx, gap ->
            val residual = gap - median
            val h = (kotlin.math.abs(residual) / maxAbs * 90)
            val y = if (residual >= 0) 130 - h else 130
            val color = if (gap > 2.0 * median) "#c0392b" else if (residual == 0.0) "#95a5a6" else "#2980b9"
            val x = 60 + idx * barW
            val dvId = stableValueId(candidate, candidate.frames[idx + 1], "spacing-residual")
            "<rect data-id='$dvId' data-value='${"%.4f".format(residual)}' data-unit='mm' " +
                "x='${"%.1f".format(x)}' y='${"%.1f".format(y)}' width='${max(4, barW - 4)}' height='${"%.1f".format(max(2, h))}' fill='$color'/>" +
                "<text x='${"%.1f".format(x + barW / 2)}' y='150' font-size='8' text-anchor='middle'>${idx + 1}-${idx + 2}</text>"
        }.joinToString("\n")
        return """
        <svg data-id='${candidate.candidateId}-residuals' viewBox='0 0 $W $H' class='panel-svg' role='img'
             aria-label='spacing residual from median in millimetres'>
          <rect width='$W' height='$H' fill='#fff' stroke='#ccc'/>
          <text x='10' y='18' font-size='11'>间距残差 (mm，相对中位间距 ${"%.3f".format(median)} mm)</text>
          <line x1='50' y1='130' x2='${W - 20}' y2='130' stroke='#333'/>
          $bars
          <text x='10' y='${H - 8}' font-size='10'>红柱: 局部间距 &gt; 2× 中位（触发分支）</text>
        </svg>
        """.trimIndent()
    }

    fun slicePlane(candidate: SeriesCandidate, frame: StackFrame): String {
        val ori = frame.geometry.orientation!!
        val center = frame.geometry.position!!
        val w = 80.0; val h = 80.0
        val p0 = center - ori.row * (w / 2) - ori.col * (h / 2)
        val p1 = center + ori.row * (w / 2) - ori.col * (h / 2)
        val p2 = center + ori.row * (w / 2) + ori.col * (h / 2)
        val p3 = center - ori.row * (w / 2) + ori.col * (h / 2)
        fun s(v: Double) = "%.1f".format(v)
        val poly = listOf(p0, p1, p2, p3).joinToString(" ") { "${s(it.x)},${s(it.z)}" }
        val scale = 3.0
        val cx = W / 2.0; val cy = H / 2.0 + 20
        fun px(v: Double, around: Double) = cx + (v - around) * scale
        val projected = listOf(p0, p1, p2, p3).map { px(it.x, center.x) to cy - (it.z - center.z) * scale }
        val polyPx = projected.joinToString(" ") { "${s(it.first)},${s(it.second)}" }
        val normalEnd = (cx + ori.normal.x * 40) to (cy - ori.normal.z * 40)
        val dvId = stableValueId(candidate, frame, "plane-xz")
        return """
        <svg data-id='$dvId' viewBox='0 0 $W $H' class='panel-svg' role='img'
             aria-label='slice plane in X/Z projection with normal vector'>
          <rect width='$W' height='$H' fill='#fff' stroke='#ccc'/>
          <text x='10' y='18' font-size='11'>切片平面 #${frame.order + 1} (X/Z 投影，mm)</text>
          <polygon points='$polyPx' fill='rgba(52,152,219,0.15)' stroke='#2980b9' stroke-width='2'/>
          <line x1='${s(cx)}' y1='${s(cy)}' x2='${s(normalEnd.first)}' y2='${s(normalEnd.second)}' stroke='#c0392b' stroke-width='2' marker-end='url(#arrow)'/>
          <defs><marker id='arrow' markerWidth='8' markerHeight='8' refX='6' refY='3' orient='auto'>
            <path d='M0,0 L6,3 L0,6 Z' fill='#c0392b'/></marker></defs>
          <text x='10' y='${H - 8}' font-size='10'>中心=(${"%.2f".format(center.x)}, ${"%.2f".format(center.y)}, ${"%.2f".format(center.z)}) mm; t=${"%.3f".format(frame.tAlongNormal)} mm</text>
        </svg>
        """.trimIndent()
    }

    private fun bounds(v: List<Double>): Pair<Double, Double> {
        val lo = v.min(); val hi = v.max()
        return if (hi - lo < 1e-9) lo - 1.0 to hi + 1.0 else lo to hi
    }

    fun stableValueId(candidate: SeriesCandidate, frame: StackFrame, kind: String): String =
        "val_${candidate.candidateId.take(14)}_${frame.geometry.instanceId.take(10)}_${frame.geometry.frameNumber}_$kind"
}
