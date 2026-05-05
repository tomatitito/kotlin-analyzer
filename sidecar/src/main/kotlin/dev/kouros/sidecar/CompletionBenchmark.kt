package dev.kouros.sidecar

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.Gson
import java.io.File
import java.util.Locale
import kotlin.math.ceil
import kotlin.system.exitProcess

private const val DEFAULT_WARMUPS = 3
private const val DEFAULT_ITERATIONS = 15

private data class BenchmarkOptions(
    val warmups: Int = DEFAULT_WARMUPS,
    val iterations: Int = DEFAULT_ITERATIONS,
    val fixtureRoot: File = findFixtureRoot(),
    val rawOutput: File? = null,
)

private data class ProjectModel(
    val sourceRoots: List<String>,
    val classpath: List<String>,
    val compilerFlags: List<String>,
    val jdkHome: String,
)

private data class CompletionScenario(
    val name: String,
    val uri: String,
    val textWithCaret: String,
    val triggerCharacter: String? = null,
)

private data class CaretDocument(
    val text: String,
    val line: Int,
    val character: Int,
)

private data class ScenarioStats(
    val name: String,
    val file: String,
    val itemCount: Int,
    val memberCount: Int,
    val scopeCount: Int,
    val autoImportCount: Int,
    val keywordCount: Int,
    val minMs: Double,
    val avgMs: Double,
    val p95Ms: Double,
    val maxMs: Double,
    val sidecarAvgMs: Double,
    val stageAveragesMs: Map<String, Double>,
)

private data class ScenarioSample(
    val scenario: String,
    val file: String,
    val iteration: Int,
    val totalMs: Double,
    val itemCount: Int,
    val counts: Map<String, Int>,
    val stagesMs: Map<String, Double>,
)

fun main(args: Array<String>) {
    runBenchmark(args)
    exitProcess(0)
}

private fun runBenchmark(args: Array<String>) {
    val options = parseOptions(args)
    val model = loadProjectModel(options.fixtureRoot)
    val sourceRoot = model.sourceRoots.first()

    val bridge = CompilerBridge()
    try {
        bridge.initialize(
            projectRoot = options.fixtureRoot.absolutePath,
            classpath = model.classpath,
            compilerFlags = model.compilerFlags,
            jdkHome = model.jdkHome,
            sourceRoots = model.sourceRoots,
        )

        val scenarios = completionScenarios(sourceRoot)
        options.rawOutput?.parentFile?.mkdirs()
        options.rawOutput?.writeText("")

        val stats = scenarios.map { scenario ->
            runScenario(bridge, scenario, options.warmups, options.iterations, options.rawOutput)
        }

        printSummary(options, stats)
    } finally {
        bridge.shutdown()
    }
}

private fun parseOptions(args: Array<String>): BenchmarkOptions {
    var warmups = DEFAULT_WARMUPS
    var iterations = DEFAULT_ITERATIONS
    var fixtureRoot: File? = null
    var rawOutput: File? = null

    var index = 0
    while (index < args.size) {
        when (val arg = args[index]) {
            "--warmups" -> warmups = args.getOrNull(++index)?.toIntOrNull()
                ?: error("--warmups requires an integer")
            "--iterations" -> iterations = args.getOrNull(++index)?.toIntOrNull()
                ?: error("--iterations requires an integer")
            "--fixture-root" -> fixtureRoot = File(args.getOrNull(++index)
                ?: error("--fixture-root requires a path"))
            "--jsonl-output" -> rawOutput = File(args.getOrNull(++index)
                ?: error("--jsonl-output requires a path"))
            else -> error("Unknown argument: $arg")
        }
        index++
    }

    require(warmups >= 0) { "--warmups must be >= 0" }
    require(iterations > 0) { "--iterations must be > 0" }

    return BenchmarkOptions(
        warmups = warmups,
        iterations = iterations,
        fixtureRoot = fixtureRoot ?: findFixtureRoot(),
        rawOutput = rawOutput,
    )
}

private fun completionScenarios(sourceRoot: String): List<CompletionScenario> {
    fun uri(name: String) = "file://$sourceRoot/$name"

    return listOf(
        CompletionScenario(
            name = "dot completion",
            uri = uri("Example.kt"),
            triggerCharacter = ".",
            textWithCaret = """
                import org.springframework.web.bind.annotation.RestController

                @RestController
                class ExampleController {
                    fun greet(name: String): String {
                        val greeting = "Hello, ${'$'}name!"
                        return greeting.<caret>
                    }
                }
            """.trimIndent(),
        ),
        CompletionScenario(
            name = "prefix completion",
            uri = uri("BenchmarkPrefix.kt"),
            textWithCaret = """
                import org.springframework.web.server.ResponseStatusException

                fun benchmarkPrefix() {
                    Resp<caret>
                }
            """.trimIndent(),
        ),
        CompletionScenario(
            name = "import completion",
            uri = uri("BenchmarkImport.kt"),
            textWithCaret = "import org.springframework.web.bind.annotation.Get<caret>\n",
        ),
        CompletionScenario(
            name = "empty prefix",
            uri = uri("BenchmarkEmptyPrefix.kt"),
            textWithCaret = """
                fun benchmarkEmptyPrefix() {
                    <caret>
                }
            """.trimIndent(),
        ),
        CompletionScenario(
            name = "after edit",
            uri = uri("SpringRegressions.kt"),
            triggerCharacter = ".",
            textWithCaret = """
                import org.springframework.http.ResponseEntity

                fun respondOk(): ResponseEntity<Void> {
                    val response = ResponseEntity.ok()
                    return response.<caret>
                }
            """.trimIndent(),
        ),
    )
}

private fun runScenario(
    bridge: CompilerBridge,
    scenario: CompletionScenario,
    warmups: Int,
    iterations: Int,
    rawOutput: File?,
): ScenarioStats {
    val doc = parseCaret(scenario.textWithCaret)
    bridge.updateFile(scenario.uri, doc.text)

    repeat(warmups) {
        bridge.completion(
            scenario.uri,
            doc.line,
            doc.character,
            scenario.triggerCharacter,
            correlationId = "${scenario.name}-warmup-$it",
            includeMetrics = true,
        )
    }

    val samples = mutableListOf<ScenarioSample>()
    repeat(iterations) { iteration ->
        val start = System.nanoTime()
        val result = bridge.completion(
            scenario.uri,
            doc.line,
            doc.character,
            scenario.triggerCharacter,
            correlationId = "${scenario.name}-$iteration",
            includeMetrics = true,
        )
        val totalMs = (System.nanoTime() - start) / 1_000_000.0
        val sample = ScenarioSample(
            scenario = scenario.name,
            file = scenario.uri.substringAfterLast('/'),
            iteration = iteration + 1,
            totalMs = totalMs,
            itemCount = result.getAsJsonArray("items")?.size() ?: 0,
            counts = result.getAsJsonObject("metrics")?.getAsJsonObject("counts")?.intMap().orEmpty(),
            stagesMs = result.getAsJsonObject("metrics")?.getAsJsonObject("stages")?.doubleMap().orEmpty(),
        )
        samples += sample
        rawOutput?.appendText(Gson().toJson(sample) + "\n")
    }

    val elapsed = samples.map { it.totalMs }
    val sorted = elapsed.sorted()
    val stageAverages = samples
        .flatMap { sample -> sample.stagesMs.keys }
        .distinct()
        .associateWith { stage ->
            samples.mapNotNull { it.stagesMs[stage] }.averageOrZero()
        }
    val lastCounts = samples.lastOrNull()?.counts.orEmpty()
    return ScenarioStats(
        name = scenario.name,
        file = scenario.uri.substringAfterLast('/'),
        itemCount = samples.lastOrNull()?.itemCount ?: 0,
        memberCount = lastCounts["memberCandidates"] ?: 0,
        scopeCount = lastCounts["scopeCandidates"] ?: 0,
        autoImportCount = lastCounts["autoImportCandidates"] ?: lastCounts["importCandidates"] ?: 0,
        keywordCount = lastCounts["keywordCandidates"] ?: 0,
        minMs = sorted.first(),
        avgMs = elapsed.average(),
        p95Ms = percentile(sorted, 0.95),
        maxMs = sorted.last(),
        sidecarAvgMs = samples.mapNotNull { it.stagesMs["analysis_api_analyze_block"] }.averageOrZero(),
        stageAveragesMs = stageAverages,
    )
}

private fun parseCaret(textWithCaret: String): CaretDocument {
    val marker = "<caret>"
    val offset = textWithCaret.indexOf(marker)
    require(offset >= 0) { "scenario text is missing $marker" }

    val text = textWithCaret.replace(marker, "")
    val before = textWithCaret.substring(0, offset)
    val line = before.count { it == '\n' } + 1
    val character = before.substringAfterLast('\n').length
    return CaretDocument(text, line, character)
}

private fun percentile(sorted: List<Double>, percentile: Double): Double {
    if (sorted.isEmpty()) return 0.0
    val index = (ceil(percentile * sorted.size).toInt() - 1).coerceIn(sorted.indices)
    return sorted[index]
}

private fun printSummary(options: BenchmarkOptions, stats: List<ScenarioStats>) {
    println("Completion Latency Sensor")
    println("fixture: ${options.fixtureRoot.absolutePath}")
    println("warmups: ${options.warmups}, iterations: ${options.iterations}")
    if (options.rawOutput != null) {
        println("raw: ${options.rawOutput.absolutePath}")
    }
    println()
    println("| Scenario | File | N | Min ms | Avg ms | P95 ms | Max ms | Sidecar ms | Members | Scope | Auto/import | Keywords | Items |")
    println("|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|")
    stats.forEach {
        println(
            "| ${it.name} | ${it.file} | ${options.iterations} | " +
                "${"%.1f".format(Locale.US, it.minMs)} | " +
                "${"%.1f".format(Locale.US, it.avgMs)} | " +
                "${"%.1f".format(Locale.US, it.p95Ms)} | " +
                "${"%.1f".format(Locale.US, it.maxMs)} | " +
                "${"%.1f".format(Locale.US, it.sidecarAvgMs)} | " +
                "${it.memberCount} | ${it.scopeCount} | ${it.autoImportCount} | " +
                "${it.keywordCount} | ${it.itemCount} |"
        )
    }

    println()
    println("Stage averages (ms)")
    val stages = stats.flatMap { it.stageAveragesMs.keys }.distinct()
    println("| Scenario | ${stages.joinToString(" | ")} |")
    println("|---|${stages.joinToString("|") { "---:" }}|")
    stats.forEach { scenario ->
        println(
            "| ${scenario.name} | " +
                stages.joinToString(" | ") { stage ->
                    "%.1f".format(Locale.US, scenario.stageAveragesMs[stage] ?: 0.0)
                } +
                " |"
        )
    }
}

private fun loadProjectModel(fixtureRoot: File): ProjectModel {
    val modelFile = fixtureRoot.resolve(".kotlin-analyzer/project-model.json")
    require(modelFile.exists()) {
        "Missing ${modelFile.absolutePath}. Run the Spring fixture tests or generate the project model first."
    }

    val json = JsonParser.parseString(modelFile.readText()).asJsonObject
    val sourceRoots = json.stringArray("source_roots").filter { File(it).exists() }
    require(sourceRoots.isNotEmpty()) { "No valid source roots in ${modelFile.absolutePath}" }

    return ProjectModel(
        sourceRoots = sourceRoots,
        classpath = json.stringArray("classpath").filter { File(it).exists() },
        compilerFlags = json.stringArray("compiler_flags"),
        jdkHome = json.get("jdk_home")?.takeUnless { it.isJsonNull }?.asString ?: "",
    )
}

private fun JsonObject.stringArray(name: String): List<String> =
    getAsJsonArray(name)?.map { it.asString } ?: emptyList()

private fun JsonObject.intMap(): Map<String, Int> =
    entrySet().associate { it.key to it.value.asInt }

private fun JsonObject.doubleMap(): Map<String, Double> =
    entrySet().associate { it.key to it.value.asDouble }

private fun List<Double>.averageOrZero(): Double =
    if (isEmpty()) 0.0 else average()

private fun findFixtureRoot(): File {
    var dir = File(System.getProperty("user.dir")).absoluteFile
    while (dir.parentFile != null) {
        val fixture = File(dir, "tests/fixtures/gradle-spring-web")
        if (fixture.exists()) return fixture
        dir = dir.parentFile
    }
    error("Could not find tests/fixtures/gradle-spring-web from ${System.getProperty("user.dir")}")
}
