package dev.kouros.sidecar

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AnalysisServerTest {

    @Test
    fun `initialize reports bundled Kotlin version`() {
        val input = buildString {
            appendRpcRequest(
                id = 1,
                method = "initialize",
                params = JsonObject(),
            )
            appendRpcRequest(
                id = 2,
                method = "shutdown",
                params = JsonObject(),
            )
        }.toByteArray()

        val output = ByteArrayOutputStream()
        val server = AnalysisServer(
            transport = JsonRpcTransport(
                input = ByteArrayInputStream(input),
                output = output,
            ),
        )

        server.run()

        val responses = parseResponses(output.toString(Charsets.UTF_8))
        val initializeResult = responses.first { it.get("id").asLong == 1L }
            .getAsJsonObject("result")

        assertEquals(
            SidecarRuntime.kotlinVersion,
            initializeResult.get("kotlinVersion").asString,
        )
        assertTrue(initializeResult.get("success").asBoolean)
    }

    private fun StringBuilder.appendRpcRequest(id: Long, method: String, params: JsonObject) {
        val body = JsonObject().apply {
            addProperty("jsonrpc", "2.0")
            addProperty("id", id)
            addProperty("method", method)
            add("params", params)
        }.toString()

        append("Content-Length: ${body.toByteArray(Charsets.UTF_8).size}\r\n\r\n")
        append(body)
    }

    private fun parseResponses(raw: String): List<JsonObject> {
        val responses = mutableListOf<JsonObject>()
        var cursor = 0
        while (cursor < raw.length) {
            val headerEnd = raw.indexOf("\r\n\r\n", cursor)
            if (headerEnd == -1) break
            val header = raw.substring(cursor, headerEnd)
            val contentLength = header.lineSequence()
                .first { it.startsWith("Content-Length: ") }
                .removePrefix("Content-Length: ")
                .toInt()
            val bodyStart = headerEnd + 4
            val bodyEnd = bodyStart + contentLength
            responses.add(JsonParser.parseString(raw.substring(bodyStart, bodyEnd)).asJsonObject)
            cursor = bodyEnd
        }
        return responses
    }
}
