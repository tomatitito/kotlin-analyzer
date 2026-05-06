package dev.kouros.sidecar

import com.google.gson.JsonObject
import java.util.Locale

internal const val COMPLETION_METRICS_ENV = "KOTLIN_ANALYZER_COMPLETION_METRICS"

internal fun completionMetricsEnabledByEnv(): Boolean =
    System.getenv(COMPLETION_METRICS_ENV)?.lowercase(Locale.US) in setOf("1", "true", "yes", "on")

internal class CompletionMetrics(private val correlationId: String) {
    private val stagesNanos = linkedMapOf<String, Long>()
    private val counts = linkedMapOf<String, Int>()
    private val fields = linkedMapOf<String, String>()
    private val startedAtNanos = System.nanoTime()

    fun <T> stage(name: String, block: () -> T): T {
        val start = System.nanoTime()
        try {
            return block()
        } finally {
            stagesNanos[name] = (stagesNanos[name] ?: 0L) + (System.nanoTime() - start)
        }
    }

    fun count(name: String, value: Int) {
        counts[name] = value
    }

    fun field(name: String, value: String) {
        fields[name] = value
    }

    fun toJson(): JsonObject {
        val json = JsonObject()
        json.addProperty("correlationId", correlationId)
        json.addProperty("totalMs", elapsedMs(System.nanoTime() - startedAtNanos))

        val stages = JsonObject()
        for ((name, nanos) in stagesNanos) {
            stages.addProperty(name, elapsedMs(nanos))
        }
        json.add("stages", stages)

        val countJson = JsonObject()
        for ((name, value) in counts) {
            countJson.addProperty(name, value)
        }
        json.add("counts", countJson)

        val fieldJson = JsonObject()
        for ((name, value) in fields) {
            fieldJson.addProperty(name, value)
        }
        json.add("fields", fieldJson)
        return json
    }

    private fun elapsedMs(nanos: Long): Double =
        nanos / 1_000_000.0
}
