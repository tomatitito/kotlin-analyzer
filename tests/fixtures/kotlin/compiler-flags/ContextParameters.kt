// FIXTURE: compiler-flags/ContextParameters.kt
// REQUIRES-FLAG: -Xcontext-parameters
// EXPECT-ERROR-WITHOUT-FLAG: syntax not recognized
// EXPECT-WITH-FLAG: no-diagnostics

package fixtures.compilerflags

class Logger(val prefix: String) {
    fun log(message: String) = println("[$prefix] $message")
}

context(logger: Logger)
fun greet(name: String) {
    logger.log("Hello, $name!")
}

fun main() {
    with(Logger("APP")) {
        greet("World")
    }
}
