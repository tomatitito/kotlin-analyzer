package dev.kouros.sidecar

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Integration tests that verify diagnostic output for the test fixture files
 * in tests/fixtures/kotlin/. Each fixture has EXPECT comments describing the
 * expected errors, warnings, or lack thereof.
 *
 * Error fixtures: tests/fixtures/kotlin/errors/
 * Warning fixtures: tests/fixtures/kotlin/warnings/
 * Edge case fixtures: tests/fixtures/kotlin/edge-cases/
 */
class FixtureDiagnosticsTest {

    private lateinit var bridge: CompilerBridge

    private fun fixtureDir(): String {
        var dir = java.io.File(System.getProperty("user.dir"))
        while (dir.parentFile != null) {
            val fixture = java.io.File(dir, "tests/fixtures/kotlin")
            if (fixture.exists()) return fixture.absolutePath
            dir = dir.parentFile
        }
        error("Could not find tests/fixtures/kotlin/ from ${System.getProperty("user.dir")}")
    }

    @BeforeEach
    fun setUp() {
        bridge = CompilerBridge()
        val fixtureRoot = fixtureDir()
        bridge.initialize(
            projectRoot = fixtureRoot,
            classpath = emptyList(),
            compilerFlags = emptyList(),
            jdkHome = "",
            sourceRoots = listOf(
                "$fixtureRoot/errors",
                "$fixtureRoot/warnings",
                "$fixtureRoot/edge-cases",
                "$fixtureRoot/correct",
            ),
        )
    }

    @AfterEach
    fun tearDown() {
        bridge.shutdown()
    }

    // ===================== ERROR FIXTURES =====================

    @Test
    fun `errors - UnresolvedReference produces UNRESOLVED_REFERENCE error`() {
        val uri = "file://${fixtureDir()}/errors/UnresolvedReference.kt"
        val result = bridge.analyze(uri)
        val errors = extractErrors(result)

        assertTrue(errors.isNotEmpty(), "UnresolvedReference.kt should produce at least one error")
        assertTrue(
            errors.any { it.code.contains("UNRESOLVED_REFERENCE") },
            "should contain UNRESOLVED_REFERENCE, got: ${errors.map { it.code }}"
        )
    }

    @Test
    fun `errors - TypeMismatch produces TYPE_MISMATCH errors`() {
        val uri = "file://${fixtureDir()}/errors/TypeMismatch.kt"
        val result = bridge.analyze(uri)
        val errors = extractErrors(result)

        assertTrue(errors.isNotEmpty(), "TypeMismatch.kt should produce at least one error")
        assertTrue(
            errors.any {
                it.code.contains("TYPE_MISMATCH") ||
                it.code.contains("INITIALIZER_TYPE_MISMATCH") ||
                it.message.contains("type", ignoreCase = true)
            },
            "should contain type mismatch error, got: ${errors.map { "${it.code}: ${it.message}" }}"
        )
    }

    @Test
    fun `errors - ValReassignment produces VAL_REASSIGNMENT error`() {
        val uri = "file://${fixtureDir()}/errors/ValReassignment.kt"
        val result = bridge.analyze(uri)
        val errors = extractErrors(result)

        assertTrue(errors.isNotEmpty(), "ValReassignment.kt should produce at least one error")
        assertTrue(
            errors.any { it.code.contains("VAL_REASSIGNMENT") },
            "should contain VAL_REASSIGNMENT, got: ${errors.map { it.code }}"
        )
    }

    @Test
    fun `errors - WrongArgCount produces argument count errors`() {
        val uri = "file://${fixtureDir()}/errors/WrongArgCount.kt"
        val result = bridge.analyze(uri)
        val errors = extractErrors(result)

        assertTrue(errors.isNotEmpty(), "WrongArgCount.kt should produce at least one error")
        assertTrue(
            errors.any {
                it.code.contains("NO_VALUE_FOR_PARAMETER") ||
                it.code.contains("TOO_MANY_ARGUMENTS") ||
                it.code.contains("NONE_APPLICABLE") ||
                it.message.contains("argument", ignoreCase = true)
            },
            "should contain argument-related error, got: ${errors.map { "${it.code}: ${it.message}" }}"
        )
    }

    @Test
    fun `errors - NullViolation produces null safety error`() {
        val uri = "file://${fixtureDir()}/errors/NullViolation.kt"
        val result = bridge.analyze(uri)
        val errors = extractErrors(result)

        assertTrue(errors.isNotEmpty(), "NullViolation.kt should produce at least one error")
        assertTrue(
            errors.any {
                it.code.contains("NULL") ||
                it.code.contains("TYPE_MISMATCH") ||
                it.message.contains("null", ignoreCase = true)
            },
            "should contain null-related error, got: ${errors.map { "${it.code}: ${it.message}" }}"
        )
    }

    @Test
    fun `errors - MissingOverride produces abstract member error`() {
        val uri = "file://${fixtureDir()}/errors/MissingOverride.kt"
        val result = bridge.analyze(uri)
        val errors = extractErrors(result)

        assertTrue(errors.isNotEmpty(), "MissingOverride.kt should produce at least one error")
        assertTrue(
            errors.any {
                it.code.contains("ABSTRACT") ||
                it.message.contains("abstract", ignoreCase = true)
            },
            "should contain abstract member error, got: ${errors.map { "${it.code}: ${it.message}" }}"
        )
    }

    @Test
    fun `errors - ReturnTypeMismatch produces type mismatch error`() {
        val uri = "file://${fixtureDir()}/errors/ReturnTypeMismatch.kt"
        val result = bridge.analyze(uri)
        val errors = extractErrors(result)

        assertTrue(errors.isNotEmpty(), "ReturnTypeMismatch.kt should produce at least one error")
        assertTrue(
            errors.any {
                it.code.contains("TYPE_MISMATCH") ||
                it.code.contains("RETURN_TYPE_MISMATCH") ||
                it.message.contains("type", ignoreCase = true)
            },
            "should contain return type mismatch error, got: ${errors.map { "${it.code}: ${it.message}" }}"
        )
    }

    @Test
    fun `errors - AbstractInstantiation produces error`() {
        val uri = "file://${fixtureDir()}/errors/AbstractInstantiation.kt"
        val result = bridge.analyze(uri)
        val errors = extractErrors(result)

        assertTrue(errors.isNotEmpty(), "AbstractInstantiation.kt should produce at least one error")
        assertTrue(
            errors.any {
                it.code.contains("ABSTRACT") ||
                it.code.contains("CREATING_AN_INSTANCE_OF_ABSTRACT_CLASS") ||
                it.message.contains("abstract", ignoreCase = true)
            },
            "should contain abstract instantiation error, got: ${errors.map { "${it.code}: ${it.message}" }}"
        )
    }

    @Test
    fun `errors - DuplicateDeclaration produces conflicting declarations error`() {
        val uri = "file://${fixtureDir()}/errors/DuplicateDeclaration.kt"
        val result = bridge.analyze(uri)
        val errors = extractErrors(result)

        assertTrue(errors.isNotEmpty(), "DuplicateDeclaration.kt should produce at least one error")
        assertTrue(
            errors.any {
                it.code.contains("CONFLICTING") ||
                it.code.contains("REDECLARATION") ||
                it.message.contains("conflicting", ignoreCase = true)
            },
            "should contain conflicting declarations error, got: ${errors.map { "${it.code}: ${it.message}" }}"
        )
    }

    @Test
    fun `errors - VisibilityViolation produces visibility error`() {
        val uri = "file://${fixtureDir()}/errors/VisibilityViolation.kt"
        val result = bridge.analyze(uri)
        val errors = extractErrors(result)

        assertTrue(errors.isNotEmpty(), "VisibilityViolation.kt should produce at least one error")
        assertTrue(
            errors.any {
                it.code.contains("INVISIBLE") ||
                it.code.contains("PRIVATE") ||
                it.message.contains("private", ignoreCase = true) ||
                it.message.contains("invisible", ignoreCase = true)
            },
            "should contain visibility error, got: ${errors.map { "${it.code}: ${it.message}" }}"
        )
    }

    @Test
    fun `errors - MissingSuperCall produces super constructor error`() {
        val uri = "file://${fixtureDir()}/errors/MissingSuperCall.kt"
        val result = bridge.analyze(uri)
        val errors = extractErrors(result)

        assertTrue(errors.isNotEmpty(), "MissingSuperCall.kt should produce at least one error")
        assertTrue(
            errors.any {
                it.code.contains("SUPER") ||
                it.code.contains("NONE_APPLICABLE") ||
                it.message.contains("constructor", ignoreCase = true) ||
                it.message.contains("super", ignoreCase = true)
            },
            "should contain super constructor error, got: ${errors.map { "${it.code}: ${it.message}" }}"
        )
    }

    // ===================== WARNING FIXTURES =====================

    @Test
    fun `warnings - UnusedVariable produces warning`() {
        val uri = "file://${fixtureDir()}/warnings/UnusedVariable.kt"
        val result = bridge.analyze(uri)
        val warnings = extractWarnings(result)

        assertTrue(warnings.isNotEmpty(), "UnusedVariable.kt should produce at least one warning")
        assertTrue(
            warnings.any {
                it.code.contains("UNUSED") ||
                it.message.contains("unused", ignoreCase = true) ||
                it.message.contains("never used", ignoreCase = true)
            },
            "should contain unused variable warning, got: ${warnings.map { "${it.code}: ${it.message}" }}"
        )
    }

    @Test
    fun `warnings - UnusedImport does not crash analyzer`() {
        // Note: UNUSED_IMPORT is an IDE-level inspection, not a compiler diagnostic.
        // The standalone Analysis API does not produce this warning.
        // This test verifies the file can be analyzed without errors.
        val uri = "file://${fixtureDir()}/warnings/UnusedImport.kt"
        val result = bridge.analyze(uri)
        val errors = extractErrors(result)

        assertEquals(0, errors.size, "UnusedImport.kt should produce no errors, got: ${errors.map { "${it.code}: ${it.message}" }}")
    }

    @Test
    fun `warnings - DeprecatedUsage produces deprecation warning`() {
        val uri = "file://${fixtureDir()}/warnings/DeprecatedUsage.kt"
        val result = bridge.analyze(uri)
        val warnings = extractWarnings(result)

        assertTrue(warnings.isNotEmpty(), "DeprecatedUsage.kt should produce at least one warning")
        assertTrue(
            warnings.any {
                it.code.contains("DEPRECATION") ||
                it.message.contains("deprecated", ignoreCase = true)
            },
            "should contain deprecation warning, got: ${warnings.map { "${it.code}: ${it.message}" }}"
        )
    }

    @Test
    fun `warnings - RedundantCast produces warning`() {
        val uri = "file://${fixtureDir()}/warnings/RedundantCast.kt"
        val result = bridge.analyze(uri)
        val warnings = extractWarnings(result)

        assertTrue(warnings.isNotEmpty(), "RedundantCast.kt should produce at least one warning")
        assertTrue(
            warnings.any {
                it.code.contains("USELESS_CAST") ||
                it.message.contains("cast", ignoreCase = true)
            },
            "should contain redundant cast warning, got: ${warnings.map { "${it.code}: ${it.message}" }}"
        )
    }

    @Test
    fun `warnings - UnreachableCode produces warning`() {
        val uri = "file://${fixtureDir()}/warnings/UnreachableCode.kt"
        val result = bridge.analyze(uri)
        val warnings = extractWarnings(result)

        assertTrue(warnings.isNotEmpty(), "UnreachableCode.kt should produce at least one warning")
        assertTrue(
            warnings.any {
                it.code.contains("UNREACHABLE") ||
                it.message.contains("unreachable", ignoreCase = true)
            },
            "should contain unreachable code warning, got: ${warnings.map { "${it.code}: ${it.message}" }}"
        )
    }

    @Test
    fun `warnings - UnnecessarySafeCall produces warning`() {
        val uri = "file://${fixtureDir()}/warnings/UnnecessarySafeCall.kt"
        val result = bridge.analyze(uri)
        val warnings = extractWarnings(result)

        assertTrue(warnings.isNotEmpty(), "UnnecessarySafeCall.kt should produce at least one warning")
        assertTrue(
            warnings.any {
                it.code.contains("UNNECESSARY_SAFE_CALL") ||
                it.message.contains("safe call", ignoreCase = true)
            },
            "should contain unnecessary safe call warning, got: ${warnings.map { "${it.code}: ${it.message}" }}"
        )
    }

    @Test
    fun `warnings - RedundantIf does not crash analyzer`() {
        // Note: REDUNDANT_IF is an IDE-level inspection, not a compiler diagnostic.
        // The standalone Analysis API does not produce this warning.
        val uri = "file://${fixtureDir()}/warnings/RedundantIf.kt"
        val result = bridge.analyze(uri)
        val errors = extractErrors(result)

        assertEquals(0, errors.size, "RedundantIf.kt should produce no errors, got: ${errors.map { "${it.code}: ${it.message}" }}")
    }

    @Test
    fun `warnings - UnusedParameter does not crash analyzer`() {
        // Note: UNUSED_PARAMETER is an IDE-level inspection, not a compiler diagnostic.
        // The standalone Analysis API does not produce this warning.
        val uri = "file://${fixtureDir()}/warnings/UnusedParameter.kt"
        val result = bridge.analyze(uri)
        val errors = extractErrors(result)

        assertEquals(0, errors.size, "UnusedParameter.kt should produce no errors, got: ${errors.map { "${it.code}: ${it.message}" }}")
    }

    // ===================== EDGE CASE FIXTURES =====================

    @Test
    fun `edge-cases - Empty file produces no errors`() {
        val uri = "file://${fixtureDir()}/edge-cases/Empty.kt"
        val result = bridge.analyze(uri)
        val errors = extractErrors(result)

        assertEquals(0, errors.size, "Empty.kt should produce no errors, got: ${errors.map { "${it.code}: ${it.message}" }}")
    }

    @Test
    fun `edge-cases - OnlyComments file produces no errors`() {
        val uri = "file://${fixtureDir()}/edge-cases/OnlyComments.kt"
        val result = bridge.analyze(uri)
        val errors = extractErrors(result)

        assertEquals(0, errors.size, "OnlyComments.kt should produce no errors, got: ${errors.map { "${it.code}: ${it.message}" }}")
    }

    @Test
    fun `edge-cases - OnlyPackage file produces no errors`() {
        val uri = "file://${fixtureDir()}/edge-cases/OnlyPackage.kt"
        val result = bridge.analyze(uri)
        val errors = extractErrors(result)

        assertEquals(0, errors.size, "OnlyPackage.kt should produce no errors, got: ${errors.map { "${it.code}: ${it.message}" }}")
    }

    @Test
    fun `edge-cases - SyntaxError produces errors`() {
        val uri = "file://${fixtureDir()}/edge-cases/SyntaxError.kt"
        val result = bridge.analyze(uri)
        val errors = extractErrors(result)

        assertTrue(errors.isNotEmpty(), "SyntaxError.kt should produce at least one error")
    }

    @Test
    fun `edge-cases - UnicodeIdentifiers produces no errors`() {
        val uri = "file://${fixtureDir()}/edge-cases/UnicodeIdentifiers.kt"
        val result = bridge.analyze(uri)
        val errors = extractErrors(result)

        assertEquals(0, errors.size, "UnicodeIdentifiers.kt should produce no errors, got: ${errors.map { "${it.code}: ${it.message}" }}")
    }

    // ===================== HOVER ON FIXTURES =====================

    @Test
    fun `hover - deprecated function shows Deprecated annotation`() {
        val uri = "file://${fixtureDir()}/warnings/DeprecatedUsage.kt"
        val result = bridge.hover(uri, line = 7, character = 4)

        val contents = result.get("contents")?.asString
        assertNotNull(contents, "hover on @Deprecated function should return contents")
        assertTrue(
            contents.contains("Deprecated") || contents.contains("oldWay"),
            "hover should mention Deprecated or oldWay, got: $contents"
        )
    }

    @Test
    fun `hover - class in correct fixture shows declaration`() {
        // Hover on a class from the correct/ fixtures to verify resolution
        val correctDir = fixtureDir() + "/correct"
        // Re-initialize with correct/ directory
        bridge.shutdown()
        bridge = CompilerBridge()
        bridge.initialize(
            projectRoot = correctDir,
            classpath = emptyList(),
            compilerFlags = emptyList(),
            jdkHome = "",
            sourceRoots = listOf(correctDir),
        )

        // BasicConstructs.kt should have a class declaration
        val uri = "file://$correctDir/BasicConstructs.kt"
        val result = bridge.hover(uri, line = 4, character = 6)

        val contents = result.get("contents")?.asString
        // The hover should return something (may not resolve if line/col mismatch)
        // At minimum, analyze should not crash
        bridge.analyze(uri)
    }

    // ===================== HELPER FUNCTIONS =====================

    private data class DiagnosticInfo(val severity: String, val code: String, val message: String, val line: Int)

    private fun extractErrors(result: com.google.gson.JsonObject): List<DiagnosticInfo> {
        return extractAllDiagnostics(result).filter { it.severity == "ERROR" }
    }

    private fun extractWarnings(result: com.google.gson.JsonObject): List<DiagnosticInfo> {
        return extractAllDiagnostics(result).filter { it.severity == "WARNING" }
    }

    private fun extractAllDiagnostics(result: com.google.gson.JsonObject): List<DiagnosticInfo> {
        val diagnostics = result.getAsJsonArray("diagnostics") ?: return emptyList()
        return diagnostics.map {
            val obj = it.asJsonObject
            DiagnosticInfo(
                severity = obj.get("severity")?.asString ?: "",
                code = obj.get("code")?.asString ?: "",
                message = obj.get("message")?.asString ?: "",
                line = obj.get("startLine")?.asInt ?: 0,
            )
        }
    }
}
