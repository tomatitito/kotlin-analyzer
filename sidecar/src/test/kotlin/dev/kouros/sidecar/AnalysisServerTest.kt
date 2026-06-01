package dev.kouros.sidecar

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.progress.ProcessCanceledException
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
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

    @Test
    fun `analyze in flight is cancelled when a competing completion arrives at dispatch`() {
        // sid-an2f load-bearing path. The analyze is dispatched to the worker
        // and the bridge body blocks on a latch. The test then writes a
        // competing completion onto the pipe; the dispatcher's lookahead must
        // (a) cancel the in-flight analyze via the per-request indicator,
        // (b) bump `counts.analyzeCancelledInFlight`, and
        // (c) emit a `cancelled: true` analyze response without the worker
        //     ever completing the heavy `collectDiagnostics` pass.
        // The bridge polls `workerIndicatorForTest()` from inside `analyze`
        // (test-only seam — see AnalysisServer.workerThreadIndicator) and
        // throws ProcessCanceledException when it observes a cancel.
        val uri = "file:///workspace/src/main/kotlin/Foo.kt"
        val pipeSink = PipedOutputStream()
        val pipeSource = PipedInputStream(pipeSink, 16 * 1024)
        val output = ByteArrayOutputStream()
        val transport = JsonRpcTransport(input = pipeSource, output = output)
        val bridge = CancellableBridge()
        val server = AnalysisServer(transport = transport, bridge = bridge)
        bridge.bindServer(server)

        val serverThread = Thread({ server.run() }, "AnalysisServer-test").apply {
            isDaemon = true
            start()
        }
        try {
            pipeSink.writeFramed(makeRequest(id = 1, method = "analyze", params = JsonObject().apply {
                addProperty("uri", uri)
                addProperty("version", 5)
            }))
            // Wait until the worker has entered bridge.analyze — only then is
            // there an in-flight analyze to cancel.
            assertTrue(
                bridge.analyzeEntered.await(3, TimeUnit.SECONDS),
                "worker should have entered bridge.analyze before we send the completion",
            )

            pipeSink.writeFramed(makeRequest(id = 2, method = "completion", params = JsonObject().apply {
                addProperty("uri", uri)
                addProperty("line", 1); addProperty("character", 0)
                addProperty("includeMetrics", true)
            }))
            // Wait for the worker to observe the cancel and unwind.
            assertTrue(
                bridge.analyzeCancelObserved.await(5, TimeUnit.SECONDS),
                "worker should have observed indicator.isCanceled and thrown PCE",
            )

            pipeSink.writeFramed(makeRequest(id = 3, method = "shutdown", params = JsonObject()))
        } finally {
            serverThread.join(5_000)
            assertFalse(serverThread.isAlive, "server event loop should have terminated")
            pipeSink.close()
        }

        val byId = parseResponses(output.toString(Charsets.UTF_8))
            .associateBy { it.get("id").asLong }

        // Analyze response: cancelled-shape (empty diagnostics, cancelled=true).
        val analyzeResp = byId.getValue(1L).getAsJsonObject("result")
        assertEquals(0, analyzeResp.getAsJsonArray("diagnostics").size())
        assertEquals(0, analyzeResp.getAsJsonArray("edits").size())
        assertEquals(5, analyzeResp.get("version").asInt)
        assertTrue(
            analyzeResp.get("cancelled")?.asBoolean ?: false,
            "in-flight cancelled analyze must carry cancelled=true",
        )
        assertEquals(
            0, bridge.analyzeReturnedNormally.get(),
            "bridge.analyze must NOT have returned normally — it was cancelled mid-flight",
        )

        // Completion response: counter reflects the single cancellation.
        val completionResp = byId.getValue(2L).getAsJsonObject("result")
        assertEquals(
            1L,
            completionResp.getAsJsonObject("metrics")
                .getAsJsonObject("counts")
                .get("analyzeCancelledInFlight").asLong,
            "analyzeCancelledInFlight should be 1 after one in-flight cancel",
        )
    }

    @Test
    fun `analyze in flight runs to completion when no competing completion arrives`() {
        // Mirror of the cancel-in-flight test. No completion arrives, so the
        // worker is released via the `analyzeRelease` latch and the analyze
        // runs to natural completion. The response carries the normal
        // (non-cancelled) shape and the in-flight counter stays at 0.
        val uri = "file:///workspace/src/main/kotlin/Bar.kt"
        val pipeSink = PipedOutputStream()
        val pipeSource = PipedInputStream(pipeSink, 16 * 1024)
        val output = ByteArrayOutputStream()
        val transport = JsonRpcTransport(input = pipeSource, output = output)
        val bridge = CancellableBridge()
        val server = AnalysisServer(transport = transport, bridge = bridge)
        bridge.bindServer(server)

        val serverThread = Thread({ server.run() }, "AnalysisServer-test").apply {
            isDaemon = true
            start()
        }
        try {
            pipeSink.writeFramed(makeRequest(id = 1, method = "analyze", params = JsonObject().apply {
                addProperty("uri", uri)
                addProperty("version", 9)
            }))
            assertTrue(
                bridge.analyzeEntered.await(3, TimeUnit.SECONDS),
                "worker should have entered bridge.analyze",
            )

            // Release the bridge so analyze returns naturally.
            bridge.releaseAnalyze()

            // Follow with a completion so we can observe the cumulative counter
            // after the natural-return analyze. Without something to assert
            // against we'd be racing the event loop's shutdown handling.
            pipeSink.writeFramed(makeRequest(id = 2, method = "completion", params = JsonObject().apply {
                addProperty("uri", uri)
                addProperty("line", 1); addProperty("character", 0)
                addProperty("includeMetrics", true)
            }))
            pipeSink.writeFramed(makeRequest(id = 3, method = "shutdown", params = JsonObject()))
        } finally {
            serverThread.join(5_000)
            assertFalse(serverThread.isAlive, "server event loop should have terminated")
            pipeSink.close()
        }

        val byId = parseResponses(output.toString(Charsets.UTF_8))
            .associateBy { it.get("id").asLong }

        val analyzeResp = byId.getValue(1L).getAsJsonObject("result")
        // Natural-completion shape: empty diagnostics from our stub bridge but
        // no `cancelled` field, and the worker recorded a normal return.
        assertEquals(9, analyzeResp.get("version").asInt)
        assertFalse(
            analyzeResp.has("cancelled"),
            "natural-completion response must not carry the cancelled marker",
        )
        assertEquals(1, bridge.analyzeReturnedNormally.get())

        // The follow-up completion sees a clean counter.
        val completionResp = byId.getValue(2L).getAsJsonObject("result")
        assertEquals(
            0L,
            completionResp.getAsJsonObject("metrics")
                .getAsJsonObject("counts")
                .get("analyzeCancelledInFlight").asLong,
            "no in-flight cancel should have happened",
        )
    }

    @Test
    fun `multiple completions arriving while analyze in flight cancel only once`() {
        // Two completions land while analyze is in flight. Both should land in
        // the same dispatch window: the dispatcher pops the first, the
        // lookahead drains the second into `pending`, and the single
        // `cancelInFlightAnalyze()` call (per ka-p741's settled wholesale
        // policy) increments the counter exactly once. The second completion
        // then dispatches normally — there is no longer an in-flight analyze
        // to cancel, so the counter stays at 1.
        val uri = "file:///workspace/src/main/kotlin/Baz.kt"
        val pipeSink = PipedOutputStream()
        val pipeSource = PipedInputStream(pipeSink, 16 * 1024)
        val output = ByteArrayOutputStream()
        val transport = JsonRpcTransport(input = pipeSource, output = output)
        val bridge = CancellableBridge()
        val server = AnalysisServer(transport = transport, bridge = bridge)
        bridge.bindServer(server)

        val serverThread = Thread({ server.run() }, "AnalysisServer-test").apply {
            isDaemon = true
            start()
        }
        try {
            pipeSink.writeFramed(makeRequest(id = 1, method = "analyze", params = JsonObject().apply {
                addProperty("uri", uri)
                addProperty("version", 2)
            }))
            assertTrue(
                bridge.analyzeEntered.await(3, TimeUnit.SECONDS),
                "worker should have entered bridge.analyze",
            )

            // Two completions back-to-back, written in a single pipe write so
            // both bytes land in the buffer before the dispatcher's next
            // `readRequest()` unblocks. The lookahead drain then pulls the
            // second into `pending` in the same dispatch window — which is
            // what makes the supersededAtDispatch assertion below
            // deterministic.
            val completion2 = makeRequest(id = 2, method = "completion", params = JsonObject().apply {
                addProperty("uri", uri)
                addProperty("line", 1); addProperty("character", 0)
                addProperty("includeMetrics", true)
            })
            val completion3 = makeRequest(id = 3, method = "completion", params = JsonObject().apply {
                addProperty("uri", uri)
                addProperty("line", 1); addProperty("character", 1)
                addProperty("includeMetrics", true)
            })
            pipeSink.writeFramed(completion2 + completion3)

            assertTrue(
                bridge.analyzeCancelObserved.await(5, TimeUnit.SECONDS),
                "worker should have observed cancellation",
            )

            pipeSink.writeFramed(makeRequest(id = 4, method = "shutdown", params = JsonObject()))
        } finally {
            serverThread.join(5_000)
            assertFalse(serverThread.isAlive, "server event loop should have terminated")
            pipeSink.close()
        }

        val byId = parseResponses(output.toString(Charsets.UTF_8))
            .associateBy { it.get("id").asLong }

        val analyzeResp = byId.getValue(1L).getAsJsonObject("result")
        assertTrue(
            analyzeResp.get("cancelled")?.asBoolean ?: false,
            "the in-flight analyze must be reported as cancelled",
        )

        // Both completion responses observe the same counter value: 1.
        val completion2 = byId.getValue(2L).getAsJsonObject("result")
        val completion3 = byId.getValue(3L).getAsJsonObject("result")
        assertEquals(
            1L,
            completion2.getAsJsonObject("metrics")
                .getAsJsonObject("counts")
                .get("analyzeCancelledInFlight").asLong,
            "first completion sees the single in-flight cancel",
        )
        // The earlier completion (sidecarSeq=2) is now stale and gets
        // supersededAtDispatch by the later completion (sidecarSeq=3); the
        // dispatcher's superseded short-circuit still attaches the counter.
        assertTrue(
            completion2.get("superseded")?.asBoolean ?: false,
            "older completion is superseded once the later one is observed in the same window",
        )
        assertEquals(
            1L,
            completion3.getAsJsonObject("metrics")
                .getAsJsonObject("counts")
                .get("analyzeCancelledInFlight").asLong,
            "later completion sees the same single-cancel total — only one cancel happens per analyze",
        )
        assertFalse(
            completion3.get("superseded")?.asBoolean ?: false,
            "the latest completion is the survivor",
        )
        assertEquals(
            1, bridge.analyzeEntries.get(),
            "exactly one analyze ever reached the worker — no retries should be issued by the dispatcher",
        )
    }

    /**
     * CompilerBridge stub that blocks inside `analyze` until either:
     *   (a) the test calls [releaseAnalyze] (natural completion), or
     *   (b) the worker's thread-local indicator
     *       ([AnalysisServer.workerIndicatorForTest]) reports cancelled, in
     *       which case it throws [ProcessCanceledException] to simulate K2's
     *       cooperative cancellation from a `checkCanceled()` poll.
     *
     * Use [bindServer] right after constructing the server so the bridge can
     * read the per-worker indicator. Without the binding the bridge can't see
     * the cancel signal and will fall back to a 10 s timeout.
     */
    private class CancellableBridge : CompilerBridge() {
        val analyzeEntered = CountDownLatch(1)
        val analyzeCancelObserved = CountDownLatch(1)
        val analyzeReturnedNormally = AtomicInteger(0)
        val analyzeEntries = AtomicInteger(0)
        private val release = CountDownLatch(1)
        @Volatile private var server: AnalysisServer? = null

        fun bindServer(s: AnalysisServer) { server = s }
        fun releaseAnalyze() { release.countDown() }

        override fun analyze(uri: String, version: Int?): JsonObject {
            analyzeEntries.incrementAndGet()
            analyzeEntered.countDown()
            val deadline = System.currentTimeMillis() + 10_000
            while (System.currentTimeMillis() < deadline) {
                if (server?.workerIndicatorForTest()?.isCanceled == true) {
                    analyzeCancelObserved.countDown()
                    throw ProcessCanceledException()
                }
                if (release.await(10, TimeUnit.MILLISECONDS)) break
            }
            analyzeReturnedNormally.incrementAndGet()
            return JsonObject().apply {
                add("diagnostics", JsonArray())
                add("edits", JsonArray())
                if (version != null) addProperty("version", version)
            }
        }
    }

    private fun makeRequest(id: Long, method: String, params: JsonObject): ByteArray {
        val body = JsonObject().apply {
            addProperty("jsonrpc", "2.0")
            addProperty("id", id)
            addProperty("method", method)
            add("params", params)
        }.toString()
        val bytes = body.toByteArray(Charsets.UTF_8)
        val header = "Content-Length: ${bytes.size}\r\n\r\n".toByteArray(Charsets.UTF_8)
        return header + bytes
    }

    private fun PipedOutputStream.writeFramed(frame: ByteArray) {
        write(frame)
        flush()
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
