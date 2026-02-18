// FIXTURE: errors/DuplicateDeclaration.kt
// EXPECT-ERROR: line 6, "Conflicting declarations"
// EXPECT-ERROR: line 7, "Conflicting declarations"

package fixtures.errors

fun duplicate(x: Int): Int = x
fun duplicate(x: Int): Int = x + 1
