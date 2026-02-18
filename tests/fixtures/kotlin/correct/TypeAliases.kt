// FIXTURE: correct/TypeAliases.kt
// EXPECT: no-diagnostics

package fixtures.correct

// Simple type alias
typealias Username = String
typealias Age = Int

// Generic type alias
typealias StringMap<V> = Map<String, V>
typealias Predicate<T> = (T) -> Boolean

// Function type alias
typealias ClickHandler = (x: Int, y: Int) -> Unit
typealias Transformer<T, R> = (T) -> R

// Nested generic type alias
typealias UserList = List<Pair<Username, Age>>

fun demonstrateTypeAliases() {
    val name: Username = "alice"
    val age: Age = 30

    val config: StringMap<Any> = mapOf(
        "name" to name,
        "age" to age,
        "active" to true
    )

    val isAdult: Predicate<Int> = { it >= 18 }
    val toUpper: Transformer<String, String> = { it.uppercase() }

    val onClick: ClickHandler = { x, y ->
        println("Clicked at ($x, $y)")
    }

    val users: UserList = listOf(
        "alice" to 30,
        "bob" to 25
    )

    onClick(10, 20)
    println("$name, $age, $config, ${isAdult(age)}, ${toUpper(name)}, $users")
}
