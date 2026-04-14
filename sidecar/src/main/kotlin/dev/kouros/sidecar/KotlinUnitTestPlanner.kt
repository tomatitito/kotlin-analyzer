package dev.kouros.sidecar

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.invariantSeparatorsPathString

data class TestTargetSelection(
    val startLine: Int,
    val startCharacter: Int,
    val endLine: Int,
    val endCharacter: Int,
)

sealed interface KotlinUnitTestPlan {
    data class Existing(
        val targetPath: Path,
        val targetUri: String,
        val targetClassName: String,
        val selection: TestTargetSelection,
    ) : KotlinUnitTestPlan

    data class Create(
        val targetPath: Path,
        val targetUri: String,
        val targetClassName: String,
        val initialContents: String,
        val selection: TestTargetSelection,
    ) : KotlinUnitTestPlan

    data class Unavailable(val reason: String) : KotlinUnitTestPlan
}

object KotlinUnitTestPlanner {
    private const val SOURCE_ROOT = "src/main/kotlin"
    private const val TEST_ROOT = "src/test/kotlin"
    private const val GENERATED_METHOD_NAME = "hurz"

    fun plan(
        sourcePath: Path,
        packageName: String,
        className: String,
        fileExists: (Path) -> Boolean = { it.exists() },
        readFile: (Path) -> String = { Files.readString(it) },
    ): KotlinUnitTestPlan {
        if (className.isBlank()) {
            return KotlinUnitTestPlan.Unavailable("class name is missing")
        }

        val packageSegments = packageName
            .split('.')
            .filter { it.isNotBlank() }
        val normalizedSource = sourcePath.normalize().toAbsolutePath()
        val normalizedSegments = normalizedSource
            .invariantSeparatorsPathString
            .trim('/')
            .split('/')
            .filter { it.isNotBlank() }

        val sourceRootIndex = normalizedSegments.indexOfSubsequence(listOf("src", "main", "kotlin"))
        if (sourceRootIndex < 0) {
            return KotlinUnitTestPlan.Unavailable("source path is not under $SOURCE_ROOT")
        }

        val secondSourceRootIndex = normalizedSegments
            .drop(sourceRootIndex + 1)
            .indexOfSubsequence(listOf("src", "main", "kotlin"))
        if (secondSourceRootIndex >= 0) {
            return KotlinUnitTestPlan.Unavailable("project layout is ambiguous")
        }

        val rootPath = normalizedSegments
            .take(sourceRootIndex)
            .fold(Path.of("/")) { acc, segment -> acc.resolve(segment) }
        val relativeDirSegments = normalizedSegments
            .drop(sourceRootIndex + 3)
            .dropLast(1)

        if (relativeDirSegments != packageSegments) {
            return KotlinUnitTestPlan.Unavailable("package path does not mirror source path")
        }

        val targetClassName = "${className}Test"
        val testRootPath = rootPath.resolve(TEST_ROOT)
        val targetDir = packageSegments.fold(testRootPath) { acc, segment -> acc.resolve(segment) }
        val targetPath = targetDir.resolve("$targetClassName.kt")
        val targetUri = targetPath.toUri().toString()

        return if (fileExists(targetPath)) {
            val selection = selectExistingTarget(readFile(targetPath), targetClassName)
                ?: return KotlinUnitTestPlan.Unavailable("existing test selection could not be determined")
            KotlinUnitTestPlan.Existing(targetPath, targetUri, targetClassName, selection)
        } else {
            val generated = generateNewTestFile(packageName, targetClassName)
            KotlinUnitTestPlan.Create(
                targetPath = targetPath,
                targetUri = targetUri,
                targetClassName = targetClassName,
                initialContents = generated.contents,
                selection = generated.selection,
            )
        }
    }

    private data class GeneratedTestFile(
        val contents: String,
        val selection: TestTargetSelection,
    )

    private fun generateNewTestFile(packageName: String, targetClassName: String): GeneratedTestFile {
        val marker = "__KOTLIN_ANALYZER_SELECTION__"
        val packageBlock = if (packageName.isBlank()) {
            ""
        } else {
            "package $packageName\n\n"
        }
        val contentsWithMarker = buildString {
            append(packageBlock)
            append("import org.junit.jupiter.api.Test\n\n")
            append("class $targetClassName {\n")
            append("    @Test\n")
            append("    fun $GENERATED_METHOD_NAME() {\n")
            append("        ")
            append(marker)
            append("\n")
            append("    }\n")
            append("}\n")
        }

        val selection = selectionForMarker(contentsWithMarker, marker)
        return GeneratedTestFile(
            contents = contentsWithMarker.replace(marker, ""),
            selection = selection,
        )
    }

    private fun selectExistingTarget(contents: String, targetClassName: String): TestTargetSelection? {
        val classMatch = Regex("""(?m)^\s*class\s+${Regex.escape(targetClassName)}\b""")
            .find(contents)
            ?: return null
        val classBody = contents.substring(classMatch.range.first)

        val annotatedMethodMatch = Regex("""(?ms)^\s*@Test\b.*?^\s*fun\s+\w+\s*\(""")
            .find(classBody)
        val methodMatch = annotatedMethodMatch
            ?: Regex("""(?m)^\s*fun\s+\w+\s*\(""").find(classBody)
            ?: return selectionFromOffset(contents, classMatch.range.first)

        val methodOffset = classMatch.range.first + methodMatch.range.first + methodMatch.value.indexOf("fun")
        return selectionFromOffset(contents, methodOffset)
    }

    private fun selectionForMarker(contents: String, marker: String): TestTargetSelection {
        val markerOffset = contents.indexOf(marker)
        check(markerOffset >= 0) { "selection marker missing from generated contents" }
        return selectionFromOffset(contents, markerOffset)
    }

    private fun selectionFromOffset(contents: String, offset: Int): TestTargetSelection {
        val clampedOffset = offset.coerceIn(0, contents.length)
        var line = 0
        var character = 0

        for (index in 0 until clampedOffset) {
            if (contents[index] == '\n') {
                line += 1
                character = 0
            } else {
                character += 1
            }
        }

        return TestTargetSelection(
            startLine = line,
            startCharacter = character,
            endLine = line,
            endCharacter = character,
        )
    }

    private fun List<String>.indexOfSubsequence(subsequence: List<String>): Int {
        if (subsequence.isEmpty() || size < subsequence.size) {
            return -1
        }

        for (start in 0..(size - subsequence.size)) {
            if (subList(start, start + subsequence.size) == subsequence) {
                return start
            }
        }

        return -1
    }
}
