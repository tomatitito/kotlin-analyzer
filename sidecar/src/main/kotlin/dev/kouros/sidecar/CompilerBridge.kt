package dev.kouros.sidecar

import com.google.gson.JsonObject
import org.jetbrains.kotlin.config.LanguageFeature
import java.nio.file.Path

/**
 * Facade over the Kotlin Analysis API.
 * Delegates all feature logic to BridgeContext extension functions
 * defined in separate files.
 */
open class CompilerBridge {
    internal val ctx = BridgeContext()

    fun initialize(
        projectRoot: String,
        classpath: List<String>,
        compilerFlags: List<String>,
        jdkHome: String,
        sourceRoots: List<String>,
    ) = ctx.initialize(projectRoot, classpath, compilerFlags, jdkHome, sourceRoots)

    fun shutdown() = ctx.shutdown()

    fun updateFile(uri: String, text: String) = ctx.updateFile(uri, text)

    fun removeFile(uri: String) = ctx.removeFile(uri)

    fun updatePebbleFile(uri: String, text: String) = ctx.updatePebbleFile(uri, text)

    fun removePebbleFile(uri: String) = ctx.removePebbleFile(uri)

    fun pebbleDefinition(uri: String, line: Int, character: Int): JsonObject =
        ctx.pebbleDefinition(uri, line, character)

    fun pebbleReferences(uri: String, line: Int, character: Int): JsonObject =
        ctx.pebbleReferences(uri, line, character)

    /**
     * Analyzes a file and returns diagnostics.
     */
    open fun analyze(uri: String, version: Int? = null): JsonObject =
        ctx.analyze(uri, version)

    fun analyzeAll(): JsonObject =
        ctx.analyzeAll()

    fun hover(uri: String, line: Int, character: Int): JsonObject =
        ctx.hover(uri, line, character)

    fun completion(
        uri: String,
        line: Int,
        character: Int,
        triggerCharacter: String? = null,
        correlationId: String? = null,
        includeMetrics: Boolean = false,
    ): JsonObject =
        ctx.completion(uri, line, character, triggerCharacter, correlationId, includeMetrics)

    fun definition(uri: String, line: Int, character: Int): JsonObject =
        ctx.definition(uri, line, character)

    fun references(uri: String, line: Int, character: Int): JsonObject =
        ctx.references(uri, line, character)

    fun signatureHelp(uri: String, line: Int, character: Int): JsonObject =
        ctx.signatureHelp(uri, line, character)

    fun rename(uri: String, line: Int, character: Int, newName: String): JsonObject =
        ctx.rename(uri, line, character, newName)

    fun codeActions(uri: String, line: Int, character: Int): JsonObject =
        ctx.codeActions(uri, line, character)
    fun workspaceSymbols(query: String): JsonObject =
        ctx.workspaceSymbols(query)

    fun inlayHints(uri: String, startLine: Int, endLine: Int): JsonObject =
        ctx.inlayHints(uri, startLine, endLine)

    fun codeLens(uri: String): JsonObject =
        ctx.codeLens(uri)

    fun semanticTokens(uri: String): JsonObject =
        ctx.semanticTokens(uri)

    fun callHierarchyPrepare(uri: String, line: Int, character: Int): JsonObject =
        ctx.callHierarchyPrepare(uri, line, character)

    fun callHierarchyIncoming(uri: String, line: Int, character: Int, name: String): JsonObject =
        ctx.callHierarchyIncoming(uri, line, character, name)

    fun typeHierarchyPrepare(uri: String, line: Int, character: Int): JsonObject =
        ctx.typeHierarchyPrepare(uri, line, character)

    fun typeHierarchySupertypes(uri: String, line: Int, character: Int, name: String): JsonObject =
        ctx.typeHierarchySupertypes(uri, line, character, name)

    fun implementation(uri: String, line: Int, character: Int): JsonObject =
        ctx.implementation(uri, line, character)

    companion object {
        fun bundledKotlinVersion(): String = CompilerBridgeRuntime.bundledKotlinVersion()

        internal fun findStdlibJarsInRepository(repositoryRoot: Path, preferVersion: String? = null): List<Path> =
            CompilerBridgeRuntime.findStdlibJarsInRepository(repositoryRoot, preferVersion)

        fun mapCompilerFlag(flag: String): LanguageFeature? = CompilerBridgeRuntime.mapCompilerFlag(flag)
    }
}
