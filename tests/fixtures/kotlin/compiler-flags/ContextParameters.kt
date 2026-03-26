// FIXTURE: compiler-flags/ContextParameters.kt
// REQUIRES-FLAG: -Xcontext-parameters
// EXPECT-ERROR-WITHOUT-FLAG: syntax not recognized
// EXPECT-WITH-FLAG: feature flag accepted (no syntax diagnostics)
// EXPECT-WITH-FLAG: usage remains semantic-error (inferred context-parameter type is not inferred at use-site in this fixture)

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
