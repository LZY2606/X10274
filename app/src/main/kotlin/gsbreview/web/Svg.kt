package gsbreview.web

import gsbreview.domain.SlicePlane
import gsbreview.domain.VolumeCandidate
import kotlin.math.max

object Svg {
    fun projection(candidate: VolumeCandidate?, rejected: Boolean = false): String {
        if (candidate == null || candidate.planes.isEmpty()) {
            return """<svg class="svg" viewBox="0 0 400 220" role="img" data-svg="empty">
              |<rect width="400" height="220" fill="#f7f7f5"/>
              |<text x="20" y="110" font-family="sans-serif" font-size="13" fill="#777">
              |No spatially ordered candidate at this event.</text></svg>""".trimMargin()
        }
        return when {
            candidate.planes.size == 1 -> singlePlane(candidate)
            else -> sliceProjection(candidate, rejected)
        }
    }

    private fun sliceProjection(c: VolumeCandidate, rejected: Boolean): String {
        val coords = c.members.mapNotNull { it.spatialCoordinateMm }
        val minC = coords.minOrNull() ?: 0.0
        val maxC = coords.maxOrNull() ?: 1.0
        val w = 720; val h = 240
        val padL = 60.0; val padR = 30.0; val padT = 30.0; val padB = 50.0
        val span = max(1e-6, maxC - minC)
        val x = { coord: Double -> padL + (coord - minC) / span * (w - padL - padR) }
        val color = when {
            rejected -> "#c0392b"
            c.accepted -> "#1e7d34"
            else -> "#2a6f97"
        }
        val points = c.members.mapIndexed { i, m ->
            val cx = x(m.spacingResidualMm?.let { coords[i] } ?: coords.getOrElse(i) { minC })
            Triple(i, cx, m)
        }
        val ticks = c.members.joinToString("\n") { m ->
            val cx = x(coords[m.ord])
            val label = "f${m.ord + 1}"
            """<g class="slice-tick" data-frame-key="${esc(m.frameKey)}">
              |<circle cx="$cx" cy="${padT + 80}" r="7" fill="$color"/>
              |<text x="$cx" y="${padT + 104}" text-anchor="middle" font-size="11" fill="#333">$label</text>
              |</g>""".trimMargin()
        }
        val residualBars = c.members.drop(1).joinToString("\n") { m ->
            val cx = x(coords[m.ord])
            val h = max(2.0, (m.spacingResidualMm ?: 0.0) * 14.0)
            val y = h - 30.0 - h
            """<rect class="residual-bar" data-frame-key="${esc(m.frameKey)}"
              |x="${cx - 8}" y="${padT + 150 - h}" width="16" height="$h"
              |fill="#e09f3e" stroke="#9c6b1f"/>
              |<title>${esc(m.frameKey)} residual=${fmt(m.spacingResidualMm)} mm</title>""".trimMargin()
        }
        val status = esc(c.status)
        return """<svg class="svg" viewBox="0 0 $w $h" role="img"
          |data-candidate-id="${esc(c.candidateId)}" data-svg="slice-projection">
          |<rect width="$w" height="$h" fill="#ffffff" stroke="#ddd"/>
          |<text x="$padL" y="20" font-family="sans-serif" font-size="13" fill="#222">
          |Slice projection along plane normal · coordinate [mm] · candidate $status</text>
          |<line x1="$padL" y1="${padT + 80}" x2="${w - padR}" y2="${padT + 80}" stroke="#bbb"/>
          |$ticks
          |<text x="$padL" y="${padT + 140}" font-size="12" fill="#7a5200">spacing residual [mm] from median gap</text>
          |$residualBars
          |<text x="$padL" y="${h - 12}" font-size="11" fill="#555">min ${fmt(minC)} mm</text>
          |<text x="${w - padR}" y="${h - 12}" text-anchor="end" font-size="11" fill="#555">max ${fmt(maxC)} mm</text>
          |</svg>""".trimMargin()
    }

    private fun singlePlane(c: VolumeCandidate): String {
        val p = c.planes.first()
        val m = c.members.first()
        return """<svg class="svg" viewBox="0 0 420 260" role="img"
          |data-candidate-id="${esc(c.candidateId)}" data-svg="single-plane">
          |<rect width="420" height="260" fill="#fff" stroke="#ddd"/>
          |<text x="20" y="26" font-family="sans-serif" font-size="13" fill="#222">
          |Single slice plane (status ${esc(c.status)})</text>
          |<g transform="translate(210,140)">
          |<line x1="0" y1="0" x2="${80 * p.row[0]}" y2="${-80 * p.row[1]}" stroke="#2a6f97" stroke-width="3"/>
          |<line x1="0" y1="0" x2="${80 * p.col[0]}" y2="${-80 * p.col[1]}" stroke="#1e7d34" stroke-width="3"/>
          |<line x1="0" y1="0" x2="${50 * p.normal[0]}" y2="${-50 * p.normal[1]}" stroke="#c0392b" stroke-width="2" stroke-dasharray="5,4"/>
          |<text x="${84 * p.row[0]}" y="${-84 * p.row[1]}" font-size="11" fill="#2a6f97">row</text>
          |<text x="${84 * p.col[0]}" y="${-84 * p.col[1]}" font-size="11" fill="#1e7d34">col</text>
          |<text x="${54 * p.normal[0]}" y="${-54 * p.normal[1]}" font-size="11" fill="#c0392b">normal</text>
          |</g>
          |<text x="20" y="234" font-size="11" fill="#555">origin [mm]: ${fmtVec(p.origin)}</text>
          |<text x="20" y="250" font-size="11" fill="#555" data-frame-key="${esc(m.frameKey)}">
          |frame: ${esc(m.frameKey)}</text>
          |</svg>""".trimMargin()
    }

    fun residualTable(c: VolumeCandidate?): String {
        if (c == null) return "<p class=\"muted\">无候选。</p>"
        val rows = c.members.joinToString("\n") { m ->
            """<tr data-member-ord="${m.ord}" data-frame-key="${esc(m.frameKey)}">
              |<td>${m.ord + 1}</td>
              |<td><code>${esc(m.frameKey)}</code></td>
              |<td>${m.instanceNumber ?: ""}</td>
              |<td data-unit="mm">${fmt(m.spatialCoordinateMm)}</td>
              |<td data-unit="mm">${fmt(m.spacingResidualMm)}</td>
              |<td>${m.instanceNumberMatchesSpatialOrder?.let { if (it) "yes" else "NO" } ?: "—"}</td>
              |</tr>""".trimMargin()
        }
        return """<table class="residual-table" data-candidate-id="${esc(c.candidateId)}">
          |<thead><tr><th>#</th><th>frame stable id</th><th>InstanceNumber (advisory)</th>
          |<th>spatial coordinate</th><th>spacing residual</th><th>instance# matches spatial?</th>
          |</tr></thead><tbody>$rows</tbody></table>""".trimMargin()
    }

    private fun fmt(v: Double?): String = v?.let {
        if (it == it.toLong().toDouble()) it.toLong().toString()
        else String.format(java.util.Locale.US, "%.4f", it)
    } ?: "—"

    private fun fmtVec(v: List<Double>) = v.joinToString(", ") { fmt(it) }
    private fun esc(s: String?) = (s ?: "").replace("&", "&amp;").replace("<", "&lt;")
        .replace(">", "&gt;").replace("\"", "&quot;")
}
