// FIXTURE: correct/Lambdas.kt
// EXPECT: no-diagnostics

package fixtures.correct

// Higher-order function
fun <T, R> transform(value: T, operation: (T) -> R): R = operation(value)

// Function returning a function
fun multiplier(factor: Int): (Int) -> Int = { it * factor }

// Function with multiple function parameters
fun <T> applyAndCombine(
    value: T,
    first: (T) -> String,
    second: (T) -> String,
    combine: (String, String) -> String
): String = combine(first(value), second(value))

fun demonstrateLambdas() {
    // Lambda syntax
    val square = { x: Int -> x * x }
    val sum = { a: Int, b: Int -> a + b }

    // Trailing lambda
    val result = transform(5) { it * 2 }

    // Lambda with explicit type
    val typedLambda: (String) -> Int = { it.length }

    // Function reference
    val words = listOf("hello", "world", "kotlin")
    val lengths = words.map(String::length)

    // Closure (capturing variables)
    var count = 0
    val increment = { count++ }
    increment()
    increment()

    // Returning from lambda vs enclosing function
    val numbers = listOf(1, 2, 3, 4, 5)
    numbers.forEach { num ->
        if (num == 3) return@forEach // Returns from lambda, not enclosing function
        println(num)
    }

    // Anonymous function
    val filtered = numbers.filter(fun(x): Boolean {
        return x > 2
    })

    // Destructuring in lambdas
    val pairs = mapOf("a" to 1, "b" to 2)
    pairs.forEach { (key, value) ->
        println("$key -> $value")
    }

    // Higher-order function with trailing lambda
    val double = multiplier(2)
    val triple = multiplier(3)

    val combined = applyAndCombine(
        42,
        { "Value: $it" },
        { "Square: ${it * it}" }
    ) { a, b -> "$a | $b" }

    println("$square, ${sum(1, 2)}, $result, ${typedLambda("hi")}")
    println("$lengths, $count, $filtered")
    println("${double(5)}, ${triple(5)}, $combined")
}
