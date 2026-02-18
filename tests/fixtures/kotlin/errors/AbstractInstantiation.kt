// FIXTURE: errors/AbstractInstantiation.kt
// EXPECT-ERROR: line 10, "abstract class"

package fixtures.errors

abstract class Animal {
    abstract fun speak(): String
}

val pet = Animal()
