package dev.kouros.sidecar

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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

    @Test
    fun `stale completion requests are superseded at dispatch without invoking BridgeCompletion`() {
        // Three completion requests for the same URI arrive back-to-back on
        // stdin (typing-burst pattern). The dispatcher's lookahead drain
        // bumps the latest seen seq for this URI to 3 before any of them is
        // processed, so the first two should be short-circuited at the
        // dispatcher and never reach BridgeCompletion. The third (latest)
        // must dispatch normally, which we observe via the distinct
        // `no-active-session` reason BridgeCompletion returns when the
        // server has not been initialized.
        val uri = "file:///workspace/src/main/kotlin/Burst.kt"
        val input = buildString {
            appendRpcRequest(
                id = 1,
                method = "completion",
                params = JsonObject().apply {
                    addProperty("uri", uri)
                    addProperty("line", 1)
                    addProperty("character", 0)
                    addProperty("includeMetrics", true)
                },
            )
            appendRpcRequest(
                id = 2,
                method = "completion",
                params = JsonObject().apply {
                    addProperty("uri", uri)
                    addProperty("line", 1)
                    addProperty("character", 1)
                    addProperty("includeMetrics", true)
                },
            )
            appendRpcRequest(
                id = 3,
                method = "completion",
                params = JsonObject().apply {
                    addProperty("uri", uri)
                    addProperty("line", 1)
                    addProperty("character", 2)
                    addProperty("includeMetrics", true)
                },
            )
            appendRpcRequest(
                id = 4,
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

        val byId = parseResponses(output.toString(Charsets.UTF_8))
            .associateBy { it.get("id").asLong }

        val resp1 = byId.getValue(1L).getAsJsonObject("result")
        val resp2 = byId.getValue(2L).getAsJsonObject("result")
        val resp3 = byId.getValue(3L).getAsJsonObject("result")

        assertTrue(resp1.get("superseded")?.asBoolean ?: false, "id=1 should be superseded at dispatch")
        assertEquals("superseded-at-dispatch", resp1.get("reason").asString)
        assertEquals(0, resp1.getAsJsonArray("items").size())

        assertTrue(resp2.get("superseded")?.asBoolean ?: false, "id=2 should be superseded at dispatch")
        assertEquals("superseded-at-dispatch", resp2.get("reason").asString)
        assertEquals(0, resp2.getAsJsonArray("items").size())

        // id=3 has the latest seq; the dispatcher must invoke BridgeCompletion.
        // BridgeCompletion without an initialized session yields
        // reason="no-active-session" — distinct from the dispatcher's
        // short-circuit, so we know the heavy path actually ran.
        assertFalse(resp3.get("superseded")?.asBoolean ?: false, "id=3 should NOT be superseded")
        assertEquals("no-active-session", resp3.get("reason").asString)

        // Cumulative supersededAtDispatch is bumped before the metrics are
        // attached, so it equals the running total at the moment each
        // response is written.
        val counts1 = resp1.getAsJsonObject("metrics").getAsJsonObject("counts")
        val counts2 = resp2.getAsJsonObject("metrics").getAsJsonObject("counts")
        val counts3 = resp3.getAsJsonObject("metrics").getAsJsonObject("counts")
        assertEquals(1L, counts1.get("supersededAtDispatch").asLong)
        assertEquals(2L, counts2.get("supersededAtDispatch").asLong)
        assertEquals(2L, counts3.get("supersededAtDispatch").asLong)
    }

    @Test
    fun `single completion request dispatches normally without supersession`() {
        // No newer request arrives, so the dispatcher must invoke
        // BridgeCompletion. With no session initialized we expect
        // reason="no-active-session" and a zero supersededAtDispatch counter.
        val uri = "file:///workspace/src/main/kotlin/Solo.kt"
        val input = buildString {
            appendRpcRequest(
                id = 1,
                method = "completion",
                params = JsonObject().apply {
                    addProperty("uri", uri)
                    addProperty("line", 1)
                    addProperty("character", 0)
                    addProperty("includeMetrics", true)
                },
            )
            appendRpcRequest(
                id = 2,
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

        val byId = parseResponses(output.toString(Charsets.UTF_8))
            .associateBy { it.get("id").asLong }

        val resp = byId.getValue(1L).getAsJsonObject("result")
        assertFalse(resp.get("superseded")?.asBoolean ?: false)
        assertEquals("no-active-session", resp.get("reason").asString)
        assertEquals(
            0L,
            resp.getAsJsonObject("metrics")
                .getAsJsonObject("counts")
                .get("supersededAtDispatch").asLong,
        )
    }

    @Test
    fun `completions on different URIs do not supersede each other`() {
        // The bucket convention is per-URI: a typing burst in Foo.kt must
        // not cause a completion in Bar.kt to be marked stale, even if both
        // arrive in the same lookahead drain.
        val fooUri = "file:///workspace/src/main/kotlin/Foo.kt"
        val barUri = "file:///workspace/src/main/kotlin/Bar.kt"
        val input = buildString {
            appendRpcRequest(
                id = 1,
                method = "completion",
                params = JsonObject().apply {
                    addProperty("uri", fooUri)
                    addProperty("line", 1); addProperty("character", 0)
                    addProperty("includeMetrics", true)
                },
            )
            appendRpcRequest(
                id = 2,
                method = "completion",
                params = JsonObject().apply {
                    addProperty("uri", barUri)
                    addProperty("line", 1); addProperty("character", 0)
                    addProperty("includeMetrics", true)
                },
            )
            appendRpcRequest(
                id = 3,
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

        val byId = parseResponses(output.toString(Charsets.UTF_8))
            .associateBy { it.get("id").asLong }

        val foo = byId.getValue(1L).getAsJsonObject("result")
        val bar = byId.getValue(2L).getAsJsonObject("result")
        assertFalse(foo.get("superseded")?.asBoolean ?: false, "Foo.kt is the only request for its bucket")
        assertFalse(bar.get("superseded")?.asBoolean ?: false, "Bar.kt is the only request for its bucket")
        assertEquals("no-active-session", foo.get("reason").asString)
        assertEquals("no-active-session", bar.get("reason").asString)
    }

    @Test
    fun `analyze for same URI is superseded when completion follows in dispatch window`() {
        // Same-URI: analyze arrives first, then a completion for the same URI
        // piles up behind it in the pipe. By the time the dispatcher's
        // lookahead drain finishes, the queued analyze must be short-circuited
        // (bridge.analyze NOT invoked) and the completion must dispatch
        // normally. The recording bridge proves the analyze never reached the
        // Analysis API: bridge.analyzeCalls stays empty.
        val uri = "file:///workspace/src/main/kotlin/Foo.kt"
        val input = buildString {
            appendRpcRequest(
                id = 1,
                method = "analyze",
                params = JsonObject().apply {
                    addProperty("uri", uri)
                    addProperty("version", 7)
                },
            )
            appendRpcRequest(
                id = 2,
                method = "completion",
                params = JsonObject().apply {
                    addProperty("uri", uri)
                    addProperty("line", 1); addProperty("character", 0)
                    addProperty("includeMetrics", true)
                },
            )
            appendRpcRequest(id = 3, method = "shutdown", params = JsonObject())
        }.toByteArray(Charsets.UTF_8)

        val recording = RecordingBridge()
        val output = ByteArrayOutputStream()
        AnalysisServer(
            transport = JsonRpcTransport(input = ByteArrayInputStream(input), output = output),
            bridge = recording,
        ).run()

        val byId = parseResponses(output.toString(Charsets.UTF_8))
            .associateBy { it.get("id").asLong }

        val analyzeResp = byId.getValue(1L).getAsJsonObject("result")
        assertTrue(recording.analyzeCalls.isEmpty(), "bridge.analyze must NOT be invoked for the superseded analyze")
        assertEquals(0, analyzeResp.getAsJsonArray("diagnostics").size())
        assertEquals(0, analyzeResp.getAsJsonArray("edits").size())
        assertEquals(7, analyzeResp.get("version").asInt)

        val completionResp = byId.getValue(2L).getAsJsonObject("result")
        assertFalse(completionResp.get("superseded")?.asBoolean ?: false)
        assertEquals(
            1L,
            completionResp.getAsJsonObject("metrics")
                .getAsJsonObject("counts")
                .get("analyzeSupersededByCompletion").asLong,
        )
    }

    @Test
    fun `analyze for different URI is also superseded when any completion arrives`() {
        // Different-URI: the ticket's settled decision says "when a completion
        // arrives at dispatch with a queued analyze for any URI, mark the
        // analyze stale." So an analyze for Foo.kt must be superseded even
        // when the completion is for Bar.kt — interactivity on ANY file
        // signals the user is typing and stale diagnostics for other files
        // should not block the completion path. The Rust debouncer re-fires
        // analyze for Foo.kt on the next post-interactive quiet window.
        val fooUri = "file:///workspace/src/main/kotlin/Foo.kt"
        val barUri = "file:///workspace/src/main/kotlin/Bar.kt"
        val input = buildString {
            appendRpcRequest(
                id = 1,
                method = "analyze",
                params = JsonObject().apply { addProperty("uri", fooUri); addProperty("version", 3) },
            )
            appendRpcRequest(
                id = 2,
                method = "completion",
                params = JsonObject().apply {
                    addProperty("uri", barUri)
                    addProperty("line", 1); addProperty("character", 0)
                    addProperty("includeMetrics", true)
                },
            )
            appendRpcRequest(id = 3, method = "shutdown", params = JsonObject())
        }.toByteArray(Charsets.UTF_8)

        val recording = RecordingBridge()
        val output = ByteArrayOutputStream()
        AnalysisServer(
            transport = JsonRpcTransport(input = ByteArrayInputStream(input), output = output),
            bridge = recording,
        ).run()

        val byId = parseResponses(output.toString(Charsets.UTF_8))
            .associateBy { it.get("id").asLong }

        val analyzeResp = byId.getValue(1L).getAsJsonObject("result")
        assertTrue(
            recording.analyzeCalls.isEmpty(),
            "bridge.analyze must NOT be invoked even though analyze URI differs from completion URI",
        )
        assertEquals(0, analyzeResp.getAsJsonArray("diagnostics").size())
        assertEquals(3, analyzeResp.get("version").asInt)

        val completionResp = byId.getValue(2L).getAsJsonObject("result")
        assertEquals(
            1L,
            completionResp.getAsJsonObject("metrics")
                .getAsJsonObject("counts")
                .get("analyzeSupersededByCompletion").asLong,
        )
    }

    @Test
    fun `multiple queued analyzes are all superseded when a single completion arrives`() {
        // Two analyzes (different versions, same URI) pile up before a
        // completion. Per Phase (a) of ka-p741, analyze supersession is
        // wholesale when a completion arrives at dispatch — both analyzes
        // are short-circuited, not just the older one. The counter increments
        // once per superseded analyze (2 here).
        val uri = "file:///workspace/src/main/kotlin/Foo.kt"
        val input = buildString {
            appendRpcRequest(
                id = 1,
                method = "analyze",
                params = JsonObject().apply { addProperty("uri", uri); addProperty("version", 1) },
            )
            appendRpcRequest(
                id = 2,
                method = "analyze",
                params = JsonObject().apply { addProperty("uri", uri); addProperty("version", 2) },
            )
            appendRpcRequest(
                id = 3,
                method = "completion",
                params = JsonObject().apply {
                    addProperty("uri", uri)
                    addProperty("line", 1); addProperty("character", 0)
                    addProperty("includeMetrics", true)
                },
            )
            appendRpcRequest(id = 4, method = "shutdown", params = JsonObject())
        }.toByteArray(Charsets.UTF_8)

        val recording = RecordingBridge()
        val output = ByteArrayOutputStream()
        AnalysisServer(
            transport = JsonRpcTransport(input = ByteArrayInputStream(input), output = output),
            bridge = recording,
        ).run()

        val byId = parseResponses(output.toString(Charsets.UTF_8))
            .associateBy { it.get("id").asLong }

        assertTrue(recording.analyzeCalls.isEmpty(), "neither analyze must reach bridge.analyze")
        assertEquals(1, byId.getValue(1L).getAsJsonObject("result").get("version").asInt)
        assertEquals(2, byId.getValue(2L).getAsJsonObject("result").get("version").asInt)

        val completionResp = byId.getValue(3L).getAsJsonObject("result")
        assertEquals(
            2L,
            completionResp.getAsJsonObject("metrics")
                .getAsJsonObject("counts")
                .get("analyzeSupersededByCompletion").asLong,
        )
    }

    /**
     * Records every call to [analyze] without invoking the heavy Analysis API.
     * Tests use the empty/non-empty state of [analyzeCalls] to distinguish
     * "bridge.analyze was invoked" from "bridge.analyze was short-circuited
     * by the dispatcher."
     */
    private class RecordingBridge : CompilerBridge() {
        val analyzeCalls = mutableListOf<Pair<String, Int?>>()
        override fun analyze(uri: String, version: Int?): JsonObject {
            analyzeCalls.add(uri to version)
            return JsonObject().apply {
                add("diagnostics", JsonArray())
                add("edits", JsonArray())
                if (version != null) addProperty("version", version)
            }
        }
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
