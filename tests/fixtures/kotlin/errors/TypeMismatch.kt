// FIXTURE: errors/TypeMismatch.kt
// EXPECT-ERROR: line 8, "Type mismatch"
// EXPECT-ERROR: line 11, "Type mismatch"

package fixtures.errors

fun main() {
    val x: String = 42
    println(x)

    val y: Int = "hello"
    println(y)
}
