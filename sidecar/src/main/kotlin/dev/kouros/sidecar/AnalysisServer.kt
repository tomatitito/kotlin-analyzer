package dev.kouros.sidecar

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * JSON-RPC server that dispatches requests to the CompilerBridge.
 * Runs a single-threaded event loop reading from stdin and writing to stdout.
 *
 * For `completion` requests the server runs a second supersession checkpoint
 * (after the Rust-side writer's `BeforeSend`): see `docs/lsp-protocol.md` §4.1.
 * Each completion is assigned a sidecar-local seq at read time and
 * `latestSeenSeq[bucket]` is bumped immediately. Before invoking the heavy
 * Analysis API call, the dispatcher checks if a newer completion for the
 * same bucket has already arrived; if yes, it short-circuits with an empty
 * `superseded-at-dispatch` response and skips the Analysis API entirely.
 */
class AnalysisServer(
    private val transport: JsonRpcTransport,
    private val bridge: CompilerBridge = CompilerBridge(),
) {
    private var running = true

    /**
     * Requests pre-read by the dispatch loop's lookahead pass but not yet
     * processed. Drained in FIFO order before the loop blocks for new input.
     */
    private val pending: ArrayDeque<PendingRequest> = ArrayDeque()

    /**
     * Sidecar-local "latest seen seq" per supersession bucket. For completion
     * the bucket is the document URI (matches Rust's bucket convention in
     * `request_with_supersession`). Strictly monotonic per bucket.
     */
    private val latestSeenSeq: ConcurrentHashMap<String, Long> = ConcurrentHashMap()

    /**
     * Cumulative count of completion requests short-circuited at the
     * dispatcher because a newer request for the same bucket had already
     * been observed. Surfaced in `metrics.counts.supersededAtDispatch` on
     * every completion-metrics record.
     */
    private val supersededAtDispatchCount: AtomicLong = AtomicLong(0L)

    /**
     * Strictly monotonic counter assigned to completion requests at read time.
     * Used as the request's seq for the supersession check.
     */
    private val sidecarSeqCounter: AtomicLong = AtomicLong(0L)

    private data class PendingRequest(val request: JsonRpcRequest, val sidecarSeq: Long?)

    /**
     * Starts the event loop. Blocks until shutdown or EOF.
     */
    fun run() {
        System.err.println("AnalysisServer: starting event loop")

        while (running) {
            val item = nextPendingRequest()
            if (item == null) {
                System.err.println("AnalysisServer: EOF on stdin, exiting")
                break
            }

            // Lookahead: drain any other framed requests already buffered on
            // stdin so their seqs are recorded before we decide if `item` is
            // still the latest. This is what makes the dispatcher-level
            // supersession check actually catch stale typing-burst completions.
            drainBufferedToPending()

            try {
                handleRequest(item)
            } catch (e: Throwable) {
                System.err.println(
                    "AnalysisServer: error handling ${item.request.method}: " +
                        "${e.javaClass.name}: ${e.message}",
                )
                e.printStackTrace(System.err)
                if (item.request.id != null) {
                    transport.sendError(
                        item.request.id,
                        -32603,
                        "Internal error: ${e.javaClass.simpleName}: ${e.message}",
                    )
                }
            }
        }

        bridge.shutdown()
        System.err.println("AnalysisServer: event loop ended")
    }

    private fun nextPendingRequest(): PendingRequest? {
        if (pending.isNotEmpty()) return pending.removeFirst()
        val request = transport.readRequest() ?: return null
        return wrapForDispatch(request)
    }

    private fun drainBufferedToPending() {
        while (transport.hasBufferedData()) {
            val request = transport.readRequest() ?: return
            pending.addLast(wrapForDispatch(request))
        }
    }

    private fun wrapForDispatch(request: JsonRpcRequest): PendingRequest {
        if (request.method == "completion") {
            val bucket = completionBucket(request)
            if (bucket != null) {
                val seq = sidecarSeqCounter.incrementAndGet()
                latestSeenSeq.merge(bucket, seq) { old, new -> maxOf(old, new) }
                return PendingRequest(request, seq)
            }
        }
        return PendingRequest(request, null)
    }

    private fun completionBucket(request: JsonRpcRequest): String? =
        request.params?.get("uri")?.takeUnless { it.isJsonNull }?.asString

    private fun handleRequest(item: PendingRequest) {
        val request = item.request
        System.err.println("AnalysisServer: received ${request.method} (id=${request.id})")

        when (request.method) {
            "initialize" -> handleInitialize(request)
            "shutdown" -> handleShutdown(request)
            "ping" -> handlePing(request)
            "textDocument/didOpen" -> handleDidOpen(request)
            "textDocument/didChange" -> handleDidChange(request)
            "textDocument/didClose" -> handleDidClose(request)
            "pebble/textDocument/didOpen" -> handlePebbleDidOpen(request)
            "pebble/textDocument/didChange" -> handlePebbleDidChange(request)
            "pebble/textDocument/didClose" -> handlePebbleDidClose(request)
            "analyze" -> handleAnalyze(request)
            "analyzeAll" -> handleAnalyzeAll(request)
            "hover" -> handleHover(request)
            "completion" -> handleCompletion(request, item.sidecarSeq)
            "definition" -> handleDefinition(request)
            "implementation" -> handleImplementation(request)
            "references" -> handleReferences(request)
            "pebble/definition" -> handlePebbleDefinition(request)
            "pebble/references" -> handlePebbleReferences(request)
            "signatureHelp" -> handleSignatureHelp(request)
            "formatting" -> handleFormatting(request)
            "rename" -> handleRename(request)
            "codeActions" -> handleCodeActions(request)
            "workspaceSymbols" -> handleWorkspaceSymbols(request)
            "inlayHints" -> handleInlayHints(request)
            "codeLens" -> handleCodeLens(request)
            "semanticTokens" -> handleSemanticTokens(request)
            "callHierarchy/prepare" -> handleCallHierarchyPrepare(request)
            "callHierarchy/incoming" -> handleCallHierarchyIncoming(request)
            "typeHierarchy/prepare" -> handleTypeHierarchyPrepare(request)
            "typeHierarchy/supertypes" -> handleTypeHierarchySupertypes(request)
            "$/cancelRequest" -> {
                // Best-effort cancellation - currently a no-op since we process sequentially
            }
            else -> {
                if (request.id != null) {
                    transport.sendError(request.id, -32601, "Method not supported: ${request.method}")
                }
            }
        }
    }

    private fun handleInitialize(request: JsonRpcRequest) {
        val params = request.params ?: JsonObject()

        val projectRoot = params.get("projectRoot")?.asString ?: ""
        val classpath = params.get("classpath")?.asJsonArray?.map { it.asString } ?: emptyList()
        val compilerFlags = params.get("compilerFlags")?.asJsonArray?.map { it.asString } ?: emptyList()
        val jdkHome = params.get("jdkHome")?.asString ?: ""
        val sourceRoots = params.get("sourceRoots")?.asJsonArray?.map { it.asString } ?: emptyList()

        bridge.initialize(projectRoot, classpath, compilerFlags, jdkHome, sourceRoots)

        val result = JsonObject()
        result.addProperty("success", true)
        result.addProperty("kotlinVersion", SidecarRuntime.kotlinVersion)
        transport.sendResult(request.id, result)
    }

    private fun handleShutdown(request: JsonRpcRequest) {
        bridge.shutdown()
        running = false

        val result = JsonObject()
        result.addProperty("success", true)
        transport.sendResult(request.id, result)
    }

    private fun handlePing(request: JsonRpcRequest) {
        val result = JsonObject()
        result.addProperty("pong", true)
        transport.sendResult(request.id, result)
    }

    private fun handleDidOpen(request: JsonRpcRequest) {
        val params = request.params ?: return
        val uri = params.get("uri")?.asString ?: return
        val text = params.get("text")?.asString ?: return
        bridge.updateFile(uri, text)
        // Notifications don't get a response
    }

    private fun handleDidChange(request: JsonRpcRequest) {
        val params = request.params ?: return
        val uri = params.get("uri")?.asString ?: return
        val text = params.get("text")?.asString ?: return
        bridge.updateFile(uri, text)
        // Notifications don't get a response
    }

    private fun handleDidClose(request: JsonRpcRequest) {
        val params = request.params ?: return
        val uri = params.get("uri")?.asString ?: return
        bridge.removeFile(uri)
        // Notifications don't get a response
    }

    private fun handlePebbleDidOpen(request: JsonRpcRequest) {
        val params = request.params ?: return
        val uri = params.get("uri")?.asString ?: return
        val text = params.get("text")?.asString ?: return
        bridge.updatePebbleFile(uri, text)
    }

    private fun handlePebbleDidChange(request: JsonRpcRequest) {
        val params = request.params ?: return
        val uri = params.get("uri")?.asString ?: return
        val text = params.get("text")?.asString ?: return
        bridge.updatePebbleFile(uri, text)
    }

    private fun handlePebbleDidClose(request: JsonRpcRequest) {
        val params = request.params ?: return
        val uri = params.get("uri")?.asString ?: return
        bridge.removePebbleFile(uri)
    }

    private fun handleAnalyze(request: JsonRpcRequest) {
        val params = request.params ?: run {
            transport.sendError(request.id, -32602, "Missing params")
            return
        }
        val uri = params.get("uri")?.asString ?: run {
            transport.sendError(request.id, -32602, "Missing uri param")
            return
        }
        val version = params.get("version")?.takeUnless { it.isJsonNull }?.asInt

        val result = bridge.analyze(uri, version)
        transport.sendResult(request.id, result)
    }

    private fun handleAnalyzeAll(request: JsonRpcRequest) {
        val result = bridge.analyzeAll()
        transport.sendResult(request.id, result)
    }

    private fun handleHover(request: JsonRpcRequest) {
        val params = request.params ?: run {
            transport.sendResult(request.id, null)
            return
        }
        val uri = params.get("uri")?.asString ?: run {
            transport.sendResult(request.id, null)
            return
        }
        val line = params.get("line")?.asInt ?: run {
            transport.sendResult(request.id, null)
            return
        }
        val character = params.get("character")?.asInt ?: 0

        val result = bridge.hover(uri, line, character)
        transport.sendResult(request.id, result)
    }

    private fun handleCompletion(request: JsonRpcRequest, sidecarSeq: Long?) {
        val dispatchStart = System.nanoTime()
        val params = request.params ?: run {
            transport.sendResult(request.id, JsonObject().apply { add("items", JsonArray()) })
            return
        }
        val uri = params.get("uri")?.asString ?: run {
            transport.sendResult(request.id, JsonObject().apply { add("items", JsonArray()) })
            return
        }
        val line = params.get("line")?.asInt ?: run {
            transport.sendResult(request.id, JsonObject().apply { add("items", JsonArray()) })
            return
        }
        val character = params.get("character")?.asInt ?: 0
        val triggerCharacter = params.get("triggerCharacter")?.takeUnless { it.isJsonNull }?.asString
        val correlationId = params.get("correlationId")?.takeUnless { it.isJsonNull }?.asString
            ?: "sidecar-request-${request.id ?: System.nanoTime()}"
        val includeMetrics = params.get("includeMetrics")?.takeUnless { it.isJsonNull }?.asBoolean ?: false
        val metricsEnabled = includeMetrics || completionMetricsEnabledByEnv()
        val rustSentAtEpochMicros = params.get("rustSentAtEpochMicros")?.takeUnless { it.isJsonNull }?.asLong
        val queueWaitMs = rustSentAtEpochMicros?.let {
            (System.currentTimeMillis() * 1000L - it).coerceAtLeast(0L) / 1000.0
        }

        // Sidecar-side supersession checkpoint (see docs/lsp-protocol.md §4.1).
        // A newer completion for the same bucket has already been observed at
        // read time, so the Analysis API result for this position will be
        // discarded by Rust's InFlight check anyway. Skip the call.
        val latestSeq = sidecarSeq?.let { latestSeenSeq[uri] }
        if (sidecarSeq != null && latestSeq != null && sidecarSeq < latestSeq) {
            val cumulative = supersededAtDispatchCount.incrementAndGet()
            val result = JsonObject().apply {
                add("items", JsonArray())
                addProperty("reason", "superseded-at-dispatch")
                addProperty("superseded", true)
            }
            if (metricsEnabled) {
                attachDispatchMetrics(
                    result = result,
                    correlationId = correlationId,
                    dispatchStart = dispatchStart,
                    queueWaitMs = queueWaitMs,
                    cumulativeSupersededAtDispatch = cumulative,
                    superseded = true,
                    sidecarSeq = sidecarSeq,
                    latestSeq = latestSeq,
                )
            }
            transport.sendResult(request.id, result)
            return
        }

        val result = bridge.completion(uri, line, character, triggerCharacter, correlationId, includeMetrics)
        if (metricsEnabled) {
            attachDispatchMetrics(
                result = result,
                correlationId = correlationId,
                dispatchStart = dispatchStart,
                queueWaitMs = queueWaitMs,
                cumulativeSupersededAtDispatch = supersededAtDispatchCount.get(),
                superseded = false,
                sidecarSeq = sidecarSeq,
                latestSeq = latestSeq,
            )
        }
        transport.sendResult(request.id, result)
    }

    private fun attachDispatchMetrics(
        result: JsonObject,
        correlationId: String,
        dispatchStart: Long,
        queueWaitMs: Double?,
        cumulativeSupersededAtDispatch: Long,
        superseded: Boolean,
        sidecarSeq: Long?,
        latestSeq: Long?,
    ) {
        val metrics = result.getAsJsonObject("metrics") ?: JsonObject().also { result.add("metrics", it) }
        metrics.addProperty("correlationId", correlationId)
        val stages = metrics.getAsJsonObject("stages") ?: JsonObject().also { metrics.add("stages", it) }
        stages.addProperty("sidecar_dispatch_total", (System.nanoTime() - dispatchStart) / 1_000_000.0)
        if (queueWaitMs != null) {
            stages.addProperty("sidecar_queue_wait", queueWaitMs)
        }
        val counts = metrics.getAsJsonObject("counts") ?: JsonObject().also { metrics.add("counts", it) }
        counts.addProperty("supersededAtDispatch", cumulativeSupersededAtDispatch)
        if (superseded) {
            val fields = metrics.getAsJsonObject("fields") ?: JsonObject().also { metrics.add("fields", it) }
            fields.addProperty("supersededAtDispatch", "true")
            if (sidecarSeq != null) fields.addProperty("sidecarSeq", sidecarSeq.toString())
            if (latestSeq != null) fields.addProperty("sidecarLatestSeq", latestSeq.toString())
        }
    }

    private fun handleDefinition(request: JsonRpcRequest) {
        val params = request.params ?: run {
            transport.sendResult(request.id, JsonObject().apply { add("locations", com.google.gson.JsonArray()) })
            return
        }
        val uri = params.get("uri")?.asString ?: run {
            transport.sendResult(request.id, JsonObject().apply { add("locations", com.google.gson.JsonArray()) })
            return
        }
        val line = params.get("line")?.asInt ?: run {
            transport.sendResult(request.id, JsonObject().apply { add("locations", com.google.gson.JsonArray()) })
            return
        }
        val character = params.get("character")?.asInt ?: 0

        val result = bridge.definition(uri, line, character)
        transport.sendResult(request.id, result)
    }

    private fun handleImplementation(request: JsonRpcRequest) {
        val params = request.params ?: run {
            transport.sendResult(request.id, JsonObject().apply { add("locations", com.google.gson.JsonArray()) })
            return
        }
        val uri = params.get("uri")?.asString ?: run {
            transport.sendResult(request.id, JsonObject().apply { add("locations", com.google.gson.JsonArray()) })
            return
        }
        val line = params.get("line")?.asInt ?: run {
            transport.sendResult(request.id, JsonObject().apply { add("locations", com.google.gson.JsonArray()) })
            return
        }
        val character = params.get("character")?.asInt ?: 0

        val result = bridge.implementation(uri, line, character)
        transport.sendResult(request.id, result)
    }

    private fun handleReferences(request: JsonRpcRequest) {
        val params = request.params ?: run {
            transport.sendResult(request.id, JsonObject().apply { add("locations", com.google.gson.JsonArray()) })
            return
        }
        val uri = params.get("uri")?.asString ?: run {
            transport.sendResult(request.id, JsonObject().apply { add("locations", com.google.gson.JsonArray()) })
            return
        }
        val line = params.get("line")?.asInt ?: run {
            transport.sendResult(request.id, JsonObject().apply { add("locations", com.google.gson.JsonArray()) })
            return
        }
        val character = params.get("character")?.asInt ?: 0

        val result = bridge.references(uri, line, character)
        transport.sendResult(request.id, result)
    }

    private fun handlePebbleDefinition(request: JsonRpcRequest) {
        val params = request.params ?: run {
            transport.sendResult(request.id, JsonObject().apply { add("locations", com.google.gson.JsonArray()) })
            return
        }
        val uri = params.get("uri")?.asString ?: run {
            transport.sendResult(request.id, JsonObject().apply { add("locations", com.google.gson.JsonArray()) })
            return
        }
        val line = params.get("line")?.asInt ?: run {
            transport.sendResult(request.id, JsonObject().apply { add("locations", com.google.gson.JsonArray()) })
            return
        }
        val character = params.get("character")?.asInt ?: 0

        transport.sendResult(request.id, bridge.pebbleDefinition(uri, line, character))
    }

    private fun handlePebbleReferences(request: JsonRpcRequest) {
        val params = request.params ?: run {
            transport.sendResult(request.id, JsonObject().apply { add("locations", com.google.gson.JsonArray()) })
            return
        }
        val uri = params.get("uri")?.asString ?: run {
            transport.sendResult(request.id, JsonObject().apply { add("locations", com.google.gson.JsonArray()) })
            return
        }
        val line = params.get("line")?.asInt ?: run {
            transport.sendResult(request.id, JsonObject().apply { add("locations", com.google.gson.JsonArray()) })
            return
        }
        val character = params.get("character")?.asInt ?: 0

        transport.sendResult(request.id, bridge.pebbleReferences(uri, line, character))
    }

    private fun handleSignatureHelp(request: JsonRpcRequest) {
        val params = request.params ?: run {
            transport.sendResult(request.id, JsonObject().apply { add("signatures", com.google.gson.JsonArray()) })
            return
        }
        val uri = params.get("uri")?.asString ?: run {
            transport.sendResult(request.id, JsonObject().apply { add("signatures", com.google.gson.JsonArray()) })
            return
        }
        val line = params.get("line")?.asInt ?: run {
            transport.sendResult(request.id, JsonObject().apply { add("signatures", com.google.gson.JsonArray()) })
            return
        }
        val character = params.get("character")?.asInt ?: 0

        val result = bridge.signatureHelp(uri, line, character)
        transport.sendResult(request.id, result)
    }

    private fun handleFormatting(request: JsonRpcRequest) {
        // Formatting is delegated to external tools (ktfmt/ktlint)
        // For now, return empty edits
        val result = JsonObject()
        result.add("edits", com.google.gson.JsonArray())
        transport.sendResult(request.id, result)
    }

    private fun handleRename(request: JsonRpcRequest) {
        val params = request.params ?: run {
            transport.sendResult(request.id, JsonObject().apply { add("edits", com.google.gson.JsonArray()) })
            return
        }
        val uri = params.get("uri")?.asString ?: run {
            transport.sendResult(request.id, JsonObject().apply { add("edits", com.google.gson.JsonArray()) })
            return
        }
        val line = params.get("line")?.asInt ?: run {
            transport.sendResult(request.id, JsonObject().apply { add("edits", com.google.gson.JsonArray()) })
            return
        }
        val character = params.get("character")?.asInt ?: 0
        val newName = params.get("newName")?.asString ?: run {
            transport.sendError(request.id, -32602, "Missing newName param")
            return
        }

        val result = bridge.rename(uri, line, character, newName)
        transport.sendResult(request.id, result)
    }

    private fun handleCodeActions(request: JsonRpcRequest) {
        val params = request.params ?: run {
            transport.sendResult(request.id, JsonObject().apply { add("actions", com.google.gson.JsonArray()) })
            return
        }
        val uri = params.get("uri")?.asString ?: run {
            transport.sendResult(request.id, JsonObject().apply { add("actions", com.google.gson.JsonArray()) })
            return
        }
        val line = params.get("line")?.asInt ?: run {
            transport.sendResult(request.id, JsonObject().apply { add("actions", com.google.gson.JsonArray()) })
            return
        }
        val character = params.get("character")?.asInt ?: 0

        val result = bridge.codeActions(uri, line, character)
        transport.sendResult(request.id, result)
    }

    private fun handleWorkspaceSymbols(request: JsonRpcRequest) {
        val params = request.params ?: run {
            transport.sendResult(request.id, JsonObject().apply { add("symbols", com.google.gson.JsonArray()) })
            return
        }
        val query = params.get("query")?.asString ?: ""

        val result = bridge.workspaceSymbols(query)
        transport.sendResult(request.id, result)
    }

    private fun handleInlayHints(request: JsonRpcRequest) {
        val params = request.params ?: run {
            transport.sendResult(request.id, JsonObject().apply { add("hints", com.google.gson.JsonArray()) })
            return
        }
        val uri = params.get("uri")?.asString ?: run {
            transport.sendResult(request.id, JsonObject().apply { add("hints", com.google.gson.JsonArray()) })
            return
        }
        val startLine = params.get("startLine")?.asInt ?: 1
        val endLine = params.get("endLine")?.asInt ?: Int.MAX_VALUE

        val result = bridge.inlayHints(uri, startLine, endLine)
        transport.sendResult(request.id, result)
    }

    private fun handleCodeLens(request: JsonRpcRequest) {
        val params = request.params ?: run {
            transport.sendResult(request.id, JsonObject().apply { add("lenses", com.google.gson.JsonArray()) })
            return
        }
        val uri = params.get("uri")?.asString ?: run {
            transport.sendResult(request.id, JsonObject().apply { add("lenses", com.google.gson.JsonArray()) })
            return
        }

        val result = bridge.codeLens(uri)
        transport.sendResult(request.id, result)
    }

    private fun handleSemanticTokens(request: JsonRpcRequest) {
        val params = request.params ?: run {
            transport.sendResult(request.id, JsonObject().apply {
                add("data", com.google.gson.JsonArray())
                add("legend", JsonObject())
            })
            return
        }
        val uri = params.get("uri")?.asString ?: run {
            transport.sendResult(request.id, JsonObject().apply {
                add("data", com.google.gson.JsonArray())
                add("legend", JsonObject())
            })
            return
        }

        val result = bridge.semanticTokens(uri)
        transport.sendResult(request.id, result)
    }

    private fun handleCallHierarchyPrepare(request: JsonRpcRequest) {
        val params = request.params ?: run {
            transport.sendResult(request.id, JsonObject().apply { add("items", com.google.gson.JsonArray()) })
            return
        }
        val uri = params.get("uri")?.asString ?: run {
            transport.sendResult(request.id, JsonObject().apply { add("items", com.google.gson.JsonArray()) })
            return
        }
        val line = params.get("line")?.asInt ?: run {
            transport.sendResult(request.id, JsonObject().apply { add("items", com.google.gson.JsonArray()) })
            return
        }
        val character = params.get("character")?.asInt ?: 0

        val result = bridge.callHierarchyPrepare(uri, line, character)
        transport.sendResult(request.id, result)
    }

    private fun handleCallHierarchyIncoming(request: JsonRpcRequest) {
        val params = request.params ?: run {
            transport.sendResult(request.id, JsonObject().apply { add("calls", com.google.gson.JsonArray()) })
            return
        }
        val uri = params.get("uri")?.asString ?: run {
            transport.sendResult(request.id, JsonObject().apply { add("calls", com.google.gson.JsonArray()) })
            return
        }
        val line = params.get("line")?.asInt ?: run {
            transport.sendResult(request.id, JsonObject().apply { add("calls", com.google.gson.JsonArray()) })
            return
        }
        val character = params.get("character")?.asInt ?: 0
        val name = params.get("name")?.asString ?: run {
            transport.sendResult(request.id, JsonObject().apply { add("calls", com.google.gson.JsonArray()) })
            return
        }

        val result = bridge.callHierarchyIncoming(uri, line, character, name)
        transport.sendResult(request.id, result)
    }

    private fun handleTypeHierarchyPrepare(request: JsonRpcRequest) {
        val params = request.params ?: run {
            transport.sendResult(request.id, JsonObject().apply { add("items", com.google.gson.JsonArray()) })
            return
        }
        val uri = params.get("uri")?.asString ?: run {
            transport.sendResult(request.id, JsonObject().apply { add("items", com.google.gson.JsonArray()) })
            return
        }
        val line = params.get("line")?.asInt ?: run {
            transport.sendResult(request.id, JsonObject().apply { add("items", com.google.gson.JsonArray()) })
            return
        }
        val character = params.get("character")?.asInt ?: 0

        val result = bridge.typeHierarchyPrepare(uri, line, character)
        transport.sendResult(request.id, result)
    }

    private fun handleTypeHierarchySupertypes(request: JsonRpcRequest) {
        val params = request.params ?: run {
            transport.sendResult(request.id, JsonObject().apply { add("supertypes", com.google.gson.JsonArray()) })
            return
        }
        val uri = params.get("uri")?.asString ?: run {
            transport.sendResult(request.id, JsonObject().apply { add("supertypes", com.google.gson.JsonArray()) })
            return
        }
        val line = params.get("line")?.asInt ?: run {
            transport.sendResult(request.id, JsonObject().apply { add("supertypes", com.google.gson.JsonArray()) })
            return
        }
        val character = params.get("character")?.asInt ?: 0
        val name = params.get("name")?.asString ?: run {
            transport.sendResult(request.id, JsonObject().apply { add("supertypes", com.google.gson.JsonArray()) })
            return
        }

        val result = bridge.typeHierarchySupertypes(uri, line, character, name)
        transport.sendResult(request.id, result)
    }
}
