package dev.kouros.sidecar

import com.google.gson.JsonArray
import com.google.gson.JsonObject

internal fun BridgeContext.workspaceSymbols(query: String): JsonObject {
    val result = JsonObject()
    val symbolsArray = JsonArray()
    val perfStart = System.currentTimeMillis()

    try {
        val indexed = symbolIndex.searchSymbols(query)
        for (decl in indexed) {
            val symbolObj = JsonObject()
            symbolObj.addProperty("name", decl.shortName)
            symbolObj.addProperty("kind", decl.kind)
            symbolObj.addProperty("uri", decl.uri)
            symbolObj.addProperty("line", decl.line)
            symbolObj.addProperty("column", decl.column)
            symbolsArray.add(symbolObj)
        }
    } catch (e: Throwable) {
        System.err.println("CompilerBridge: workspaceSymbols failed: ${e.javaClass.name}: ${e.message}")
    }

    System.err.println("[PERF] method=workspaceSymbols elapsed=${System.currentTimeMillis() - perfStart}ms")
    result.add("symbols", symbolsArray)
    return result
}
