/**
 * A sample class to test hover on annotated declarations.
 *
 * @author Test Author
 * @since 1.0
 */
@Deprecated("Use NewGreeter instead", replaceWith = ReplaceWith("NewGreeter"))
class OldGreeter(val name: String) {
    @Deprecated("Use newGreet() instead")
    fun greet(): String = "Hello, $name!"
}

@Suppress("unused")
annotation class MyMarker

@MyMarker
class AnnotatedClass {
    fun doSomething(): String = "done"
}
