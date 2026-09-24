package gsbreview

import gsbreview.db.Database
import gsbreview.db.Store
import gsbreview.web.startServer
import java.nio.file.Paths

fun main(args: Array<String>) {
    var host = "127.0.0.1"
    var port = 5373
    var dbPath = "data/gsbreview.sqlite"
    var i = 0
    while (i < args.size) {
        when (args[i]) {
            "--host" -> { host = args[i + 1]; i += 2 }
            "--port" -> { port = args[i + 1].toInt(); i += 2 }
            "--db" -> { dbPath = args[i + 1]; i += 2 }
            else -> i += 1
        }
    }
    val conn = Database.open(Paths.get(dbPath))
    val app = AppService(Store(conn))
    println("DICOM GSB review listening on http://$host:$port (db=$dbPath)")
    startServer(app, host, port)
}
