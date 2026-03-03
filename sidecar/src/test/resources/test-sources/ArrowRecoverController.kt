import arrow.core.raise.Raise
import arrow.core.raise.recover
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException

sealed class ServiceError {
    data class NotFound(val id: String) : ServiceError()
    data class BadInput(val reason: String) : ServiceError()
}

context(raise: Raise<ServiceError>)
fun doWork(id: String): String {
    if (id.isBlank()) raise.raise(ServiceError.BadInput("id must not be blank"))
    return "result-$id"
}

class ArrowRecoverController {
    fun handleRequest(id: String): String {
        val result = recover(
            block = { doWork(id) },
            recover = { error ->
                when (error) {
                    is ServiceError.NotFound -> {
                        throw ResponseStatusException(HttpStatus.NOT_FOUND, "Not found: ${error.id}")
                    }
                    is ServiceError.BadInput -> {
                        throw ResponseStatusException(HttpStatus.BAD_REQUEST, error.reason)
                    }
                }
            },
        )
        return result
    }
}
