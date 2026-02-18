// FIXTURE: errors/NullViolation.kt
// EXPECT-ERROR: line 7, "Null"

package fixtures.errors

fun main() {
    val x: String = null
    println(x)
}
