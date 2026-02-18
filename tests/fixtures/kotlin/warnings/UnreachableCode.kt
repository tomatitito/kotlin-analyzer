// FIXTURE: warnings/UnreachableCode.kt
// EXPECT-WARNING: line 8, "unreachable"

package fixtures.warnings

fun test(): Int {
    return 42
    println("this is unreachable")
}
