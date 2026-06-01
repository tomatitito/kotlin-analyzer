package dev.kouros.sidecar

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.StandardProgressIndicator
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

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
 *
 * When a completion is observed at dispatch (either as the request just popped
 * or anywhere in the lookahead-drained pending queue), any queued `analyze`
 * requests (any URI) are short-circuited with a synthetic empty-diagnostics
 * response: the user is interacting, so the queued analyze's diagnostics are
 * stale by definition. The Rust debouncer will re-fire analyze after the
 * next post-interactive quiet window. See `docs/lsp-protocol.md` §4.1.
 *
 * Analyze requests run on a dedicated single-thread worker (`sidecar-analyze`)
 * wrapped in `ProgressManager.runProcess` with a per-request indicator so K2's
 * `ProgressManager.checkCanceled()` polls can unwind a mid-`collectDiagnostics`
 * pass via `ProcessCanceledException` when a competing completion arrives.
 * The dispatcher cancels the in-flight analyze before processing the completion;
 * for non-analyze non-completion requests, the dispatcher awaits the worker
 * first so the K2 Analysis API's single-thread contract is preserved. See
 * `docs/lsp-protocol.md` §4.2 (in-flight cancellation contract).
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
     * `request_with_supersession`). For analyze the bucket is namespaced as
     * `"analyze:<uri>"` to keep the two channels disjoint, mirroring the Rust
     * `cancel_pending("analyze:{uri}")` convention. Strictly monotonic per
     * bucket.
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
     * Cumulative count of analyze requests short-circuited at the dispatcher
     * because a completion was observed in the same dispatch window.
     * Surfaced in `metrics.counts.analyzeSupersededByCompletion` on every
     * completion-metrics record.
     */
    private val analyzeSupersededByCompletionCount: AtomicLong = AtomicLong(0L)

    /**
     * Cumulative count of analyze requests cancelled while in flight (i.e.
     * mid-`collectDiagnostics`) because a completion arrived at the dispatcher.
     * Surfaced in `metrics.counts.analyzeCancelledInFlight` on every
     * completion-metrics record. Complements
     * [analyzeSupersededByCompletionCount], which counts the queued case
     * caught by ka-p741 before the analyze ever reaches `bridge.analyze`.
     */
    private val analyzeCancelledInFlightCount: AtomicLong = AtomicLong(0L)

    /**
     * Number of times [cancelInFlightAnalyze] found a non-empty slot and
     * actually invoked `indicator.cancel()` + `future.get(...)`. Surfaced as
     * `analyzeCancelAttempts`. Diagnoses Hypothesis B (cancel never sent at
     * all because the slot was empty when the completion arrived).
     */
    private val analyzeCancelAttemptCount: AtomicLong = AtomicLong(0L)

    /**
     * Number of cancel attempts where `future.get(2s, TimeUnit.SECONDS)` threw
     * `TimeoutException` — K2 did not yield to `checkCanceled()` within the
     * grace window. Surfaced as `analyzeCancelTimeouts`. Diagnoses
     * Hypothesis A (K2 doesn't honor the cancel signal).
     */
    private val analyzeCancelTimeoutCount: AtomicLong = AtomicLong(0L)

    /**
     * Maximum observed analyze age in milliseconds at the moment the
     * dispatcher signalled cancel (`indicator.cancel()`), measured from
     * worker submit. Surfaced as `analyzeMaxAgeAtCancelMs`. Tells us whether
     * the cancel signal arrived early (K2 hasn't started polling yet) or
     * late (K2 had ample time and ignored polls).
     */
    private val analyzeMaxAgeAtCancelMs: AtomicLong = AtomicLong(0L)

    /**
     * Maximum observed time the dispatcher waited inside `future.get(2s)`
     * after signalling cancel. Surfaced as `analyzeMaxCancelWaitMs`. Worst-
     * case cancel responsiveness; ideally near 0.
     */
    private val analyzeMaxCancelWaitMs: AtomicLong = AtomicLong(0L)

    /**
     * Strictly monotonic counter assigned to completion requests at read time.
     * Used as the request's seq for the supersession check.
     */
    private val sidecarSeqCounter: AtomicLong = AtomicLong(0L)

    /**
     * Worker that runs the heavy `bridge.analyze` body so the dispatcher can
     * keep peeking at the inbound queue and cancel the in-flight analyze when
     * a competing completion arrives. Single-threaded — analyzes serialize
     * naturally and the K2 Analysis API's single-thread contract is preserved.
     */
    private val analyzeExecutor: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "sidecar-analyze").apply { isDaemon = true }
    }

    /**
     * The analyze currently submitted to the worker (running or about to start).
     * Null when no analyze is in flight. Set by the dispatcher on analyze
     * dispatch; cleared by the worker on natural completion / cancellation, or
     * by the dispatcher when it awaits or cancels before the next request.
     */
    private val inFlightAnalyze: AtomicReference<InFlightAnalyze?> = AtomicReference(null)

    private class InFlightAnalyze(
        val request: JsonRpcRequest,
        val uri: String,
        val sidecarSeq: Long?,
        val indicator: ProgressIndicator,
        val startedAtNanos: Long,
    ) {
        @Volatile var future: Future<*>? = null
    }

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

            // If a completion is anywhere in this dispatch window the user is
            // interacting. Three things follow, in order:
            //   1. sid-an2f: cancel any in-flight analyze on the worker via
            //      `ProgressManager` so it unwinds via `ProcessCanceledException`
            //      instead of blocking the next completion for the rest of the
            //      `collectDiagnostics` pass.
            //   2. ka-p741: if `item` itself is the queued analyze, short-circuit
            //      it with a synthetic empty-diagnostics response and skip the
            //      Analysis API entirely.
            //   3. ka-p741: short-circuit any other queued analyzes still in the
            //      pending queue. The Rust debouncer re-fires analyze after the
            //      next post-interactive quiet window.
            if (hasCompletionInQueue(item)) {
                cancelInFlightAnalyze()
                if (item.request.method == "analyze") {
                    sendSyntheticAnalyzeResponse(item.request)
                    analyzeSupersededByCompletionCount.incrementAndGet()
                    continue
                }
                supersedeQueuedAnalyzes()
            }

            try {
                if (item.request.method == "analyze") {
                    // Worker is single-threaded; the K2 Analysis API is too.
                    // Drain any prior analyze before queueing the next so the
                    // `inFlightAnalyze` slot tracks at most one entry and the
                    // dispatcher's cancellation/await semantics stay simple.
                    awaitInFlightAnalyze()
                    startAnalyzeOnWorker(item)
                } else {
                    // K2 Analysis API is single-threaded; completion/hover/etc.
                    // run on the dispatcher thread and would race the worker
                    // into the same session. Await any in-flight analyze first.
                    awaitInFlightAnalyze()
                    handleRequest(item)
                }
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

        // Best-effort drain on shutdown: cancel in-flight analyze, stop worker.
        cancelInFlightAnalyze()
        analyzeExecutor.shutdown()
        try {
            if (!analyzeExecutor.awaitTermination(2, TimeUnit.SECONDS)) {
                analyzeExecutor.shutdownNow()
            }
        } catch (e: InterruptedException) {
            analyzeExecutor.shutdownNow()
            Thread.currentThread().interrupt()
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
        if (request.method == "analyze") {
            val bucket = analyzeBucket(request)
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

    private fun analyzeBucket(request: JsonRpcRequest): String? =
        request.params?.get("uri")?.takeUnless { it.isJsonNull }?.asString?.let { "analyze:$it" }

    private fun hasCompletionInQueue(item: PendingRequest): Boolean {
        if (item.request.method == "completion") return true
        return pending.any { it.request.method == "completion" }
    }

    /**
     * Removes every queued analyze entry from `pending` and short-circuits each
     * with a synthetic empty-diagnostics response in the same JSON shape
     * `handleAnalyze` would have emitted on a normal call. Increments
     * `analyzeSupersededByCompletionCount` once per short-circuit.
     */
    private fun supersedeQueuedAnalyzes() {
        val iter = pending.iterator()
        while (iter.hasNext()) {
            val entry = iter.next()
            if (entry.request.method != "analyze") continue
            iter.remove()
            sendSyntheticAnalyzeResponse(entry.request)
            analyzeSupersededByCompletionCount.incrementAndGet()
        }
    }

    private fun sendSyntheticAnalyzeResponse(request: JsonRpcRequest) {
        val params = request.params
        val version = params?.get("version")?.takeUnless { it.isJsonNull }?.asInt
        if (request.id != null) {
            transport.sendResult(request.id, emptyAnalyzeResult(version))
        }
    }

    private fun emptyAnalyzeResult(version: Int?, cancelled: Boolean = false): JsonObject =
        JsonObject().apply {
            add("diagnostics", JsonArray())
            add("edits", JsonArray())
            if (version != null) addProperty("version", version)
            if (cancelled) addProperty("cancelled", true)
        }

    /**
     * Submits `bridge.analyze(uri, version)` to the worker wrapped in
     * `ProgressManager.runProcess(...)` with a per-request indicator. The
     * worker sends the response on natural completion or
     * `ProcessCanceledException`; the dispatcher does not await this future
     * unless a competing completion arrives or another Analysis API call is
     * about to run on the dispatcher thread.
     */
    private fun startAnalyzeOnWorker(item: PendingRequest) {
        val request = item.request
        val params = request.params ?: run {
            transport.sendError(request.id, -32602, "Missing params")
            return
        }
        val uri = params.get("uri")?.asString ?: run {
            transport.sendError(request.id, -32602, "Missing uri param")
            return
        }
        val version = params.get("version")?.takeUnless { it.isJsonNull }?.asInt

        val tracking = InFlightAnalyze(
            request = request,
            uri = uri,
            sidecarSeq = item.sidecarSeq,
            indicator = SidecarProgressIndicator(),
            startedAtNanos = System.nanoTime(),
        )
        inFlightAnalyze.set(tracking)
        tracking.future = analyzeExecutor.submit {
            val workerStartNanos = System.nanoTime()
            // `getInstanceOrNull()` covers the test path: the standalone
            // `AnalysisServerTest`s instantiate the server without ever
            // calling `bridge.initialize()`, so the IntelliJ Application
            // (and the ProgressManager service it registers) has never
            // been wired up. In production the bridge has run
            // `initialize()` long before any analyze reaches the worker,
            // so `runProcess` installs the indicator as the worker
            // thread's progress indicator and K2's `checkCanceled()` polls
            // see it. When ProgressManager is absent we skip the wrap and
            // run the body directly — the test bridge polls the
            // indicator via [inFlightIndicatorForTest] instead.
            val pm = ProgressManager.getInstanceOrNull()
            System.err.println(
                "[sid-an2f] worker start uri=$uri pm=${pm?.javaClass?.name ?: "null"}"
            )
            var outcome = "natural"
            try {
                val body = Runnable {
                    workerThreadIndicator.set(tracking.indicator)
                    try {
                        val result = bridge.analyze(uri, version)
                        if (request.id != null) transport.sendResult(request.id, result)
                    } finally {
                        workerThreadIndicator.remove()
                    }
                }
                if (pm != null) pm.runProcess(body, tracking.indicator) else body.run()
            } catch (e: ProcessCanceledException) {
                outcome = "pce"
                analyzeCancelledInFlightCount.incrementAndGet()
                if (request.id != null) {
                    transport.sendResult(request.id, emptyAnalyzeResult(version, cancelled = true))
                }
            } catch (e: Throwable) {
                outcome = "error:${e.javaClass.simpleName}"
                System.err.println(
                    "AnalysisServer: worker analyze failed for $uri: ${e.javaClass.name}: ${e.message}"
                )
                e.printStackTrace(System.err)
                if (request.id != null) {
                    transport.sendError(
                        request.id,
                        -32603,
                        "Internal error in analyze: ${e.javaClass.simpleName}: ${e.message}",
                    )
                }
            } finally {
                val elapsedMs = ((System.nanoTime() - workerStartNanos) / 1_000_000L).coerceAtLeast(0L)
                System.err.println(
                    "[sid-an2f] worker outcome uri=$uri outcome=$outcome elapsed_ms=$elapsedMs " +
                        "indicator_cancelled=${tracking.indicator.isCanceled}"
                )
                // Clear the in-flight slot if it still points at this entry. If
                // the dispatcher already cleared it (await or cancel), this is
                // a no-op.
                inFlightAnalyze.compareAndSet(tracking, null)
            }
        }
    }

    /**
     * Cancels the in-flight analyze (if any) and waits briefly for it to
     * unwind. The worker emits the cancelled-shape response from its PCE
     * handler; the counter is incremented there. The 2 s upper bound is a
     * safety valve — if K2 fails to honor the cancel within that window we
     * let the worker continue and proceed with the next request (the
     * dispatcher thread will not race the worker because subsequent Analysis
     * API calls go through [awaitInFlightAnalyze]).
     */
    private fun cancelInFlightAnalyze() {
        val current = inFlightAnalyze.getAndSet(null) ?: return
        analyzeCancelAttemptCount.incrementAndGet()
        val signalAtNanos = System.nanoTime()
        val ageAtCancelMs = ((signalAtNanos - current.startedAtNanos) / 1_000_000L).coerceAtLeast(0L)
        analyzeMaxAgeAtCancelMs.updateAndGet { prev -> maxOf(prev, ageAtCancelMs) }
        val future = current.future
        val futureDoneAtSignal = future?.isDone == true
        System.err.println(
            "[sid-an2f] cancel signal uri=${current.uri} analyze_age_ms=$ageAtCancelMs " +
                "future_done_at_signal=$futureDoneAtSignal"
        )
        current.indicator.cancel()
        if (future == null) return
        val waitStartNanos = System.nanoTime()
        val outcome: String = try {
            future.get(2, TimeUnit.SECONDS)
            "settled"
        } catch (e: TimeoutException) {
            analyzeCancelTimeoutCount.incrementAndGet()
            "timeout-2s"
        } catch (e: Throwable) {
            "exception:${e.javaClass.simpleName}"
        }
        val waitMs = ((System.nanoTime() - waitStartNanos) / 1_000_000L).coerceAtLeast(0L)
        analyzeMaxCancelWaitMs.updateAndGet { prev -> maxOf(prev, waitMs) }
        System.err.println(
            "[sid-an2f] cancel result uri=${current.uri} outcome=$outcome wait_ms=$waitMs " +
                "indicator_cancelled=${current.indicator.isCanceled}"
        )
    }

    /**
     * Awaits the in-flight analyze (if any) without cancelling. Used before
     * the dispatcher runs another Analysis API call (completion/hover/etc.)
     * or queues another analyze.
     */
    private fun awaitInFlightAnalyze() {
        val current = inFlightAnalyze.getAndSet(null) ?: return
        val future = current.future ?: return
        try {
            future.get()
        } catch (e: Throwable) {
            System.err.println(
                "AnalysisServer: awaitInFlightAnalyze: ${e.javaClass.name}: ${e.message}"
            )
        }
    }

    /**
     * Indicator currently bound to the analyze worker thread. Set by the
     * worker before invoking `bridge.analyze` and cleared in `finally`. In
     * production K2's internal `ProgressManager.checkCanceled()` polls see
     * the indicator via the thread-local that `ProgressManager.runProcess`
     * installs, so this is unused on the production path. Tests that
     * exercise the cancellation path poll the indicator from a stub
     * `CompilerBridge.analyze` via [workerIndicatorForTest], because in
     * tests `ProgressManager.getInstanceOrNull()` is null (no IntelliJ
     * Application is registered).
     */
    private val workerThreadIndicator: ThreadLocal<ProgressIndicator?> = ThreadLocal()

    /** Test-only seam — see [workerThreadIndicator]. */
    internal fun workerIndicatorForTest(): ProgressIndicator? = workerThreadIndicator.get()

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
            // "analyze" is intercepted in run() and dispatched on the worker;
            // see startAnalyzeOnWorker. It never reaches this switch.
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
        counts.addProperty("analyzeSupersededByCompletion", analyzeSupersededByCompletionCount.get())
        counts.addProperty("analyzeCancelledInFlight", analyzeCancelledInFlightCount.get())
        counts.addProperty("analyzeCancelAttempts", analyzeCancelAttemptCount.get())
        counts.addProperty("analyzeCancelTimeouts", analyzeCancelTimeoutCount.get())
        counts.addProperty("analyzeMaxAgeAtCancelMs", analyzeMaxAgeAtCancelMs.get())
        counts.addProperty("analyzeMaxCancelWaitMs", analyzeMaxCancelWaitMs.get())
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

/**
 * Minimal `ProgressIndicator` used by [AnalysisServer.startAnalyzeOnWorker] for the
 * per-analyze indicator passed into `ProgressManager.runProcess`. Implements
 * [StandardProgressIndicator] so the cooperative-cancel contract is honored.
 *
 * We can't use the stock `EmptyProgressIndicator` because its no-arg constructor
 * calls `ModalityState.defaultModalityState()` which goes through
 * `ApplicationManager.getApplication()` — that returns null in standalone tests
 * (no IntelliJ Application is registered) and NPEs before the indicator is even
 * constructed. The K2 analyzer only ever reads `isCanceled` / `checkCanceled`
 * from this indicator, so the other ProgressIndicator methods are safe no-op
 * stubs.
 */
internal class SidecarProgressIndicator :
    ProgressIndicator,
    StandardProgressIndicator {

    @Volatile private var cancelled: Boolean = false
    @Volatile private var running: Boolean = false
    @Volatile private var indeterminate: Boolean = true
    @Volatile private var fraction: Double = 0.0
    @Volatile private var text: String? = null
    @Volatile private var text2: String? = null

    override fun start() { running = true }
    override fun stop() { running = false }
    override fun isRunning(): Boolean = running

    override fun cancel() { cancelled = true }
    override fun isCanceled(): Boolean = cancelled
    override fun checkCanceled() {
        if (cancelled) throw ProcessCanceledException()
    }

    override fun setText(text: String?) { this.text = text }
    override fun getText(): String? = text
    override fun setText2(text: String?) { this.text2 = text }
    override fun getText2(): String? = text2

    override fun getFraction(): Double = fraction
    override fun setFraction(fraction: Double) { this.fraction = fraction }

    override fun pushState() {}
    override fun popState() {}

    override fun isModal(): Boolean = false
    override fun getModalityState(): ModalityState = ModalityState.nonModal()

    override fun isIndeterminate(): Boolean = indeterminate
    override fun setIndeterminate(indeterminate: Boolean) { this.indeterminate = indeterminate }
}
