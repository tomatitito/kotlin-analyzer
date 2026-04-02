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
    fun `json rpc transport reads didOpen payloads with multibyte UTF-8 text`() {
        val text = """
            package café

            fun greet() = "Grüße 🚀"
        """.trimIndent()
        val input = buildString {
            appendRpcNotification(
                method = "textDocument/didOpen",
                params = JsonObject().apply {
                    addProperty("uri", "file:///workspace/src/main/kotlin/Greeting.kt")
                    addProperty("text", text)
                },
            )
        }.toByteArray(Charsets.UTF_8)

        val request = JsonRpcTransport(
            input = ByteArrayInputStream(input),
            output = ByteArrayOutputStream(),
        ).readRequest()

        assertEquals("textDocument/didOpen", request?.method)
        assertEquals(text, request?.params?.get("text")?.asString)
    }

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

    @Test
    fun `pebble definition resolves indexed template target`() {
        val layoutUri = "file:///workspace/src/main/resources/templates/layouts/base.peb"
        val pageUri = "file:///workspace/src/main/resources/templates/users/detail.peb"
        val input = buildString {
            appendRpcNotification(
                method = "pebble/textDocument/didOpen",
                params = JsonObject().apply {
                    addProperty("uri", layoutUri)
                    addProperty("text", "base")
                },
            )
            appendRpcNotification(
                method = "pebble/textDocument/didOpen",
                params = JsonObject().apply {
                    addProperty("uri", pageUri)
                    addProperty("text", """{% extends "layouts/base" %}""")
                },
            )
            appendRpcRequest(
                id = 1,
                method = "pebble/definition",
                params = JsonObject().apply {
                    addProperty("uri", pageUri)
                    addProperty("line", 1)
                    addProperty("character", 15)
                },
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
        val definitionResult = responses.first { it.get("id").asLong == 1L }
            .getAsJsonObject("result")
        val locations = definitionResult.getAsJsonArray("locations")

        assertEquals(1, locations.size())
        assertEquals(layoutUri, locations[0].asJsonObject.get("uri").asString)
    }

    @Test
    fun `didOpen with non ascii content preserves JSON-RPC framing`() {
        val uri = "file:///workspace/src/main/kotlin/Unicode.kt"
        val unicodeText = buildString {
            append("package demo\n\n")
            append("val greeting = \"Grüße aus Köln 👋\"\n")
            append("// ")
            append("äöüß".repeat(512))
            append('\n')
        }

        val input = buildString {
            appendRpcNotification(
                method = "textDocument/didOpen",
                params = JsonObject().apply {
                    addProperty("uri", uri)
                    addProperty("text", unicodeText)
                },
            )
            appendRpcRequest(
                id = 1,
                method = "shutdown",
                params = JsonObject(),
            )
        }.toByteArray(Charsets.UTF_8)

        val output = ByteArrayOutputStream()
        val server = AnalysisServer(
            transport = JsonRpcTransport(
                input = ByteArrayInputStream(input),
                output = output,
            ),
        )

        server.run()

        val responses = parseResponses(output.toString(Charsets.UTF_8))
        val shutdownResult = responses.first { it.get("id").asLong == 1L }
            .getAsJsonObject("result")

        assertTrue(shutdownResult.get("success").asBoolean)
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

    private fun StringBuilder.appendRpcNotification(method: String, params: JsonObject) {
        val body = JsonObject().apply {
            addProperty("jsonrpc", "2.0")
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
