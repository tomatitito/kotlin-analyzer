import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException

class SpringController {
    fun convert(input: String): String {
        if (input.isBlank()) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Input must not be blank")
        }
        return input.uppercase()
    }

    fun validate(value: Int): Int {
        if (value < 0) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Value must be non-negative")
        }
        return value
    }
}
