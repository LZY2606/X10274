package org.gsb.dicomreview

import java.nio.file.Path
import org.gsb.dicomreview.review.ReviewService
import org.gsb.dicomreview.store.Store
import org.gsb.dicomreview.web.startServer

fun main(args: Array<String>) {
    var host = "127.0.0.1"
    var port = 5373
    var db = "data/dicom-review.sqlite"
    var generateCases: String? = null
    var i = 0
    while (i < args.size) {
        when (args[i]) {
            "--host" -> { host = args[++i] }
            "--port" -> { port = args[++i].toInt() }
            "--db" -> { db = args[++i] }
            "--generate-cases" -> { generateCases = args.getOrNull(++i) ?: "cases" }
        }
        i++
    }
    if (generateCases != null) {
        org.gsb.dicomreview.cases.CasePackGenerator.generate(Path.of(generateCases))
        println("case pack written to $generateCases")
        if (args.contains("--generate-and-exit")) return
    }
    val store = Store(Path.of(db))
    val service = ReviewService(store)
    println("DICOM geometry review listening on http://$host:$port (db=$db)")
    startServer(service, host, port)
}
