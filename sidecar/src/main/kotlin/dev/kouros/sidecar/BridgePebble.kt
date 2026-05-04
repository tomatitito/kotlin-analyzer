package dev.kouros.sidecar

import com.google.gson.JsonArray
import com.google.gson.JsonObject

internal fun BridgeContext.updatePebbleFile(uri: String, text: String) {
    pebbleDocuments.put(uri, text)
    pebbleIndex.update(uri, text)
    pebbleSpringIndexDirty = true
}

internal fun BridgeContext.removePebbleFile(uri: String) {
    pebbleDocuments.remove(uri)
    pebbleIndex.remove(uri)
    pebbleSpringIndexDirty = true
}

internal fun BridgeContext.pebbleDefinition(uri: String, line: Int, character: Int): JsonObject {
    ensurePebbleSpringIndexCurrent()

    val result = JsonObject()
    val locationsArray = JsonArray()
    val targetUri = pebbleIndex.definition(uri, line, character)
    if (targetUri != null) {
        val location = JsonObject()
        location.addProperty("uri", targetUri)
        location.addProperty("line", 1)
        location.addProperty("column", 0)
        locationsArray.add(location)
    } else {
        val symbolLocation = pebbleSpringIndex.definitionForPebbleSymbol(uri, line, character)
        if (symbolLocation != null) {
            val location = JsonObject()
            location.addProperty("uri", symbolLocation.uri)
            location.addProperty("line", symbolLocation.line)
            location.addProperty("column", symbolLocation.column)
            pebbleSpringIndex.producerTypeForPebbleVariableRoot(uri, line, character)?.let {
                location.addProperty("type", it)
            }
            locationsArray.add(location)
        }
    }
    result.add("locations", locationsArray)
    return result
}

internal fun BridgeContext.pebbleReferences(uri: String, line: Int, character: Int): JsonObject {
    val result = JsonObject()
    val locationsArray = JsonArray()
    for (reference in pebbleIndex.referencesAt(uri, line, character)) {
        val location = JsonObject()
        location.addProperty("uri", reference.sourceUri)
        location.addProperty("line", reference.range.startLine)
        location.addProperty("column", reference.range.startColumn)
        locationsArray.add(location)
    }
    result.add("locations", locationsArray)
    return result
}
