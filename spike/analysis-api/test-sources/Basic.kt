package test

fun greet(name: String): String {
    return "Hello, $name!"
}

fun main() {
    val message: Int = greet("World") // Type error: String assigned to Int
    println(message)
}
