// FIXTURE: correct/Collections.kt
// EXPECT: no-diagnostics

package fixtures.correct

fun demonstrateCollections() {
    // List operations
    val numbers = listOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10)
    val evens = numbers.filter { it % 2 == 0 }
    val doubled = numbers.map { it * 2 }
    val sum = numbers.reduce { acc, n -> acc + n }
    val grouped = numbers.groupBy { if (it % 2 == 0) "even" else "odd" }

    // Map operations
    val userScores = mapOf("Alice" to 95, "Bob" to 87, "Charlie" to 92)
    val highScores = userScores.filter { (_, score) -> score >= 90 }
    val names = userScores.keys.sorted()

    // Set operations
    val setA = setOf(1, 2, 3, 4, 5)
    val setB = setOf(4, 5, 6, 7, 8)
    val union = setA union setB
    val intersection = setA intersect setB
    val difference = setA subtract setB

    // Mutable collections
    val mutableList = mutableListOf(1, 2, 3)
    mutableList.add(4)
    mutableList.removeAt(0)
    mutableList += 5

    val mutableMap = mutableMapOf("a" to 1)
    mutableMap["b"] = 2
    mutableMap.putIfAbsent("a", 99)

    // Collection transformations
    val flatMapped = listOf(listOf(1, 2), listOf(3, 4)).flatMap { it }
    val associated = listOf("apple", "banana", "cherry").associateBy { it.first() }
    val zipped = listOf(1, 2, 3).zip(listOf("a", "b", "c"))
    val chunked = (1..10).toList().chunked(3)
    val windowed = (1..5).toList().windowed(3)

    // Sequence (lazy evaluation)
    val lazyResult = numbers.asSequence()
        .filter { it > 3 }
        .map { it * it }
        .take(3)
        .toList()

    // Collection builders
    val built = buildList {
        add(1)
        addAll(listOf(2, 3))
        add(4)
    }

    println("$evens, $doubled, $sum, $grouped")
    println("$highScores, $names")
    println("$union, $intersection, $difference")
    println("$mutableList, $mutableMap")
    println("$flatMapped, $associated, $zipped, $chunked, $windowed")
    println("$lazyResult, $built")
}
