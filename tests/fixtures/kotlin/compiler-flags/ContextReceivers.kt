// FIXTURE: compiler-flags/ContextReceivers.kt
// REQUIRES-FLAG: -Xcontext-receivers
// EXPECT-ERROR-WITHOUT-FLAG: syntax not recognized
// EXPECT-WITH-FLAG: no-diagnostics

package fixtures.compilerflags

interface LoggingContext {
    fun log(message: String)
}

context(LoggingContext)
fun performAction(action: String) {
    log("Performing: $action")
}

class ConsoleLogging : LoggingContext {
    override fun log(message: String) = println("[LOG] $message")
}

fun main() {
    with(ConsoleLogging()) {
        performAction("initialize")
    }
}
