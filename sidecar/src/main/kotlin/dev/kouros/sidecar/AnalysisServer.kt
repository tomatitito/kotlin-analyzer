package dev.kouros.sidecar

import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive

/**
 * JSON-RPC server that dispatches requests to the CompilerBridge.
 * Runs a single-threaded event loop reading from stdin and writing to stdout.
 */
class AnalysisServer(
    private val transport: JsonRpcTransport,
    private val bridge: CompilerBridge = CompilerBridge(),
) {
    private var running = true

    /**
     * Starts the event loop. Blocks until shutdown or EOF.
     */
    fun run() {
        System.err.println("AnalysisServer: starting event loop")

        while (running) {
            val request = transport.readRequest()
            if (request == null) {
                System.err.println("AnalysisServer: EOF on stdin, exiting")
                break
            }

            try {
                handleRequest(request)
            } catch (e: Throwable) {
                System.err.println("AnalysisServer: error handling ${request.method}: ${e.javaClass.name}: ${e.message}")
                e.printStackTrace(System.err)
                if (request.id != null) {
                    transport.sendError(request.id, -32603, "Internal error: ${e.javaClass.simpleName}: ${e.message}")
                }
            }
        }

        bridge.shutdown()
        System.err.println("AnalysisServer: event loop ended")
    }

    private fun handleRequest(request: JsonRpcRequest) {
        System.err.println("AnalysisServer: received ${request.method} (id=${request.id})")

        when (request.method) {
            "initialize" -> handleInitialize(request)
            "shutdown" -> handleShutdown(request)
            "ping" -> handlePing(request)
            "textDocument/didOpen" -> handleDidOpen(request)
            "textDocument/didChange" -> handleDidChange(request)
            "textDocument/didClose" -> handleDidClose(request)
            "analyze" -> handleAnalyze(request)
            "hover" -> handleHover(request)
            "completion" -> handleCompletion(request)
            "definition" -> handleDefinition(request)
            "references" -> handleReferences(request)
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
        result.addProperty("kotlinVersion", "2.1.20")
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

    private fun handleAnalyze(request: JsonRpcRequest) {
        val params = request.params ?: run {
            transport.sendError(request.id, -32602, "Missing params")
            return
        }
        val uri = params.get("uri")?.asString ?: run {
            transport.sendError(request.id, -32602, "Missing uri param")
            return
        }

        val result = bridge.analyze(uri)
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

    private fun handleCompletion(request: JsonRpcRequest) {
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

        val result = bridge.completion(uri, line, character)
        transport.sendResult(request.id, result)
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
