// FIXTURE: errors/ReturnTypeMismatch.kt
// EXPECT-ERROR: line 7, "Type mismatch"

package fixtures.errors

fun getNumber(): Int {
    return "not a number"
}
