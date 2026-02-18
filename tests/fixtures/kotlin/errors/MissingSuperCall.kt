// FIXTURE: errors/MissingSuperCall.kt
// EXPECT-ERROR: line 9, "constructor call expected"

package fixtures.errors

open class Base(val value: Int)

class Derived : Base {
    // Missing super constructor call
}
