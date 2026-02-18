// FIXTURE: correct/Annotations.kt
// EXPECT: no-diagnostics

package fixtures.correct

// Annotation declaration
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class Documented(val description: String)

@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
@Repeatable
annotation class Tag(val name: String)

// Annotation with default values
@Target(AnnotationTarget.PROPERTY)
annotation class Validate(
    val min: Int = 0,
    val max: Int = Int.MAX_VALUE,
    val required: Boolean = true
)

// Using annotations
@Documented("Main application class")
class Application {

    @Validate(min = 1, max = 100)
    var port: Int = 8080

    @Validate(required = false)
    var host: String = "localhost"

    @Tag("core")
    @Tag("startup")
    fun start() {
        println("Starting on $host:$port")
    }

    @Deprecated("Use start() instead", ReplaceWith("start()"))
    fun run() = start()

    @Suppress("UNUSED_PARAMETER")
    fun configure(options: Map<String, Any>) {
        // Configuration logic
    }
}

// Use-site targets
class DataEntity(
    @field:Validate(min = 0)
    @get:Documented("Entity identifier")
    val id: Int,

    @param:Documented("Entity name")
    val name: String
)

fun demonstrateAnnotations() {
    val app = Application()
    app.start()

    val entity = DataEntity(1, "test")
    println("${entity.id}: ${entity.name}")
}
