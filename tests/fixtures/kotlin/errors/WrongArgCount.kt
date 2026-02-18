// FIXTURE: errors/WrongArgCount.kt
// EXPECT-ERROR: line 10, "argument"
// EXPECT-ERROR: line 11, "argument"

package fixtures.errors

fun add(a: Int, b: Int): Int = a + b

fun main() {
    add(1)
    add(1, 2, 3)
}
