// FIXTURE: warnings/UnusedParameter.kt
// EXPECT-WARNING: line 6, "unused"

package fixtures.warnings

fun greet(name: String, unused: Int): String {
    return "Hello, $name!"
}
