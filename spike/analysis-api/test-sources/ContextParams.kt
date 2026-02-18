package test

class Logger(val prefix: String) {
    fun log(message: String) {
        println("[$prefix] $message")
    }
}

context(logger: Logger)
fun doWork() {
    logger.log("Doing some work...")
}

fun main() {
    with(Logger("APP")) {
        doWork()
    }
}
