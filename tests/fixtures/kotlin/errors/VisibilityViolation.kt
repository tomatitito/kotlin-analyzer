// FIXTURE: errors/VisibilityViolation.kt
// EXPECT-ERROR: line 12, "private"

package fixtures.errors

class Secret {
    private val hidden = "secret"
    private fun whisper(): String = hidden
}

fun main() {
    val s = Secret()
    println(s.hidden)
}
