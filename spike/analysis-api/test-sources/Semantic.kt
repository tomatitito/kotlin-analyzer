package test

/** A simple greeter that says hello. */
class Greeter(val name: String) {
    /** Returns a greeting message. */
    fun greet(): String {
        return "Hello, $name!"
    }

    fun greetTimes(n: Int): List<String> {
        return (1..n).map { greet() }
    }
}

fun main() {
    val greeter = Greeter("World")
    val message = greeter.greet()
    println(message)
}
