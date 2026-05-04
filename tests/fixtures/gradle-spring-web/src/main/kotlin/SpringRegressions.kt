import org.springframework.http.ResponseEntity
import org.springframework.web.reactive.function.client.WebClient

/**
 * Regression fixture for false-positive diagnostics observed in Spring Boot + Kotlin 2.3 projects.
 *
 * Each construct below previously triggered spurious errors such as unresolved `build()`,
 * unresolved string literal content, or unresolved enum references. This file must analyze
 * with zero diagnostics when the correct classpath and compiler flags are provided.
 */

// --- ResponseEntity.ok().build() ---

fun respondOk(): ResponseEntity<Void> {
    return ResponseEntity.ok().build()
}

fun respondWithBody(): ResponseEntity<String> {
    return ResponseEntity.ok("hello")
}

fun respondNotFound(): ResponseEntity<Void> {
    return ResponseEntity.notFound().build()
}

// --- WebClient.Builder.build() ---

fun createClient(builder: WebClient.Builder): WebClient {
    return builder.build()
}

fun createClientWithBaseUrl(builder: WebClient.Builder): WebClient {
    return builder.baseUrl("https://example.com").build()
}

// --- Multi-dollar interpolation ---

fun multiDollarInterpolation() {
    val app = object {
        val foo = "bar"
    }
    val result = $$"$${app.foo}"
    println(result)
}

// --- Enum / string literal content ---

enum class Variante {
    VARIANTE1,
    VARIANTE2,
    VARIANTE3,
}

fun useEnum(): String {
    val v = Variante.VARIANTE1
    return when (v) {
        Variante.VARIANTE1 -> "variante1"
        Variante.VARIANTE2 -> "variante2"
        Variante.VARIANTE3 -> "variante3"
    }
}

fun stringLiteralContent(): String {
    return "variante1"
}
