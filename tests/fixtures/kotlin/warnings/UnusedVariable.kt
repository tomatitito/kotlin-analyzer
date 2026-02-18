// FIXTURE: warnings/UnusedVariable.kt
// EXPECT-WARNING: line 7, "unused"

package fixtures.warnings

fun main() {
    val unused = 42
    println("hello")
}
