import org.springframework.web.bind.annotation.RestController
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.server.ResponseStatusException
import org.springframework.http.HttpStatus
import org.springframework.ui.Model

/**
 * A simple REST controller for testing hover, diagnostics, and other LSP features.
 *
 * @author Test Author
 * @since 1.0
 */
@RestController
class ExampleController {

    @GetMapping("/greeting/{name}")
    fun greeting(
        @PathVariable name: String,
        @RequestParam title: String?,
        model: Model,
    ): String {
        val resolvedTitle = title ?: "Guest"
        model.addAttribute("name", name)
        model.addAttribute("title", resolvedTitle)
        return "greeting"
    }

    @GetMapping("/greet/{name}")
    fun greet(@PathVariable name: String, @RequestParam(required = false) title: String?): String {
        val greeting = if (title != null) {
            "Hello, $title $name!"
        } else {
            "Hello, $name!"
        }
        return greeting
    }

    @GetMapping("/error")
    fun throwError(): Nothing {
        throw ResponseStatusException(HttpStatus.NOT_FOUND, "Not found")
    }
}

fun main() = println("fixture")
