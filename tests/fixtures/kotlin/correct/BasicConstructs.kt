// FIXTURE: correct/BasicConstructs.kt
// EXPECT: no-diagnostics

package fixtures.correct

fun greet(name: String): String {
    return "Hello, $name!"
}

fun demonstrateBasics() {
    // val and var
    val immutable = 42
    var mutable = "hello"
    mutable = "world"

    // String templates
    val message = "The answer is $immutable and the word is $mutable"
    val complex = "Length: ${mutable.length}"

    // if expression
    val max = if (immutable > 10) immutable else 10

    // when expression
    val description = when (immutable) {
        0 -> "zero"
        in 1..10 -> "small"
        in 11..100 -> "medium"
        else -> "large"
    }

    // for loop
    for (i in 1..5) {
        println("Count: $i")
    }

    // while loop
    var counter = 0
    while (counter < 3) {
        counter++
    }

    // do-while loop
    do {
        counter--
    } while (counter > 0)

    // Ranges
    val range = 1..10
    val downRange = 10 downTo 1
    val stepped = 1..100 step 5

    println(greet("Kotlin"))
    println(description)
    println(max)
}
