package dev.kouros.sidecar

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.*

/**
 * JSON-RPC 2.0 message types for stdin/stdout communication with the Rust LSP frontend.
 * Uses Content-Length framing identical to LSP transport.
 */

data class JsonRpcRequest(
    val jsonrpc: String = "2.0",
    val id: Long? = null,
    val method: String,
    val params: JsonObject? = null,
)

data class JsonRpcResponse(
    val jsonrpc: String = "2.0",
    val id: Long? = null,
    val result: JsonElement? = null,
    val error: JsonRpcError? = null,
)

data class JsonRpcError(
    val code: Int,
    val message: String,
    val data: JsonElement? = null,
)

/**
 * Reads and writes JSON-RPC messages with Content-Length framing.
 */
class JsonRpcTransport(
    private val input: InputStream,
    private val output: OutputStream,
) {
    private val bufferedInput = BufferedInputStream(input)
    private val bufferedOutput = BufferedOutputStream(output)
    private val gson = Gson()

    /**
     * Reads a single JSON-RPC request from the input stream.
     * Returns null on EOF.
     */
    fun readRequest(): JsonRpcRequest? {
        val contentLength = readContentLength() ?: return null

        val body = ByteArray(contentLength)
        var read = 0
        while (read < contentLength) {
            val n = bufferedInput.read(body, read, contentLength - read)
            if (n == -1) return null
            read += n
        }

        val json = body.toString(Charsets.UTF_8)
        return try {
            val obj = JsonParser.parseString(json).asJsonObject
            JsonRpcRequest(
                jsonrpc = obj.get("jsonrpc")?.asString ?: "2.0",
                id = obj.get("id")?.asLong,
                method = obj.get("method")?.asString ?: "",
                params = obj.get("params")?.asJsonObject,
            )
        } catch (e: Exception) {
            System.err.println("Failed to parse JSON-RPC request: ${e.message}")
            null
        }
    }

    /**
     * Writes a JSON-RPC response to the output stream.
     */
    fun writeResponse(response: JsonRpcResponse) {
        val json = gson.toJson(response)
        val bytes = json.toByteArray(Charsets.UTF_8)
        val header = "Content-Length: ${bytes.size}\r\n\r\n".toByteArray(Charsets.UTF_8)

        synchronized(bufferedOutput) {
            bufferedOutput.write(header)
            bufferedOutput.write(bytes)
            bufferedOutput.flush()
        }
    }

    /**
     * Sends a successful response.
     */
    fun sendResult(id: Long?, result: JsonElement?) {
        writeResponse(JsonRpcResponse(id = id, result = result))
    }

    /**
     * Sends an error response.
     */
    fun sendError(id: Long?, code: Int, message: String) {
        writeResponse(
            JsonRpcResponse(
                id = id,
                error = JsonRpcError(code = code, message = message),
            )
        )
    }

    private fun readContentLength(): Int? {
        var contentLength: Int? = null

        while (true) {
            val line = readAsciiLine() ?: return null // EOF
            val trimmed = line.trim()

            if (trimmed.isEmpty()) break // End of headers

            if (trimmed.startsWith("Content-Length: ")) {
                contentLength = trimmed.removePrefix("Content-Length: ").toIntOrNull()
            }
        }

        return contentLength
    }

    private fun readAsciiLine(): String? {
        val buffer = ByteArrayOutputStream()

        while (true) {
            val next = bufferedInput.read()
            if (next == -1) {
                return if (buffer.size() == 0) null else buffer.toString(Charsets.UTF_8)
            }

            if (next == '\n'.code) {
                return buffer.toString(Charsets.UTF_8).removeSuffix("\r")
            }

            buffer.write(next)
        }
    }
}
