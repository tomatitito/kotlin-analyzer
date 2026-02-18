// FIXTURE: correct/Delegation.kt
// EXPECT: no-diagnostics

package fixtures.correct

import kotlin.properties.Delegates

// Interface delegation
interface Logger {
    fun log(message: String)
}

class ConsoleLogger : Logger {
    override fun log(message: String) = println("[LOG] $message")
}

class Service(logger: Logger) : Logger by logger {
    fun doWork() {
        log("Starting work")
        // ... work ...
        log("Work completed")
    }
}

// Property delegation with lazy
class ExpensiveResource {
    val data: String by lazy {
        println("Computing data...")
        "expensive result"
    }
}

// Observable property delegation
class UserProfile {
    var name: String by Delegates.observable("unnamed") { _, old, new ->
        println("Name changed from '$old' to '$new'")
    }

    var age: Int by Delegates.vetoable(0) { _, _, new ->
        new >= 0 // Only accept non-negative ages
    }
}

// Map delegation
class Config(map: Map<String, Any?>) {
    val host: String by map
    val port: Int by map
    val debug: Boolean by map
}

fun demonstrateDelegation() {
    // Interface delegation
    val service = Service(ConsoleLogger())
    service.doWork()

    // Lazy delegation
    val resource = ExpensiveResource()
    println(resource.data) // Triggers lazy computation
    println(resource.data) // Uses cached value

    // Observable delegation
    val profile = UserProfile()
    profile.name = "Alice"
    profile.age = 30
    profile.age = -1 // Vetoed, stays 30

    // Map delegation
    val config = Config(mapOf(
        "host" to "localhost",
        "port" to 8080,
        "debug" to true
    ))
    println("${config.host}:${config.port} (debug=${config.debug})")
}
