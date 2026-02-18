// FIXTURE: correct/Generics.kt
// EXPECT: no-diagnostics

package fixtures.correct

// Generic class with variance
class Box<out T>(val value: T) {
    fun get(): T = value
}

// Generic interface with contravariance
interface Comparator<in T> {
    fun compare(a: T, b: T): Int
}

// Generic function with type bound
fun <T : Comparable<T>> findMax(list: List<T>): T? {
    if (list.isEmpty()) return null
    var max = list[0]
    for (item in list.drop(1)) {
        if (item > max) max = item
    }
    return max
}

// Multiple type parameters with bounds
fun <K, V : Comparable<V>> sortByValue(map: Map<K, V>): List<Map.Entry<K, V>> {
    return map.entries.sortedBy { it.value }
}

// Star projection
fun printAll(list: List<*>) {
    for (item in list) {
        println(item)
    }
}

// Reified type parameter
inline fun <reified T> isInstance(value: Any): Boolean = value is T

// Generic class with multiple constraints
class Repository<T> where T : Comparable<T>, T : Any {
    private val items = mutableListOf<T>()

    fun add(item: T) {
        items.add(item)
        items.sort()
    }

    fun getAll(): List<T> = items.toList()
}

fun demonstrateGenerics() {
    val stringBox: Box<String> = Box("hello")
    val anyBox: Box<Any> = stringBox // Covariance: Box<String> is Box<Any>

    val maxInt = findMax(listOf(3, 1, 4, 1, 5, 9))
    val maxStr = findMax(listOf("banana", "apple", "cherry"))

    val map = mapOf("c" to 3, "a" to 1, "b" to 2)
    val sorted = sortByValue(map)

    printAll(listOf(1, "two", 3.0))

    println("Is String: ${isInstance<String>("hello")}")
    println("Is Int: ${isInstance<Int>("hello")}")

    val repo = Repository<Int>()
    repo.add(3)
    repo.add(1)
    repo.add(2)

    println("${anyBox.get()}, $maxInt, $maxStr, $sorted, ${repo.getAll()}")
}
