// FIXTURE: correct/TestClass.kt
// EXPECT: no-diagnostics
//
// This file is used for runnables detection of @Test functions.
// Note: Requires JUnit on classpath for semantic validation (M2+).
// For tree-sitter query testing (M1), the annotation syntax is sufficient.

package fixtures.correct

// Simulated annotation for tree-sitter testing (no JUnit dependency needed)
annotation class Test

class CalculatorTest {

    @Test
    fun testAddition() {
        val result = 2 + 2
        assert(result == 4)
    }

    @Test
    fun testSubtraction() {
        val result = 5 - 3
        assert(result == 2)
    }

    @Test
    fun testMultiplication() {
        val result = 3 * 4
        assert(result == 12)
    }

    fun helperMethod(): Int = 42 // Not a test, no @Test annotation
}
