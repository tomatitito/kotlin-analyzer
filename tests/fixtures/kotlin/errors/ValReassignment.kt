// FIXTURE: errors/ValReassignment.kt
// EXPECT-ERROR: line 8, "Val cannot be reassigned"

package fixtures.errors

fun main() {
    val x = 42
    x = 99
}
