// FIXTURE: warnings/RedundantIf.kt
// EXPECT-WARNING: line 7, "redundant"

package fixtures.warnings

fun isPositive(x: Int): Boolean {
    return if (x > 0) true else false
}
