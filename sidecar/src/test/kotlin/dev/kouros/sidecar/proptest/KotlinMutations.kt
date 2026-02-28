package dev.kouros.sidecar.proptest

import net.jqwik.api.Arbitraries
import net.jqwik.api.Arbitrary

/**
 * A single mutation operator that transforms valid Kotlin source into a broken variant.
 *
 * @property name Human-readable label used in test reports and shrinking output.
 * @property apply Transformation function: takes valid source, returns mutated source.
 *     If the mutation cannot be applied (e.g., the required pattern is absent),
 *     the function returns the original source unchanged.
 */
data class Mutation(
    val name: String,
    val apply: (String) -> String,
)

/**
 * Returns a jqwik [Arbitrary] that produces random [Mutation] operators.
 *
 * Each mutation targets a different class of error (syntax errors, unresolved references,
 * duplicate declarations, missing returns) so that property-based tests exercise a wide
 * range of diagnostic scenarios.
 */
fun mutationArbitrary(): Arbitrary<Mutation> =
    Arbitraries.of(
        corruptTypeName(),
        deleteClosingBrace(),
        deleteOpeningBrace(),
        removeReturnStatement(),
        duplicateParameter(),
        breakStringLiteral(),
        changeFunToVal(),
    )

// ---------------------------------------------------------------------------
// Mutation operators
// ---------------------------------------------------------------------------

private val COMMON_TYPES = listOf("Int", "String", "Boolean", "Double", "Long", "Float", "Unit")

/**
 * Finds a well-known type name and appends "xxx" to corrupt it.
 * Produces an unresolved-reference diagnostic.
 */
fun corruptTypeName(): Mutation = Mutation("corruptTypeName") { source ->
    val target = COMMON_TYPES.firstOrNull { source.contains(it) }
    if (target != null) {
        source.replaceFirst(target, "${target}xxx")
    } else {
        source
    }
}

/**
 * Removes the last `}` in the source.
 * Always produces a syntax error (unexpected EOF / unmatched brace).
 */
fun deleteClosingBrace(): Mutation = Mutation("deleteClosingBrace") { source ->
    val index = source.lastIndexOf('}')
    if (index >= 0) {
        source.removeRange(index, index + 1)
    } else {
        source
    }
}

/**
 * Removes the first `{` in the source.
 * Always produces a syntax error (unexpected token / missing brace).
 */
fun deleteOpeningBrace(): Mutation = Mutation("deleteOpeningBrace") { source ->
    val index = source.indexOf('{')
    if (index >= 0) {
        source.removeRange(index, index + 1)
    } else {
        source
    }
}

/**
 * Replaces the first `return <expr>` with a comment.
 * If the enclosing function has a non-Unit return type, the compiler
 * reports a "no return statement" error.
 */
fun removeReturnStatement(): Mutation = Mutation("removeReturnStatement") { source ->
    val pattern = Regex("""return\s+\S+""")
    pattern.replaceFirst(source, "// removed")
}

/**
 * Finds the first function parameter and duplicates it, producing a
 * "conflicting declarations" / duplicate-parameter-name error.
 *
 * Matches patterns like `(x: Int)` or `(x: Int,` and inserts a copy
 * of the parameter after the original.
 */
fun duplicateParameter(): Mutation = Mutation("duplicateParameter") { source ->
    // Match a parameter at the start of a parameter list: (name: Type
    val pattern = Regex("""\((\w+\s*:\s*\w+)""")
    val match = pattern.find(source)
    if (match != null) {
        val param = match.groupValues[1]
        val startOfParam = match.range.first // the '('
        source.replaceRange(
            match.range,
            "(${param}, ${param}"
        )
    } else {
        source
    }
}

/**
 * Finds the first string literal and removes its closing quote,
 * producing a syntax error (unterminated string literal).
 */
fun breakStringLiteral(): Mutation = Mutation("breakStringLiteral") { source ->
    // Match a simple double-quoted string literal (non-greedy, no escapes for simplicity)
    val pattern = Regex(""""[^"]*"""")
    val match = pattern.find(source)
    if (match != null) {
        val literal = match.value
        // Remove the closing quote
        val broken = literal.dropLast(1)
        source.replaceFirst(literal, broken)
    } else {
        source
    }
}

/**
 * Replaces the keyword `fun ` with `val `, producing a syntax error
 * because the parameter list and body that follow are not valid for a
 * property declaration.
 */
fun changeFunToVal(): Mutation = Mutation("changeFunToVal") { source ->
    source.replaceFirst("fun ", "val ")
}
