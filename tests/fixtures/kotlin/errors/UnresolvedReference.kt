// FIXTURE: errors/UnresolvedReference.kt
// EXPECT-ERROR: line 7, "Unresolved reference"

package fixtures.errors

fun main() {
    doesNotExist()
}
