package bridgegen.generators

import bridgegen.*

object AndroidGenerator {

    // ── File-level generation ─────────────────────────────────────────────────

    /**
     * Generates the full contents of one Android bridge Kotlin file for a single KMP source file.
     *
     * Emits, in order: `Record` classes for bridged data classes, sealed-class codecs, and
     * `Module()` classes for every bridgeable class/object/file-scope declaration and every
     * interface/abstract class registry. Declarations that cannot be bridged (empty
     * classes/objects, fieldless data classes, member functions on data/sealed classes) are
     * skipped with a message reported via [onSkip] rather than failing the build.
     *
     * @return the complete file text (header + package + imports + bodies), or `""` if this
     *         source file contributes nothing to the Android bridge.
     */
    fun generateFile(sourceFile: KmpSourceFile, module: KmpModule, kmpPackageName: String, androidPackage: String, onSkip: (String) -> Unit = {}): String {
        val enumNames      = module.declarations.filterIsInstance<KmpDeclaration.KmpEnum>().map { it.name }.toSet()
        val dataClassNames = module.declarations.filterIsInstance<KmpDeclaration.KmpDataClass>().map { it.name }.toSet()
        val sealedNames    = module.declarations.filterIsInstance<KmpDeclaration.KmpSealedClass>().map { it.name }.toSet()
        val interfaceNames = module.declarations.filterIsInstance<KmpDeclaration.KmpInterface>().map { it.name }.toSet()
        val abstractNames  = module.declarations.filterIsInstance<KmpDeclaration.KmpClass>().filter { it.isAbstract }.map { it.name }.toSet()

        val records = sourceFile.declarations.filterIsInstance<KmpDeclaration.KmpDataClass>()
            .filter { dc ->
                if (dc.fields.isEmpty()) {
                    onSkip("DATA CLASS SKIPPED: ${dc.name} — no fields, no Record generated.")
                    false
                } else true
            }
        val sealeds = sourceFile.declarations.filterIsInstance<KmpDeclaration.KmpSealedClass>()
        val interfaceDecls = sourceFile.declarations.filter { decl ->
            decl is KmpDeclaration.KmpInterface || (decl is KmpDeclaration.KmpClass && decl.isAbstract)
        }
        val modules = sourceFile.declarations.filter { decl ->
            when {
                decl is KmpDeclaration.KmpInterface -> false
                decl is KmpDeclaration.KmpClass && decl.isAbstract -> false
                decl is KmpDeclaration.KmpClass && decl.functions.isEmpty() -> {
                    onSkip("CLASS SKIPPED: ${decl.name} — no functions to bridge.")
                    false
                }
                decl is KmpDeclaration.KmpObject && decl.functions.isEmpty() -> {
                    onSkip("OBJECT SKIPPED: ${decl.name} — no functions to bridge.")
                    false
                }
                decl is KmpDeclaration.KmpFileScope && decl.functions.isEmpty() -> {
                    onSkip("FILE SCOPE SKIPPED: ${decl.fileName} — no functions to bridge.")
                    false
                }
                decl is KmpDeclaration.KmpClass     -> true
                decl is KmpDeclaration.KmpObject    -> true
                decl is KmpDeclaration.KmpFileScope -> true
                else                                -> false
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

        if (records.isEmpty() && sealeds.isEmpty() && modules.isEmpty() && interfaceDecls.isEmpty()) return ""

        val allImports   = mutableSetOf<String>()
        val recordBodies = mutableListOf<String>()
        val sealedBodies = mutableListOf<String>()
        val moduleBodies = mutableListOf<String>()

        for (record in records) {
            val (imports, body) = generateRecord(record, kmpPackageName, enumNames, dataClassNames, sealedNames)
            allImports.addAll(imports)
            recordBodies.add(body)
        }

        for (sealed in sealeds) {
            val (imports, body) = generateSealedCodec(sealed, kmpPackageName, enumNames, dataClassNames, sealedNames)
            allImports.addAll(imports)
            sealedBodies.add(body)
        }

        // Runtime-typed wire conversion helper for generic (erased) positions — emitted once
        // per file when any function return/flow element or record field mentions a type param.
        val needsWireHelper = sourceFile.declarations.any { d ->
            d.declFunctions().any { fn -> fn.returnType.containsTypeParam() } ||
                (d as? KmpDeclaration.KmpDataClass)?.fields?.any { it.type.containsTypeParam() } == true ||
                (d as? KmpDeclaration.KmpSealedClass)?.variants?.any { v -> v.variantFields().any { it.type.containsTypeParam() } } == true
        }
        val helperBodies = mutableListOf<String>()
        if (needsWireHelper) {
            (dataClassNames + sealedNames + enumNames).forEach { allImports.add("$kmpPackageName.$it") }
            helperBodies.add(wireHelper(enumNames, dataClassNames, sealedNames))
        }

        val takenNames = (modules.filter { it !is KmpDeclaration.KmpFileScope } + interfaceDecls).map { it.declName() }.toSet()
        for (decl in modules) {
            val nameOverride = if (decl is KmpDeclaration.KmpFileScope && decl.fileName in takenNames) "${decl.fileName}Kt" else null
            val (imports, body) = buildModuleBody(decl, kmpPackageName, enumNames, dataClassNames, sealedNames, interfaceNames, abstractNames, onSkip, moduleNameOverride = nameOverride)
            allImports.addAll(imports)
            moduleBodies.add(body)
        }

        for (decl in interfaceDecls) {
            val (imports, body) = buildInterfaceModuleBody(decl, kmpPackageName, enumNames, dataClassNames, sealedNames, interfaceNames, abstractNames, onSkip)
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
        if (helperBodies.isNotEmpty()) {
            sb.appendLine()
            helperBodies.forEach { sb.appendLine(it) }
        }
        if (moduleBodies.isNotEmpty()) {
            moduleBodies.forEach { sb.appendLine(it) }
        }

        return sb.toString().trimEnd() + "\n"
    }

    // ── Record generation (data classes) ─────────────────────────────────────

    /**
     * Generates the Expo `Record` class and `toKmp()`/`toRecord()` conversion functions for a
     * bridged Kotlin data class.
     *
     * The `Record` subclass is the JS → Kotlin direction (Expo populates its `@Field`s from the
     * JS object); `toKmp()` converts that Record into the real KMP data class; `toRecord()` is
     * the reverse conversion used when returning the data class to JS.
     *
     * @return the set of fully-qualified imports the generated code requires, paired with the
     *         generated Kotlin source text (record class + conversion functions).
     */
    fun generateRecord(
        decl: KmpDeclaration.KmpDataClass,
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
        for (field in decl.fields) {
            collectClassRefImports(field.type, enumNames, dataClassNames, sealedNames, kmpPackageName, imports)
        }

        val sb = StringBuilder()

        // Record class — JS → Kotlin via Expo
        sb.appendLine("class ${decl.name}Record : Record {")
        for (field in decl.fields) {
            val fieldType = field.type.toRecordFieldType(enumNames, dataClassNames, sealedNames)
            val default   = field.type.toRecordFieldDefault(enumNames, dataClassNames, sealedNames)
            sb.appendLine("    @Field var ${field.name}: $fieldType = $default")
        }
        sb.appendLine("}")
        sb.appendLine()

        // toKmp() — Record → Kotlin KMP type
        sb.appendLine("fun ${decl.name}Record.toKmp() = ${decl.name}(")
        for (field in decl.fields) {
            val conv = field.type.toKmpFieldConversion(field.name, enumNames, dataClassNames, sealedNames)
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

    /**
     * Generates the flat `Record` type and `toRecord()`/`toKmp()` codec for a sealed class
     * hierarchy.
     *
     * All variant fields across the hierarchy are unioned into a single nullable-field Record
     * with a `type` discriminator string naming the variant. Fields that share a name across
     * variants are deduplicated by first occurrence (see the verification doc for the caveat
     * this introduces when types differ across variants). Abstract variants encode fine but
     * throw on decode, since there is no way to know which concrete subclass to reconstruct.
     *
     * @return the set of fully-qualified imports required, paired with the generated codec
     *         source.
     */
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
            // Top-level (non-nested) variants are their own classes and need their own import.
            if (!variant.isNestedVariant) imports.add("$kmpPackageName.${variant.variantName()}")
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
            sb.appendLine("        is ${variant.variantRef(n)} -> \"${variant.variantName()}\"")
        }
        sb.appendLine("    }")
        sb.appendLine("    when (this) {")
        for (variant in decl.variants) {
            val vRef   = variant.variantRef(n)
            val fields = variant.variantFields()
            if (fields.isEmpty()) {
                sb.appendLine("        is $vRef -> Unit")
            } else {
                sb.appendLine("        is $vRef -> {")
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
            val vRef      = variant.variantRef(n)
            val fields    = variant.variantFields()
            val isAbstract = (variant as? KmpVariant.ClassVariant)?.isAbstract ?: false
            when {
                isAbstract -> sb.appendLine("    \"$vName\" -> error(\"$n.$vName is abstract — cannot deserialize\")")
                variant is KmpVariant.ObjectVariant -> sb.appendLine("    \"$vName\" -> $vRef")
                fields.isEmpty() -> sb.appendLine("    \"$vName\" -> $vRef()")
                else -> {
                    sb.appendLine("    \"$vName\" -> $vRef(")
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

    /**
     * Generates the Expo `Module()` class that bridges one class, object, or file-scope
     * declaration.
     *
     * Objects and file-scope declarations call straight through to the singleton/package;
     * classes are instance-based and tracked in an `instances` map keyed by a generated instance
     * id created via a `create` bridge function (and torn down via `destroy`). When a class has
     * any suspend or Flow function, instances are wrapped in a per-instance holder pairing the
     * instance with its own `CoroutineScope` so `destroy()` can cancel exactly that instance's
     * in-flight work (see the `useHolder` branch below).
     *
     * @param moduleNameOverride used to disambiguate a file-scope module from a same-named class
     *        in the same source file (appends a `Kt` suffix), mirroring the Kotlin compiler's
     *        own `FilenameKt` facade naming.
     * @return the set of fully-qualified imports required, paired with the generated module
     *         source.
     */
    private fun buildModuleBody(
        decl: KmpDeclaration,
        kmpPackageName: String,
        enumNames: Set<String>,
        dataClassNames: Set<String>,
        sealedNames: Set<String>,
        interfaceNames: Set<String>,
        abstractNames: Set<String>,
        onSkip: (String) -> Unit = {},
        moduleNameOverride: String? = null,
    ): Pair<Set<String>, String> {
        val name        = moduleNameOverride ?: decl.declName()
        val functions   = decl.declFunctions()
        val isObject    = decl is KmpDeclaration.KmpObject
        val isFileScope = decl is KmpDeclaration.KmpFileScope
        val isInstanceBased = !isObject && !isFileScope

        // Flows whose effective param count exceeds the Expo limit are skipped by flowFunctions;
        // exclude them from event/FlowKey bookkeeping so no unused declarations are emitted.
        val flows      = functions.filter { it.kind == FunctionKind.FLOW }
            .filter { it.params.size + (if (isInstanceBased) 1 else 0) <= MAX_EXPO_FUNCTION_PARAMS }
        val hasSuspend = functions.any { it.kind == FunctionKind.SUSPEND }
        val hasFlows   = flows.isNotEmpty()
        val eventNames = flows.map { "on${it.flowBaseName.cap()}Update" }
        val usedEnums  = enumNames.filter { eName -> functions.any { fn -> fn.referencesEnum(eName) } }
        // callTarget: object → type name (singleton), file scope → package name (FQN call), class → unused (uses instance map)
        val callTarget = when {
            isObject    -> name
            isFileScope -> kmpPackageName
            else        -> name.decap()
        }

        val imports = mutableSetOf<String>()
        if (!isFileScope) imports.add("$kmpPackageName.$name")
        for (eName in usedEnums) imports.add("$kmpPackageName.$eName")
        // Data/sealed/enum types referenced by function signatures (params or returns) need
        // imports so the generated Record conversions resolve even across source files.
        for (fn in functions) {
            for (p in fn.params) collectClassRefImports(p.type, enumNames, dataClassNames, sealedNames, kmpPackageName, imports)
            collectClassRefImports(fn.returnType, enumNames, dataClassNames, sealedNames, kmpPackageName, imports)
        }
        if (hasSuspend) { imports.add("expo.modules.kotlin.Promise"); imports.add("java.util.concurrent.atomic.AtomicBoolean") }
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
        if (isInstanceBased) {
            imports.add("java.util.UUID")
            imports.add("java.util.concurrent.ConcurrentHashMap")
        }

        val typeArgsSuffix = if (decl is KmpDeclaration.KmpClass && decl.typeParameters.isNotEmpty()) {
            "<${decl.typeParameters.joinToString(", ") { "Any" }}>"
        } else ""

        // Instance-based classes with async work use a per-instance holder so that destroy()
        // can cancel the scope in one call and the two maps (instances + flowJobs) collapse into one.
        val useHolder = isInstanceBased && (hasSuspend || hasFlows)

        val sb = StringBuilder()
        sb.appendLine("class ${name}Module : Module() {")
        if (useHolder) {
            if (hasFlows) {
                val enumCases = flows.joinToString(", ") { it.flowBaseName.toSnakeUpperCase() }
                sb.appendLine("  private enum class FlowKey { $enumCases }")
            }
            sb.appendLine("  private class InstanceHolder(val instance: $name$typeArgsSuffix) {")
            sb.appendLine("    val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())")
            if (hasFlows) sb.appendLine("    val flowJobs = mutableMapOf<FlowKey, Job>()")
            sb.appendLine("  }")
            sb.appendLine("  private val instances = ConcurrentHashMap<String, InstanceHolder>()")
        } else {
            if (isInstanceBased) sb.appendLine("  private val instances = ConcurrentHashMap<String, $name$typeArgsSuffix>()")
            if (hasSuspend || hasFlows) {
                sb.appendLine("  private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())")
            }
            if (hasFlows) {
                val enumCases = flows.joinToString(", ") { it.flowBaseName.toSnakeUpperCase() }
                sb.appendLine("  private enum class FlowKey { $enumCases }")
                sb.appendLine("  private val flowJobs = mutableMapOf<FlowKey, Job>()")
            }
        }

        if (hasSuspend) {
            sb.appendLine()
            sb.appendLine(settledLaunchHelper())
        }
        sb.appendLine()
        sb.appendLine("  override fun definition() = ModuleDefinition {")
        sb.appendLine("""    Name("$name")""")

        if (isInstanceBased) {
            sb.appendLine()
            sb.appendLine("""    Function("create") {""")
            sb.appendLine("      val id = UUID.randomUUID().toString()")
            if (useHolder) {
                sb.appendLine("      instances[id] = InstanceHolder($name$typeArgsSuffix())")
            } else {
                sb.appendLine("      instances[id] = $name$typeArgsSuffix()")
            }
            sb.appendLine("      id")
            sb.appendLine("    }")
            sb.appendLine()
            sb.appendLine("""    Function("destroy") { instanceId: String ->""")
            if (useHolder) {
                sb.appendLine("      instances.remove(instanceId)?.scope?.cancel()")
            } else {
                sb.appendLine("      instances.remove(instanceId)")
            }
            sb.appendLine("    }")
        }

        if (eventNames.isNotEmpty()) {
            sb.appendLine()
            sb.appendLine("""    Events(${eventNames.joinToString(", ") { "\"$it\"" }})""")
        }

        if (hasSuspend || hasFlows) {
            sb.appendLine()
            sb.appendLine("    OnDestroy {")
            if (useHolder) {
                sb.appendLine("      instances.values.forEach { it.scope.cancel() }")
            } else {
                sb.appendLine("      scope.cancel()")
            }
            sb.appendLine("    }")
        }

        for (fn in functions) {
            sb.appendLine()
            when (fn.kind) {
                FunctionKind.SYNC    -> sb.append(syncFunction(fn, callTarget, enumNames, dataClassNames, sealedNames, onSkip, isInstanceBased = isInstanceBased, useHolder = useHolder, interfaceNames = interfaceNames, abstractNames = abstractNames))
                FunctionKind.SUSPEND -> sb.append(suspendFunction(fn, callTarget, enumNames, dataClassNames, sealedNames, onSkip, isInstanceBased = isInstanceBased, useHolder = useHolder, interfaceNames = interfaceNames, abstractNames = abstractNames))
                FunctionKind.FLOW    -> sb.append(flowFunctions(fn, callTarget, enumNames, dataClassNames, sealedNames, useHolder = useHolder, interfaceNames = interfaceNames, abstractNames = abstractNames, onSkip = onSkip))
            }
        }

        sb.appendLine("  }")
        sb.append("}")

        return imports to sb.toString()
    }

    // ── Interface registry module body ────────────────────────────────────────

    /**
     * Generates the registry object + Expo `Module()` for a bridged `interface` or
     * `abstract class`.
     *
     * Because an interface/abstract type has no concrete instance of its own, instances are held
     * in an internal `<Name>Registry` singleton keyed by instance id, so both directions of use
     * are supported from the same registry:
     *  - **KMP-implemented**: a concrete instance obtained elsewhere (e.g. returned from another
     *    bridged function) is registered and its id handed to JS as an opaque handle; further JS
     *    calls dispatch back through the registry via that id.
     *  - **JS-implemented**: `create()` builds an anonymous KMP object whose suspend functions
     *    proxy to JS — each call emits a `call<Fn>` event carrying a `callId`, blocks on a
     *    `CompletableDeferred`, and a matching `resolve<Fn>` bridge function (called by JS once
     *    it has a result) completes that deferred.
     *
     * Sync functions cannot be JS-implemented (no way to block JS synchronously) and throw if
     * called on a JS-backed instance; Flow functions are not yet supported for JS-implemented
     * instances.
     *
     * @return the set of fully-qualified imports required, paired with the generated registry +
     *         module source.
     */
    private fun buildInterfaceModuleBody(
        decl: KmpDeclaration,
        kmpPackageName: String,
        enumNames: Set<String>,
        dataClassNames: Set<String>,
        sealedNames: Set<String>,
        interfaceNames: Set<String>,
        abstractNames: Set<String>,
        onSkip: (String) -> Unit = {},
    ): Pair<Set<String>, String> {
        val name = decl.declName()
        val functions = decl.declFunctions()
        val registryName = "${name}Registry"

        // Flows over the Expo param limit are skipped by flowFunctions (interface flows always
        // carry the synthetic instanceId); keep event/FlowKey bookkeeping in sync.
        val flows = functions.filter { it.kind == FunctionKind.FLOW }
            .filter { it.params.size + 1 <= MAX_EXPO_FUNCTION_PARAMS }
        val hasSuspend = functions.any { it.kind == FunctionKind.SUSPEND }
        val hasFlows = flows.isNotEmpty()
        // The call<Fn> reverse-bridge events only exist when a JS implementation can be created.
        val jsImplementable = decl.isJsImplementable()
        val eventNames = flows.map { "on${it.flowBaseName.cap()}Update" } +
            (if (jsImplementable) decl.proxiedSuspendFunctions().map { "call${it.name.cap()}" } else emptyList())
        val usedEnums = enumNames.filter { eName -> functions.any { fn -> fn.referencesEnum(eName) } }

        val imports = mutableSetOf<String>()
        imports.add("$kmpPackageName.$name")
        for (eName in usedEnums) imports.add("$kmpPackageName.$eName")
        // Data/sealed/enum types referenced by function signatures (params or returns) need
        // imports so the generated Record conversions resolve even across source files.
        for (fn in functions) {
            for (p in fn.params) collectClassRefImports(p.type, enumNames, dataClassNames, sealedNames, kmpPackageName, imports)
            collectClassRefImports(fn.returnType, enumNames, dataClassNames, sealedNames, kmpPackageName, imports)
        }
        imports.add("expo.modules.kotlin.modules.Module")
        imports.add("expo.modules.kotlin.modules.ModuleDefinition")
        if (hasSuspend) { imports.add("expo.modules.kotlin.Promise"); imports.add("java.util.concurrent.atomic.AtomicBoolean") }
        imports.add("kotlinx.coroutines.CompletableDeferred")
        imports.add("kotlinx.coroutines.CoroutineScope")
        imports.add("kotlinx.coroutines.Dispatchers")
        imports.add("kotlinx.coroutines.Job")
        imports.add("kotlinx.coroutines.SupervisorJob")
        imports.add("kotlinx.coroutines.cancel")
        imports.add("kotlinx.coroutines.launch")
        if (hasFlows) imports.add("kotlinx.coroutines.flow.collect")
        imports.add("java.util.UUID")
        imports.add("java.util.concurrent.ConcurrentHashMap")

        val sb = StringBuilder()

        sb.appendLine("internal object $registryName {")
        if (hasFlows) {
            val enumCases = flows.joinToString(", ") { it.flowBaseName.toSnakeUpperCase() }
            sb.appendLine("  enum class FlowKey { $enumCases }")
        }
        sb.appendLine("  class Holder(val instance: $name) {")
        sb.appendLine("    val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())")
        if (hasFlows) sb.appendLine("    val flowJobs = mutableMapOf<FlowKey, Job>()")
        sb.appendLine("    val pendingCalls = ConcurrentHashMap<String, CompletableDeferred<Any?>>()")
        sb.appendLine("  }")
        sb.appendLine("  private val holders = ConcurrentHashMap<String, Holder>()")
        sb.appendLine()
        sb.appendLine("  fun register(instance: $name): String {")
        sb.appendLine("    val id = UUID.randomUUID().toString()")
        sb.appendLine("    holders[id] = Holder(instance)")
        sb.appendLine("    return id")
        sb.appendLine("  }")
        sb.appendLine()
        sb.appendLine("  fun registerWithId(id: String, instance: $name) {")
        sb.appendLine("    holders[id] = Holder(instance)")
        sb.appendLine("  }")
        sb.appendLine()
        sb.appendLine("  fun get(instanceId: String): Holder =")
        sb.appendLine("    holders[instanceId] ?: error(\"No $name instance for id: \$instanceId\")")
        sb.appendLine()
        sb.appendLine("  fun release(instanceId: String) {")
        sb.appendLine("    holders.remove(instanceId)?.scope?.cancel()")
        sb.appendLine("  }")
        sb.appendLine("}")
        sb.appendLine()

        sb.appendLine("class ${name}Module : Module() {")
        if (hasSuspend) {
            sb.appendLine()
            sb.appendLine(settledLaunchHelper())
        }
        sb.appendLine()
        sb.appendLine("  override fun definition() = ModuleDefinition {")
        sb.appendLine("""    Name("$name")""")

        if (eventNames.isNotEmpty()) {
            sb.appendLine()
            sb.appendLine("""    Events(${eventNames.joinToString(", ") { "\"$it\"" }})""")
        }

        sb.appendLine()
        sb.appendLine("""    Function("destroy") { instanceId: String ->""")
        sb.appendLine("      $registryName.release(instanceId)")
        sb.appendLine("    }")

        for (fn in functions) {
            sb.appendLine()
            when (fn.kind) {
                FunctionKind.SYNC    -> sb.append(syncFunction(fn, "", enumNames, dataClassNames, sealedNames, onSkip, isInstanceBased = true, useHolder = false, registryName = registryName, interfaceNames = interfaceNames, abstractNames = abstractNames))
                FunctionKind.SUSPEND -> sb.append(suspendFunction(fn, "", enumNames, dataClassNames, sealedNames, onSkip, isInstanceBased = true, useHolder = false, registryName = registryName, interfaceNames = interfaceNames, abstractNames = abstractNames))
                FunctionKind.FLOW    -> sb.append(flowFunctions(fn, "", enumNames, dataClassNames, sealedNames, useHolder = false, registryName = registryName, interfaceNames = interfaceNames, abstractNames = abstractNames, onSkip = onSkip))
            }
        }

        // ── Task 5: JS-implemented interface ─────────────────────────────────────
        // Only emitted when the anonymous subtype can actually be compiled — a ctor with
        // parameters, a final member function, or an abstract property makes that impossible.
        if (!jsImplementable) {
            val msg = "CREATE SKIPPED: $name — cannot be JS-implemented (${decl.jsImplementabilityGap()})."
            onSkip(msg)
            sb.appendLine()
            sb.appendLine("    // $msg")
            sb.appendLine("  }")
            sb.append("}")
            return imports to sb.toString()
        }
        val isAbstractClass = decl is KmpDeclaration.KmpClass && (decl as KmpDeclaration.KmpClass).isAbstract
        // Abstract-class constructor parameters and abstract-property initial values both
        // thread through create(...) — ctor args into super(...), property values into overrides.
        val ctorFields    = if (isAbstractClass) (decl as KmpDeclaration.KmpClass).ctorFields else emptyList()
        val abstractProps = decl.abstractProperties()
        if (ctorFields.size + abstractProps.size > MAX_EXPO_FUNCTION_PARAMS) {
            val msg = "CREATE SKIPPED: $name — constructor params + abstract properties exceed $MAX_EXPO_FUNCTION_PARAMS parameters."
            onSkip(msg)
            sb.appendLine()
            sb.appendLine("    // $msg")
            sb.appendLine("  }")
            sb.append("}")
            return imports to sb.toString()
        }
        for (f in ctorFields) collectClassRefImports(f.type, enumNames, dataClassNames, sealedNames, kmpPackageName, imports)
        for (pr in abstractProps) collectClassRefImports(pr.type, enumNames, dataClassNames, sealedNames, kmpPackageName, imports)
        val createParams = (ctorFields.map { it.name to it.type } + abstractProps.map { it.name to it.type })
            .joinToString(", ") { (n, t) -> "$n: ${t.toBridgeParamType(enumNames, interfaceNames, abstractNames, dataClassNames, sealedNames)}" }
        val ctorArgs   = ctorFields.joinToString(", ") { it.type.toCallArg(it.name, enumNames, interfaceNames, abstractNames, dataClassNames, sealedNames) }
        val implBase = if (isAbstractClass) "$name($ctorArgs)" else name
        // Abstract classes: only abstract members are overridden — concrete members are
        // inherited from the real KMP class. Interfaces: every member is overridden (iOS's
        // ObjC-runtime conformance cannot inherit Kotlin default impls; keep platforms aligned).
        val overrideFns = if (isAbstractClass) functions.filter { it.isAbstractMember } else functions
        val suspendFns = decl.proxiedSuspendFunctions()

        sb.appendLine()
        if (createParams.isEmpty()) {
            sb.appendLine("""    Function("create") {""")
        } else {
            sb.appendLine("""    Function("create") { $createParams ->""")
        }
        sb.appendLine("      val instanceId = UUID.randomUUID().toString()")
        sb.appendLine("      val emitEvent = { eventName: String, body: Map<String, Any?> -> sendEvent(eventName, body) }")
        // Hoist property conversions into locals — an override's initializer cannot reference
        // the create() parameter of the same name (the member declaration shadows it).
        for (pr in abstractProps) {
            sb.appendLine("      val __${pr.name} = ${pr.type.toCallArg(pr.name, enumNames, interfaceNames, abstractNames, dataClassNames, sealedNames)}")
        }
        sb.appendLine("      val impl = object : $implBase {")
        for (pr in abstractProps) {
            val kw = if (pr.isVar) "var" else "val"
            sb.appendLine("        override $kw ${pr.name}: ${pr.type.toKotlinTypeName()} = __${pr.name}")
        }
        for (fn in overrideFns) {
            val pList = fn.params.joinToString(", ") { "${it.name}: ${it.type.toKotlinTypeName()}" }
            val retT  = fn.returnType.toKotlinTypeName()
            when (fn.kind) {
                FunctionKind.SYNC -> {
                    sb.appendLine("""        override fun ${fn.name}($pList): $retT = throw UnsupportedOperationException("${fn.name} is sync — cannot be JS-implemented")""")
                }
                FunctionKind.SUSPEND -> {
                    val isUnit = fn.returnType is KmpTypeRef.UnitType
                    val eventName = "call${fn.name.cap()}"
                    val paramMapEntries = buildString {
                        append(""""instanceId" to instanceId, "callId" to callId""")
                        for (p in fn.params) {
                            // Event payloads cross the JS bridge — convert like any other wire value.
                            val wire = p.type.toJsElemConversion(p.name, enumNames, dataClassNames, sealedNames) ?: p.name
                            append(""", "${p.name}" to $wire""")
                        }
                    }
                    val (_, castExpr) = fn.returnType.resolveWireContract(enumNames, dataClassNames, sealedNames)
                    sb.appendLine("        override suspend fun ${fn.name}($pList): $retT {")
                    sb.appendLine("          val holder = ${registryName}.get(instanceId)")
                    sb.appendLine("          val callId = UUID.randomUUID().toString()")
                    sb.appendLine("          val deferred = CompletableDeferred<Any?>()")
                    sb.appendLine("          holder.pendingCalls[callId] = deferred")
                    sb.appendLine("          try {")
                    sb.appendLine("""            emitEvent("$eventName", mapOf($paramMapEntries))""")
                    if (isUnit) sb.appendLine("            deferred.await()")
                    else        sb.appendLine("            return $castExpr")
                    sb.appendLine("          } finally {")
                    sb.appendLine("            // Abandoned/cancelled awaits must not leak their pendingCalls entry.")
                    sb.appendLine("            holder.pendingCalls.remove(callId)")
                    sb.appendLine("          }")
                    sb.appendLine("        }")
                }
                FunctionKind.FLOW -> {
                    val elemType = fn.returnType.toKotlinTypeName()
                    sb.appendLine("""        override fun ${fn.name}($pList): kotlinx.coroutines.flow.Flow<$elemType> = throw UnsupportedOperationException("${fn.name} is Flow — JS implementation not yet supported")""")
                }
            }
        }
        sb.appendLine("      }")
        sb.appendLine("      ${registryName}.registerWithId(instanceId, impl)")
        sb.appendLine("      instanceId")
        sb.appendLine("    }")

        for (fn in suspendFns) {
            val resolveName = "resolve${fn.name.cap()}"
            val (resultType, _) = fn.returnType.resolveWireContract(enumNames, dataClassNames, sealedNames)
            sb.appendLine()
            if (resultType == null) {
                sb.appendLine("""    Function("$resolveName") { instanceId: String, callId: String ->""")
                sb.appendLine("      ${registryName}.get(instanceId).pendingCalls.remove(callId)?.complete(null)")
            } else {
                sb.appendLine("""    Function("$resolveName") { instanceId: String, callId: String, result: $resultType ->""")
                sb.appendLine("      ${registryName}.get(instanceId).pendingCalls.remove(callId)?.complete(result)")
            }
            sb.appendLine("    }")
        }

        sb.appendLine("  }")
        sb.append("}")

        return imports to sb.toString()
    }

    // ── Function emitters ─────────────────────────────────────────────────────

    /**
     * Emits one Expo `Function("name") { ... }` block for a [FunctionKind.SYNC] KMP function.
     *
     * For instance-based declarations, an `instanceId: String` is prepended to the parameter
     * list and used to look up the backing instance (via [registryName], a per-instance holder
     * when [useHolder] is set, or a plain instance map otherwise).
     *
     * @return the generated `Function(...)` block, or a `// BRIDGE SKIPPED` comment line if the
     *         effective parameter count (including the synthetic `instanceId`) exceeds
     *         [MAX_EXPO_FUNCTION_PARAMS].
     */
    private fun syncFunction(
        fn: KmpFunction,
        callTarget: String,
        enumNames: Set<String>,
        dataClassNames: Set<String> = emptySet(),
        sealedNames: Set<String> = emptySet(),
        onSkip: (String) -> Unit = {},
        isInstanceBased: Boolean = false,
        useHolder: Boolean = false,
        registryName: String? = null,
        interfaceNames: Set<String> = emptySet(),
        abstractNames: Set<String> = emptySet(),
    ): String {
        val effectiveParamCount = fn.params.size + if (isInstanceBased) 1 else 0
        if (effectiveParamCount > MAX_EXPO_FUNCTION_PARAMS) {
            val msg = "BRIDGE SKIPPED: ${fn.name}(${fn.params.size} params) — Expo Function DSL supports max $MAX_EXPO_FUNCTION_PARAMS parameters."
            onSkip(msg)
            return "    // $msg\n"
        }
        if (fn.usesNonStringKeyMap()) {
            val msg = "BRIDGE SKIPPED: ${fn.name}() — Map with non-String keys is not bridgeable (JS objects are string-keyed)."
            onSkip(msg)
            return "    // $msg\n"
        }
        val sb  = StringBuilder()
        val ret = fn.returnType.toReturnSuffix(enumNames, dataClassNames, sealedNames, interfaceNames, abstractNames)
        val instanceExpr = when {
            registryName != null -> "$registryName.get(instanceId).instance"
            useHolder      -> "(instances[instanceId] ?: error(\"Instance not found: \$instanceId\")).instance"
            isInstanceBased -> "(instances[instanceId] ?: error(\"Instance not found: \$instanceId\"))"
            else            -> callTarget
        }
        val ownParams = fn.params.joinToString(", ") { "${it.name}: ${it.type.toBridgeParamType(enumNames, interfaceNames, abstractNames, dataClassNames, sealedNames)}" }
        val callArgs  = fn.params.joinToString(", ") { it.type.toCallArg(it.name, enumNames, interfaceNames, abstractNames, dataClassNames, sealedNames) }
        if (!isInstanceBased && fn.params.isEmpty()) {
            sb.appendLine("""    Function("${fn.name}") {""")
            if (fn.isPropertyGetter) {
                sb.appendLine("      $instanceExpr.${fn.name}$ret")
            } else {
                sb.appendLine("      $instanceExpr.${fn.name}()$ret")
            }
        } else {
            val paramList = if (isInstanceBased)
                if (ownParams.isEmpty()) "instanceId: String" else "instanceId: String, $ownParams"
            else ownParams
            sb.appendLine("""    Function("${fn.name}") { $paramList ->""")
            sb.appendLine("      $instanceExpr.${fn.name}($callArgs)$ret")
        }
        sb.appendLine("    }")
        return sb.toString()
    }

    /**
     * Emits one Expo `AsyncFunction("name") { ... }` block for a [FunctionKind.SUSPEND] KMP
     * function.
     *
     * Delegates to the generated `launchSettled` helper (see [settledLaunchHelper]), which runs
     * the suspend call on the owning `CoroutineScope` (module-level, per-instance holder, or
     * interface [registryName] holder) and guarantees the trailing `promise: Promise` parameter
     * settles exactly once — resolved with the result, or rejected with an
     * `<FUNCTION_NAME>_ERROR` code on exception or scope cancellation (e.g. `destroy()` during
     * an in-flight call).
     *
     * @return the generated `AsyncFunction(...)` block, or a `// BRIDGE SKIPPED` comment line if
     *         the effective parameter count exceeds [MAX_EXPO_FUNCTION_PARAMS].
     */
    private fun suspendFunction(
        fn: KmpFunction,
        callTarget: String,
        enumNames: Set<String>,
        dataClassNames: Set<String> = emptySet(),
        sealedNames: Set<String> = emptySet(),
        onSkip: (String) -> Unit = {},
        isInstanceBased: Boolean = false,
        useHolder: Boolean = false,
        registryName: String? = null,
        interfaceNames: Set<String> = emptySet(),
        abstractNames: Set<String> = emptySet(),
    ): String {
        val effectiveParamCount = fn.params.size + if (isInstanceBased) 1 else 0
        if (effectiveParamCount > MAX_EXPO_FUNCTION_PARAMS) {
            val msg = "BRIDGE SKIPPED: ${fn.name}(${fn.params.size} params) — Expo AsyncFunction DSL supports max $MAX_EXPO_FUNCTION_PARAMS parameters."
            onSkip(msg)
            return "    // $msg\n"
        }
        if (fn.usesNonStringKeyMap()) {
            val msg = "BRIDGE SKIPPED: ${fn.name}() — Map with non-String keys is not bridgeable (JS objects are string-keyed)."
            onSkip(msg)
            return "    // $msg\n"
        }
        val sb       = StringBuilder()
        val errorTag = "${fn.name.toSnakeUpperCase()}_ERROR"
        val ret      = fn.returnType.toReturnSuffix(enumNames, dataClassNames, sealedNames, interfaceNames, abstractNames)
        val ownParams = fn.params.map { "${it.name}: ${it.type.toBridgeParamType(enumNames, interfaceNames, abstractNames, dataClassNames, sealedNames)}" }
        val allParams = (if (isInstanceBased) listOf("instanceId: String") else emptyList()) + ownParams + listOf("promise: Promise")
        val paramList = allParams.joinToString(", ")
        val callArgs  = fn.params.joinToString(", ") { it.type.toCallArg(it.name, enumNames, interfaceNames, abstractNames, dataClassNames, sealedNames) }

        sb.appendLine("""    AsyncFunction("${fn.name}") { $paramList ->""")
        if (registryName != null) {
            sb.appendLine("      val holder = $registryName.get(instanceId)")
            sb.appendLine("""      launchSettled(holder.scope, promise, "$errorTag") { holder.instance.${fn.name}($callArgs)$ret }""")
        } else if (useHolder) {
            sb.appendLine("      val holder = instances[instanceId] ?: error(\"Instance not found: \$instanceId\")")
            sb.appendLine("""      launchSettled(holder.scope, promise, "$errorTag") { holder.instance.${fn.name}($callArgs)$ret }""")
        } else {
            val instanceExpr = if (isInstanceBased) "(instances[instanceId] ?: error(\"Instance not found: \$instanceId\"))" else callTarget
            sb.appendLine("""      launchSettled(scope, promise, "$errorTag") { $instanceExpr.${fn.name}($callArgs)$ret }""")
        }
        sb.appendLine("    }")
        return sb.toString()
    }

    /**
     * The generated per-module `launchSettled` helper: launches [block] on the given scope and
     * guarantees the promise settles exactly once — including when the scope is cancelled
     * before the coroutine runs (a plain `scope.launch` would silently drop the block and leave
     * the JS promise pending forever) or while it is suspended.
     */
    private fun settledLaunchHelper(): String = buildString {
        appendLine("  private fun launchSettled(scope: CoroutineScope, promise: Promise, errorTag: String, block: suspend () -> Any?) {")
        appendLine("    val settled = AtomicBoolean(false)")
        appendLine("    val job = scope.launch {")
        appendLine("      try {")
        appendLine("        val result = block()")
        appendLine("        if (settled.compareAndSet(false, true)) promise.resolve(result)")
        appendLine("      } catch (e: Exception) {")
        appendLine("        if (settled.compareAndSet(false, true)) promise.reject(errorTag, e.message, e)")
        appendLine("      }")
        appendLine("    }")
        appendLine("    job.invokeOnCompletion { cause ->")
        appendLine("      if (cause != null && settled.compareAndSet(false, true)) {")
        appendLine("        promise.reject(errorTag, \"Cancelled: \${cause.message}\", Exception(cause))")
        appendLine("      }")
        appendLine("    }")
        append("  }")
    }

    /**
     * Emits the `start<Name>` / `stop<Name>` Expo `Function` pair for a [FunctionKind.FLOW] KMP
     * function.
     *
     * `start<Name>` cancels any existing collection job for this flow key, launches a new
     * coroutine that collects the Kotlin `Flow` and forwards each element as an `on<Name>Update`
     * event (converting enums/data classes/sealed classes to their wire representation where
     * applicable), and tracks the job by flow key so `stop<Name>` (or `destroy`) can cancel it.
     * Instance-based declarations key jobs per instance via [useHolder] / [registryName].
     *
     * Function parameters are threaded through `start<Name>` and passed to the underlying flow
     * call; `stop<Name>` never takes them (it cancels by flow key regardless of start args).
     *
     * @return the generated block, or a `// BRIDGE SKIPPED` comment line if the effective
     *         parameter count (including the synthetic `instanceId`) exceeds
     *         [MAX_EXPO_FUNCTION_PARAMS].
     */
    private fun flowFunctions(
        fn: KmpFunction,
        callTarget: String,
        enumNames: Set<String>,
        dataClassNames: Set<String> = emptySet(),
        sealedNames: Set<String> = emptySet(),
        useHolder: Boolean = false,
        registryName: String? = null,
        interfaceNames: Set<String> = emptySet(),
        abstractNames: Set<String> = emptySet(),
        onSkip: (String) -> Unit = {},
    ): String {
        val isInstanceBased = useHolder || registryName != null
        val effectiveParamCount = fn.params.size + if (isInstanceBased) 1 else 0
        if (effectiveParamCount > MAX_EXPO_FUNCTION_PARAMS) {
            val msg = "BRIDGE SKIPPED: ${fn.name}(${fn.params.size} params) — Expo Function DSL supports max $MAX_EXPO_FUNCTION_PARAMS parameters."
            onSkip(msg)
            return "    // $msg\n"
        }
        if (fn.usesNonStringKeyMap()) {
            val msg = "BRIDGE SKIPPED: ${fn.name}() — Map with non-String keys is not bridgeable (JS objects are string-keyed)."
            onSkip(msg)
            return "    // $msg\n"
        }
        val sb        = StringBuilder()
        val base      = fn.flowBaseName
        val Cap       = base.cap()
        val eventName = "on${Cap}Update"
        val enumKey   = if (registryName != null) "$registryName.FlowKey.${base.toSnakeUpperCase()}" else "FlowKey.${base.toSnakeUpperCase()}"
        val emit = fn.returnType.toJsElemConversion("value", enumNames, dataClassNames, sealedNames) ?: "value"
        val ownParams = fn.params.joinToString(", ") { "${it.name}: ${it.type.toBridgeParamType(enumNames, interfaceNames, abstractNames, dataClassNames, sealedNames)}" }
        val callArgs  = fn.params.joinToString(", ") { it.type.toCallArg(it.name, enumNames, interfaceNames, abstractNames, dataClassNames, sealedNames) }
        // A Flow-typed property is read as a property access, not a call.
        val invoke    = if (fn.isPropertyGetter) "" else "($callArgs)"

        if (registryName != null) {
            val paramList = if (ownParams.isEmpty()) "instanceId: String" else "instanceId: String, $ownParams"
            sb.appendLine("""    Function("start$Cap") { $paramList ->""")
            sb.appendLine("      val holder = $registryName.get(instanceId)")
            sb.appendLine("      holder.flowJobs[$enumKey]?.cancel()")
            sb.appendLine("      holder.flowJobs[$enumKey] = holder.scope.launch {")
            sb.appendLine("        holder.instance.${fn.name}$invoke.collect { value ->")
            sb.appendLine("""          sendEvent("$eventName", mapOf("instanceId" to instanceId, "value" to $emit))""")
            sb.appendLine("        }")
            sb.appendLine("      }")
            sb.appendLine("    }")
            sb.appendLine()
            sb.appendLine("""    Function("stop$Cap") { instanceId: String ->""")
            sb.appendLine("      val holder = $registryName.get(instanceId)")
            sb.appendLine("      holder.flowJobs[$enumKey]?.cancel()")
            sb.appendLine("      holder.flowJobs.remove($enumKey)")
            sb.appendLine("    }")
        } else if (useHolder) {
            val paramList = if (ownParams.isEmpty()) "instanceId: String" else "instanceId: String, $ownParams"
            sb.appendLine("""    Function("start$Cap") { $paramList ->""")
            sb.appendLine("      val holder = instances[instanceId] ?: error(\"Instance not found: \$instanceId\")")
            sb.appendLine("      holder.flowJobs[$enumKey]?.cancel()")
            sb.appendLine("      holder.flowJobs[$enumKey] = holder.scope.launch {")
            sb.appendLine("        holder.instance.${fn.name}$invoke.collect { value ->")
            sb.appendLine("""          sendEvent("$eventName", mapOf("instanceId" to instanceId, "value" to $emit))""")
            sb.appendLine("        }")
            sb.appendLine("      }")
            sb.appendLine("    }")
            sb.appendLine()
            sb.appendLine("""    Function("stop$Cap") { instanceId: String ->""")
            sb.appendLine("      val holder = instances[instanceId] ?: return@Function")
            sb.appendLine("      holder.flowJobs[$enumKey]?.cancel()")
            sb.appendLine("      holder.flowJobs.remove($enumKey)")
            sb.appendLine("    }")
        } else {
            if (ownParams.isEmpty()) {
                sb.appendLine("""    Function("start$Cap") {""")
            } else {
                sb.appendLine("""    Function("start$Cap") { $ownParams ->""")
            }
            sb.appendLine("      flowJobs[$enumKey]?.cancel()")
            sb.appendLine("      flowJobs[$enumKey] = scope.launch {")
            sb.appendLine("        $callTarget.${fn.name}$invoke.collect { value ->")
            sb.appendLine("""          sendEvent("$eventName", mapOf("value" to $emit))""")
            sb.appendLine("        }")
            sb.appendLine("      }")
            sb.appendLine("    }")
            sb.appendLine()
            sb.appendLine("""    Function("stop$Cap") {""")
            sb.appendLine("      flowJobs[$enumKey]?.cancel()")
            sb.appendLine("      flowJobs.remove($enumKey)")
            sb.appendLine("    }")
        }
        return sb.toString()
    }

    // ── Element-wise wire conversion (shared by returns, flows, record codecs, events) ──

    /**
     * The KMP → JS wire conversion for a value expression [v] of this type, or `null` when the
     * value crosses as-is. Handles enums (`.name`), data/sealed classes (`.toRecord()`), `Char`
     * (`.toString()`), `Set` (`.toList()` — JS has no Set on the wire), and — recursively —
     * collections of any of those. When [forRecordField] is set, the numeric widenings required
     * by generated Record field types (`Long`→`Double`, `Byte`/`Short`→`Int`) apply too.
     *
     * Nullability is respected at every level via `?.`.
     */
    private fun KmpTypeRef.toJsElemConversion(
        v: String,
        enumNames: Set<String>,
        dataClassNames: Set<String>,
        sealedNames: Set<String>,
        forRecordField: Boolean = false,
        depth: Int = 0,
    ): String? {
        val q = if (isNullable) "?" else ""
        return when {
            this is KmpTypeRef.Primitive && kind == PrimitiveKind.CHAR -> "$v$q.toString()"
            forRecordField && this is KmpTypeRef.Primitive && kind == PrimitiveKind.LONG  -> "$v$q.toDouble()"
            forRecordField && this is KmpTypeRef.Primitive && kind == PrimitiveKind.BYTE  -> "$v$q.toInt()"
            forRecordField && this is KmpTypeRef.Primitive && kind == PrimitiveKind.SHORT -> "$v$q.toInt()"
            this is KmpTypeRef.ClassRef && simpleName in enumNames -> "$v$q.name"
            this is KmpTypeRef.ClassRef && (simpleName in dataClassNames || simpleName in sealedNames) -> "$v$q.toRecord()"
            // Generic (type-erased) positions: static conversion is impossible — convert by
            // the value's runtime type via the generated __toWire helper.
            this is KmpTypeRef.TypeParam -> "__toWire($v)"
            this is KmpTypeRef.CollectionType -> {
                val e = "e$depth"
                when (kind) {
                    CollectionKind.LIST -> typeArgs.getOrNull(0)?.typeOrNull()
                        ?.toJsElemConversion(e, enumNames, dataClassNames, sealedNames, forRecordField, depth + 1)
                        ?.let { "$v$q.map { $e -> $it }" }
                    CollectionKind.SET -> {
                        val inner = typeArgs.getOrNull(0)?.typeOrNull()
                            ?.toJsElemConversion(e, enumNames, dataClassNames, sealedNames, forRecordField, depth + 1)
                        if (inner != null) "$v$q.map { $e -> $inner }" else "$v$q.toList()"
                    }
                    CollectionKind.MAP -> typeArgs.getOrNull(1)?.typeOrNull()
                        ?.toJsElemConversion(e, enumNames, dataClassNames, sealedNames, forRecordField, depth + 1)
                        ?.let { "$v$q.mapValues { (_, $e) -> $it }" }
                }
            }
            else -> null
        }
    }

    /**
     * The JS → KMP conversion for a wire value expression [v] (typed by [toRecordFieldType] /
     * [toBridgeParamType]), or `null` when the wire value already is the KMP type. Inverse of
     * [toJsElemConversion]: enum-name `valueOf`, Record `.toKmp()`, `String`→`Char`, numeric
     * narrowings, `List`→`Set` restoration, and element-wise collection conversion.
     */
    private fun KmpTypeRef.toKmpElemConversion(
        v: String,
        enumNames: Set<String>,
        dataClassNames: Set<String>,
        sealedNames: Set<String>,
        depth: Int = 0,
    ): String? {
        val q = if (isNullable) "?" else ""
        return when {
            this is KmpTypeRef.Primitive && kind == PrimitiveKind.CHAR ->
                if (isNullable) "$v?.firstOrNull()" else "$v.first()"
            this is KmpTypeRef.Primitive && kind == PrimitiveKind.LONG  -> "$v$q.toLong()"
            this is KmpTypeRef.Primitive && kind == PrimitiveKind.BYTE  -> "$v$q.toByte()"
            this is KmpTypeRef.Primitive && kind == PrimitiveKind.SHORT -> "$v$q.toShort()"
            this is KmpTypeRef.ClassRef && simpleName in enumNames ->
                if (isNullable) "$v?.let { ${simpleName}.valueOf(it) }" else "${simpleName}.valueOf($v)"
            this is KmpTypeRef.ClassRef && (simpleName in dataClassNames || simpleName in sealedNames) -> "$v$q.toKmp()"
            this is KmpTypeRef.CollectionType -> {
                val e = "e$depth"
                when (kind) {
                    CollectionKind.LIST -> typeArgs.getOrNull(0)?.typeOrNull()
                        ?.toKmpElemConversion(e, enumNames, dataClassNames, sealedNames, depth + 1)
                        ?.let { "$v$q.map { $e -> $it }" }
                    CollectionKind.SET -> {
                        val inner = typeArgs.getOrNull(0)?.typeOrNull()
                            ?.toKmpElemConversion(e, enumNames, dataClassNames, sealedNames, depth + 1)
                        if (inner != null) "$v$q.map { $e -> $inner }$q.toSet()" else "$v$q.toSet()"
                    }
                    CollectionKind.MAP -> typeArgs.getOrNull(1)?.typeOrNull()
                        ?.toKmpElemConversion(e, enumNames, dataClassNames, sealedNames, depth + 1)
                        ?.let { "$v$q.mapValues { (_, $e) -> $it }" }
                }
            }
            else -> null
        }
    }

    /**
     * Wraps a non-identity conversion of a whole value into a `.let { r0 -> ... }` suffix, so
     * callers can append it to a call/field expression. Returns `""` for identity conversions.
     */
    private fun KmpTypeRef.toLetSuffix(conversion: (KmpTypeRef, String) -> String?): String {
        // The receiver inside .let is non-null even when the reference itself is nullable.
        val nonNull = withoutNullability()
        val inner = conversion(nonNull, "r0") ?: return ""
        return if (isNullable) "?.let { r0 -> $inner }" else ".let { r0 -> $inner }"
    }

    /** This type reference with `nullable = false`, other properties unchanged. */
    private fun KmpTypeRef.withoutNullability(): KmpTypeRef = when (this) {
        is KmpTypeRef.Primitive      -> copy(nullable = false)
        is KmpTypeRef.UnitType       -> copy(nullable = false)
        is KmpTypeRef.CollectionType -> copy(nullable = false)
        is KmpTypeRef.FlowType       -> copy(nullable = false)
        is KmpTypeRef.ClassRef       -> copy(nullable = false)
        is KmpTypeRef.TypeParam      -> copy(nullable = false)
    }

    /**
     * The runtime-typed wire converter emitted into files that bridge generic (type-erased)
     * positions: the static type is unknown (`T` erased to `Any`), so conversion dispatches on
     * the value's actual class — data/sealed classes to Records, enums to case names,
     * collections element-wise; primitives and anything unknown pass through.
     */
    private fun wireHelper(
        enumNames: Set<String>,
        dataClassNames: Set<String>,
        sealedNames: Set<String>,
    ): String = buildString {
        appendLine("private fun __toWire(value: Any?): Any? = when (value) {")
        for (n in (dataClassNames + sealedNames).sorted()) appendLine("    is $n -> value.toRecord()")
        for (n in enumNames.sorted()) appendLine("    is $n -> value.name")
        appendLine("    is List<*> -> value.map { __toWire(it) }")
        appendLine("    is Set<*> -> value.map { __toWire(it) }")
        appendLine("    is Map<*, *> -> value.mapValues { (_, v) -> __toWire(v) }")
        appendLine("    else -> value")
        append("}")
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
        this is KmpTypeRef.Primitive && kind == PrimitiveKind.BYTE ->
            if (nullable) "$fieldName?.toInt()" else "$fieldName.toInt()"
        this is KmpTypeRef.Primitive && kind == PrimitiveKind.SHORT ->
            if (nullable) "$fieldName?.toInt()" else "$fieldName.toInt()"
        this is KmpTypeRef.Primitive && kind == PrimitiveKind.CHAR ->
            if (nullable) "$fieldName?.toString()" else "$fieldName.toString()"
        this is KmpTypeRef.Primitive -> fieldName
        this is KmpTypeRef.ClassRef && simpleName in enumNames ->
            if (nullable) "$fieldName?.name" else "$fieldName.name"
        this is KmpTypeRef.ClassRef && (simpleName in dataClassNames || simpleName in sealedNames) ->
            if (nullable) "$fieldName?.toRecord()" else "$fieldName.toRecord()"
        this is KmpTypeRef.CollectionType ->
            fieldName + toLetSuffix { t, v -> t.toJsElemConversion(v, enumNames, dataClassNames, sealedNames, forRecordField = true, depth = 1) }
        else -> fieldName
    }

    // ── Sealed Record field type (always nullable) ────────────────────────────

    /**
     * Maps a KMP field type to its nullable Kotlin type in a sealed class's flat `Record`.
     *
     * Every field in a sealed Record is nullable regardless of the original field's
     * nullability, since a given field is only populated for the variant(s) that declare it —
     * see [generateSealedCodec]. Delegates to [toRecordFieldType] so sealed-record fields use
     * exactly the same wire types as data-class Record fields.
     */
    private fun KmpTypeRef.toSealedRecordFieldType(
        enumNames: Set<String>,
        dataClassNames: Set<String>,
        sealedNames: Set<String>,
    ): String = toRecordFieldType(enumNames, dataClassNames, sealedNames).trimEnd('?') + "?"

    // ── Extract from nullable sealed Record field → KMP type ──────────────────

    /**
     * Produces the expression that extracts a KMP-typed value from a nullable sealed Record
     * field.
     *
     * If the original KMP field was itself nullable, the Record value passes through unchanged.
     * Otherwise a Kotlin-appropriate fallback default (`""`, `0`, `false`, `emptyList()`, ...) is
     * substituted for a missing/null Record value, since [toSealedRecordFieldType] widens every
     * field to nullable.
     */
    private fun KmpTypeRef.fromSealedRecordField(
        fieldName: String,
        enumNames: Set<String>,
        dataClassNames: Set<String>,
        sealedNames: Set<String>,
    ): String {
        // Original nullable → the Record field is nullable too; convert with ?-chaining
        // (identical to a nullable data-class Record field).
        if (isNullable) return toKmpFieldConversion(fieldName, enumNames, dataClassNames, sealedNames)
        return when {
            this is KmpTypeRef.Primitive -> when (kind) {
                PrimitiveKind.STRING  -> "$fieldName ?: \"\""
                PrimitiveKind.INT     -> "$fieldName ?: 0"
                PrimitiveKind.LONG    -> "$fieldName?.toLong() ?: 0L"
                PrimitiveKind.DOUBLE  -> "$fieldName ?: 0.0"
                PrimitiveKind.FLOAT   -> "$fieldName ?: 0f"
                PrimitiveKind.BOOLEAN -> "$fieldName ?: false"
                PrimitiveKind.BYTE    -> "$fieldName?.toByte() ?: 0"
                PrimitiveKind.SHORT   -> "$fieldName?.toShort() ?: 0"
                PrimitiveKind.CHAR    -> "($fieldName ?: \"\").firstOrNull() ?: ' '"
            }
            this is KmpTypeRef.ClassRef && simpleName in enumNames ->
                "$simpleName.valueOf($fieldName ?: \"\")"
            this is KmpTypeRef.ClassRef && (simpleName in dataClassNames || simpleName in sealedNames) ->
                "($fieldName ?: ${simpleName}Record()).toKmp()"
            this is KmpTypeRef.CollectionType -> {
                val fallback = if (kind == CollectionKind.MAP) "emptyMap()" else "emptyList()"
                val inner = withoutNullability().toKmpElemConversion("r0", enumNames, dataClassNames, sealedNames, depth = 1)
                if (inner == null) "$fieldName ?: $fallback"
                else "($fieldName ?: $fallback).let { r0 -> $inner }"
            }
            else -> "$fieldName ?: null"
        }
    }

    // ── Variant helpers ───────────────────────────────────────────────────────

    /** The simple declared name of this sealed class variant. */
    private fun KmpVariant.variantName(): String = when (this) {
        is KmpVariant.DataVariant   -> name
        is KmpVariant.ClassVariant  -> name
        is KmpVariant.ObjectVariant -> name
    }

    /** This variant's constructor fields, or an empty list for an [KmpVariant.ObjectVariant]. */
    private fun KmpVariant.variantFields(): List<KmpField> = when (this) {
        is KmpVariant.DataVariant   -> fields
        is KmpVariant.ClassVariant  -> fields
        is KmpVariant.ObjectVariant -> emptyList()
    }

    /** Kotlin reference to this variant's type: `Parent.Variant` when nested, bare top-level name otherwise. */
    private fun KmpVariant.variantRef(parent: String): String =
        if (isNestedVariant) "$parent.${variantName()}" else variantName()

    // ── Record type helpers ───────────────────────────────────────────────────

    /** Whether this type reference is nullable, regardless of which [KmpTypeRef] subtype it is. */
    private val KmpTypeRef.isNullable: Boolean get() = when (this) {
        is KmpTypeRef.Primitive      -> nullable
        is KmpTypeRef.ClassRef       -> nullable
        is KmpTypeRef.CollectionType -> nullable
        is KmpTypeRef.FlowType       -> nullable
        is KmpTypeRef.UnitType       -> nullable
        is KmpTypeRef.TypeParam      -> nullable
    }

    /**
     * Maps a KMP field type to the Kotlin type used for the corresponding `@Field` in a data
     * class's Expo `Record` (not the sealed-class flat Record — see [toSealedRecordFieldType]
     * for that variant).
     */
    private fun KmpTypeRef.toRecordFieldType(
        enumNames: Set<String>,
        dataClassNames: Set<String>,
        sealedNames: Set<String> = emptySet(),
    ): String {
        val q = if (isNullable) "?" else ""
        return when {
            this is KmpTypeRef.Primitive -> when (kind) {
                PrimitiveKind.STRING  -> "String"
                PrimitiveKind.INT     -> "Int"
                PrimitiveKind.LONG    -> "Double"
                PrimitiveKind.DOUBLE  -> "Double"
                PrimitiveKind.FLOAT   -> "Float"
                PrimitiveKind.BOOLEAN -> "Boolean"
                PrimitiveKind.BYTE    -> "Int"
                PrimitiveKind.SHORT   -> "Int"
                PrimitiveKind.CHAR    -> "String"
            } + q
            this is KmpTypeRef.ClassRef && simpleName in enumNames      -> "String$q"
            this is KmpTypeRef.ClassRef && (simpleName in dataClassNames || simpleName in sealedNames) -> "${simpleName}Record$q"
            this is KmpTypeRef.ClassRef                                 -> "Any?"
            this is KmpTypeRef.CollectionType -> {
                // Sets cross the wire as JS arrays — the wire type is always List.
                val inner = when (kind) {
                    CollectionKind.LIST, CollectionKind.SET -> {
                        val elem = typeArgs.getOrNull(0)?.typeOrNull()
                            ?.toRecordFieldType(enumNames, dataClassNames, sealedNames) ?: "Any?"
                        "List<$elem>"
                    }
                    CollectionKind.MAP -> {
                        val key = typeArgs.getOrNull(0)?.typeOrNull()
                            ?.toRecordFieldType(enumNames, dataClassNames, sealedNames) ?: "Any?"
                        val value = typeArgs.getOrNull(1)?.typeOrNull()
                            ?.toRecordFieldType(enumNames, dataClassNames, sealedNames) ?: "Any?"
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

    /**
     * The default value Expo's `Record` requires for a `@Field var` of this type.
     *
     * Expo `Record` fields must have a default; nullable fields default to `null`, non-nullable
     * fields get a type-appropriate zero value (`""`, `0`, `false`, `emptyList()`, a fresh nested
     * `Record()`, ...).
     */
    private fun KmpTypeRef.toRecordFieldDefault(
        enumNames: Set<String>,
        dataClassNames: Set<String>,
        sealedNames: Set<String> = emptySet(),
    ): String {
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
            this is KmpTypeRef.ClassRef && (simpleName in dataClassNames || simpleName in sealedNames) -> "${simpleName}Record()"
            this is KmpTypeRef.ClassRef                                 -> "null"
            this is KmpTypeRef.CollectionType -> when (kind) {
                CollectionKind.MAP -> "emptyMap()"
                else               -> "emptyList()"
            }
            else -> "null"
        }
    }

    /**
     * Produces the expression that converts a Record field's value into the corresponding KMP
     * field type inside `toKmp()`.
     *
     * Handles the primitive widenings JS requires (`Long`→`Double`, `Byte`/`Short`→`Int` on the
     * wire, `Char`→`String`), enum name lookups, nested data/sealed-class conversion, and —
     * via [toKmpElemConversion] — element-wise conversion for collections at any nesting depth.
     */
    private fun KmpTypeRef.toKmpFieldConversion(
        fieldName: String,
        enumNames: Set<String>,
        dataClassNames: Set<String>,
        sealedNames: Set<String> = emptySet(),
    ): String = when {
        this is KmpTypeRef.Primitive && kind == PrimitiveKind.LONG ->
            if (nullable) "$fieldName?.toLong()" else "$fieldName.toLong()"
        this is KmpTypeRef.Primitive && kind == PrimitiveKind.BYTE ->
            if (nullable) "$fieldName?.toByte()" else "$fieldName.toByte()"
        this is KmpTypeRef.Primitive && kind == PrimitiveKind.SHORT ->
            if (nullable) "$fieldName?.toShort()" else "$fieldName.toShort()"
        this is KmpTypeRef.Primitive && kind == PrimitiveKind.CHAR ->
            if (nullable) "$fieldName?.firstOrNull()" else "$fieldName.first()"
        this is KmpTypeRef.ClassRef && simpleName in enumNames ->
            if (nullable) "$fieldName?.let { ${simpleName}.valueOf(it) }"
            else "${simpleName}.valueOf($fieldName)"
        this is KmpTypeRef.ClassRef && (simpleName in dataClassNames || simpleName in sealedNames) ->
            if (nullable) "$fieldName?.toKmp()" else "$fieldName.toKmp()"
        this is KmpTypeRef.CollectionType ->
            fieldName + toLetSuffix { t, v -> t.toKmpElemConversion(v, enumNames, dataClassNames, sealedNames, depth = 1) }
        else -> fieldName
    }

    /**
     * Recursively walks a type reference (including into collection type arguments) and adds an
     * import for every enum, data class, or sealed class it references.
     *
     * @param into the mutable import set being accumulated across the whole declaration.
     */
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

    /**
     * The suffix appended to a KMP function call expression to convert its Kotlin return value
     * into a bridge-safe value.
     *
     * Enums become `.name`, data/sealed classes become `.toRecord()`, interface/abstract class
     * return values are registered in their `<Name>Registry` and returned as an opaque instance
     * id string via `.let { ... }`, and collections convert element-wise (see
     * [toJsElemConversion]). Plain primitives and `Unit` cross as-is, so this returns `""`.
     */
    private fun KmpTypeRef.toReturnSuffix(
        enumNames: Set<String>,
        dataClassNames: Set<String> = emptySet(),
        sealedNames: Set<String> = emptySet(),
        interfaceNames: Set<String> = emptySet(),
        abstractNames: Set<String> = emptySet(),
    ): String = when {
        this is KmpTypeRef.Primitive && kind == PrimitiveKind.CHAR  -> if (nullable) "?.toString()" else ".toString()"
        this is KmpTypeRef.ClassRef && simpleName in enumNames      -> if (nullable) "?.name" else ".name"
        this is KmpTypeRef.ClassRef && simpleName in dataClassNames -> if (nullable) "?.toRecord()" else ".toRecord()"
        this is KmpTypeRef.ClassRef && simpleName in sealedNames    -> if (nullable) "?.toRecord()" else ".toRecord()"
        this is KmpTypeRef.ClassRef && simpleName in interfaceNames ->
            if (nullable) "?.let { ${simpleName}Registry.register(it) }" else ".let { ${simpleName}Registry.register(it) }"
        this is KmpTypeRef.ClassRef && simpleName in abstractNames  ->
            if (nullable) "?.let { ${simpleName}Registry.register(it) }" else ".let { ${simpleName}Registry.register(it) }"
        this is KmpTypeRef.TypeParam ->
            ".let { r0 -> __toWire(r0) }"
        this is KmpTypeRef.CollectionType ->
            toLetSuffix { t, v -> t.toJsElemConversion(v, enumNames, dataClassNames, sealedNames, depth = 1) }
        else -> ""
    }

    // ── Declaration accessors ─────────────────────────────────────────────────

    /**
     * The simple name used to identify this declaration in generated code — its class/object
     * name, or the file name for a [KmpDeclaration.KmpFileScope].
     */
    private fun KmpDeclaration.declName(): String = when (this) {
        is KmpDeclaration.KmpClass       -> name
        is KmpDeclaration.KmpObject      -> name
        is KmpDeclaration.KmpDataClass   -> name
        is KmpDeclaration.KmpInterface   -> name
        is KmpDeclaration.KmpSealedClass -> name
        is KmpDeclaration.KmpEnum        -> name
        is KmpDeclaration.KmpFileScope   -> fileName
    }

    /**
     * The functions to bridge for this declaration; empty for [KmpDeclaration.KmpEnum], which
     * has no bridgeable functions of its own.
     */
    private fun KmpDeclaration.declFunctions(): List<KmpFunction> = when (this) {
        is KmpDeclaration.KmpClass       -> functions
        is KmpDeclaration.KmpObject      -> functions
        is KmpDeclaration.KmpDataClass   -> functions
        is KmpDeclaration.KmpInterface   -> functions
        is KmpDeclaration.KmpSealedClass -> functions
        is KmpDeclaration.KmpEnum        -> emptyList()
        is KmpDeclaration.KmpFileScope   -> functions
    }

    // ── Bridge param / call arg ───────────────────────────────────────────────

    /**
     * The Kotlin parameter type used in the generated `Function`/`AsyncFunction` lambda for a
     * parameter of this KMP type.
     *
     * Enums, interfaces, and abstract classes all cross the bridge as a `String` (an enum case
     * name or a registry instance id); data/sealed classes as their `<Name>Record` wire type;
     * `Long` arrives as a JS `number`, i.e. `Double`; `Char` arrives as a single-character
     * `String`; collections use their Record wire types element-wise (see [toRecordFieldType]).
     * Everything else keeps its natural Kotlin type via [toKotlinTypeName].
     */
    private fun KmpTypeRef.toBridgeParamType(
        enumNames: Set<String>,
        interfaceNames: Set<String> = emptySet(),
        abstractNames: Set<String> = emptySet(),
        dataClassNames: Set<String> = emptySet(),
        sealedNames: Set<String> = emptySet(),
    ): String = when {
        this is KmpTypeRef.ClassRef && simpleName in enumNames ->
            if (nullable) "String?" else "String"
        this is KmpTypeRef.ClassRef && simpleName in interfaceNames ->
            if (nullable) "String?" else "String"
        this is KmpTypeRef.ClassRef && simpleName in abstractNames ->
            if (nullable) "String?" else "String"
        this is KmpTypeRef.ClassRef && (simpleName in dataClassNames || simpleName in sealedNames) ->
            if (nullable) "${simpleName}Record?" else "${simpleName}Record"
        this is KmpTypeRef.Primitive && kind == PrimitiveKind.LONG ->
            if (nullable) "Double?" else "Double"
        this is KmpTypeRef.Primitive && kind == PrimitiveKind.CHAR ->
            if (nullable) "String?" else "String"
        this is KmpTypeRef.CollectionType ->
            toRecordFieldType(enumNames, dataClassNames, sealedNames)
        else -> toKotlinTypeName()
    }

    /**
     * The expression that converts a bridge parameter (as typed by [toBridgeParamType]) back
     * into the real KMP type expected by the underlying function call.
     *
     * Mirrors [toBridgeParamType]: reverses the enum-name / registry-id / Record / collection /
     * numeric-widening conversions applied on the way in.
     *
     * @param paramName the generated parameter's identifier in the emitted lambda.
     */
    private fun KmpTypeRef.toCallArg(
        paramName: String,
        enumNames: Set<String>,
        interfaceNames: Set<String> = emptySet(),
        abstractNames: Set<String> = emptySet(),
        dataClassNames: Set<String> = emptySet(),
        sealedNames: Set<String> = emptySet(),
    ): String = when {
        this is KmpTypeRef.ClassRef && simpleName in enumNames ->
            if (nullable) "$paramName?.let { ${simpleName}.valueOf(it) }"
            else "${simpleName}.valueOf($paramName)"
        this is KmpTypeRef.ClassRef && simpleName in interfaceNames ->
            if (nullable) "$paramName?.let { ${simpleName}Registry.get(it).instance }"
            else "${simpleName}Registry.get($paramName).instance"
        this is KmpTypeRef.ClassRef && simpleName in abstractNames ->
            if (nullable) "$paramName?.let { ${simpleName}Registry.get(it).instance }"
            else "${simpleName}Registry.get($paramName).instance"
        this is KmpTypeRef.ClassRef && (simpleName in dataClassNames || simpleName in sealedNames) ->
            if (nullable) "$paramName?.toKmp()" else "$paramName.toKmp()"
        this is KmpTypeRef.Primitive && kind == PrimitiveKind.LONG ->
            if (nullable) "$paramName?.toLong()" else "$paramName.toLong()"
        this is KmpTypeRef.Primitive && kind == PrimitiveKind.CHAR ->
            if (nullable) "$paramName?.firstOrNull()" else "$paramName.first()"
        this is KmpTypeRef.CollectionType ->
            paramName + toLetSuffix { t, v -> t.toKmpElemConversion(v, enumNames, dataClassNames, sealedNames, depth = 1) }
        else -> paramName
    }

    /**
     * The plain Kotlin type name for this type reference, as it would appear in KMP source —
     * used where no bridge-specific conversion applies (e.g. inside a JS-implemented interface's
     * anonymous object, or as the fallback branch of [toBridgeParamType]).
     *
     * Generic type parameters and `Flow<T>` both erase to `Any`/`Any?`, since Android generation
     * has no way to recover the concrete type argument.
     */
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

    /** The unqualified Kotlin type name for this primitive kind (e.g. [PrimitiveKind.INT] → `"Int"`). */
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

    /** The unqualified Kotlin collection interface name for this kind (e.g. [CollectionKind.LIST] → `"List"`). */
    private fun CollectionKind.toClassName(): String = when (this) {
        CollectionKind.LIST -> "List"
        CollectionKind.MAP  -> "Map"
        CollectionKind.SET  -> "Set"
    }

    // ── Task 5 helpers ────────────────────────────────────────────────────────

    /**
     * The wire contract for a JS-implemented interface's suspend return value: the Kotlin type
     * of the `resolve<Fn>` bridge function's `result` parameter, paired with the expression that
     * converts the completed deferred's `Any?` value back to the real KMP return type.
     *
     * `resolve<Fn>` completes the deferred with exactly the declared parameter type (Expo
     * converts the JS value into it), so the cast side must expect that same type — both sides
     * live in this one `when` so they cannot drift. Nullable returns use safe casts so a JS
     * `null` resolves to `null` instead of throwing.
     *
     * @return `null` param type for [KmpTypeRef.UnitType], since a `resolve<Fn>` for a
     *         `Unit`-returning function takes no result parameter at all.
     */
    private fun KmpTypeRef.resolveWireContract(
        enumNames: Set<String>,
        dataClassNames: Set<String>,
        sealedNames: Set<String>,
    ): Pair<String?, String> {
        val q = if (isNullable) "?" else ""
        fun cast(wireType: String, convert: String = "") =
            if (isNullable) "(deferred.await() as? $wireType)?$convert".removeSuffix("?")
            else "(deferred.await() as $wireType)$convert"
        return when {
            this is KmpTypeRef.UnitType -> null to "deferred.await()"
            this is KmpTypeRef.Primitive -> when (kind) {
                PrimitiveKind.STRING  -> "String$q"  to cast("String")
                PrimitiveKind.BOOLEAN -> "Boolean$q" to cast("Boolean")
                PrimitiveKind.INT     -> "Int$q"     to cast("Int")
                PrimitiveKind.LONG    -> "Double$q"  to cast("Double", ".toLong()")
                PrimitiveKind.DOUBLE  -> "Double$q"  to cast("Double")
                PrimitiveKind.FLOAT   -> "Float$q"   to cast("Float")
                PrimitiveKind.BYTE    -> "Int$q"     to cast("Int", ".toByte()")
                PrimitiveKind.SHORT   -> "Int$q"     to cast("Int", ".toShort()")
                PrimitiveKind.CHAR    ->
                    if (isNullable) "String?" to "(deferred.await() as? String)?.firstOrNull()"
                    else "String" to "(deferred.await() as String).first()"
            }
            this is KmpTypeRef.ClassRef && simpleName in enumNames ->
                if (isNullable) "String?" to "(deferred.await() as? String)?.let { ${simpleName}.valueOf(it) }"
                else "String" to "${simpleName}.valueOf(deferred.await() as String)"
            this is KmpTypeRef.ClassRef && (simpleName in dataClassNames || simpleName in sealedNames) ->
                "${simpleName}Record$q" to cast("${simpleName}Record", ".toKmp()")
            else -> "Any?" to "deferred.await()"
        }
    }

    // ── Enum reference detection ──────────────────────────────────────────────

    /**
     * Whether any parameter or the return type of this function references [enumName],
     * including inside a collection or Flow element type.
     *
     * Used to scope a generated file's imports to only the enums actually used by its functions.
     */
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

/** Uppercases the first character, e.g. for turning a lowerCamelCase name into a type name. */
private fun String.cap()  = replaceFirstChar { it.uppercase() }

/** Lowercases the first character, e.g. for turning a type name into a lowerCamelCase call target. */
private fun String.decap() = replaceFirstChar { it.lowercase() }

/** Converts a camelCase identifier to `SCREAMING_SNAKE_CASE`, for enum case / error-tag names. */
private fun String.toSnakeUpperCase() = replace(Regex("([A-Z])"), "_$1").uppercase().trimStart('_')

// Expo's Function/AsyncFunction DSL has overloads up to this many parameters.
// Functions with more params are skipped with a comment in the generated output.
private const val MAX_EXPO_FUNCTION_PARAMS = 8

private const val HEADER = "// AUTO-GENERATED by shared-artifacts bridge generator. DO NOT EDIT.\n" +
    "// Re-run `bash scripts/push-bridges.sh` from shared-artifacts to regenerate."
