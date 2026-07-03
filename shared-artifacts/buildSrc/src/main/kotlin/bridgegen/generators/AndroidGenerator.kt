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
            val (imports, body) = generateRecord(record, module, kmpPackageName)
            allImports.addAll(imports)
            recordBodies.add(body)
        }

        for (sealed in sealeds) {
            val (imports, body) = generateSealedCodec(sealed, kmpPackageName, enumNames, dataClassNames, sealedNames)
            allImports.addAll(imports)
            sealedBodies.add(body)
        }

        val takenNames = modules.filter { it !is KmpDeclaration.KmpFileScope }.map { it.declName() }.toSet()
        for (decl in modules) {
            val nameOverride = if (decl is KmpDeclaration.KmpFileScope && decl.fileName in takenNames) "${decl.fileName}Kt" else null
            val (imports, body) = buildModuleBody(decl, module, kmpPackageName, enumNames, dataClassNames, sealedNames, onSkip, moduleNameOverride = nameOverride)
            allImports.addAll(imports)
            moduleBodies.add(body)
        }

        for (decl in interfaceDecls) {
            val (imports, body) = buildInterfaceModuleBody(decl, module, kmpPackageName, enumNames, dataClassNames, sealedNames, onSkip)
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
        module: KmpModule,
        kmpPackageName: String,
        enumNames: Set<String>,
        dataClassNames: Set<String>,
        sealedNames: Set<String>,
        onSkip: (String) -> Unit = {},
        moduleNameOverride: String? = null,
    ): Pair<Set<String>, String> {
        val name        = moduleNameOverride ?: decl.declName()
        val functions   = decl.declFunctions()
        val isObject    = decl is KmpDeclaration.KmpObject
        val isFileScope = decl is KmpDeclaration.KmpFileScope
        val isInstanceBased = !isObject && !isFileScope

        val flows      = functions.filter { it.kind == FunctionKind.FLOW }
        val hasSuspend = functions.any { it.kind == FunctionKind.SUSPEND }
        val hasFlows   = flows.isNotEmpty()
        val eventNames = flows.map { "on${it.flowBaseName.cap()}Update" }
        val usedEnums  = enumNames.filter { eName -> functions.any { fn -> fn.referencesEnum(eName) } }
        val interfaceNames = module.declarations.filterIsInstance<KmpDeclaration.KmpInterface>().map { it.name }.toSet()
        val abstractNames  = module.declarations.filterIsInstance<KmpDeclaration.KmpClass>().filter { it.isAbstract }.map { it.name }.toSet()
        // callTarget: object → type name (singleton), file scope → package name (FQN call), class → unused (uses instance map)
        val callTarget = when {
            isObject    -> name
            isFileScope -> kmpPackageName
            else        -> name.decap()
        }

        val imports = mutableSetOf<String>()
        if (!isFileScope) imports.add("$kmpPackageName.$name")
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
                FunctionKind.FLOW    -> sb.append(flowFunctions(fn, callTarget, enumNames, dataClassNames, sealedNames, useHolder = useHolder))
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
        module: KmpModule,
        kmpPackageName: String,
        enumNames: Set<String>,
        dataClassNames: Set<String>,
        sealedNames: Set<String>,
        onSkip: (String) -> Unit = {},
    ): Pair<Set<String>, String> {
        val name = decl.declName()
        val functions = decl.declFunctions()
        val registryName = "${name}Registry"

        val flows = functions.filter { it.kind == FunctionKind.FLOW }
        val hasSuspend = functions.any { it.kind == FunctionKind.SUSPEND }
        val hasFlows = flows.isNotEmpty()
        val eventNames = flows.map { "on${it.flowBaseName.cap()}Update" } +
            functions.filter { it.kind == FunctionKind.SUSPEND }.map { "call${it.name.cap()}" }
        val usedEnums = enumNames.filter { eName -> functions.any { fn -> fn.referencesEnum(eName) } }
        val interfaceNames = module.declarations.filterIsInstance<KmpDeclaration.KmpInterface>().map { it.name }.toSet()
        val abstractNames  = module.declarations.filterIsInstance<KmpDeclaration.KmpClass>().filter { it.isAbstract }.map { it.name }.toSet()

        val imports = mutableSetOf<String>()
        imports.add("$kmpPackageName.$name")
        for (eName in usedEnums) imports.add("$kmpPackageName.$eName")
        imports.add("expo.modules.kotlin.modules.Module")
        imports.add("expo.modules.kotlin.modules.ModuleDefinition")
        if (hasSuspend) imports.add("expo.modules.kotlin.Promise")
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
                FunctionKind.FLOW    -> sb.append(flowFunctions(fn, "", enumNames, dataClassNames, sealedNames, useHolder = false, registryName = registryName))
            }
        }

        // ── Task 5: JS-implemented interface ─────────────────────────────────────
        val isAbstractClass = decl is KmpDeclaration.KmpClass && (decl as KmpDeclaration.KmpClass).isAbstract
        val implBase = if (isAbstractClass) "$name()" else name
        val suspendFns = functions.filter { it.kind == FunctionKind.SUSPEND }

        sb.appendLine()
        sb.appendLine("""    Function("create") {""")
        sb.appendLine("      val instanceId = UUID.randomUUID().toString()")
        sb.appendLine("      val emitEvent = { eventName: String, body: Map<String, Any?> -> sendEvent(eventName, body) }")
        sb.appendLine("      val impl = object : $implBase {")
        for (fn in functions) {
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
                        for (p in fn.params) append(""", "${p.name}" to ${p.name}""")
                    }
                    val (_, castExpr) = fn.returnType.resolveWireContract(enumNames, dataClassNames, sealedNames)
                    sb.appendLine("        override suspend fun ${fn.name}($pList): $retT {")
                    sb.appendLine("          val callId = UUID.randomUUID().toString()")
                    sb.appendLine("          val deferred = CompletableDeferred<Any?>()")
                    sb.appendLine("          ${registryName}.get(instanceId).pendingCalls[callId] = deferred")
                    sb.appendLine("""          emitEvent("$eventName", mapOf($paramMapEntries))""")
                    if (isUnit) sb.appendLine("          deferred.await()")
                    else        sb.appendLine("          return $castExpr")
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
        val sb  = StringBuilder()
        val ret = fn.returnType.toReturnSuffix(enumNames, dataClassNames, sealedNames, interfaceNames, abstractNames)
        val instanceExpr = when {
            registryName != null -> "$registryName.get(instanceId).instance"
            useHolder      -> "(instances[instanceId] ?: error(\"Instance not found: \$instanceId\")).instance"
            isInstanceBased -> "(instances[instanceId] ?: error(\"Instance not found: \$instanceId\"))"
            else            -> callTarget
        }
        val ownParams = fn.params.joinToString(", ") { "${it.name}: ${it.type.toBridgeParamType(enumNames, interfaceNames, abstractNames)}" }
        val callArgs  = fn.params.joinToString(", ") { it.type.toCallArg(it.name, enumNames, interfaceNames, abstractNames) }
        sb.append(formatComment(fn.docComment))
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
     * Launches the suspend call on the owning `CoroutineScope` (module-level, per-instance
     * holder, or interface [registryName] holder) and resolves/rejects the trailing
     * `promise: Promise` parameter with the result or a caught exception, tagged with an
     * `<FUNCTION_NAME>_ERROR` code.
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
        val sb       = StringBuilder()
        val errorTag = "${fn.name.toSnakeUpperCase()}_ERROR"
        val ret      = fn.returnType.toReturnSuffix(enumNames, dataClassNames, sealedNames, interfaceNames, abstractNames)
        val ownParams = fn.params.map { "${it.name}: ${it.type.toBridgeParamType(enumNames, interfaceNames, abstractNames)}" }
        val allParams = (if (isInstanceBased) listOf("instanceId: String") else emptyList()) + ownParams + listOf("promise: Promise")
        val paramList = allParams.joinToString(", ")
        val callArgs  = fn.params.joinToString(", ") { it.type.toCallArg(it.name, enumNames, interfaceNames, abstractNames) }

        sb.append(formatComment(fn.docComment))
        sb.appendLine("""    AsyncFunction("${fn.name}") { $paramList ->""")
        if (registryName != null) {
            sb.appendLine("      val holder = $registryName.get(instanceId)")
            sb.appendLine("      holder.scope.launch {")
            sb.appendLine("        try {")
            sb.appendLine("          promise.resolve(holder.instance.${fn.name}($callArgs)$ret)")
        } else if (useHolder) {
            sb.appendLine("      val holder = instances[instanceId] ?: error(\"Instance not found: \$instanceId\")")
            sb.appendLine("      holder.scope.launch {")
            sb.appendLine("        try {")
            sb.appendLine("          promise.resolve(holder.instance.${fn.name}($callArgs)$ret)")
        } else {
            sb.appendLine("      scope.launch {")
            sb.appendLine("        try {")
            val instanceExpr = if (isInstanceBased) "(instances[instanceId] ?: error(\"Instance not found: \$instanceId\"))" else callTarget
            sb.appendLine("          promise.resolve($instanceExpr.${fn.name}($callArgs)$ret)")
        }
        sb.appendLine("        } catch (e: Exception) {")
        sb.appendLine("""          promise.reject("$errorTag", e.message, e)""")
        sb.appendLine("        }")
        sb.appendLine("      }")
        sb.appendLine("    }")
        return sb.toString()
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
     */
    private fun flowFunctions(
        fn: KmpFunction,
        callTarget: String,
        enumNames: Set<String>,
        dataClassNames: Set<String> = emptySet(),
        sealedNames: Set<String> = emptySet(),
        useHolder: Boolean = false,
        registryName: String? = null,
    ): String {
        val sb        = StringBuilder()
        val base      = fn.flowBaseName
        val Cap       = base.cap()
        val eventName = "on${Cap}Update"
        val enumKey   = if (registryName != null) "$registryName.FlowKey.${base.toSnakeUpperCase()}" else "FlowKey.${base.toSnakeUpperCase()}"
        val retType   = fn.returnType
        val emit = when {
            retType is KmpTypeRef.Primitive && retType.kind == PrimitiveKind.CHAR              -> "value.toString()"
            retType is KmpTypeRef.ClassRef  && retType.simpleName in enumNames                 -> "value.name"
            retType is KmpTypeRef.ClassRef  && retType.simpleName in dataClassNames            -> "value.toRecord()"
            retType is KmpTypeRef.ClassRef  && retType.simpleName in sealedNames               -> "value.toRecord()"
            else -> "value"
        }

        sb.append(formatComment(fn.docComment))
        if (registryName != null) {
            sb.appendLine("""    Function("start$Cap") { instanceId: String ->""")
            sb.appendLine("      val holder = $registryName.get(instanceId)")
            sb.appendLine("      holder.flowJobs[$enumKey]?.cancel()")
            sb.appendLine("      holder.flowJobs[$enumKey] = holder.scope.launch {")
            sb.appendLine("        holder.instance.${fn.name}().collect { value ->")
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
            sb.appendLine("""    Function("start$Cap") { instanceId: String ->""")
            sb.appendLine("      val holder = instances[instanceId] ?: error(\"Instance not found: \$instanceId\")")
            sb.appendLine("      holder.flowJobs[$enumKey]?.cancel()")
            sb.appendLine("      holder.flowJobs[$enumKey] = holder.scope.launch {")
            sb.appendLine("        holder.instance.${fn.name}().collect { value ->")
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
        }
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
        this is KmpTypeRef.CollectionType && kind == CollectionKind.SET ->
            if (nullable) "$fieldName?.toList()" else "$fieldName.toList()"
        else -> fieldName
    }

    // ── Sealed Record field type (always nullable) ────────────────────────────

    /**
     * Maps a KMP field type to its nullable Kotlin type in a sealed class's flat `Record`.
     *
     * Every field in a sealed Record is nullable regardless of the original field's
     * nullability, since a given field is only populated for the variant(s) that declare it —
     * see [generateSealedCodec].
     */
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
                PrimitiveKind.BYTE    -> "Int"
                PrimitiveKind.SHORT   -> "Int"
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

    /**
     * The default value Expo's `Record` requires for a `@Field var` of this type.
     *
     * Expo `Record` fields must have a default; nullable fields default to `null`, non-nullable
     * fields get a type-appropriate zero value (`""`, `0`, `false`, `emptyList()`, a fresh nested
     * `Record()`, ...).
     */
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

    /**
     * Produces the expression that converts a Record field's value into the corresponding KMP
     * field type inside `toKmp()`.
     *
     * Handles the primitive widenings JS requires (`Long`→`Double`, `Byte`/`Short`→`Int` on the
     * wire, `Char`→`String`), enum name lookups, nested data-class conversion, and element-wise
     * conversion for collections whose elements themselves need one of those conversions (see
     * [needsConversion] / [singleElemConversion]).
     */
    private fun KmpTypeRef.toKmpFieldConversion(
        fieldName: String,
        enumNames: Set<String>,
        dataClassNames: Set<String>,
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

    /**
     * Whether a collection element of this type requires per-element conversion in
     * [toKmpFieldConversion] — i.e. it's a `Long`, an enum, or a nested data class, rather than
     * already being wire-compatible as-is.
     */
    private fun KmpTypeRef.needsConversion(enumNames: Set<String>, dataClassNames: Set<String>): Boolean = when {
        this is KmpTypeRef.Primitive && kind == PrimitiveKind.LONG -> true
        this is KmpTypeRef.ClassRef  && simpleName in enumNames    -> true
        this is KmpTypeRef.ClassRef  && simpleName in dataClassNames -> true
        else -> false
    }

    /**
     * The single-element conversion expression used inside a `.map { ... }` /
     * `.mapValues { ... }` when a collection's element type [needsConversion].
     *
     * @param elemVar the loop variable name the conversion expression should reference (defaults
     *        to Kotlin's implicit `it`; callers pass an explicit name like `v` when both a key
     *        and a value are in scope).
     */
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
     * Enums become `.name`, data/sealed classes become `.toRecord()`, and interface/abstract
     * class return values are registered in their `<Name>Registry` and returned as an opaque
     * instance id string via `.let { ... }`. Anything else (primitives, collections, `Unit`)
     * crosses as-is, so this returns `""`.
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
     * name or a registry instance id); `Long` arrives as a JS `number`, i.e. `Double`; `Char`
     * arrives as a single-character `String`. Everything else keeps its natural Kotlin type via
     * [toKotlinTypeName].
     */
    private fun KmpTypeRef.toBridgeParamType(
        enumNames: Set<String>,
        interfaceNames: Set<String> = emptySet(),
        abstractNames: Set<String> = emptySet(),
    ): String = when {
        this is KmpTypeRef.ClassRef && simpleName in enumNames ->
            if (nullable) "String?" else "String"
        this is KmpTypeRef.ClassRef && simpleName in interfaceNames ->
            if (nullable) "String?" else "String"
        this is KmpTypeRef.ClassRef && simpleName in abstractNames ->
            if (nullable) "String?" else "String"
        this is KmpTypeRef.Primitive && kind == PrimitiveKind.LONG ->
            if (nullable) "Double?" else "Double"
        this is KmpTypeRef.Primitive && kind == PrimitiveKind.CHAR ->
            if (nullable) "String?" else "String"
        else -> toKotlinTypeName()
    }

    /**
     * The expression that converts a bridge parameter (as typed by [toBridgeParamType]) back
     * into the real KMP type expected by the underlying function call.
     *
     * Mirrors [toBridgeParamType]: reverses the enum-name / registry-id / numeric-widening
     * conversions applied on the way in.
     *
     * @param paramName the generated parameter's identifier in the emitted lambda.
     */
    private fun KmpTypeRef.toCallArg(
        paramName: String,
        enumNames: Set<String>,
        interfaceNames: Set<String> = emptySet(),
        abstractNames: Set<String> = emptySet(),
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
        this is KmpTypeRef.Primitive && kind == PrimitiveKind.LONG ->
            if (nullable) "$paramName?.toLong()" else "$paramName.toLong()"
        this is KmpTypeRef.Primitive && kind == PrimitiveKind.CHAR ->
            if (nullable) "$paramName?.firstOrNull()" else "$paramName.first()"
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

/**
 * Reformats a KMP KDoc/line comment (as captured by the klib reader) into indented `//` line
 * comments suitable for placing directly above a generated Expo `Function`/`AsyncFunction`
 * block.
 *
 * Strips `/**`, `*/`, leading `*`, and `//` comment markup from each line; blank lines are
 * dropped entirely.
 *
 * @return the reformatted comment block (each line indented by [indent], terminated by a
 *         trailing newline), or `""` if [docComment] is `null` or contains no non-blank content.
 */
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
