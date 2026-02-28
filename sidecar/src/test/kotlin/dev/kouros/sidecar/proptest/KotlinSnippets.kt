package dev.kouros.sidecar.proptest

import net.jqwik.api.Arbitraries
import net.jqwik.api.Arbitrary

/**
 * A small, self-contained Kotlin program suitable for feeding into the
 * compiler bridge during property-based tests.
 *
 * Every snippet produced by [snippetArbitrary] must compile without errors
 * under the Kotlin compiler with no external imports (only kotlin.* / primitives).
 *
 * @property name A human-readable label describing the template that produced this snippet.
 * @property source The complete Kotlin source text.
 */
data class KotlinSnippet(val name: String, val source: String)

// ---------------------------------------------------------------------------
// Type-safe fill sets
// ---------------------------------------------------------------------------

/**
 * A fill set that pairs a Kotlin type name with literal expressions that are
 * valid values of that type. Every combination of [typeName], [returnExpr],
 * and [defaultExpr] is guaranteed to produce compilable code.
 */
private data class TypeFill(
    val typeName: String,
    val returnExpr: (String) -> String,
    val defaultExpr: String,
)

private val TYPE_FILLS = listOf(
    TypeFill("Int", { p -> "$p + 1" }, "0"),
    TypeFill("String", { _ -> "\"hello\"" }, "\"\""),
    TypeFill("Boolean", { _ -> "true" }, "false"),
    TypeFill("Double", { _ -> "3.14" }, "0.0"),
)

private val UNIT_FILL = TypeFill("Unit", { _ -> "Unit" }, "Unit")

private val FUNCTION_NAMES = listOf(
    "compute", "process", "evaluate", "transform", "resolve",
    "calculate", "derive", "produce", "build", "generate",
)

private val CLASS_NAMES = listOf(
    "Item", "Entry", "Record", "Detail", "Element",
    "Wrapper", "Holder", "Container", "Payload", "Descriptor",
)

private val PROPERTY_NAMES = listOf(
    "value", "count", "label", "score", "flag",
    "amount", "name", "size", "level", "index",
)

private val VAR_NAMES = listOf("x", "y", "z", "a", "b", "n", "m", "p", "q", "k")

// ---------------------------------------------------------------------------
// Template generators
// ---------------------------------------------------------------------------

/**
 * Simple function: `fun <name>(<param>: <T>): <T> { return <expr> }`
 */
private fun simpleFunctionArbitrary(): Arbitrary<KotlinSnippet> {
    val types = Arbitraries.of(TYPE_FILLS)
    val names = Arbitraries.of(FUNCTION_NAMES)
    val params = Arbitraries.of(VAR_NAMES)

    return Arbitraries.oneOf(
        // Non-Unit functions with a parameter
        types.flatMap { fill ->
            names.flatMap { fname ->
                params.map { param ->
                    KotlinSnippet(
                        name = "simple_function_${fill.typeName}",
                        source = """
                            |fun $fname($param: ${fill.typeName}): ${fill.typeName} {
                            |    return ${fill.returnExpr(param)}
                            |}
                        """.trimMargin(),
                    )
                }
            }
        },
        // Unit functions
        names.flatMap { fname ->
            params.map { param ->
                KotlinSnippet(
                    name = "simple_function_Unit",
                    source = """
                        |fun $fname($param: Int) {
                        |    println($param)
                        |}
                    """.trimMargin(),
                )
            }
        },
    )
}

/**
 * Data class or regular class with 1-2 properties.
 *
 * Examples:
 * - `data class Item(val name: String, val count: Int)`
 * - `class Holder(val value: Double)`
 */
private fun classWithPropertyArbitrary(): Arbitrary<KotlinSnippet> {
    val classKinds = Arbitraries.of("data class", "class")
    val classNames = Arbitraries.of(CLASS_NAMES)
    val propNames1 = Arbitraries.of(PROPERTY_NAMES)
    val propNames2 = Arbitraries.of(PROPERTY_NAMES)
    val types1 = Arbitraries.of(TYPE_FILLS)
    val types2 = Arbitraries.of(TYPE_FILLS)
    val propCount = Arbitraries.of(1, 2)

    return classKinds.flatMap { kind ->
        classNames.flatMap { cname ->
            propNames1.flatMap { p1 ->
                types1.flatMap { t1 ->
                    propCount.flatMap { count ->
                        if (count == 1) {
                            Arbitraries.just(
                                KotlinSnippet(
                                    name = "class_with_property_1",
                                    source = "$kind $cname(val $p1: ${t1.typeName})",
                                )
                            )
                        } else {
                            propNames2
                                .filter { it != p1 }
                                .flatMap { p2 ->
                                    types2.map { t2 ->
                                        KotlinSnippet(
                                            name = "class_with_property_2",
                                            source = "$kind $cname(val $p1: ${t1.typeName}, val $p2: ${t2.typeName})",
                                        )
                                    }
                                }
                        }
                    }
                }
            }
        }
    }
}

/**
 * A class with a property and a method that uses the property.
 *
 * Example: `class Counter(var count: Int) { fun increment(): Int { count++; return count } }`
 */
/**
 * Classes with methods that use only their own parameters (not class properties).
 * The Analysis API's virtual file handling doesn't resolve `this`-scoped member
 * references, so methods must be self-contained.
 */
private fun classWithMethodArbitrary(): Arbitrary<KotlinSnippet> {
    val classNames = Arbitraries.of(CLASS_NAMES)
    val types = Arbitraries.of(TYPE_FILLS)
    val params = Arbitraries.of(VAR_NAMES)

    return classNames.flatMap { cname ->
        types.flatMap { fill ->
            params.map { param ->
                KotlinSnippet(
                    name = "class_with_method_${fill.typeName}",
                    source = "class $cname {\n" +
                        "    fun compute($param: ${fill.typeName}): ${fill.typeName} {\n" +
                        "        return ${fill.returnExpr(param)}\n" +
                        "    }\n}",
                )
            }
        }
    }
}

/**
 * Functions with nullable parameter types or return types.
 *
 * Example: `fun findName(id: Int): String? { return if (id > 0) "found" else null }`
 */
private fun nullableFunctionArbitrary(): Arbitrary<KotlinSnippet> {
    data class NullableTemplate(
        val paramType: String,
        val returnType: String,
        val body: String,
    )

    val templates = listOf(
        NullableTemplate(
            paramType = "id: Int",
            returnType = "String?",
            body = """return if (id > 0) "found" else null""",
        ),
        NullableTemplate(
            paramType = "text: String?",
            returnType = "Int?",
            body = "return text?.length",
        ),
        NullableTemplate(
            paramType = "flag: Boolean",
            returnType = "Double?",
            body = "return if (flag) 1.0 else null",
        ),
        NullableTemplate(
            paramType = "value: Int?",
            returnType = "Int",
            body = "return value ?: 0",
        ),
        NullableTemplate(
            paramType = "name: String?",
            returnType = "String",
            body = """return name ?: "unknown"""",
        ),
        NullableTemplate(
            paramType = "items: List<Int>?",
            returnType = "Int?",
            body = "return items?.size",
        ),
    )

    val names = Arbitraries.of(FUNCTION_NAMES)
    val params = Arbitraries.of(VAR_NAMES)
    val tmpls = Arbitraries.of(templates)

    return names.flatMap { fname ->
        tmpls.map { t ->
            KotlinSnippet(
                name = "nullable_function",
                source = """
                    |fun $fname(${t.paramType}): ${t.returnType} {
                    |    ${t.body}
                    |}
                """.trimMargin(),
            )
        }
    }
}

/**
 * Simple generic functions.
 *
 * Example: `fun <T> identity(x: T): T { return x }`
 */
private fun genericFunctionArbitrary(): Arbitrary<KotlinSnippet> {
    data class GenericTemplate(
        val typeParams: String,
        val params: String,
        val returnType: String,
        val body: String,
    )

    val templates = listOf(
        GenericTemplate(
            typeParams = "<T>",
            params = "x: T",
            returnType = "T",
            body = "return x",
        ),
        GenericTemplate(
            typeParams = "<T>",
            params = "items: List<T>",
            returnType = "T?",
            body = "return items.firstOrNull()",
        ),
        GenericTemplate(
            typeParams = "<T>",
            params = "a: T, b: T",
            returnType = "List<T>",
            body = "return listOf(a, b)",
        ),
        GenericTemplate(
            typeParams = "<T, R>",
            params = "value: T, transform: (T) -> R",
            returnType = "R",
            body = "return transform(value)",
        ),
        GenericTemplate(
            typeParams = "<T : Comparable<T>>",
            params = "a: T, b: T",
            returnType = "T",
            body = "return if (a > b) a else b",
        ),
        GenericTemplate(
            typeParams = "<T>",
            params = "value: T",
            returnType = "String",
            body = "return value.toString()",
        ),
    )

    val names = Arbitraries.of(FUNCTION_NAMES)
    val tmpls = Arbitraries.of(templates)

    return names.flatMap { fname ->
        tmpls.map { t ->
            KotlinSnippet(
                name = "generic_function",
                source = """
                    |fun ${t.typeParams} $fname(${t.params}): ${t.returnType} {
                    |    ${t.body}
                    |}
                """.trimMargin(),
            )
        }
    }
}

/**
 * Top-level `val` and `var` declarations with initializers.
 *
 * Example: `val greeting: String = "hello"`
 */
private fun topLevelPropertyArbitrary(): Arbitrary<KotlinSnippet> {
    val mutabilities = Arbitraries.of("val", "var")
    val names = Arbitraries.of(PROPERTY_NAMES)
    val types = Arbitraries.of(TYPE_FILLS)

    return mutabilities.flatMap { mut ->
        names.flatMap { pname ->
            types.map { fill ->
                KotlinSnippet(
                    name = "top_level_property_${fill.typeName}",
                    source = "$mut $pname: ${fill.typeName} = ${fill.defaultExpr}",
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Public API
// ---------------------------------------------------------------------------

/**
 * Returns a jqwik [Arbitrary] that produces small, self-contained Kotlin
 * snippets drawn uniformly from several template categories.
 *
 * Every generated [KotlinSnippet] is syntactically and semantically valid
 * Kotlin source code that compiles without errors and requires no imports
 * beyond `kotlin.*` / primitives.
 *
 * Template categories:
 * - Simple functions with typed parameters and return values
 * - Data classes and regular classes with properties
 * - Classes with methods that reference their own properties
 * - Functions using nullable types and safe-call operators
 * - Generic functions with type parameters
 * - Top-level `val` / `var` property declarations
 */
fun snippetArbitrary(): Arbitrary<KotlinSnippet> = Arbitraries.oneOf(
    simpleFunctionArbitrary(),
    classWithPropertyArbitrary(),
    classWithMethodArbitrary(),
    nullableFunctionArbitrary(),
    genericFunctionArbitrary(),
    topLevelPropertyArbitrary(),
)
