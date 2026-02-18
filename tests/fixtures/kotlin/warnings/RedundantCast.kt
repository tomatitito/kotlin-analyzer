// FIXTURE: warnings/RedundantCast.kt
// EXPECT-WARNING: line 7, "redundant"

package fixtures.warnings

fun main() {
    val x: String = "hello" as String
    println(x)
}
