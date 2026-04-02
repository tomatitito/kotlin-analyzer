package dev.kouros.sidecar

import org.junit.jupiter.api.Test
import java.nio.file.Files
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class KotlinUnitTestPlannerTest {

    @Test
    fun `plan returns existing target for mirrored test file`() {
        val workspace = Files.createTempDirectory("unit-test-planner-existing")
        val sourcePath = workspace.resolve("src/main/kotlin/com/example/Foo.kt")
        Files.createDirectories(sourcePath.parent)
        sourcePath.writeText("package com.example\n\nclass Foo\n")

        val targetPath = workspace.resolve("src/test/kotlin/com/example/FooTest.kt")
        Files.createDirectories(targetPath.parent)
        targetPath.writeText(
            """
            package com.example

            import org.junit.jupiter.api.Test

            class FooTest {
                @Test
                fun existingWorks() {
                }
            }
            """.trimIndent() + "\n"
        )

        val plan = KotlinUnitTestPlanner.plan(sourcePath, "com.example", "Foo")

        val existing = assertIs<KotlinUnitTestPlan.Existing>(plan)
        assertEquals(targetPath.normalize().toAbsolutePath(), existing.targetPath)
        assertEquals(targetPath.toUri().toString(), existing.targetUri)
        assertEquals("FooTest", existing.targetClassName)
        assertEquals(TestTargetSelection(6, 4, 6, 4), existing.selection)
    }

    @Test
    fun `plan returns create target with junit skeleton when missing`() {
        val workspace = Files.createTempDirectory("unit-test-planner-create")
        val sourcePath = workspace.resolve("src/main/kotlin/com/example/Foo.kt")
        Files.createDirectories(sourcePath.parent)
        sourcePath.writeText("package com.example\n\nclass Foo\n")

        val plan = KotlinUnitTestPlanner.plan(sourcePath, "com.example", "Foo")

        val create = assertIs<KotlinUnitTestPlan.Create>(plan)
        assertEquals(
            workspace.resolve("src/test/kotlin/com/example/FooTest.kt").normalize().toAbsolutePath(),
            create.targetPath,
        )
        assertEquals("FooTest", create.targetClassName)
        assertTrue(create.initialContents.contains("package com.example"))
        assertTrue(create.initialContents.contains("import org.junit.jupiter.api.Test"))
        assertTrue(create.initialContents.contains("class FooTest"))
        assertTrue(create.initialContents.contains("fun hurz()"))
        assertEquals(TestTargetSelection(7, 8, 7, 8), create.selection)
    }

    @Test
    fun `plan rejects sources outside main kotlin root`() {
        val workspace = Files.createTempDirectory("unit-test-planner-invalid-root")
        val sourcePath = workspace.resolve("src/test/kotlin/com/example/Foo.kt")

        val plan = KotlinUnitTestPlanner.plan(sourcePath, "com.example", "Foo")

        assertEquals(
            KotlinUnitTestPlan.Unavailable("source path is not under src/main/kotlin"),
            plan,
        )
    }

    @Test
    fun `plan rejects package path mismatches`() {
        val workspace = Files.createTempDirectory("unit-test-planner-package-mismatch")
        val sourcePath = workspace.resolve("src/main/kotlin/com/example/internal/Foo.kt")

        val plan = KotlinUnitTestPlanner.plan(sourcePath, "com.example", "Foo")

        assertEquals(
            KotlinUnitTestPlan.Unavailable("package path does not mirror source path"),
            plan,
        )
    }

    @Test
    fun `plan rejects ambiguous nested main kotlin roots`() {
        val workspace = Files.createTempDirectory("unit-test-planner-ambiguous")
        val sourcePath = workspace.resolve("src/main/kotlin/generated/src/main/kotlin/com/example/Foo.kt")

        val plan = KotlinUnitTestPlanner.plan(sourcePath, "com.example", "Foo")

        assertEquals(
            KotlinUnitTestPlan.Unavailable("project layout is ambiguous"),
            plan,
        )
    }
}
