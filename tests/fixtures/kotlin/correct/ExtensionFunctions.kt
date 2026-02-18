// FIXTURE: correct/ExtensionFunctions.kt
// EXPECT: no-diagnostics

package fixtures.correct

// Extension function on String
fun String.isPalindrome(): Boolean {
    val cleaned = this.lowercase().filter { it.isLetterOrDigit() }
    return cleaned == cleaned.reversed()
}

// Extension property
val String.wordCount: Int
    get() = this.trim().split("\\s+".toRegex()).size

// Extension function with generics
fun <T> List<T>.secondOrNull(): T? = if (size >= 2) this[1] else null

// Extension function on nullable type
fun String?.orEmpty(): String = this ?: ""

fun demonstrateExtensions() {
    // Extension function
    println("racecar".isPalindrome())
    println("hello".isPalindrome())

    // Extension property
    println("hello world foo".wordCount)

    // Generic extension
    val list = listOf(1, 2, 3)
    println(list.secondOrNull())
    println(emptyList<Int>().secondOrNull())

    // Nullable extension
    val nullStr: String? = null
    println(nullStr.orEmpty())

    // Scope functions
    val result = "Hello, World!"
        .let { it.uppercase() }
        .also { println("Uppercase: $it") }
        .run { length }

    val sb = StringBuilder().apply {
        append("Hello")
        append(", ")
        append("World!")
    }

    val length = with(sb) {
        println(toString())
        length
    }

    println("Result: $result, Length: $length")
}
