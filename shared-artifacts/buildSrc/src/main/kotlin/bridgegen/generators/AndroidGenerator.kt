package bridgegen.generators

import bridgegen.*

object AndroidGenerator {

    // ── File-level generation ─────────────────────────────────────────────────

    fun generateFile(sourceFile: KmpSourceFile, module: KmpModule, kmpPackageName: String, androidPackage: String, onSkip: (String) -> Unit = {}): String {
        val enumNames      = module.declarations.filterIsInstance<KmpDeclaration.KmpEnum>().map { it.name }.toSet()
        val dataClassNames = module.declarations.filterIsInstance<KmpDeclaration.KmpDataClass>().map { it.name }.toSet()
        val sealedNames    = module.declarations.filterIsInstance<KmpDeclaration.KmpSealedClass>().map { it.name }.toSet()

        val records = sourceFile.declarations.filterIsInstance<KmpDeclaration.KmpDataClass>()
            .filter { dc ->
                if (dc.fields.isEmpty()) {
                    onSkip("DATA CLASS SKIPPED: ${dc.name} — no fields, no Record generated.")
                    false
                } else true
            }
        val sealeds = sourceFile.declarations.filterIsInstance<KmpDeclaration.KmpSealedClass>()
        val modules = sourceFile.declarations.filter { decl ->
            when {
                decl is KmpDeclaration.KmpInterface -> {
                    onSkip("CLASS SKIPPED: ${decl.name} — interfaces are not bridged.")
                    false
                }
                decl is KmpDeclaration.KmpClass && decl.isAbstract -> {
                    onSkip("CLASS SKIPPED: ${decl.name} — abstract classes are not bridged.")
                    false
                }
                decl is KmpDeclaration.KmpClass && decl.functions.isEmpty() -> {
                    onSkip("CLASS SKIPPED: ${decl.name} — no functions to bridge.")
                    false
                }
                decl is KmpDeclaration.KmpObject && decl.functions.isEmpty() -> {
                    onSkip("OBJECT SKIPPED: ${decl.name} — no functions to bridge.")
                    false
                }
                decl is KmpDeclaration.KmpClass  -> true
                decl is KmpDeclaration.KmpObject -> true
                else                             -> false
            }
        }

        for (dc in records) {
            for (fn in dc.functions) {
                onSkip("FUNCTION SKIPPED: ${dc.name}.${fn.name}() — member functions on data classes are not bridged.")
            }
        }
        for (sealed in sealeds) {
            for (fn in sealed.functions) {
                onSkip("FUNCTION SKIPPED: ${sealed.name}.${fn.name}() — member functions on sealed classes are not bridged.")
            }
        }

        if (records.isEmpty() && sealeds.isEmpty() && modules.isEmpty()) return ""

        val allImports   = mutableSetOf<String>()
        val recordBodies = mutableListOf<String>()
        val sealedBodies = mutableListOf<String>()
        val moduleBodies = mutableListOf<String>()

        for (record in records) {
            val (imports, body) = generateRecord(record, module, kmpPackageName)
            allImports.addAll(imports)
            recordBodies.add(body)
        }

        for (sealed in sealeds) {
            val (imports, body) = generateSealedCodec(sealed, kmpPackageName, enumNames, dataClassNames, sealedNames)
            allImports.addAll(imports)
            sealedBodies.add(body)
        }

        for (decl in modules) {
            val (imports, body) = buildModuleBody(decl, module, kmpPackageName, enumNames, dataClassNames, sealedNames, onSkip)
            allImports.addAll(imports)
            moduleBodies.add(body)
        }

        val sb = StringBuilder()
        sb.appendLine(HEADER)
        sb.appendLine("package $androidPackage")
        sb.appendLine()
        allImports.sorted().forEach { sb.appendLine("import $it") }

        if (recordBodies.isNotEmpty()) {
            sb.appendLine()
            recordBodies.forEach { sb.appendLine(it) }
        }
        if (sealedBodies.isNotEmpty()) {
            sealedBodies.forEach { sb.appendLine(it) }
        }
        if (moduleBodies.isNotEmpty()) {
            moduleBodies.forEach { sb.appendLine(it) }
        }

        return sb.toString().trimEnd() + "\n"
    }

    // ── Record generation (data classes) ─────────────────────────────────────

    fun generateRecord(
        decl: KmpDeclaration.KmpDataClass,
        module: KmpModule,
        kmpPackageName: String,
    ): Pair<Set<String>, String> {
        val enumNames      = module.declarations.filterIsInstance<KmpDeclaration.KmpEnum>().map { it.name }.toSet()
        val dataClassNames = module.declarations.filterIsInstance<KmpDeclaration.KmpDataClass>().map { it.name }.toSet()
        val sealedNames    = module.declarations.filterIsInstance<KmpDeclaration.KmpSealedClass>().map { it.name }.toSet()

        val imports = mutableSetOf(
            "expo.modules.kotlin.records.Field",
            "expo.modules.kotlin.records.Record",
            "$kmpPackageName.${decl.name}",
        )
        for (field in decl.fields) {
            collectClassRefImports(field.type, enumNames, dataClassNames, sealedNames, kmpPackageName, imports)
        }

        val sb = StringBuilder()

        // Record class — JS → Kotlin via Expo
        sb.appendLine("class ${decl.name}Record : Record {")
        for (field in decl.fields) {
            val fieldType = field.type.toRecordFieldType(enumNames, dataClassNames)
            val default   = field.type.toRecordFieldDefault(enumNames, dataClassNames)
            sb.appendLine("    @Field var ${field.name}: $fieldType = $default")
        }
        sb.appendLine("}")
        sb.appendLine()

        // toKmp() — Record → Kotlin KMP type
        sb.appendLine("fun ${decl.name}Record.toKmp() = ${decl.name}(")
        for (field in decl.fields) {
            val conv = field.type.toKmpFieldConversion(field.name, enumNames, dataClassNames)
            sb.appendLine("    ${field.name} = $conv,")
        }
        sb.appendLine(")")
        sb.appendLine()

        // toRecord() — Kotlin KMP type → Record (for return values)
        sb.appendLine("fun ${decl.name}.toRecord(): ${decl.name}Record = ${decl.name}Record().also {")
        for (field in decl.fields) {
            val assign = field.type.toRecordAssignment(field.name, enumNames, dataClassNames, sealedNames)
            sb.appendLine("    it.${field.name} = $assign")
        }
        sb.append("}")

        return imports to sb.toString()
    }

    // ── Sealed codec generation ───────────────────────────────────────────────

    private fun generateSealedCodec(
        decl: KmpDeclaration.KmpSealedClass,
        kmpPackageName: String,
        enumNames: Set<String>,
        dataClassNames: Set<String>,
        sealedNames: Set<String>,
    ): Pair<Set<String>, String> {
        val imports = mutableSetOf(
            "expo.modules.kotlin.records.Field",
            "expo.modules.kotlin.records.Record",
            "$kmpPackageName.${decl.name}",
        )
        for (variant in decl.variants) {
            for (field in variant.variantFields()) {
                collectClassRefImports(field.type, enumNames, dataClassNames, sealedNames, kmpPackageName, imports)
            }
        }

        // Collect all variant fields, deduplicated by name
        val allFields = mutableListOf<KmpField>()
        val seenNames = mutableSetOf<String>()
        for (variant in decl.variants) {
            for (field in variant.variantFields()) {
                if (seenNames.add(field.name)) allFields.add(field)
            }
        }

        val sb = StringBuilder()
        val n = decl.name

        // Flat Record — all variant fields as nullable
        sb.appendLine("class ${n}Record : Record {")
        sb.appendLine("    @Field var type: String = \"\"")
        for (field in allFields) {
            val fieldType = field.type.toSealedRecordFieldType(enumNames, dataClassNames, sealedNames)
            sb.appendLine("    @Field var ${field.name}: $fieldType = null")
        }
        sb.appendLine("}")
        sb.appendLine()

        // toRecord() — Kotlin sealed → flat Record
        sb.appendLine("fun $n.toRecord(): ${n}Record = ${n}Record().also { r ->")
        sb.appendLine("    r.type = when (this) {")
        for (variant in decl.variants) {
            sb.appendLine("        is $n.${variant.variantName()} -> \"${variant.variantName()}\"")
        }
        sb.appendLine("    }")
        sb.appendLine("    when (this) {")
        for (variant in decl.variants) {
            val vName  = variant.variantName()
            val fields = variant.variantFields()
            if (fields.isEmpty()) {
                sb.appendLine("        is $n.$vName -> Unit")
            } else {
                sb.appendLine("        is $n.$vName -> {")
                for (field in fields) {
                    val assign = field.type.toRecordAssignment(field.name, enumNames, dataClassNames, sealedNames)
                    sb.appendLine("            r.${field.name} = $assign")
                }
                sb.appendLine("        }")
            }
        }
        sb.appendLine("    }")
        sb.appendLine("}")
        sb.appendLine()

        // toKmp() — flat Record → Kotlin sealed
        sb.appendLine("fun ${n}Record.toKmp(): $n = when (type) {")
        for (variant in decl.variants) {
            val vName     = variant.variantName()
            val fields    = variant.variantFields()
            val isAbstract = (variant as? KmpVariant.ClassVariant)?.isAbstract ?: false
            when {
                isAbstract -> sb.appendLine("    \"$vName\" -> error(\"$n.$vName is abstract — cannot deserialize\")")
                variant is KmpVariant.ObjectVariant -> sb.appendLine("    \"$vName\" -> $n.$vName")
                fields.isEmpty() -> sb.appendLine("    \"$vName\" -> $n.$vName()")
                else -> {
                    sb.appendLine("    \"$vName\" -> $n.$vName(")
                    for (field in fields) {
                        val extract = field.type.fromSealedRecordField(field.name, enumNames, dataClassNames, sealedNames)
                        sb.appendLine("        ${field.name} = $extract,")
                    }
                    sb.appendLine("    )")
                }
            }
        }
        sb.appendLine("    else -> error(\"Unknown $n type: \$type\")")
        sb.append("}")

        return imports to sb.toString()
    }

    // ── Module body generation ────────────────────────────────────────────────

    private fun buildModuleBody(
        decl: KmpDeclaration,
        module: KmpModule,
        kmpPackageName: String,
        enumNames: Set<String>,
        dataClassNames: Set<String>,
        sealedNames: Set<String>,
        onSkip: (String) -> Unit = {},
    ): Pair<Set<String>, String> {
        val name      = decl.declName()
        val functions = decl.declFunctions()
        val isObject  = decl is KmpDeclaration.KmpObject

        val flows      = functions.filter { it.kind == FunctionKind.FLOW }
        val hasSuspend = functions.any { it.kind == FunctionKind.SUSPEND }
        val hasFlows   = flows.isNotEmpty()
        val eventNames = flows.map { "on${it.flowBaseName.cap()}Update" }
        val usedEnums  = enumNames.filter { eName -> functions.any { fn -> fn.referencesEnum(eName) } }
        val callTarget = if (isObject) name else name.decap()

        val imports = mutableSetOf<String>()
        imports.add("$kmpPackageName.$name")
        for (eName in usedEnums) imports.add("$kmpPackageName.$eName")
        if (hasSuspend) imports.add("expo.modules.kotlin.Promise")
        imports.add("expo.modules.kotlin.modules.Module")
        imports.add("expo.modules.kotlin.modules.ModuleDefinition")
        if (hasSuspend || hasFlows) {
            imports.add("kotlinx.coroutines.CoroutineScope")
            imports.add("kotlinx.coroutines.Dispatchers")
            imports.add("kotlinx.coroutines.Job")
            imports.add("kotlinx.coroutines.SupervisorJob")
            imports.add("kotlinx.coroutines.cancel")
            imports.add("kotlinx.coroutines.launch")
        }
        if (hasFlows) imports.add("kotlinx.coroutines.flow.collect")

        val typeArgsSuffix = if (decl is KmpDeclaration.KmpClass && decl.typeParameters.isNotEmpty()) {
            "<${decl.typeParameters.joinToString(", ") { "Any" }}>"
        } else ""

        val sb = StringBuilder()
        sb.appendLine("class ${name}Module : Module() {")
        if (!isObject) sb.appendLine("  private val $callTarget = $name$typeArgsSuffix()")
        if (hasSuspend || hasFlows) {
            sb.appendLine("  private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())")
        }
        if (hasFlows) {
            val enumCases = flows.joinToString(", ") { it.flowBaseName.toSnakeUpperCase() }
            sb.appendLine("  private enum class FlowKey { $enumCases }")
            sb.appendLine("  private val flowJobs = mutableMapOf<FlowKey, Job>()")
        }

        sb.appendLine()
        sb.appendLine("  override fun definition() = ModuleDefinition {")
        sb.appendLine("""    Name("$name")""")

        if (eventNames.isNotEmpty()) {
            sb.appendLine()
            sb.appendLine("""    Events(${eventNames.joinToString(", ") { "\"$it\"" }})""")
        }

        if (hasSuspend || hasFlows) {
            sb.appendLine()
            sb.appendLine("    OnDestroy {")
            sb.appendLine("      scope.cancel()")
            sb.appendLine("    }")
        }

        for (fn in functions) {
            sb.appendLine()
            when (fn.kind) {
                FunctionKind.SYNC    -> sb.append(syncFunction(fn, callTarget, enumNames, dataClassNames, sealedNames, onSkip))
                FunctionKind.SUSPEND -> sb.append(suspendFunction(fn, callTarget, enumNames, dataClassNames, sealedNames, onSkip))
                FunctionKind.FLOW    -> sb.append(flowFunctions(fn, callTarget, enumNames, dataClassNames, sealedNames))
            }
        }

        sb.appendLine("  }")
        sb.append("}")

        return imports to sb.toString()
    }

    // ── Function emitters ─────────────────────────────────────────────────────

    private fun syncFunction(
        fn: KmpFunction,
        callTarget: String,
        enumNames: Set<String>,
        dataClassNames: Set<String> = emptySet(),
        sealedNames: Set<String> = emptySet(),
        onSkip: (String) -> Unit = {},
    ): String {
        if (fn.params.size > MAX_EXPO_FUNCTION_PARAMS) {
            val msg = "BRIDGE SKIPPED: ${fn.name}(${fn.params.size} params) — Expo Function DSL supports max $MAX_EXPO_FUNCTION_PARAMS parameters."
            onSkip(msg)
            return "    // $msg\n"
        }
        val sb  = StringBuilder()
        val ret = fn.returnType.toReturnSuffix(enumNames, dataClassNames, sealedNames)
        sb.append(formatComment(fn.docComment))
        if (fn.params.isEmpty()) {
            sb.appendLine("""    Function("${fn.name}") {""")
            sb.appendLine("      $callTarget.${fn.name}()$ret")
        } else {
            val paramList = fn.params.joinToString(", ") { "${it.name}: ${it.type.toBridgeParamType(enumNames)}" }
            val callArgs  = fn.params.joinToString(", ") { it.type.toCallArg(it.name, enumNames) }
            sb.appendLine("""    Function("${fn.name}") { $paramList ->""")
            sb.appendLine("      $callTarget.${fn.name}($callArgs)$ret")
        }
        sb.appendLine("    }")
        return sb.toString()
    }

    private fun suspendFunction(
        fn: KmpFunction,
        callTarget: String,
        enumNames: Set<String>,
        dataClassNames: Set<String> = emptySet(),
        sealedNames: Set<String> = emptySet(),
        onSkip: (String) -> Unit = {},
    ): String {
        if (fn.params.size > MAX_EXPO_FUNCTION_PARAMS) {
            val msg = "BRIDGE SKIPPED: ${fn.name}(${fn.params.size} params) — Expo AsyncFunction DSL supports max $MAX_EXPO_FUNCTION_PARAMS parameters."
            onSkip(msg)
            return "    // $msg\n"
        }
        val sb       = StringBuilder()
        val errorTag = "${fn.name.toSnakeUpperCase()}_ERROR"
        val ret      = fn.returnType.toReturnSuffix(enumNames, dataClassNames, sealedNames)
        val paramList = (fn.params.map { "${it.name}: ${it.type.toBridgeParamType(enumNames)}" }
                + listOf("promise: Promise")).joinToString(", ")
        val callArgs = fn.params.joinToString(", ") { it.type.toCallArg(it.name, enumNames) }

        sb.append(formatComment(fn.docComment))
        sb.appendLine("""    AsyncFunction("${fn.name}") { $paramList ->""")
        sb.appendLine("      scope.launch {")
        sb.appendLine("        try {")
        sb.appendLine("          promise.resolve($callTarget.${fn.name}($callArgs)$ret)")
        sb.appendLine("        } catch (e: Exception) {")
        sb.appendLine("""          promise.reject("$errorTag", e.message, e)""")
        sb.appendLine("        }")
        sb.appendLine("      }")
        sb.appendLine("    }")
        return sb.toString()
    }

    private fun flowFunctions(
        fn: KmpFunction,
        callTarget: String,
        enumNames: Set<String>,
        dataClassNames: Set<String> = emptySet(),
        sealedNames: Set<String> = emptySet(),
    ): String {
        val sb        = StringBuilder()
        val base      = fn.flowBaseName
        val Cap       = base.cap()
        val eventName = "on${Cap}Update"
        val enumKey   = "FlowKey.${base.toSnakeUpperCase()}"
        val retType   = fn.returnType
        val emit = when {
            retType is KmpTypeRef.Primitive && retType.kind == PrimitiveKind.CHAR              -> "value.toString()"
            retType is KmpTypeRef.ClassRef  && retType.simpleName in enumNames                 -> "value.name"
            retType is KmpTypeRef.ClassRef  && retType.simpleName in dataClassNames            -> "value.toRecord()"
            retType is KmpTypeRef.ClassRef  && retType.simpleName in sealedNames               -> "value.toRecord()"
            else -> "value"
        }

        sb.append(formatComment(fn.docComment))
        sb.appendLine("""    Function("start$Cap") {""")
        sb.appendLine("      flowJobs[$enumKey]?.cancel()")
        sb.appendLine("      flowJobs[$enumKey] = scope.launch {")
        sb.appendLine("        $callTarget.${fn.name}().collect { value ->")
        sb.appendLine("""          sendEvent("$eventName", mapOf("value" to $emit))""")
        sb.appendLine("        }")
        sb.appendLine("      }")
        sb.appendLine("    }")
        sb.appendLine()
        sb.appendLine("""    Function("stop$Cap") {""")
        sb.appendLine("      flowJobs[$enumKey]?.cancel()")
        sb.appendLine("      flowJobs.remove($enumKey)")
        sb.appendLine("    }")
        return sb.toString()
    }

    // ── toRecord() assignment helper ──────────────────────────────────────────

    /** Produces the RHS expression for `it.fieldName = <expr>` in a `toRecord()` call. */
    private fun KmpTypeRef.toRecordAssignment(
        fieldName: String,
        enumNames: Set<String>,
        dataClassNames: Set<String>,
        sealedNames: Set<String>,
    ): String = when {
        this is KmpTypeRef.Primitive && kind == PrimitiveKind.LONG ->
            if (nullable) "$fieldName?.toDouble()" else "$fieldName.toDouble()"
        this is KmpTypeRef.Primitive && kind == PrimitiveKind.CHAR ->
            if (nullable) "$fieldName?.toString()" else "$fieldName.toString()"
        this is KmpTypeRef.Primitive -> fieldName
        this is KmpTypeRef.ClassRef && simpleName in enumNames ->
            if (nullable) "$fieldName?.name" else "$fieldName.name"
        this is KmpTypeRef.ClassRef && (simpleName in dataClassNames || simpleName in sealedNames) ->
            if (nullable) "$fieldName?.toRecord()" else "$fieldName.toRecord()"
        this is KmpTypeRef.CollectionType && kind == CollectionKind.SET ->
            if (nullable) "$fieldName?.toList()" else "$fieldName.toList()"
        else -> fieldName
    }

    // ── Sealed Record field type (always nullable) ────────────────────────────

    private fun KmpTypeRef.toSealedRecordFieldType(
        enumNames: Set<String>,
        dataClassNames: Set<String>,
        sealedNames: Set<String>,
    ): String = when {
        this is KmpTypeRef.Primitive -> when (kind) {
            PrimitiveKind.STRING  -> "String?"
            PrimitiveKind.INT     -> "Int?"
            PrimitiveKind.LONG    -> "Double?"
            PrimitiveKind.DOUBLE  -> "Double?"
            PrimitiveKind.FLOAT   -> "Float?"
            PrimitiveKind.BOOLEAN -> "Boolean?"
            PrimitiveKind.BYTE    -> "Byte?"
            PrimitiveKind.SHORT   -> "Short?"
            PrimitiveKind.CHAR    -> "String?"
        }
        this is KmpTypeRef.ClassRef && simpleName in enumNames                                    -> "String?"
        this is KmpTypeRef.ClassRef && (simpleName in dataClassNames || simpleName in sealedNames) -> "${simpleName}Record?"
        this is KmpTypeRef.ClassRef -> "Any?"
        this is KmpTypeRef.CollectionType -> {
            val inner = when (kind) {
                CollectionKind.LIST, CollectionKind.SET -> {
                    val elem = (typeArgs.firstOrNull() as? KmpTypeArg.Invariant)
                        ?.type?.toRecordFieldType(enumNames, dataClassNames) ?: "Any?"
                    "List<${elem.trimEnd('?')}>"
                }
                CollectionKind.MAP -> {
                    val k = (typeArgs.getOrNull(0) as? KmpTypeArg.Invariant)
                        ?.type?.toRecordFieldType(enumNames, dataClassNames) ?: "Any"
                    val v = (typeArgs.getOrNull(1) as? KmpTypeArg.Invariant)
                        ?.type?.toRecordFieldType(enumNames, dataClassNames) ?: "Any?"
                    "Map<${k.trimEnd('?')}, ${v.trimEnd('?')}>"
                }
            }
            "$inner?"
        }
        else -> "Any?"
    }

    // ── Extract from nullable sealed Record field → KMP type ──────────────────

    private fun KmpTypeRef.fromSealedRecordField(
        fieldName: String,
        enumNames: Set<String>,
        dataClassNames: Set<String>,
        sealedNames: Set<String>,
    ): String {
        // Char is bridged as String — handle before the nullable pass-through
        if (this is KmpTypeRef.Primitive && kind == PrimitiveKind.CHAR) {
            return if (nullable) "$fieldName?.firstOrNull()" else "($fieldName ?: \"\").first()"
        }
        // If the original KMP field is nullable, the Record field is also nullable — pass through
        if (isNullable) return fieldName
        return when {
            this is KmpTypeRef.Primitive -> when (kind) {
                PrimitiveKind.STRING  -> "$fieldName ?: \"\""
                PrimitiveKind.INT     -> "$fieldName ?: 0"
                PrimitiveKind.LONG    -> "$fieldName?.toLong() ?: 0L"
                PrimitiveKind.DOUBLE  -> "$fieldName ?: 0.0"
                PrimitiveKind.FLOAT   -> "$fieldName ?: 0f"
                PrimitiveKind.BOOLEAN -> "$fieldName ?: false"
                PrimitiveKind.BYTE    -> "$fieldName ?: 0"
                PrimitiveKind.SHORT   -> "$fieldName ?: 0"
                PrimitiveKind.CHAR    -> "$fieldName ?: ' '" // unreachable — handled above
            }
            this is KmpTypeRef.ClassRef && simpleName in enumNames ->
                "$simpleName.valueOf($fieldName ?: \"\")"
            this is KmpTypeRef.ClassRef && (simpleName in dataClassNames || simpleName in sealedNames) ->
                "($fieldName ?: ${simpleName}Record()).toKmp()"
            this is KmpTypeRef.CollectionType && kind == CollectionKind.LIST ->
                "$fieldName ?: emptyList()"
            this is KmpTypeRef.CollectionType && kind == CollectionKind.SET ->
                "($fieldName ?: emptyList()).toSet()"
            this is KmpTypeRef.CollectionType && kind == CollectionKind.MAP ->
                "$fieldName ?: emptyMap()"
            else -> "$fieldName ?: null"
        }
    }

    // ── Variant helpers ───────────────────────────────────────────────────────

    private fun KmpVariant.variantName(): String = when (this) {
        is KmpVariant.DataVariant   -> name
        is KmpVariant.ClassVariant  -> name
        is KmpVariant.ObjectVariant -> name
    }

    private fun KmpVariant.variantFields(): List<KmpField> = when (this) {
        is KmpVariant.DataVariant   -> fields
        is KmpVariant.ClassVariant  -> fields
        is KmpVariant.ObjectVariant -> emptyList()
    }

    // ── Record type helpers ───────────────────────────────────────────────────

    private val KmpTypeRef.isNullable: Boolean get() = when (this) {
        is KmpTypeRef.Primitive      -> nullable
        is KmpTypeRef.ClassRef       -> nullable
        is KmpTypeRef.CollectionType -> nullable
        is KmpTypeRef.FlowType       -> nullable
        is KmpTypeRef.UnitType       -> nullable
        is KmpTypeRef.TypeParam      -> nullable
    }

    private fun KmpTypeRef.toRecordFieldType(enumNames: Set<String>, dataClassNames: Set<String>): String {
        val q = if (isNullable) "?" else ""
        return when {
            this is KmpTypeRef.Primitive -> when (kind) {
                PrimitiveKind.STRING  -> "String"
                PrimitiveKind.INT     -> "Int"
                PrimitiveKind.LONG    -> "Double"
                PrimitiveKind.DOUBLE  -> "Double"
                PrimitiveKind.FLOAT   -> "Float"
                PrimitiveKind.BOOLEAN -> "Boolean"
                PrimitiveKind.BYTE    -> "Byte"
                PrimitiveKind.SHORT   -> "Short"
                PrimitiveKind.CHAR    -> "String"
            } + q
            this is KmpTypeRef.ClassRef && simpleName in enumNames      -> "String$q"
            this is KmpTypeRef.ClassRef && simpleName in dataClassNames -> "${simpleName}Record$q"
            this is KmpTypeRef.ClassRef                                 -> "Any?"
            this is KmpTypeRef.CollectionType -> {
                val inner = when (kind) {
                    CollectionKind.LIST, CollectionKind.SET -> {
                        val elem = (typeArgs.firstOrNull() as? KmpTypeArg.Invariant)
                            ?.type?.toRecordFieldType(enumNames, dataClassNames) ?: "Any?"
                        "List<$elem>"
                    }
                    CollectionKind.MAP -> {
                        val key = (typeArgs.getOrNull(0) as? KmpTypeArg.Invariant)
                            ?.type?.toRecordFieldType(enumNames, dataClassNames) ?: "Any?"
                        val value = (typeArgs.getOrNull(1) as? KmpTypeArg.Invariant)
                            ?.type?.toRecordFieldType(enumNames, dataClassNames) ?: "Any?"
                        "Map<$key, $value>"
                    }
                }
                "$inner$q"
            }
            this is KmpTypeRef.UnitType  -> "Unit"
            this is KmpTypeRef.TypeParam -> "Any?"
            else                         -> "Any?"
        }
    }

    private fun KmpTypeRef.toRecordFieldDefault(enumNames: Set<String>, dataClassNames: Set<String>): String {
        if (isNullable) return "null"
        return when {
            this is KmpTypeRef.Primitive -> when (kind) {
                PrimitiveKind.STRING  -> "\"\""
                PrimitiveKind.INT, PrimitiveKind.BYTE, PrimitiveKind.SHORT -> "0"
                PrimitiveKind.LONG    -> "0.0"
                PrimitiveKind.DOUBLE  -> "0.0"
                PrimitiveKind.FLOAT   -> "0f"
                PrimitiveKind.BOOLEAN -> "false"
                PrimitiveKind.CHAR    -> "\"\""
            }
            this is KmpTypeRef.ClassRef && simpleName in enumNames      -> "\"\""
            this is KmpTypeRef.ClassRef && simpleName in dataClassNames -> "${simpleName}Record()"
            this is KmpTypeRef.ClassRef                                 -> "null"
            this is KmpTypeRef.CollectionType -> when (kind) {
                CollectionKind.MAP -> "emptyMap()"
                else               -> "emptyList()"
            }
            else -> "null"
        }
    }

    private fun KmpTypeRef.toKmpFieldConversion(
        fieldName: String,
        enumNames: Set<String>,
        dataClassNames: Set<String>,
    ): String = when {
        this is KmpTypeRef.Primitive && kind == PrimitiveKind.LONG ->
            if (nullable) "$fieldName?.toLong()" else "$fieldName.toLong()"
        this is KmpTypeRef.Primitive && kind == PrimitiveKind.CHAR ->
            if (nullable) "$fieldName?.firstOrNull()" else "$fieldName.first()"
        this is KmpTypeRef.ClassRef && simpleName in enumNames ->
            if (nullable) "$fieldName?.let { ${simpleName}.valueOf(it) }"
            else "${simpleName}.valueOf($fieldName)"
        this is KmpTypeRef.ClassRef && simpleName in dataClassNames ->
            if (nullable) "$fieldName?.toKmp()" else "$fieldName.toKmp()"
        this is KmpTypeRef.CollectionType && kind == CollectionKind.SET -> {
            val elemArg  = (typeArgs.firstOrNull() as? KmpTypeArg.Invariant)?.type
            val needsMap = elemArg != null && elemArg.needsConversion(enumNames, dataClassNames)
            val conv     = if (needsMap) ".map { ${elemArg!!.singleElemConversion(enumNames, dataClassNames)} }.toSet()" else ".toSet()"
            if (nullable) "$fieldName?.let { it$conv }" else "$fieldName$conv"
        }
        this is KmpTypeRef.CollectionType && kind == CollectionKind.LIST -> {
            val elemArg  = (typeArgs.firstOrNull() as? KmpTypeArg.Invariant)?.type
            val needsMap = elemArg != null && elemArg.needsConversion(enumNames, dataClassNames)
            if (!needsMap) fieldName
            else {
                val conv = elemArg!!.singleElemConversion(enumNames, dataClassNames)
                if (nullable) "$fieldName?.map { $conv }" else "$fieldName.map { $conv }"
            }
        }
        this is KmpTypeRef.CollectionType && kind == CollectionKind.MAP -> {
            val valArg   = (typeArgs.getOrNull(1) as? KmpTypeArg.Invariant)?.type
            val needsMap = valArg != null && valArg.needsConversion(enumNames, dataClassNames)
            if (!needsMap) fieldName
            else {
                val conv = valArg!!.singleElemConversion(enumNames, dataClassNames, "v")
                if (nullable) "$fieldName?.mapValues { (_, v) -> $conv }"
                else "$fieldName.mapValues { (_, v) -> $conv }"
            }
        }
        else -> fieldName
    }

    private fun KmpTypeRef.needsConversion(enumNames: Set<String>, dataClassNames: Set<String>): Boolean = when {
        this is KmpTypeRef.Primitive && kind == PrimitiveKind.LONG -> true
        this is KmpTypeRef.ClassRef  && simpleName in enumNames    -> true
        this is KmpTypeRef.ClassRef  && simpleName in dataClassNames -> true
        else -> false
    }

    private fun KmpTypeRef.singleElemConversion(
        enumNames: Set<String>,
        dataClassNames: Set<String>,
        elemVar: String = "it",
    ): String = when {
        this is KmpTypeRef.Primitive && kind == PrimitiveKind.LONG -> "$elemVar.toLong()"
        this is KmpTypeRef.ClassRef  && simpleName in enumNames    -> "${simpleName}.valueOf($elemVar)"
        this is KmpTypeRef.ClassRef  && simpleName in dataClassNames -> "$elemVar.toKmp()"
        else -> elemVar
    }

    private fun collectClassRefImports(
        type: KmpTypeRef,
        enumNames: Set<String>,
        dataClassNames: Set<String>,
        sealedNames: Set<String>,
        pkg: String,
        into: MutableSet<String>,
    ) {
        when {
            type is KmpTypeRef.ClassRef && type.simpleName in enumNames      -> into.add("$pkg.${type.simpleName}")
            type is KmpTypeRef.ClassRef && type.simpleName in dataClassNames -> into.add("$pkg.${type.simpleName}")
            type is KmpTypeRef.ClassRef && type.simpleName in sealedNames    -> into.add("$pkg.${type.simpleName}")
            type is KmpTypeRef.CollectionType -> type.typeArgs.forEach { arg ->
                val inner = when (arg) {
                    is KmpTypeArg.Invariant     -> arg.type
                    is KmpTypeArg.Covariant     -> arg.type
                    is KmpTypeArg.Contravariant -> arg.type
                    KmpTypeArg.Star             -> null
                }
                if (inner != null) collectClassRefImports(inner, enumNames, dataClassNames, sealedNames, pkg, into)
            }
            else -> Unit
        }
    }

    // ── Return suffix ─────────────────────────────────────────────────────────

    private fun KmpTypeRef.toReturnSuffix(
        enumNames: Set<String>,
        dataClassNames: Set<String> = emptySet(),
        sealedNames: Set<String> = emptySet(),
    ): String = when {
        this is KmpTypeRef.Primitive && kind == PrimitiveKind.CHAR  -> if (nullable) "?.toString()" else ".toString()"
        this is KmpTypeRef.ClassRef && simpleName in enumNames      -> if (nullable) "?.name" else ".name"
        this is KmpTypeRef.ClassRef && simpleName in dataClassNames -> if (nullable) "?.toRecord()" else ".toRecord()"
        this is KmpTypeRef.ClassRef && simpleName in sealedNames    -> if (nullable) "?.toRecord()" else ".toRecord()"
        else -> ""
    }

    // ── Declaration accessors ─────────────────────────────────────────────────

    private fun KmpDeclaration.declName(): String = when (this) {
        is KmpDeclaration.KmpClass       -> name
        is KmpDeclaration.KmpObject      -> name
        is KmpDeclaration.KmpDataClass   -> name
        is KmpDeclaration.KmpInterface   -> name
        is KmpDeclaration.KmpSealedClass -> name
        is KmpDeclaration.KmpEnum        -> name
    }

    private fun KmpDeclaration.declFunctions(): List<KmpFunction> = when (this) {
        is KmpDeclaration.KmpClass       -> functions
        is KmpDeclaration.KmpObject      -> functions
        is KmpDeclaration.KmpDataClass   -> functions
        is KmpDeclaration.KmpInterface   -> functions
        is KmpDeclaration.KmpSealedClass -> functions
        is KmpDeclaration.KmpEnum        -> emptyList()
    }

    // ── Bridge param / call arg ───────────────────────────────────────────────

    private fun KmpTypeRef.toBridgeParamType(enumNames: Set<String>): String = when {
        this is KmpTypeRef.ClassRef && simpleName in enumNames ->
            if (nullable) "String?" else "String"
        this is KmpTypeRef.Primitive && kind == PrimitiveKind.LONG ->
            if (nullable) "Double?" else "Double"
        this is KmpTypeRef.Primitive && kind == PrimitiveKind.CHAR ->
            if (nullable) "String?" else "String"
        else -> toKotlinTypeName()
    }

    private fun KmpTypeRef.toCallArg(paramName: String, enumNames: Set<String>): String = when {
        this is KmpTypeRef.ClassRef && simpleName in enumNames ->
            if (nullable) "$paramName?.let { ${simpleName}.valueOf(it) }"
            else "${simpleName}.valueOf($paramName)"
        this is KmpTypeRef.Primitive && kind == PrimitiveKind.LONG ->
            if (nullable) "$paramName?.toLong()" else "$paramName.toLong()"
        this is KmpTypeRef.Primitive && kind == PrimitiveKind.CHAR ->
            if (nullable) "$paramName?.firstOrNull()" else "$paramName.first()"
        else -> paramName
    }

    private fun KmpTypeRef.toKotlinTypeName(): String = when (this) {
        is KmpTypeRef.Primitive      -> primitiveKotlinName() + if (nullable) "?" else ""
        is KmpTypeRef.UnitType       -> "Unit"
        is KmpTypeRef.ClassRef       -> simpleName + if (nullable) "?" else ""
        is KmpTypeRef.CollectionType -> {
            val args = typeArgs.joinToString(", ") { arg ->
                when (arg) {
                    is KmpTypeArg.Invariant     -> arg.type.toKotlinTypeName()
                    is KmpTypeArg.Covariant     -> "out ${arg.type.toKotlinTypeName()}"
                    is KmpTypeArg.Contravariant -> "in ${arg.type.toKotlinTypeName()}"
                    KmpTypeArg.Star             -> "*"
                }
            }
            "${kind.toClassName()}<$args>" + if (nullable) "?" else ""
        }
        is KmpTypeRef.FlowType  -> "Any"
        is KmpTypeRef.TypeParam -> if (nullable) "Any?" else "Any"
    }

    private fun KmpTypeRef.Primitive.primitiveKotlinName(): String = when (kind) {
        PrimitiveKind.STRING  -> "String"
        PrimitiveKind.INT     -> "Int"
        PrimitiveKind.LONG    -> "Long"
        PrimitiveKind.DOUBLE  -> "Double"
        PrimitiveKind.FLOAT   -> "Float"
        PrimitiveKind.BOOLEAN -> "Boolean"
        PrimitiveKind.BYTE    -> "Byte"
        PrimitiveKind.SHORT   -> "Short"
        PrimitiveKind.CHAR    -> "Char"
    }

    private fun CollectionKind.toClassName(): String = when (this) {
        CollectionKind.LIST -> "List"
        CollectionKind.MAP  -> "Map"
        CollectionKind.SET  -> "Set"
    }

    // ── Enum reference detection ──────────────────────────────────────────────

    private fun KmpFunction.referencesEnum(enumName: String): Boolean {
        fun KmpTypeRef.hasEnumRef(): Boolean = when (this) {
            is KmpTypeRef.ClassRef       -> simpleName == enumName
            is KmpTypeRef.CollectionType -> typeArgs.any { arg ->
                when (arg) {
                    is KmpTypeArg.Invariant     -> arg.type.hasEnumRef()
                    is KmpTypeArg.Covariant     -> arg.type.hasEnumRef()
                    is KmpTypeArg.Contravariant -> arg.type.hasEnumRef()
                    KmpTypeArg.Star             -> false
                }
            }
            is KmpTypeRef.FlowType -> when (val a = typeArg) {
                is KmpTypeArg.Invariant -> a.type.hasEnumRef()
                else                   -> false
            }
            else -> false
        }
        return params.any { it.type.hasEnumRef() } || returnType.hasEnumRef()
    }
}

// ── File-level helpers ────────────────────────────────────────────────────────

private fun formatComment(docComment: String?, indent: String = "    "): String {
    if (docComment == null) return ""
    val lines = docComment.lines().mapNotNull { line ->
        val content = line.trim()
            .removePrefix("/**").removePrefix("*/").removePrefix("/*")
            .let {
                when {
                    it.startsWith("* ") -> it.drop(2)
                    it.startsWith("*")  -> it.drop(1).trimStart()
                    it.startsWith("// ") -> it.drop(3)
                    it.startsWith("//")  -> it.drop(2).trimStart()
                    else -> it
                }
            }.trim()
        if (content.isBlank()) null else "$indent// $content"
    }
    return if (lines.isEmpty()) "" else lines.joinToString("\n") + "\n"
}

private fun String.cap()  = replaceFirstChar { it.uppercase() }
private fun String.decap() = replaceFirstChar { it.lowercase() }
private fun String.toSnakeUpperCase() = replace(Regex("([A-Z])"), "_$1").uppercase().trimStart('_')

// Expo's Function/AsyncFunction DSL has overloads up to this many parameters.
// Functions with more params are skipped with a comment in the generated output.
private const val MAX_EXPO_FUNCTION_PARAMS = 8

private const val HEADER = "// AUTO-GENERATED by shared-artifacts bridge generator. DO NOT EDIT.\n" +
    "// Re-run `bash scripts/push-bridges.sh` from shared-artifacts to regenerate."
