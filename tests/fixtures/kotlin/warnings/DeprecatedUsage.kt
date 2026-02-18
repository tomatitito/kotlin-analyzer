// FIXTURE: warnings/DeprecatedUsage.kt
// EXPECT-WARNING: line 11, "deprecated"

package fixtures.warnings

@Deprecated("Use newWay() instead")
fun oldWay(): String = "old"

fun newWay(): String = "new"

fun main() {
    println(oldWay())
}
