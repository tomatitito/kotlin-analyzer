// FIXTURE: compiler-flags/MultiDollarInterpolation.kt
// REQUIRES-FLAG: -Xmulti-dollar-interpolation
// EXPECT-ERROR-WITHOUT-FLAG: unexpected $
// EXPECT-WITH-FLAG: no-diagnostics

package fixtures.compilerflags

fun main() {
    val name = "World"
    // With multi-dollar interpolation, $$ is treated as a literal $
    val template = $$"Hello, $name! Use $$name for interpolation."
    println(template)
}
