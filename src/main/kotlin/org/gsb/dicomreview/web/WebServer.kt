package org.gsb.dicomreview.web

import io.ktor.http.ContentDisposition
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.http.content.staticResources
import io.ktor.server.request.receiveMultipart
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondRedirect
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import java.io.ByteArrayOutputStream
import io.ktor.http.content.PartData
import io.ktor.http.content.streamProvider
import org.gsb.dicomreview.model.GroupingParameters
import org.gsb.dicomreview.review.ConcurrentModification
import org.gsb.dicomreview.review.ImportRejected
import org.gsb.dicomreview.review.ReviewService

data class MultiPartRead(val fields: Map<String, String>, val files: List<Pair<String, ByteArray>>)

suspend fun readMultipartSafe(call: io.ktor.server.application.ApplicationCall): MultiPartRead {
    val multipart = call.receiveMultipart()
    val fields = mutableMapOf<String, String>()
    val files = mutableListOf<Pair<String, ByteArray>>()
    multipart.readAllParts().forEach { part ->
        when (part) {
            is PartData.FormItem -> fields[part.name!!] = part.value
            is PartData.FileItem -> {
                val baos = ByteArrayOutputStream()
                part.streamProvider().use { it.copyTo(baos) }
                files += (part.originalFileName ?: "file") to baos.toByteArray()
            }
            else -> Unit
        }
        part.dispose()
    }
    return MultiPartRead(fields, files)
}

fun Application.webModule(service: ReviewService) {
    routing {
        get("/") { call.respondRedirect("/review?branch=br_main") }
        get("/health") { call.respondText("ok", ContentType.Text.Plain) }

        get("/review") {
            val branch = call.request.queryParameters["branch"] ?: "br_main"
            val asOf = call.request.queryParameters["asOf"]?.toLongOrNull()
            val candidateLineage = call.request.queryParameters["candidate"]
            val frameIdx = call.request.queryParameters["frame"]?.toIntOrNull() ?: 0
            val state = service.state(branch, asOf)
            val html = ReviewHtml.render(state, branch, asOf, candidateLineage, frameIdx,
                call.request.queryParameters["msg"])
            call.respondText(html, ContentType.Text.Html)
        }

        post("/import") {
            val mp = readMultipartSafe(call)
            val label = mp.fields["label"] ?: "imported"
            val branch = mp.fields["branch"] ?: "br_main"
            val baseHead = mp.fields["baseHead"]?.ifBlank { null }
            try {
                service.importMaterials(mp.files, label, branch, baseHead)
                call.respondRedirect("/review?branch=$branch&msg=imported")
            } catch (e: ImportRejected) {
                call.respond(HttpStatusCode.Conflict, conflictPage(e.message ?: "import rejected and rolled back"))
            } catch (e: ConcurrentModification) {
                call.respond(HttpStatusCode.Conflict, conflictPage(e.message ?: "version conflict"))
            }
        }

        post("/reinterpret") {
            val mp = readMultipartSafe(call)
            val branch = mp.fields["branch"] ?: "br_main"
            val label = mp.fields["label"] ?: "reinterpreted"
            val baseHead = mp.fields["baseHead"]?.ifBlank { null }
            try {
                service.reinterpret(mp.files, label, branch, baseHead)
                call.respondRedirect("/review?branch=$branch&msg=reinterpreted")
            } catch (e: ImportRejected) {
                call.respond(HttpStatusCode.Conflict, conflictPage(e.message ?: "reinterpret rejected"))
            } catch (e: ConcurrentModification) {
                call.respond(HttpStatusCode.Conflict, conflictPage(e.message ?: "version conflict"))
            }
        }

        post("/params") {
            val p = call.receiveParameters()
            val branch = p["branch"] ?: "br_main"
            val newParams = GroupingParameters(
                orthogonalityTolerance = p["orthogonalityTolerance"]!!.toDouble(),
                unitNormalTolerance = p["unitNormalTolerance"]!!.toDouble(),
                duplicatePositionToleranceMm = p["duplicatePositionToleranceMm"]!!.toDouble(),
                spacingJumpRatio = p["spacingJumpRatio"]!!.toDouble(),
                parameterVersion = "params-manual"
            )
            try {
                service.reviseParameters(newParams, branch, p["baseHead"])
                call.respondRedirect("/review?branch=$branch&msg=params")
            } catch (e: ConcurrentModification) {
                call.respond(HttpStatusCode.Conflict, conflictPage(e.message ?: "version conflict"))
            }
        }

        post("/decide") {
            val p = call.receiveParameters()
            val branch = p["branch"] ?: "br_main"
            try {
                service.decide(p["lineage"]!!, p["verdict"]!!, p["rationale"] ?: "", branch,
                    p["baseHead"], p["candidateId"])
                call.respondRedirect("/review?branch=$branch&candidate=${java.net.URLEncoder.encode(p["lineage"]!!, "UTF-8")}&msg=decided")
            } catch (e: ConcurrentModification) {
                call.respond(HttpStatusCode.Conflict, conflictPage(e.message ?: "version conflict"))
            }
        }

        post("/revert") {
            val p = call.receiveParameters()
            val branch = p["branch"] ?: "br_main"
            try {
                service.revertDecision(p["targetEventId"]!!, branch, p["baseHead"])
                call.respondRedirect("/review?branch=$branch&msg=reverted")
            } catch (e: ConcurrentModification) {
                call.respond(HttpStatusCode.Conflict, conflictPage(e.message ?: "version conflict"))
            }
        }

        post("/fork") {
            val p = call.receiveParameters()
            val branch = p["branch"] ?: "br_main"
            try {
                val (newBranch, _) = service.forkBranch(p["name"] ?: "branch", branch, p["baseHead"]!!)
                call.respondRedirect("/review?branch=${newBranch.branchId}&msg=forked")
            } catch (e: ConcurrentModification) {
                call.respond(HttpStatusCode.Conflict, conflictPage(e.message ?: "version conflict"))
            }
        }

        get("/export") {
            val bytes = service.exportBundle()
            call.response.header("Content-Disposition",
                ContentDisposition.Attachment.withParameter(ContentDisposition.Parameters.FileName, "dicom-review-bundle.zip").toString())
            call.respondBytes(bytes, ContentType.Application.Zip, HttpStatusCode.OK)
        }

        post("/importBundle") {
            val mp = readMultipartSafe(call)
            val bundle = mp.files.firstOrNull()?.second
                ?: return@post call.respond(HttpStatusCode.BadRequest, conflictPage("missing bundle zip"))
            try {
                service.importBundle(bundle)
                call.respondRedirect("/review?branch=br_main&msg=bundle")
            } catch (e: Exception) {
                call.respond(HttpStatusCode.Conflict, conflictPage("bundle import rejected (rolled back): ${e.message}"))
            }
        }
    }
}

private fun conflictPage(message: String): String = """
<!doctype html><html lang='zh'><head><meta charset='utf-8'><title>版本冲突</title>
<style>body{font-family:ui-sans-serif,system-ui;margin:40px} .box{border:2px solid #c0392b;padding:20px;max-width:760px}</style>
</head><body><div class='box' data-id='conflict-panel'>
<h1>版本冲突（后提交者）</h1>
<p data-id='conflict-message'>${escapeHtml(message)}</p>
<p>另一窗口已经提交；你的裁定没有覆盖对方。请返回复核页查看双方差异后，在最新版本上重试。</p>
<p><a href='/' data-id='conflict-back'>返回复核</a></p>
</div></body></html>
""".trimIndent()

fun escapeHtml(s: String?): String = (s ?: "").replace("&", "&amp;").replace("<", "&lt;")
    .replace(">", "&gt;").replace("\"", "&quot;")

fun startServer(service: ReviewService, host: String, port: Int) {
    embeddedServer(Netty, host = host, port = port) { webModule(service) }.start(wait = true)
}
