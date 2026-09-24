package app

fun main(args: Array<String>) {
    var host = "127.0.0.1"
    var port = 5373
    var i = 0
    while (i < args.size) {
        when (args[i]) {
            "--host" -> { host = args[i + 1]; i += 2 }
            "--port" -> { port = args[i + 1].toInt(); i += 2 }
            else -> i++
        }
    }
    println("starting on $host:$port")
}
