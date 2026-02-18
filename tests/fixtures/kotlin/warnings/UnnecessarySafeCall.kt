// FIXTURE: warnings/UnnecessarySafeCall.kt
// EXPECT-WARNING: line 7, "unnecessary safe call"

package fixtures.warnings

fun main() {
    val x: String = "hello"
    println(x?.length)
}
