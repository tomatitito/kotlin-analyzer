// FIXTURE: correct/DataAndSealed.kt
// EXPECT: no-diagnostics

package fixtures.correct

data class Point(val x: Double, val y: Double) {
    operator fun plus(other: Point): Point = Point(x + other.x, y + other.y)
}

data class User(val name: String, val email: String, val age: Int)

sealed class Result<out T> {
    data class Success<T>(val value: T) : Result<T>()
    data class Failure(val error: String) : Result<Nothing>()
    data object Loading : Result<Nothing>()
}

sealed interface Event {
    data class Click(val x: Int, val y: Int) : Event
    data class KeyPress(val key: Char) : Event
    data object Close : Event
}

enum class Direction(val dx: Int, val dy: Int) {
    NORTH(0, 1),
    SOUTH(0, -1),
    EAST(1, 0),
    WEST(-1, 0);

    fun opposite(): Direction = when (this) {
        NORTH -> SOUTH
        SOUTH -> NORTH
        EAST -> WEST
        WEST -> EAST
    }
}

fun demonstrateDataAndSealed() {
    // Data class
    val p1 = Point(1.0, 2.0)
    val p2 = Point(3.0, 4.0)
    val p3 = p1 + p2
    val (x, y) = p3 // Destructuring

    // Data class copy
    val user = User("Alice", "alice@example.com", 30)
    val updated = user.copy(age = 31)

    // Sealed class with when
    val result: Result<String> = Result.Success("data")
    val message = when (result) {
        is Result.Success -> "Got: ${result.value}"
        is Result.Failure -> "Error: ${result.error}"
        Result.Loading -> "Loading..."
    }

    // Sealed interface with when
    val event: Event = Event.Click(10, 20)
    when (event) {
        is Event.Click -> println("Clicked at (${event.x}, ${event.y})")
        is Event.KeyPress -> println("Key: ${event.key}")
        Event.Close -> println("Closing")
    }

    // Enum
    val dir = Direction.NORTH
    println("Opposite of $dir is ${dir.opposite()}")
    println("$x, $y, $message, $updated")
}
