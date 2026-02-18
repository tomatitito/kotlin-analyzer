// FIXTURE: correct/OutlineShowcase.kt
// EXPECT: no-diagnostics
//
// This file is used for outline query validation.
// Expected outline items: package, TopLevelFunction, Greeter, Greeter.greet,
// Shape (interface), Circle, Circle.area, Circle.Companion, Circle.Companion.unit,
// AnimalType (enum), AnimalType.DOG, AnimalType.CAT, AnimalType.BIRD,
// Registry (object), Registry.entries, Registry.register,
// Color (sealed class), Color.Red, Color.Green, Color.Blue, Color.Custom,
// NestedOuter, NestedOuter.Inner, NestedOuter.Inner.value,
// topLevelVal, topLevelVar

package fixtures.correct

fun topLevelFunction(x: Int): String = "Result: $x"

val topLevelVal: Int = 42

var topLevelVar: String = "hello"

class Greeter(val name: String) {
    fun greet(): String = "Hello, $name!"
}

interface Shape {
    fun area(): Double
}

class Circle(val radius: Double) : Shape {
    override fun area(): Double = Math.PI * radius * radius

    companion object {
        fun unit(): Circle = Circle(1.0)
    }
}

enum class AnimalType {
    DOG,
    CAT,
    BIRD
}

object Registry {
    private val entries = mutableListOf<String>()

    fun register(name: String) {
        entries.add(name)
    }
}

sealed class Color {
    data object Red : Color()
    data object Green : Color()
    data object Blue : Color()
    data class Custom(val hex: String) : Color()
}

class NestedOuter {
    class Inner {
        val value = "inner"
    }
}

typealias StringList = List<String>
