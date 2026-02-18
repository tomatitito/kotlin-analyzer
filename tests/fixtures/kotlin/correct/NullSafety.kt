// FIXTURE: correct/NullSafety.kt
// EXPECT: no-diagnostics

package fixtures.correct

data class Address(val street: String, val city: String, val zip: String?)

data class Person(val name: String, val address: Address?)

fun demonstrateNullSafety() {
    // Nullable types
    val nullable: String? = null
    val nonNull: String = "hello"

    // Safe call operator
    val length: Int? = nullable?.length

    // Elvis operator
    val safeLength: Int = nullable?.length ?: 0

    // Not-null assertion (use with caution)
    val definitelyNotNull: String = nonNull!!

    // Smart cast after null check
    if (nullable != null) {
        println(nullable.length) // Smart cast to String
    }

    // let-based null check
    nullable?.let {
        println("Not null: $it")
    }

    // Chained safe calls
    val person = Person("Alice", Address("123 Main St", "Springfield", null))
    val zip: String? = person.address?.zip
    val zipLength: Int = person.address?.zip?.length ?: -1

    // Safe cast
    val any: Any = "hello"
    val str: String? = any as? String
    val num: Int? = any as? Int

    // Collection of nullable types
    val nullableList: List<String?> = listOf("a", null, "b", null, "c")
    val nonNullList: List<String> = nullableList.filterNotNull()

    // Nullable function parameter
    fun process(input: String?) {
        val result = input ?: return
        println(result.uppercase())
    }

    process("hello")
    process(null)

    println("$length, $safeLength, $definitelyNotNull, $zip, $zipLength, $str, $num, $nonNullList")
}
