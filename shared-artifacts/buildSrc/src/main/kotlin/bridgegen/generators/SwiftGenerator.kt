package bridgegen.generators

import bridgegen.*

private const val MAX_EXPO_FUNCTION_PARAMS = 8

object SwiftGenerator {

    /**
     * Generates a complete Swift source file for one [KmpSourceFile].
     * Mirrors Android's generateFile() — one output file per source file containing:
     * - struct XRecord: Record + toRecord() + toKmp() for each data class and sealed class
     * - one public Module class per bridgeable KmpClass / KmpObject
     */
    fun generateFile(
        sourceFile: KmpSourceFile,
        module: KmpModule,
        frameworkName: String,
        onSkip: (String) -> Unit = {},
    ): String {
        val enumNames   = module.declarations.filterIsInstance<KmpDeclaration.KmpEnum>().map { it.name }.toSet()
        val dataNames   = module.declarations.filterIsInstance<KmpDeclaration.KmpDataClass>().map { it.name }.toSet()
        val sealedNames = module.declarations.filterIsInstance<KmpDeclaration.KmpSealedClass>().map { it.name }.toSet()

        val records = sourceFile.declarations.filterIsInstance<KmpDeclaration.KmpDataClass>()
            .filter { dc ->
                if (dc.fields.isEmpty()) {
                    onSkip("DATA CLASS SKIPPED: ${dc.name} — no fields, no Record generated.")
                    false
                } else true
            }
        val sealeds = sourceFile.declarations.filterIsInstance<KmpDeclaration.KmpSealedClass>()

        val bridgeableDecls = sourceFile.declarations.filter { decl ->
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
                else -> false
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
        for (decl in bridgeableDecls) {
            for (fn in decl.declFunctions()) {
                if (!fn.isBridgeable(enumNames, dataNames, sealedNames)) {
                    val reason = when {
                        fn.params.size > MAX_EXPO_FUNCTION_PARAMS -> "too many params (${fn.params.size} > $MAX_EXPO_FUNCTION_PARAMS)"
                        !fn.returnType.isSwiftBridgeable(enumNames, dataNames, sealedNames) -> "unbridgeable return type"
                        else -> "unbridgeable param type"
                    }
                    onSkip("FUNCTION SKIPPED: ${decl.declName()}.${fn.name}() — $reason.")
                }
            }
        }

        if (records.isEmpty() && sealeds.isEmpty() && bridgeableDecls.isEmpty()) return ""

        // Collect enum names that need decode helpers — from function params, Record fields,
        // and sealed variant fields.
        val decodedEnumNames = mutableSetOf<String>()
        fun collectEnumRef(t: KmpTypeRef) {
            if (t is KmpTypeRef.ClassRef && t.simpleName in enumNames) decodedEnumNames.add(t.simpleName)
        }
        for (decl in bridgeableDecls) {
            val bridgeable = decl.declFunctions().filter { it.isBridgeable(enumNames, dataNames, sealedNames) }
            for (fn in bridgeable) { fn.params.forEach { collectEnumRef(it.type) } }
        }
        for (record in records) { record.fields.forEach { collectEnumRef(it.type) } }
        for (sealed in sealeds) {
            for (variant in sealed.variants) { variant.variantFields().forEach { collectEnumRef(it.type) } }
        }

        return buildString {
            appendLine(HEADER)
            appendLine("import ExpoModulesCore")
            appendLine("import $frameworkName")

            // ── Data class Record structs + codecs ──────────────────────────
            for (record in records) {
                appendLine()
                appendRecordStruct(record, enumNames, dataNames, sealedNames)
            }

            // ── Sealed class Record structs + codecs ────────────────────────
            for (sealed in sealeds) {
                appendLine()
                appendSealedCodec(sealed, enumNames, dataNames, sealedNames)
            }

            // ── Module classes ──────────────────────────────────────────────
            for (decl in bridgeableDecls) {
                appendLine()
                appendModuleClass(decl, enumNames, dataNames, sealedNames)
            }

            // ── Enum decode helpers (throwing) ──────────────────────────────
            for (eName in decodedEnumNames.sorted()) {
                appendLine()
                appendLine("fileprivate func decode${eName}(_ raw: String) throws -> ${eName} {")
                appendLine("  guard let value = ${eName}.allCases.first(where: { \$0.name == raw }) else {")
                appendLine("    throw NSError(domain: \"BridgeError\", code: 0,")
                appendLine("                  userInfo: [NSLocalizedDescriptionKey: \"Unknown ${eName}: \\(raw)\"])")
                appendLine("  }")
                appendLine("  return value")
                appendLine("}")
            }
        }
    }

    // ── Data class Record codec ───────────────────────────────────────────────

    private fun StringBuilder.appendRecordStruct(
        decl: KmpDeclaration.KmpDataClass,
        enumNames: Set<String>,
        dataNames: Set<String>,
        sealedNames: Set<String>,
    ) {
        val n = decl.name

        appendLine("struct ${n}Record: Record {")
        for (field in decl.fields) {
            val type    = field.type.toRecordFieldType(enumNames, dataNames, sealedNames)
            val default = field.type.toRecordFieldDefault(enumNames, dataNames, sealedNames)
            appendLine("  @Field var ${field.name}: $type = $default")
        }
        appendLine("}")
        appendLine()

        appendLine("fileprivate func toRecord(_ v: $n) -> ${n}Record {")
        appendLine("  var r = ${n}Record()")
        for (field in decl.fields) {
            val rhs = field.type.toRecordAssignment("v.${field.name}", enumNames, dataNames, sealedNames)
            appendLine("  r.${field.name} = $rhs")
        }
        appendLine("  return r")
        appendLine("}")
        appendLine()

        val needsThrows = decl.fields.any { f ->
            val t = f.type
            t is KmpTypeRef.ClassRef && (t.simpleName in enumNames || t.simpleName in dataNames || t.simpleName in sealedNames)
        }
        appendLine("fileprivate extension ${n}Record {")
        appendLine("  func toKmp() ${if (needsThrows) "throws " else ""}-> $n {")
        appendLine("    return $n(")
        for (field in decl.fields) {
            val (prefix, arg) = field.type.toKmpConversionWithPrefix(field.name, enumNames, dataNames, sealedNames)
            appendLine("      ${field.name}: ${prefix}$arg,")
        }
        appendLine("    )")
        appendLine("  }")
        appendLine()
        // __toDict() — converts this Record to a plain [String: Any?] for sendEvent payloads.
        // Expo's sendEvent requires plain dictionaries; Record structs are not directly serializable.
        appendLine("  func __toDict() -> [String: Any?] {")
        appendLine("    return [")
        for (field in decl.fields) {
            val value = field.type.toAnyExpr(field.name, dataNames, sealedNames)
            appendLine("      \"${field.name}\": $value,")
        }
        appendLine("    ]")
        appendLine("  }")
        appendLine("}")
    }

    // ── Sealed class Record codec ─────────────────────────────────────────────

    private fun StringBuilder.appendSealedCodec(
        decl: KmpDeclaration.KmpSealedClass,
        enumNames: Set<String>,
        dataNames: Set<String>,
        sealedNames: Set<String>,
    ) {
        val n = decl.name

        // Collect all variant fields deduplicated by name — same approach as Android.
        val allFields = mutableListOf<KmpField>()
        val seenNames = mutableSetOf<String>()
        for (variant in decl.variants) {
            for (field in variant.variantFields()) {
                if (seenNames.add(field.name)) allFields.add(field)
            }
        }

        // struct XRecord: Record — flat, type discriminator + all variant fields nullable
        appendLine("struct ${n}Record: Record {")
        appendLine("  @Field var type: String = \"\"")
        for (field in allFields) {
            // Sealed record fields are always nullable — the active variant determines which are set.
            val fieldType = field.type.toRecordFieldType(enumNames, dataNames, sealedNames).trimEnd('?') + "?"
            appendLine("  @Field var ${field.name}: $fieldType = nil")
        }
        appendLine("}")
        appendLine()

        // func toRecord(_ v: X) -> XRecord
        // SKIE exposes sealed classes via onEnum(of:) which returns a __Sealed Swift enum.
        // Variant case names are the Kotlin variant names with lowercased first letter.
        appendLine("fileprivate func toRecord(_ v: $n) -> ${n}Record {")
        appendLine("  var r = ${n}Record()")
        appendLine("  switch onEnum(of: v) {")
        for (variant in decl.variants) {
            val vName  = variant.variantName()
            val fields = variant.variantFields()
            val swiftCase = vName.decap()
            if (fields.isEmpty()) {
                appendLine("  case .$swiftCase:")
                appendLine("    r.type = \"$vName\"")
            } else {
                appendLine("  case .${swiftCase}(let s):")
                appendLine("    r.type = \"$vName\"")
                for (field in fields) {
                    val rhs = field.type.toRecordAssignment("s.${field.name}", enumNames, dataNames, sealedNames)
                    appendLine("    r.${field.name} = $rhs")
                }
            }
        }
        appendLine("  }")
        appendLine("  return r")
        appendLine("}")
        appendLine()

        // extension XRecord { func toKmp() throws -> X }
        appendLine("fileprivate extension ${n}Record {")
        appendLine("  func toKmp() throws -> $n {")
        appendLine("    switch type {")
        for (variant in decl.variants) {
            val vName      = variant.variantName()
            val fields     = variant.variantFields()
            val isAbstract = (variant as? KmpVariant.ClassVariant)?.isAbstract ?: false
            appendLine("    case \"$vName\":")
            when {
                isAbstract -> {
                    appendLine("      throw NSError(domain: \"BridgeError\", code: 0,")
                    appendLine("                    userInfo: [NSLocalizedDescriptionKey: \"$n.$vName is abstract — cannot deserialize\"])")
                }
                variant is KmpVariant.ObjectVariant -> {
                    // Kotlin object singletons are exposed as .shared in Swift via Kotlin/Native.
                    appendLine("      return $n.$vName.shared")
                }
                fields.isEmpty() -> {
                    appendLine("      return $n.$vName()")
                }
                else -> {
                    appendLine("      return $n.$vName(")
                    for (field in fields) {
                        val (prefix, arg) = field.type.toSealedKmpConversionWithPrefix(field.name, enumNames, dataNames, sealedNames)
                        appendLine("        ${field.name}: ${prefix}$arg,")
                    }
                    appendLine("      )")
                }
            }
        }
        appendLine("    default:")
        appendLine("      throw NSError(domain: \"BridgeError\", code: 0,")
        appendLine("                    userInfo: [NSLocalizedDescriptionKey: \"Unknown $n type: \\(type)\"])")
        appendLine("    }")
        appendLine("  }")
        appendLine()
        appendLine("  func __toDict() -> [String: Any?] {")
        appendLine("    var d: [String: Any?] = [\"type\": type]")
        for (field in allFields) {
            // Sealed record fields are always nullable — force optional chaining for nested records
            val value = field.type.toAnyExpr(field.name, dataNames, sealedNames, forceNullable = true)
            appendLine("    d[\"${field.name}\"] = $value")
        }
        appendLine("    return d")
        appendLine("  }")
        appendLine("}")
    }

    // ── Module class generation ───────────────────────────────────────────────

    private fun StringBuilder.appendModuleClass(
        decl: KmpDeclaration,
        enumNames: Set<String>,
        dataNames: Set<String>,
        sealedNames: Set<String>,
    ) {
        val name       = decl.declName()
        val isObject   = decl is KmpDeclaration.KmpObject
        val bridgeable = decl.declFunctions().filter { it.isBridgeable(enumNames, dataNames, sealedNames) }
        if (bridgeable.isEmpty()) return

        val typeArgsSuffix = if (decl is KmpDeclaration.KmpClass && decl.typeParameters.isNotEmpty()) {
            "<${decl.typeParameters.joinToString(", ") { "AnyObject" }}>"
        } else ""
        val instance     = name.decap()
        val instanceInit = if (isObject) "$name.shared" else "$name$typeArgsSuffix()"
        val flows        = bridgeable.filter { it.kind == FunctionKind.FLOW }
        val hasFlows     = flows.isNotEmpty()
        val eventNames   = flows.map { "on${it.flowBaseName.cap()}Update" }

        appendLine("public class ${name}Module: Module {")
        appendLine("  private let $instance = $instanceInit")
        if (hasFlows) {
            val enumCases = "case " + flows.joinToString(", ") { it.flowBaseName }
            appendLine("  private enum FlowKey { $enumCases }")
            appendLine("  private var flowTasks: [FlowKey: Task<Void, Never>] = [:]")
        }

        appendLine()
        appendLine("  public func definition() -> ModuleDefinition {")
        appendLine("""    Name("$name")""")

        if (eventNames.isNotEmpty()) {
            appendLine()
            appendLine("""    Events(${eventNames.joinToString(", ") { "\"$it\"" }})""")
        }
        if (hasFlows) {
            appendLine()
            appendLine("    OnDestroy {")
            appendLine("      self.flowTasks.values.forEach { \$0.cancel() }")
            appendLine("    }")
        }

        for (fn in bridgeable) {
            appendLine()
            when (fn.kind) {
                FunctionKind.SYNC    -> appendSyncFunction(fn, instance, enumNames, dataNames, sealedNames)
                FunctionKind.SUSPEND -> appendSuspendFunction(fn, instance, enumNames, dataNames, sealedNames)
                FunctionKind.FLOW    -> appendFlowFunctions(fn, instance, enumNames, dataNames, sealedNames)
            }
        }

        appendLine("  }")
        appendLine("}")
    }

    // ── Function emitters ─────────────────────────────────────────────────────

    private fun StringBuilder.appendSyncFunction(
        fn: KmpFunction,
        instance: String,
        enumNames: Set<String>,
        dataNames: Set<String>,
        sealedNames: Set<String>,
    ) {
        append(formatComment(fn.docComment))
        val paramList    = fn.params.joinToString(", ") { "${it.name}: ${it.type.toSwiftBridgeType(enumNames, dataNames, sealedNames)}" }
        val callArgs     = fn.params.joinToString(", ") { p ->
            val (prefix, arg) = p.type.toSwiftCallArgWithPrefix(p.name, enumNames, dataNames, sealedNames)
            "${p.name}: ${prefix}$arg"
        }
        val throwsClause = if (fn.needsThrows(enumNames, dataNames, sealedNames)) " throws" else ""
        val rawCall      = "self.$instance.${fn.name}(${if (fn.params.isEmpty()) "" else callArgs})"
        val returnExpr   = fn.returnType.wrapReturnExpr(rawCall, enumNames, dataNames, sealedNames)

        if (fn.params.isEmpty()) {
            appendLine("""    Function("${fn.name}") {""")
        } else {
            appendLine("""    Function("${fn.name}") { ($paramList)$throwsClause in""")
        }
        appendLine("      return $returnExpr")
        appendLine("    }")
    }

    private fun StringBuilder.appendSuspendFunction(
        fn: KmpFunction,
        instance: String,
        enumNames: Set<String>,
        dataNames: Set<String>,
        sealedNames: Set<String>,
    ) {
        append(formatComment(fn.docComment))
        val allParams    = fn.params.map { "${it.name}: ${it.type.toSwiftBridgeType(enumNames, dataNames, sealedNames)}" } + listOf("promise: Promise")
        val paramList    = allParams.joinToString(", ")
        val callArgs     = fn.params.joinToString(", ") { p ->
            val (prefix, arg) = p.type.toSwiftCallArgWithPrefix(p.name, enumNames, dataNames, sealedNames)
            "${p.name}: ${prefix}$arg"
        }
        val throwsClause = if (fn.needsThrows(enumNames, dataNames, sealedNames)) " throws" else ""
        // SKIE exposes suspend functions with generic TypeParam returns via skie() wrapper
        val callTarget   = if (fn.returnType is KmpTypeRef.TypeParam) "skie(self.$instance)" else "self.$instance"
        val rawCall      = "$callTarget.${fn.name}(${if (fn.params.isEmpty()) "" else callArgs})"
        val returnExpr   = fn.returnType.wrapReturnExpr(rawCall, enumNames, dataNames, sealedNames)

        appendLine("""    AsyncFunction("${fn.name}") { ($paramList)$throwsClause in""")
        appendLine("      Task { [weak self] in")
        appendLine("        guard let self else { return }")
        appendLine("        do {")
        appendLine("          let result = try await $returnExpr")
        appendLine("          promise.resolve(result)")
        appendLine("        } catch {")
        appendLine("          promise.reject(error)")
        appendLine("        }")
        appendLine("      }")
        appendLine("    }")
    }

    private fun StringBuilder.appendFlowFunctions(
        fn: KmpFunction,
        instance: String,
        enumNames: Set<String>,
        dataNames: Set<String>,
        sealedNames: Set<String>,
    ) {
        val base      = fn.flowBaseName
        val Cap       = base.cap()
        val eventName = "on${Cap}Update"
        val valueExpr = fn.returnType.toSwiftFlowValueExpr(enumNames, dataNames, sealedNames)
        append(formatComment(fn.docComment))
        appendLine("""    Function("start$Cap") {""")
        appendLine("      self.flowTasks[.$base]?.cancel()")
        appendLine("      self.flowTasks[.$base] = Task { [weak self] in")
        appendLine("        guard let self else { return }")
        appendLine("        for await value in self.$instance.${fn.name}() {")
        appendLine("""          self.sendEvent("$eventName", ["value": $valueExpr])""")
        appendLine("        }")
        appendLine("      }")
        appendLine("    }")
        appendLine()
        appendLine("""    Function("stop$Cap") {""")
        appendLine("      self.flowTasks[.$base]?.cancel()")
        appendLine("      self.flowTasks[.$base] = nil")
        appendLine("    }")
    }

    // ── Bridgeability ─────────────────────────────────────────────────────────

    private fun KmpFunction.isBridgeable(
        enumNames: Set<String>,
        dataNames: Set<String>,
        sealedNames: Set<String>,
    ): Boolean {
        if (params.size > MAX_EXPO_FUNCTION_PARAMS) return false
        if (!returnType.isSwiftBridgeable(enumNames, dataNames, sealedNames)) return false
        return params.all { it.type.isSwiftBridgeable(enumNames, dataNames, sealedNames) }
    }

    private fun KmpFunction.needsThrows(
        enumNames: Set<String>,
        dataNames: Set<String>,
        sealedNames: Set<String>,
    ): Boolean = params.any { p ->
        val t = p.type
        t is KmpTypeRef.ClassRef && (t.simpleName in enumNames || t.simpleName in dataNames || t.simpleName in sealedNames)
    }

    private fun KmpTypeRef.isSwiftBridgeable(
        enumNames: Set<String>,
        dataNames: Set<String>,
        sealedNames: Set<String>,
    ): Boolean = when {
        this is KmpTypeRef.Primitive      -> true
        this is KmpTypeRef.UnitType       -> true
        this is KmpTypeRef.ClassRef       -> simpleName in enumNames || simpleName in dataNames || simpleName in sealedNames
        this is KmpTypeRef.CollectionType -> typeArgs.all { arg ->
            when (arg) {
                is KmpTypeArg.Invariant     -> arg.type.isSwiftBridgeable(enumNames, dataNames, sealedNames)
                is KmpTypeArg.Covariant     -> arg.type.isSwiftBridgeable(enumNames, dataNames, sealedNames)
                is KmpTypeArg.Contravariant -> arg.type.isSwiftBridgeable(enumNames, dataNames, sealedNames)
                KmpTypeArg.Star             -> true  // star → Any/AnyHashable, always bridgeable
            }
        }
        this is KmpTypeRef.TypeParam -> true  // generic T → Any at the bridge layer
        else -> false
    }

    // ── Type mapping ──────────────────────────────────────────────────────────

    private fun KmpTypeRef.toSwiftBridgeType(
        enumNames: Set<String>,
        dataNames: Set<String>,
        sealedNames: Set<String>,
    ): String {
        val base = when {
            this is KmpTypeRef.Primitive -> when (kind) {
                PrimitiveKind.STRING  -> "String"
                PrimitiveKind.INT     -> "Int32"
                PrimitiveKind.LONG    -> "Double"
                PrimitiveKind.DOUBLE  -> "Double"
                PrimitiveKind.FLOAT   -> "Float"
                PrimitiveKind.BOOLEAN -> "Bool"
                PrimitiveKind.CHAR    -> "String"
                PrimitiveKind.BYTE    -> "Int32"
                PrimitiveKind.SHORT   -> "Int32"
            }
            this is KmpTypeRef.UnitType -> "Void"
            this is KmpTypeRef.ClassRef && simpleName in enumNames  -> "String"
            this is KmpTypeRef.ClassRef && (simpleName in dataNames || simpleName in sealedNames) -> "${simpleName}Record"
            this is KmpTypeRef.CollectionType -> when (kind) {
                CollectionKind.LIST, CollectionKind.SET -> {
                    val elem = typeArgs.firstOrNull().toSwiftTypeArgString(enumNames, dataNames, sealedNames) ?: "Any"
                    "[$elem]"
                }
                CollectionKind.MAP -> {
                    val key   = typeArgs.getOrNull(0).toSwiftTypeArgString(enumNames, dataNames, sealedNames) ?: "AnyHashable"
                    val value = typeArgs.getOrNull(1).toSwiftTypeArgString(enumNames, dataNames, sealedNames) ?: "Any"
                    "[$key: $value]"
                }
            }
            this is KmpTypeRef.TypeParam -> "Any"
            else -> "Any"
        }
        return if (isNullable) "$base?" else base
    }

    private fun KmpTypeArg?.toSwiftTypeArgString(
        enumNames: Set<String>,
        dataNames: Set<String>,
        sealedNames: Set<String>,
    ): String? = when (this) {
        is KmpTypeArg.Invariant     -> type.toSwiftBridgeType(enumNames, dataNames, sealedNames)
        is KmpTypeArg.Covariant     -> type.toSwiftBridgeType(enumNames, dataNames, sealedNames)
        is KmpTypeArg.Contravariant -> type.toSwiftBridgeType(enumNames, dataNames, sealedNames)
        KmpTypeArg.Star             -> "Any"
        null                        -> null
    }

    private fun KmpTypeRef.toSwiftCallArgWithPrefix(
        paramName: String,
        enumNames: Set<String>,
        dataNames: Set<String>,
        sealedNames: Set<String>,
    ): Pair<String, String> = when {
        this is KmpTypeRef.Primitive && kind == PrimitiveKind.LONG ->
            "" to if (isNullable) "$paramName.map { Int64(\$0) }" else "Int64($paramName)"
        // Char: String bridge → unichar (UInt16) for KMP function call
        this is KmpTypeRef.Primitive && kind == PrimitiveKind.CHAR ->
            "" to if (isNullable) "$paramName?.utf16.first" else "$paramName.utf16.first ?? 0"
        // Nullable primitives: SKIE expects KotlinX? boxed types
        this is KmpTypeRef.Primitive && kind == PrimitiveKind.INT && isNullable ->
            "" to "$paramName.map { KotlinInt(value: \$0) }"
        this is KmpTypeRef.Primitive && kind == PrimitiveKind.BOOLEAN && isNullable ->
            "" to "$paramName.map { KotlinBoolean(value: \$0) }"
        this is KmpTypeRef.Primitive && kind == PrimitiveKind.BYTE && isNullable ->
            "" to "$paramName.map { KotlinByte(value: \$0) }"
        this is KmpTypeRef.Primitive && kind == PrimitiveKind.SHORT && isNullable ->
            "" to "$paramName.map { KotlinShort(value: \$0) }"
        this is KmpTypeRef.ClassRef && simpleName in enumNames ->
            "try " to if (isNullable) "$paramName.map { try decode${simpleName}(\$0) }" else "decode${simpleName}($paramName)"
        this is KmpTypeRef.ClassRef && (simpleName in dataNames || simpleName in sealedNames) ->
            "try " to if (isNullable) "$paramName?.toKmp()" else "$paramName.toKmp()"
        else -> "" to paramName
    }

    private fun KmpTypeRef.wrapReturnExpr(
        rawCall: String,
        enumNames: Set<String>,
        dataNames: Set<String>,
        sealedNames: Set<String>,
    ): String = when {
        this is KmpTypeRef.ClassRef && simpleName in enumNames ->
            if (isNullable) "$rawCall?.name" else "$rawCall.name"
        this is KmpTypeRef.ClassRef && (simpleName in dataNames || simpleName in sealedNames) ->
            if (isNullable) "$rawCall.map { toRecord(\$0) }" else "toRecord($rawCall)"
        this is KmpTypeRef.Primitive && kind == PrimitiveKind.CHAR ->
            if (isNullable) "$rawCall?.description" else "$rawCall.description"
        this is KmpTypeRef.CollectionType -> {
            val inner = collectionBridgeExpr(rawCall, enumNames, dataNames, sealedNames)
            inner ?: rawCall
        }
        else -> rawCall
    }

    /**
     * Recursively builds a conversion expression for a collection return value.
     * Returns null when no conversion is needed (all elements are already bridge-compatible).
     * Handles nested cases like Map<String, List<DataClass>>.
     */
    private fun KmpTypeRef.CollectionType.collectionBridgeExpr(
        expr: String,
        enumNames: Set<String>,
        dataNames: Set<String>,
        sealedNames: Set<String>,
    ): String? {
        val chain = if (isNullable) "?." else "."
        return when (kind) {
            CollectionKind.LIST, CollectionKind.SET -> {
                val elemType = when (val a = typeArgs.firstOrNull()) {
                    is KmpTypeArg.Invariant -> a.type
                    is KmpTypeArg.Covariant -> a.type
                    else                    -> null
                }
                val inner = elemType?.singleElemBridgeExpr("i", enumNames, dataNames, sealedNames)
                if (inner != null) "$expr${chain}map { i in $inner }" else null
            }
            CollectionKind.MAP -> {
                val valType = (typeArgs.getOrNull(1) as? KmpTypeArg.Invariant)?.type
                val inner = valType?.singleElemBridgeExpr("v", enumNames, dataNames, sealedNames)
                if (inner != null) "$expr${chain}mapValues { v in $inner }" else null
            }
        }
    }

    /**
     * Returns a conversion expression for a single element variable, or null if no conversion needed.
     * Used recursively to build nested collection transformations.
     */
    private fun KmpTypeRef.singleElemBridgeExpr(
        varName: String,
        enumNames: Set<String>,
        dataNames: Set<String>,
        sealedNames: Set<String>,
    ): String? = when {
        this is KmpTypeRef.ClassRef && (simpleName in dataNames || simpleName in sealedNames) -> "toRecord($varName)"
        this is KmpTypeRef.ClassRef && simpleName in enumNames -> "$varName.name"
        this is KmpTypeRef.Primitive && kind == PrimitiveKind.CHAR -> "$varName.description"
        this is KmpTypeRef.CollectionType ->
            collectionBridgeExpr(varName, enumNames, dataNames, sealedNames)
        else -> null
    }

    /** Expression for the value sent in a flow event — wraps with toRecord for data/sealed types. */
    private fun KmpTypeRef.toSwiftFlowValueExpr(
        enumNames: Set<String>,
        dataNames: Set<String>,
        sealedNames: Set<String>,
    ): String = when {
        this is KmpTypeRef.ClassRef && simpleName in enumNames -> "value.name"
        // Use __toDict() — sendEvent requires plain [String: Any?], not Expo Record structs
        this is KmpTypeRef.ClassRef && (simpleName in dataNames || simpleName in sealedNames) -> "toRecord(value).__toDict()"
        this is KmpTypeRef.Primitive -> when (kind) {
            PrimitiveKind.INT     -> "value.intValue"
            PrimitiveKind.LONG    -> "value.int64Value"
            PrimitiveKind.DOUBLE  -> "value.doubleValue"
            PrimitiveKind.FLOAT   -> "value.floatValue"
            PrimitiveKind.BOOLEAN -> "value.boolValue"
            PrimitiveKind.CHAR    -> "value.description"
            PrimitiveKind.STRING,
            PrimitiveKind.BYTE,
            PrimitiveKind.SHORT   -> "value"
        }
        else -> "value"
    }

    // ── Record field helpers ──────────────────────────────────────────────────

    private fun KmpTypeRef.toRecordFieldType(
        enumNames: Set<String>,
        dataNames: Set<String>,
        sealedNames: Set<String>,
    ): String {
        val base = when {
            this is KmpTypeRef.Primitive -> when (kind) {
                PrimitiveKind.STRING  -> "String"
                PrimitiveKind.INT     -> "Int32"
                PrimitiveKind.LONG    -> "Int64"
                PrimitiveKind.DOUBLE  -> "Double"
                PrimitiveKind.FLOAT   -> "Float"
                PrimitiveKind.BOOLEAN -> "Bool"
                PrimitiveKind.CHAR    -> "String"
                PrimitiveKind.BYTE    -> "Int8"
                PrimitiveKind.SHORT   -> "Int16"
            }
            this is KmpTypeRef.UnitType -> "Bool"
            this is KmpTypeRef.ClassRef && simpleName in enumNames -> "String"
            this is KmpTypeRef.ClassRef && (simpleName in dataNames || simpleName in sealedNames) -> "${simpleName}Record"
            this is KmpTypeRef.ClassRef -> "String"
            this is KmpTypeRef.CollectionType -> when (kind) {
                CollectionKind.LIST, CollectionKind.SET -> {
                    val elem = (typeArgs.firstOrNull() as? KmpTypeArg.Invariant)
                        ?.type?.toRecordFieldType(enumNames, dataNames, sealedNames) ?: "Any"
                    "[$elem]"
                }
                CollectionKind.MAP -> {
                    val key   = (typeArgs.getOrNull(0) as? KmpTypeArg.Invariant)
                        ?.type?.toRecordFieldType(enumNames, dataNames, sealedNames) ?: "String"
                    val value = (typeArgs.getOrNull(1) as? KmpTypeArg.Invariant)
                        ?.type?.toRecordFieldType(enumNames, dataNames, sealedNames) ?: "Any"
                    "[$key: $value]"
                }
            }
            else -> "String"
        }
        return if (isNullable) "$base?" else base
    }

    private fun KmpTypeRef.toRecordFieldDefault(
        enumNames: Set<String>,
        dataNames: Set<String>,
        sealedNames: Set<String>,
    ): String {
        if (isNullable) return "nil"
        return when {
            this is KmpTypeRef.Primitive -> when (kind) {
                PrimitiveKind.STRING  -> "\"\""
                PrimitiveKind.INT, PrimitiveKind.BYTE, PrimitiveKind.SHORT -> "0"
                PrimitiveKind.LONG    -> "0"
                PrimitiveKind.DOUBLE  -> "0.0"
                PrimitiveKind.FLOAT   -> "0.0"
                PrimitiveKind.BOOLEAN -> "false"
                PrimitiveKind.CHAR    -> "\"\""
            }
            this is KmpTypeRef.ClassRef && simpleName in enumNames  -> "\"\""
            this is KmpTypeRef.ClassRef && simpleName in dataNames  -> "${simpleName}Record()"
            this is KmpTypeRef.ClassRef && simpleName in sealedNames -> "${simpleName}Record()"
            this is KmpTypeRef.CollectionType -> if (kind == CollectionKind.MAP) "[:]" else "[]"
            else -> "\"\""
        }
    }

    private fun KmpTypeRef.toRecordAssignment(
        expr: String,
        enumNames: Set<String>,
        dataNames: Set<String>,
        sealedNames: Set<String>,
    ): String = when {
        // Char: unichar (UInt16) in SKIE → one-char String for @Field
        this is KmpTypeRef.Primitive && kind == PrimitiveKind.CHAR ->
            if (isNullable) "$expr.map { String(decoding: [\$0], as: UTF16.self) }"
            else "String(decoding: [$expr], as: UTF16.self)"
        this is KmpTypeRef.Primitive -> expr
        this is KmpTypeRef.ClassRef && simpleName in enumNames ->
            if (isNullable) "$expr?.name" else "$expr.name"
        this is KmpTypeRef.ClassRef && (simpleName in dataNames || simpleName in sealedNames) ->
            if (isNullable) "$expr.map { toRecord(\$0) }" else "toRecord($expr)"
        // Int in Map values: SKIE boxes to KotlinInt → convert to Int32 for @Field
        this is KmpTypeRef.CollectionType && kind == CollectionKind.MAP -> {
            val valType = (typeArgs.getOrNull(1) as? KmpTypeArg.Invariant)?.type
            val valConv = valType?.singleElemToRecordConv()
            if (valConv != null)
                if (isNullable) "$expr?.mapValues { v in $valConv }" else "$expr.mapValues { v in $valConv }"
            else expr
        }
        this is KmpTypeRef.CollectionType && kind == CollectionKind.SET ->
            if (isNullable) "$expr.map { Array(\$0) }" else "Array($expr)"
        else -> expr
    }

    /** Converts a single SKIE-boxed element to the Expo-friendly @Field type. */
    private fun KmpTypeRef.singleElemToRecordConv(): String? = when {
        this is KmpTypeRef.Primitive && kind == PrimitiveKind.INT     -> "Int32(v.intValue)"
        this is KmpTypeRef.Primitive && kind == PrimitiveKind.LONG    -> "Int64(truncating: v)"
        this is KmpTypeRef.Primitive && kind == PrimitiveKind.FLOAT   -> "Float(v.floatValue)"
        this is KmpTypeRef.Primitive && kind == PrimitiveKind.DOUBLE  -> "Double(v.doubleValue)"
        this is KmpTypeRef.Primitive && kind == PrimitiveKind.BOOLEAN -> "Bool(v.boolValue)"
        this is KmpTypeRef.Primitive && kind == PrimitiveKind.BYTE    -> "Int8(v.int8Value)"
        this is KmpTypeRef.Primitive && kind == PrimitiveKind.SHORT   -> "Int16(truncating: v)"
        else -> null
    }

    private fun KmpTypeRef.toKmpConversionWithPrefix(
        fieldName: String,
        enumNames: Set<String>,
        dataNames: Set<String>,
        sealedNames: Set<String>,
    ): Pair<String, String> = when {
        // Char: String @Field → unichar (UInt16) for KMP constructor
        this is KmpTypeRef.Primitive && kind == PrimitiveKind.CHAR ->
            "" to if (isNullable) "$fieldName?.utf16.first" else "$fieldName.utf16.first ?? 0"
        // Nullable primitive boxing: Int? → KotlinInt?, Bool? → KotlinBoolean?, etc.
        this is KmpTypeRef.Primitive && kind == PrimitiveKind.INT && isNullable ->
            "" to "$fieldName.map { KotlinInt(value: \$0) }"
        this is KmpTypeRef.Primitive && kind == PrimitiveKind.BOOLEAN && isNullable ->
            "" to "$fieldName.map { KotlinBoolean(value: \$0) }"
        this is KmpTypeRef.Primitive && kind == PrimitiveKind.BYTE && isNullable ->
            "" to "$fieldName.map { KotlinByte(value: \$0) }"
        this is KmpTypeRef.Primitive && kind == PrimitiveKind.SHORT && isNullable ->
            "" to "$fieldName.map { KotlinShort(value: \$0) }"
        this is KmpTypeRef.Primitive -> "" to fieldName
        this is KmpTypeRef.ClassRef && simpleName in enumNames ->
            "try " to if (isNullable) "$fieldName.map { try decode${simpleName}(\$0) }" else "decode${simpleName}($fieldName)"
        this is KmpTypeRef.ClassRef && (simpleName in dataNames || simpleName in sealedNames) ->
            "try " to if (isNullable) "$fieldName?.toKmp()" else "$fieldName.toKmp()"
        // Int in Map values: Int32 @Field → KotlinInt for KMP
        this is KmpTypeRef.CollectionType && kind == CollectionKind.MAP -> {
            val valType = (typeArgs.getOrNull(1) as? KmpTypeArg.Invariant)?.type
            val valConv = valType?.singleElemToKmpConv()
            if (valConv != null)
                "" to if (isNullable) "$fieldName?.mapValues { v in $valConv }" else "$fieldName.mapValues { v in $valConv }"
            else "" to fieldName
        }
        this is KmpTypeRef.CollectionType && kind == CollectionKind.SET ->
            "" to if (isNullable) "$fieldName.map { Set(\$0) }" else "Set($fieldName)"
        else -> "" to fieldName
    }

    /**
     * Converts a @Field property value to Any? for use in sendEvent [String: Any?] payloads.
     * Nested Record types call .__toDict(); everything else passes through directly.
     */
    private fun KmpTypeRef.toAnyExpr(
        fieldName: String,
        dataNames: Set<String>,
        sealedNames: Set<String>,
        forceNullable: Boolean = false,
    ): String {
        val nullable = isNullable || forceNullable
        return toAnyExprInternal(fieldName, dataNames, sealedNames, nullable)
    }

    private fun KmpTypeRef.toAnyExprInternal(
        fieldName: String,
        dataNames: Set<String>,
        sealedNames: Set<String>,
        nullable: Boolean,
    ): String = when {
        this is KmpTypeRef.ClassRef && (simpleName in dataNames || simpleName in sealedNames) ->
            if (nullable) "$fieldName?.__toDict()" else "$fieldName.__toDict()"
        this is KmpTypeRef.CollectionType && kind == CollectionKind.LIST -> {
            val elemType = (typeArgs.firstOrNull() as? KmpTypeArg.Invariant)?.type
                ?: (typeArgs.firstOrNull() as? KmpTypeArg.Covariant)?.type
            if (elemType is KmpTypeRef.ClassRef && (elemType.simpleName in dataNames || elemType.simpleName in sealedNames)) {
                if (nullable) "$fieldName?.map { \$0.__toDict() }" else "$fieldName.map { \$0.__toDict() }"
            } else fieldName
        }
        this is KmpTypeRef.CollectionType && kind == CollectionKind.MAP -> {
            val valType = (typeArgs.getOrNull(1) as? KmpTypeArg.Invariant)?.type
            if (valType is KmpTypeRef.ClassRef && (valType.simpleName in dataNames || valType.simpleName in sealedNames)) {
                if (nullable) "$fieldName?.mapValues { \$0.__toDict() }" else "$fieldName.mapValues { \$0.__toDict() }"
            } else fieldName
        }
        else -> fieldName
    }

    /** Converts a single Expo-friendly @Field element to the SKIE-boxed Kotlin type. */
    private fun KmpTypeRef.singleElemToKmpConv(): String? = when {
        this is KmpTypeRef.Primitive && kind == PrimitiveKind.INT     -> "KotlinInt(value: v)"
        this is KmpTypeRef.Primitive && kind == PrimitiveKind.LONG    -> "KotlinLong(value: v)"
        this is KmpTypeRef.Primitive && kind == PrimitiveKind.FLOAT   -> "KotlinFloat(value: v)"
        this is KmpTypeRef.Primitive && kind == PrimitiveKind.DOUBLE  -> "KotlinDouble(value: v)"
        this is KmpTypeRef.Primitive && kind == PrimitiveKind.BOOLEAN -> "KotlinBoolean(value: v)"
        this is KmpTypeRef.Primitive && kind == PrimitiveKind.BYTE    -> "KotlinByte(value: v)"
        this is KmpTypeRef.Primitive && kind == PrimitiveKind.SHORT   -> "KotlinShort(value: v)"
        else -> null
    }

    /**
     * Like toKmpConversionWithPrefix but for sealed Record fields — all sealed @Field properties
     * are nullable (flat record pattern), so non-nullable KMP fields need explicit defaults.
     */
    private fun KmpTypeRef.toSealedKmpConversionWithPrefix(
        fieldName: String,
        enumNames: Set<String>,
        dataNames: Set<String>,
        sealedNames: Set<String>,
    ): Pair<String, String> {
        if (isNullable) return toKmpConversionWithPrefix(fieldName, enumNames, dataNames, sealedNames)
        return when {
            this is KmpTypeRef.Primitive && kind == PrimitiveKind.CHAR ->
                "" to "$fieldName?.utf16.first ?? 0"
            this is KmpTypeRef.Primitive && kind == PrimitiveKind.LONG ->
                "" to "Int64($fieldName ?? 0.0)"
            this is KmpTypeRef.Primitive -> "" to when (kind) {
                PrimitiveKind.STRING  -> "$fieldName ?? \"\""
                PrimitiveKind.INT     -> "$fieldName ?? 0"
                PrimitiveKind.DOUBLE  -> "$fieldName ?? 0.0"
                PrimitiveKind.FLOAT   -> "$fieldName ?? 0.0"
                PrimitiveKind.BOOLEAN -> "$fieldName ?? false"
                PrimitiveKind.BYTE    -> "$fieldName ?? 0"
                PrimitiveKind.SHORT   -> "$fieldName ?? 0"
                else                  -> fieldName
            }
            this is KmpTypeRef.ClassRef && simpleName in enumNames ->
                "try " to "decode${simpleName}($fieldName ?? \"\")"
            this is KmpTypeRef.ClassRef && (simpleName in dataNames || simpleName in sealedNames) ->
                "try " to "($fieldName ?? ${simpleName}Record()).toKmp()"
            else -> toKmpConversionWithPrefix(fieldName, enumNames, dataNames, sealedNames)
        }
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

    private val KmpTypeRef.isNullable: Boolean get() = when (this) {
        is KmpTypeRef.Primitive      -> nullable
        is KmpTypeRef.ClassRef       -> nullable
        is KmpTypeRef.CollectionType -> nullable
        is KmpTypeRef.UnitType       -> nullable
        is KmpTypeRef.FlowType       -> nullable
        is KmpTypeRef.TypeParam      -> nullable
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

private fun String.cap()   = replaceFirstChar { it.uppercase() }
private fun String.decap() = replaceFirstChar { it.lowercase() }

private const val HEADER =
    "// AUTO-GENERATED by shared-artifacts bridge generator. DO NOT EDIT.\n" +
    "// Re-run `bash scripts/push-bridges.sh` from shared-artifacts to regenerate.\n"
