class Greeter(
    val name: String,
) {
    fun greet(): String {
        return "Hello, $name!"
    }
}

fun main() {
    val greeter = Greeter("World")
    println(greeter.greet())
}
