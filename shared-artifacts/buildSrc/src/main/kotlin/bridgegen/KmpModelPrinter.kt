package bridgegen

/**
 * Renders a [KmpModule] as a human-readable indented tree for inspection and validation.
 *
 * Output is plain text — no dependencies beyond the model types. Call [print] and write
 * the result wherever you like (file, stdout, test assertion).
 *
 * Example output:
 * ```
 * KmpModule: shared  [com.example.shared]
 * Declarations: 3
 * ════════════════════════════════════════
 * ENUM  FixtureStatus
 *   entries: ACTIVE · INACTIVE · PENDING
 * ════════════════════════════════════════
 * SEALED CLASS  FixtureResult
 *   variants:
 *     ▸ DataVariant    Success
 *         · user : FixtureUser [ClassRef]
 *         · code : Int
 *     ▸ ObjectVariant  Empty
 *     ▸ ClassVariant   Failure
 *         · message   : String
 *         · retryable : Boolean
 *     ▸ ClassVariant(abstract)  Partial
 *         · hint : String
 * ════════════════════════════════════════
 * CLASS  FixtureAsyncApi
 *   functions:
 *     [SYNC]    greet(name: String) → String
 *     [SUSPEND] fetchUser(id: String) → FixtureUser [ClassRef]
 *     [FLOW]    observeResult() → FixtureResult [ClassRef]
 * ```
 */
object KmpModelPrinter {

    private const val DIVIDER      = "════════════════════════════════════════"
    private const val THIN_DIVIDER = "────────────────────────────────────────"

    /** Renders the entire [module] as a formatted string, grouped by source file. */
    fun print(module: KmpModule): String = buildString {
        appendLine("KmpModule: ${module.moduleName}  [${module.packageName}]")
        appendLine("Files: ${module.files.size}  Declarations: ${module.declarations.size}")

        for (file in module.files) {
            val count = file.declarations.size
            appendLine(DIVIDER)
            appendLine("FILE  ${file.fileName}.kt  ($count declaration${if (count == 1) "" else "s"})")
            appendLine(DIVIDER)
            for ((idx, decl) in file.declarations.withIndex()) {
                if (idx > 0) appendLine(THIN_DIVIDER)
                appendDeclaration(decl, indent = "")
            }
        }

        appendLine(DIVIDER)
    }

    // ── Declarations ──────────────────────────────────────────────────────────

    private fun StringBuilder.appendDeclaration(decl: KmpDeclaration, indent: String) {
        when (decl) {
            is KmpDeclaration.KmpEnum         -> appendEnum(decl, indent)
            is KmpDeclaration.KmpDataClass    -> appendDataClass(decl, indent)
            is KmpDeclaration.KmpSealedClass  -> appendSealedClass(decl, indent)
            is KmpDeclaration.KmpInterface    -> appendInterface(decl, indent)
            is KmpDeclaration.KmpObject       -> appendObject(decl, indent)
            is KmpDeclaration.KmpClass        -> appendClass(decl, indent)
            is KmpDeclaration.KmpFileScope    -> appendFileScope(decl, indent)
        }
    }

    private fun StringBuilder.appendEnum(decl: KmpDeclaration.KmpEnum, indent: String) {
        appendLine("${indent}ENUM  ${decl.name}")
        appendLine("${indent}  entries: ${decl.entries.joinToString(" · ")}")
        if (decl.docComment != null) appendLine("${indent}  doc: ${decl.docComment.trimmed()}")
    }

    private fun StringBuilder.appendDataClass(decl: KmpDeclaration.KmpDataClass, indent: String) {
        appendLine("${indent}DATA CLASS  ${decl.name}")
        if (decl.docComment != null) appendLine("${indent}  doc: ${decl.docComment.trimmed()}")
        appendLine("${indent}  fields:")
        for (field in decl.fields) appendField(field, "$indent    ")
        appendFunctions(decl.functions, indent)
    }

    private fun StringBuilder.appendSealedClass(decl: KmpDeclaration.KmpSealedClass, indent: String) {
        appendLine("${indent}SEALED CLASS  ${decl.name}")
        if (decl.docComment != null) appendLine("${indent}  doc: ${decl.docComment.trimmed()}")
        appendLine("${indent}  variants:")
        for (variant in decl.variants) appendVariant(variant, "$indent    ")
        appendFunctions(decl.functions, indent)
    }

    private fun StringBuilder.appendInterface(decl: KmpDeclaration.KmpInterface, indent: String) {
        appendLine("${indent}INTERFACE  ${decl.name}")
        if (decl.docComment != null) appendLine("${indent}  doc: ${decl.docComment.trimmed()}")
        appendFunctions(decl.functions, indent)
    }

    private fun StringBuilder.appendObject(decl: KmpDeclaration.KmpObject, indent: String) {
        appendLine("${indent}OBJECT  ${decl.name}")
        if (decl.docComment != null) appendLine("${indent}  doc: ${decl.docComment.trimmed()}")
        appendFunctions(decl.functions, indent)
    }

    private fun StringBuilder.appendClass(decl: KmpDeclaration.KmpClass, indent: String) {
        val label = if (decl.isAbstract) "ABSTRACT CLASS" else "CLASS"
        appendLine("${indent}$label  ${decl.name}")
        if (decl.docComment != null) appendLine("${indent}  doc: ${decl.docComment.trimmed()}")
        appendFunctions(decl.functions, indent)
    }

    private fun StringBuilder.appendFileScope(decl: KmpDeclaration.KmpFileScope, indent: String) {
        appendLine("${indent}FILE SCOPE  ${decl.fileName}")
        appendFunctions(decl.functions, indent)
    }

    // ── Variants ──────────────────────────────────────────────────────────────

    private fun StringBuilder.appendVariant(variant: KmpVariant, indent: String) {
        when (variant) {
            is KmpVariant.DataVariant -> {
                appendLine("${indent}▸ DataVariant    ${variant.name}")
                for (field in variant.fields) appendField(field, "$indent    ")
            }
            is KmpVariant.ObjectVariant -> {
                appendLine("${indent}▸ ObjectVariant  ${variant.name}")
            }
            is KmpVariant.ClassVariant -> {
                val label = if (variant.isAbstract) "ClassVariant(abstract)" else "ClassVariant  "
                appendLine("${indent}▸ $label  ${variant.name}")
                for (field in variant.fields) appendField(field, "$indent    ")
            }
        }
    }

    // ── Fields ────────────────────────────────────────────────────────────────

    private fun StringBuilder.appendField(field: KmpField, indent: String) {
        appendLine("${indent}· ${field.name.padEnd(16)}: ${field.type.render()}")
    }

    // ── Functions ─────────────────────────────────────────────────────────────

    private fun StringBuilder.appendFunctions(functions: List<KmpFunction>, indent: String) {
        if (functions.isEmpty()) {
            appendLine("${indent}  functions: (none)")
            return
        }
        appendLine("${indent}  functions:")
        for (fn in functions) appendFunction(fn, "$indent    ")
    }

    private fun StringBuilder.appendFunction(fn: KmpFunction, indent: String) {
        val kind = when (fn.kind) {
            FunctionKind.SYNC    -> "[SYNC]   "
            FunctionKind.SUSPEND -> "[SUSPEND]"
            FunctionKind.FLOW    -> "[FLOW]   "
        }
        val params = fn.params.joinToString(", ") { "${it.name}: ${it.type.render()}" }
        appendLine("${indent}$kind  ${fn.name}($params) → ${fn.returnType.render()}")
        if (fn.docComment != null) appendLine("${indent}          // ${fn.docComment.trimmed()}")
    }

    // ── Type rendering ────────────────────────────────────────────────────────

    private fun KmpTypeRef.render(): String = when (this) {
        is KmpTypeRef.Primitive      -> "${kind.name}${nullable.q()}"
        is KmpTypeRef.UnitType       -> "Unit${nullable.q()}"
        is KmpTypeRef.ClassRef       -> {
            val args = if (typeArgs.isEmpty()) "" else "<${typeArgs.joinToString(", ") { it.render() }}>"
            "${simpleName}$args${nullable.q()}"
        }
        is KmpTypeRef.CollectionType -> {
            val args = typeArgs.joinToString(", ") { it.render() }
            "${kind.name}<$args>${nullable.q()}"
        }
        is KmpTypeRef.FlowType       -> "Flow<${typeArg.render()}>${nullable.q()}"
        is KmpTypeRef.TypeParam      -> "${name}${nullable.q()}"
    }

    private fun KmpTypeArg.render(): String = when (this) {
        is KmpTypeArg.Invariant      -> type.render()
        is KmpTypeArg.Covariant      -> "out ${type.render()}"
        is KmpTypeArg.Contravariant  -> "in ${type.render()}"
        is KmpTypeArg.Star           -> "*"
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Returns `"?"` when true, `""` when false — for nullable suffix rendering. */
    private fun Boolean.q(): String = if (this) "?" else ""

    /** Trims a doc comment to a single line for inline display. */
    private fun String.trimmed(): String = lines().first().trim()
        .removePrefix("/**").removePrefix("/*").removePrefix("//").removePrefix("*").trim()
}
