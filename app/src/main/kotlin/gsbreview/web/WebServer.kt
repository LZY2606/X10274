package gsbreview.web

import gsbreview.AppService
import gsbreview.events.VersionConflict
import gsbreview.imports.IncomingFile
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.request.receiveMultipart
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondOutputStream
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.http.content.PartData
import io.ktor.http.content.readAllParts
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import io.ktor.server.request.receiveText

fun startServer(app: AppService, host: String, port: Int) {
    embeddedServer(Netty, host = host, port = port) {
        install(ContentNegotiation) {
            json(Json { prettyPrint = false; encodeDefaults = true })
        }
        routing {
            get("/") {
                val html = WebServer::class.java.classLoader
                    .getResourceAsStream("web/index.html")!!.readBytes()
                call.respondBytes(html, ContentType.Text.Html)
            }
            get("/web/{file...}") {
                val rel = call.parameters.getAll("file")?.joinToString("/") ?: "index.html"
                val resource = WebServer::class.java.classLoader.getResourceAsStream("web/$rel")
                if (resource == null) {
                    call.respond(HttpStatusCode.NotFound, "not found")
                } else {
                    val ct = when {
                        rel.endsWith(".js") -> ContentType.Application.JavaScript
                        rel.endsWith(".css") -> ContentType.Text.CSS
                        else -> ContentType.Text.Html
                    }
                    call.respondBytes(resource.readBytes(), ct)
                }
            }

            get("/api/state") {
                val branch = call.request.queryParameters["branch"] ?: app.eventStore.mainBranchId
                val asOf = call.request.queryParameters["asOf"]?.toLongOrNull()
                call.respond(stateJson(app, branch, asOf))
            }

            get("/api/branches") {
                call.respond(JsonArray(app.eventStore.allBranches().map {
                    JsonObject(mapOf(
                        "branchId" to JsonPrimitive(it.branchId),
                        "name" to JsonPrimitive(it.name),
                        "parent" to (it.parent?.let(::JsonPrimitive) ?: JsonNull),
                        "forkedAfter" to (it.forkedAfter?.let { v -> JsonPrimitive(v) } ?: JsonNull)
                    ))
                }))
            }

            post("/api/import") {
                val multipart = call.receiveMultipart()
                val files = mutableListOf<IncomingFile>()
                var caseId: String? = null
                var note = ""
                var stage = "initial"
                multipart.forEachPart { part ->
                    when (part) {
                        is PartData.FormItem -> when (part.name) {
                            "caseId" -> caseId = part.value
                            "note" -> note = part.value
                            "stage" -> stage = part.value
                        }
                        is PartData.FileItem -> {
                            val bytes = part.provider().readBytes()
                            files += IncomingFile(part.originalFileName ?: "file", bytes)
                        }
                        else -> {}
                    }
                    part.dispose()
                }
                try {
                    val outcome = app.importAndInterpret(files, caseId, note.ifBlank { "manual import" }, stage)
                    call.respond(buildJsonObject {
                        put("batchId", JsonPrimitive(outcome.batchId))
                        put("interpretationId", JsonPrimitive(outcome.interpretationId))
                        put("eventSeq", JsonPrimitive(outcome.eventSeq))
                        put("uidConflicts", JsonArray(outcome.uidConflicts.map(::JsonPrimitive)))
                    })
                } catch (t: Throwable) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to (t.message ?: "import failed")))
                }
            }

            post("/api/decision") {
                val body = Json.parseToJsonElement(call.receiveText()).jsonObject
                try {
                    val seq = app.decide(
                        candidateId = body["candidateId"]!!.jsonPrimitive.content,
                        accept = body["decision"]!!.jsonPrimitive.content == "accept",
                        note = body["note"]?.jsonPrimitive?.content,
                        actor = body["actor"]?.jsonPrimitive?.content ?: "reviewer",
                        branchId = body["branchId"]?.jsonPrimitive?.content ?: app.eventStore.mainBranchId,
                        baseSeq = body["baseSeq"]!!.jsonPrimitive.content.toLong()
                    )
                    call.respond(mapOf("eventSeq" to seq))
                } catch (t: VersionConflict) {
                    call.respond(HttpStatusCode.Conflict, conflictJson(t))
                } catch (t: Throwable) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to (t.message ?: "decision failed")))
                }
            }

            post("/api/undo") {
                val body = Json.parseToJsonElement(call.receiveText()).jsonObject
                try {
                    val seq = app.undoLastDecision(
                        actor = body["actor"]?.jsonPrimitive?.content ?: "reviewer",
                        branchId = body["branchId"]?.jsonPrimitive?.content ?: app.eventStore.mainBranchId,
                        baseSeq = body["baseSeq"]!!.jsonPrimitive.content.toLong(),
                        targetSeq = body["targetSeq"]!!.jsonPrimitive.content.toLong()
                    )
                    call.respond(mapOf("eventSeq" to seq))
                } catch (t: VersionConflict) {
                    call.respond(HttpStatusCode.Conflict, conflictJson(t))
                }
            }

            post("/api/parameters") {
                val body = Json.parseToJsonElement(call.receiveText()).jsonObject
                val overrides = body["overrides"]!!.jsonObject.mapValues { it.value.jsonPrimitive.content.toDouble() }
                try {
                    val seq = app.reviseParameters(
                        overrides = overrides,
                        note = body["note"]?.jsonPrimitive?.content ?: "parameter revision",
                        actor = body["actor"]?.jsonPrimitive?.content ?: "reviewer",
                        branchId = body["branchId"]?.jsonPrimitive?.content ?: app.eventStore.mainBranchId,
                        baseSeq = body["baseSeq"]!!.jsonPrimitive.content.toLong()
                    )
                    call.respond(mapOf("eventSeq" to seq))
                } catch (t: VersionConflict) {
                    call.respond(HttpStatusCode.Conflict, conflictJson(t))
                }
            }

            post("/api/branch") {
                val body = Json.parseToJsonElement(call.receiveText()).jsonObject
                val id = app.forkBranch(
                    name = body["name"]!!.jsonPrimitive.content,
                    fromBranch = body["fromBranch"]?.jsonPrimitive?.content ?: app.eventStore.mainBranchId,
                    atSeq = body["atSeq"]!!.jsonPrimitive.content.toLong()
                )
                call.respond(mapOf("branchId" to id))
            }

            get("/api/subject/{id}/tags") {
                val id = call.parameters["id"]!!
                val rows = app.tags(id)
                call.respond(JsonArray(rows.map { r ->
                    JsonObject(mapOf(
                        "tagPath" to JsonPrimitive(r.tagPath),
                        "tagName" to (r.tagName?.let(::JsonPrimitive) ?: JsonNull),
                        "vr" to (r.vr?.let(::JsonPrimitive) ?: JsonNull),
                        "rawRepr" to JsonPrimitive(r.rawRepr),
                        "kind" to JsonPrimitive(r.kind),
                        "sourceId" to JsonPrimitive(r.sourceId),
                        "relPath" to JsonPrimitive(r.relPath)
                    ))
                }))
            }

            get("/api/source/{id}/bytes") {
                val id = call.parameters["id"]!!
                val meta = app.store.sourceMeta(id)
                if (meta == null) call.respond(HttpStatusCode.NotFound, "unknown source")
                else call.respondBytes(app.store.sourceBytes(id), ContentType.Application.OctetStream)
            }
        }
    }.start(wait = true)
}

private fun conflictJson(t: VersionConflict): JsonObject = JsonObject(mapOf(
    "error" to JsonPrimitive("version conflict"),
    "message" to JsonPrimitive(t.view.message),
    "attemptedBaseSeq" to JsonPrimitive(t.view.attemptedBaseSeq),
    "currentHeadSeq" to JsonPrimitive(t.view.currentHeadSeq),
    "remoteEvents" to JsonArray(t.view.remoteEvents.map {
        JsonObject(mapOf(
            "seq" to JsonPrimitive(it.eventSeq),
            "type" to JsonPrimitive(it.eventType),
            "actor" to JsonPrimitive(it.actor),
            "payload" to (Json.parseToJsonElement(it.payloadJson) as JsonObject)
        ))
    })
))
