// FIXTURE: errors/MissingOverride.kt
// EXPECT-ERROR: line 10, "abstract member"

package fixtures.errors

interface Greeter {
    fun greet(): String
}

class SimpleGreeter : Greeter {
    // Missing: override fun greet(): String
}
