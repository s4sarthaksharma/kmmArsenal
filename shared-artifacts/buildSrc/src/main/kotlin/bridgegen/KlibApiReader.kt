package bridgegen

import org.jetbrains.kotlin.konan.file.File as KFile
import org.jetbrains.kotlin.library.resolveSingleFileKlib
import org.jetbrains.kotlin.library.metadata.parseModuleHeader
import org.jetbrains.kotlin.library.metadata.parsePackageFragment
import org.jetbrains.kotlin.metadata.ProtoBuf
import org.jetbrains.kotlin.metadata.deserialization.Flags
import org.jetbrains.kotlin.metadata.deserialization.NameResolverImpl
import org.jetbrains.kotlin.metadata.deserialization.TypeTable
import org.jetbrains.kotlin.serialization.deserialization.getClassId
import java.io.File

/**
 * Reads a compiled Kotlin Multiplatform `.klib` file and produces a [KmpModule] containing
 * the fully resolved public API surface of the given [targetPackage], grouped by source file.
 *
 * Each klib package fragment corresponds to one `.kt` source file. When [sourceDir] is provided,
 * the reader scans source files to resolve human-readable file names; otherwise the klib part
 * name is used as a fallback.
 *
 * ### Normalization applied before returning:
 * - `StateFlow`, `SharedFlow`, `MutableStateFlow`, `MutableSharedFlow` → [KmpTypeRef.FlowType]
 * - `MutableList`, `MutableMap`, `MutableSet` → their read-only [CollectionKind] equivalents
 * - `suspend fun` returning `Flow<T>` → [FunctionKind.FLOW], `suspend` modifier discarded
 * - Type aliases → resolved to the underlying expanded type (the klib stores the expansion;
 *   the alias name is not preserved in the model)
 * - Non-public declarations, annotation classes, and generated data-class methods are excluded
 */
object KlibApiReader {

    /** Compiler-synthesized member names filtered on `data class`es only — user-declared
     *  functions with these names on other types are real API members and are bridged. */
    private val SKIP_FUNCTION_NAMES = setOf("hashCode", "equals", "toString", "copy")

    /** Matches synthesized `componentN()` destructuring functions on data classes, to exclude them. */
    private val COMPONENT_REGEX = Regex("^component\\d+$")

    /** Regex that matches the simple name of any top-level class/interface/object declaration. */
    private val DECL_NAME_REGEX = Regex(
        """^\s*(?:public\s+)?(?:(?:data|sealed|abstract|open|inner|enum|annotation)\s+)*(?:class|interface|object)\s+(\w+)"""
    )

    /** Regex that matches the simple name of any top-level fun/val/var declaration. */
    private val TOP_LEVEL_DECL_REGEX = Regex(
        """^\s*(?:public\s+)?(?:suspend\s+)?(?:fun|val|var)\s+(\w+)"""
    )

    /**
     * One `class`/`interface`/`object` declaration read from klib metadata, paired with the
     * [NameResolverImpl] needed to resolve string/class-id references within it.
     */
    private data class ClassEntry(
        val cls: ProtoBuf.Class,
        val nr: NameResolverImpl,
    ) {
        // Type table is per-class in klib metadata, not per package fragment.
        val tt: TypeTable get() = TypeTable(
            if (cls.hasTypeTable()) cls.typeTable else ProtoBuf.TypeTable.getDefaultInstance()
        )
    }

    /**
     * Top-level (file-scope) functions and properties from one package fragment, paired with
     * the resolver/type-table needed to read them.
     */
    private data class TopLevelEntry(
        val functions: List<ProtoBuf.Function>,
        val properties: List<ProtoBuf.Property>,
        val nr: NameResolverImpl,
        val tt: TypeTable,
    )

    /**
     * Everything read from klib parts sharing one part name — accumulates across all package
     * fragments that map to the same part before being resolved into a single [KmpSourceFile].
     */
    private data class PartData(
        val classes: MutableList<ClassEntry> = mutableListOf(),
        val topLevel: MutableList<TopLevelEntry> = mutableListOf(),
    )

    /**
     * Reads all public declarations in [targetPackage] from [klibFile], grouped by source file.
     *
     * @param klibFile      The commonMain metadata klib directory (or ZIP) to read.
     * @param targetPackage The Kotlin package to include (e.g. `"com.example.shared"`).
     * @param sourceDir     Optional commonMain source directory used to resolve source file names
     *                      from class names. When null, falls back to klib part names.
     * @param onSkip        Callback for declarations/functions the reader excludes for reasons
     *                      the developer should know about (e.g. extension functions).
     */
    fun read(klibFile: File, targetPackage: String, sourceDir: File? = null, onSkip: (String) -> Unit = {}): KmpModule {
        val library = resolveSingleFileKlib(KFile(klibFile.absolutePath))
        val header  = parseModuleHeader(library.moduleHeaderData)

        val classToFile: Map<String, String> =
            if (sourceDir != null) scanSourceFiles(sourceDir) else emptyMap()

        // Preserve insertion order so file order matches compilation order.
        val partEntries = LinkedHashMap<String, PartData>()

        for (pkg in header.packageFragmentNameList) {
            if (pkg != targetPackage && !pkg.startsWith("$targetPackage.")) continue
            for (part in library.packageMetadataParts(pkg)) {
                val fragment  = parsePackageFragment(library.packageMetadata(pkg, part))
                val nr        = NameResolverImpl(fragment.strings, fragment.qualifiedNames)
                // Top-level functions/properties live in the Package sub-message, not on
                // PackageFragment directly. `package` is a reserved word → accessed as package_.
                val fragmentPkg = fragment.`package`
                val fragmentTt = TypeTable(
                    if (fragmentPkg.hasTypeTable()) fragmentPkg.typeTable
                    else ProtoBuf.TypeTable.getDefaultInstance()
                )
                val data = partEntries.getOrPut(part) { PartData() }
                fragment.class_List.forEach { data.classes.add(ClassEntry(it, nr)) }
                if (fragmentPkg.functionList.isNotEmpty() || fragmentPkg.propertyList.isNotEmpty()) {
                    data.topLevel.add(TopLevelEntry(fragmentPkg.functionList, fragmentPkg.propertyList, nr, fragmentTt))
                }
            }
        }

        // Build FQN → entry map across all parts for nested-class resolution.
        val byFqName: Map<String, ClassEntry> = partEntries.values
            .flatMap { it.classes }
            .associateBy { entry ->
                entry.nr.getClassId(entry.cls.fqName).asSingleFqName().asString()
            }

        // Direct subclasses of sealed classes are represented as variants of their parent's
        // codec, never as standalone top-level declarations.
        val sealedVariantFqns: Set<String> = byFqName.values
            .filter { Flags.MODALITY.get(it.cls.flags) == ProtoBuf.Modality.SEALED }
            .flatMap { e -> e.cls.sealedSubclassFqNameList.map { e.nr.getClassId(it).asSingleFqName().asString() } }
            .toSet()

        val moduleName = library.manifestProperties
            .getProperty("unique_name", klibFile.name)
            .substringBefore("_")

        val files = partEntries.entries.mapNotNull { (partName, data) ->
            val topLevel = data.classes.filter { entry ->
                val classId = entry.nr.getClassId(entry.cls.fqName)
                !classId.isNestedClass && classId.asSingleFqName().asString() !in sealedVariantFqns
            }

            val fileScopeFunctions = mutableListOf<KmpFunction>()
            for (entry in data.topLevel) {
                entry.functions.mapNotNullTo(fileScopeFunctions) { fn ->
                    readFunction(fn, entry.nr, entry.tt, emptyList(), context = partName, onSkip = onSkip)
                }
                entry.properties.mapNotNullTo(fileScopeFunctions) { prop ->
                    readPropertyAsGetter(prop, entry.nr, entry.tt)
                }
            }

            if (topLevel.isEmpty() && fileScopeFunctions.isEmpty()) return@mapNotNull null

            // Resolve file name: try class names first, then fall back to top-level function names.
            val fileName = topLevel
                .firstNotNullOfOrNull { entry ->
                    val simpleName = entry.nr.getClassId(entry.cls.fqName).shortClassName.asString()
                    classToFile[simpleName]
                }
                ?: fileScopeFunctions.firstNotNullOfOrNull { fn -> classToFile[fn.name] }
                ?: partName

            val declarations = mutableListOf<KmpDeclaration>()
            topLevel.mapNotNullTo(declarations) { entry ->
                readDeclaration(entry.cls, entry.nr, entry.tt, byFqName, onSkip)
            }
            if (fileScopeFunctions.isNotEmpty()) {
                declarations.add(KmpDeclaration.KmpFileScope(fileName, targetPackage, fileScopeFunctions))
            }

            if (declarations.isEmpty()) null
            else KmpSourceFile(fileName, declarations)
        }

        return KmpModule(
            moduleName  = moduleName,
            packageName = targetPackage,
            files       = files,
        )
    }

    /**
     * Scans all `.kt` files under [sourceDir] and returns a map from declaration simple name
     * to source file name (without `.kt` extension).
     *
     * Only the declaration name is extracted — no type information is parsed. This is used
     * solely to resolve human-readable file names for klib parts.
     */
    private fun scanSourceFiles(sourceDir: File): Map<String, String> {
        val result = mutableMapOf<String, String>()
        sourceDir.walkTopDown()
            .filter { it.extension == "kt" }
            .forEach { file ->
                val fileName = file.nameWithoutExtension
                file.forEachLine { line ->
                    val name = DECL_NAME_REGEX.find(line)?.groupValues?.get(1)
                        ?: TOP_LEVEL_DECL_REGEX.find(line)?.groupValues?.get(1)
                    if (!name.isNullOrBlank()) result.putIfAbsent(name, fileName)
                }
            }
        return result
    }

    // ── Declaration reading ───────────────────────────────────────────────────

    /**
     * Reads one top-level class/interface/object declaration into its corresponding
     * [KmpDeclaration] subtype, dispatching on its klib `ClassKind`/modality/`IS_DATA` flags.
     *
     * @param all all classes read so far, keyed by fully-qualified name — used to resolve a
     *        sealed class's nested variant declarations by name.
     * @return `null` for non-public declarations, `expect` classes, and annotation classes,
     *         since none of these are meaningful to bridge.
     */
    private fun readDeclaration(
        cls: ProtoBuf.Class,
        nr: NameResolverImpl,
        tt: TypeTable,
        all: Map<String, ClassEntry>,
        onSkip: (String) -> Unit = {},
    ): KmpDeclaration? {
        if (Flags.VISIBILITY.get(cls.flags) != ProtoBuf.Visibility.PUBLIC) return null
        if (Flags.IS_EXPECT_CLASS.get(cls.flags)) return null

        val classId  = nr.getClassId(cls.fqName)
        val name     = classId.shortClassName.asString()
        val pkg      = classId.packageFqName.asString()
        val kind     = Flags.CLASS_KIND.get(cls.flags)
        val modality = Flags.MODALITY.get(cls.flags)
        val isData   = Flags.IS_DATA.get(cls.flags)

        return when {
            kind == ProtoBuf.Class.Kind.ANNOTATION_CLASS -> null

            kind == ProtoBuf.Class.Kind.ENUM_CLASS -> KmpDeclaration.KmpEnum(
                name        = name,
                packageName = pkg,
                entries     = cls.enumEntryList.map { nr.getString(it.name) },
            )

            // SEALED must be checked before INTERFACE: a `sealed interface` is a closed
            // hierarchy and must bridge as a tagged record, not as a registry-backed interface.
            modality == ProtoBuf.Modality.SEALED -> {
                val parentFqn = classId.asSingleFqName().asString()
                // Prefer the compiler-recorded subclass list — it also covers variants declared
                // at file top level; fall back to nested classes for metadata without it.
                val variantFqns =
                    if (cls.sealedSubclassFqNameList.isNotEmpty())
                        cls.sealedSubclassFqNameList.map { nr.getClassId(it).asSingleFqName().asString() }
                    else cls.nestedClassNameList.map { "$parentFqn.${nr.getString(it)}" }
                val variants = variantFqns.mapNotNull { fqn ->
                    all[fqn]?.let { e -> readVariant(e.cls, e.nr, e.tt) }
                }
                KmpDeclaration.KmpSealedClass(
                    name        = name,
                    packageName = pkg,
                    variants    = variants,
                    functions   = readFunctions(cls, nr, tt, context = name, onSkip = onSkip),
                )
            }

            kind == ProtoBuf.Class.Kind.INTERFACE -> KmpDeclaration.KmpInterface(
                name        = name,
                packageName = pkg,
                functions   = readFunctions(cls, nr, tt, context = name, onSkip = onSkip),
                hasAbstractProperties = hasAbstractProperties(cls),
            )

            kind == ProtoBuf.Class.Kind.OBJECT ||
            kind == ProtoBuf.Class.Kind.COMPANION_OBJECT -> KmpDeclaration.KmpObject(
                name        = name,
                packageName = pkg,
                functions   = readFunctions(cls, nr, tt, context = name, onSkip = onSkip),
            )

            isData -> KmpDeclaration.KmpDataClass(
                name        = name,
                packageName = pkg,
                fields      = primaryConstructorFields(cls, nr, tt),
                functions   = readFunctions(cls, nr, tt, context = name, onSkip = onSkip),
            )

            else -> KmpDeclaration.KmpClass(
                name           = name,
                packageName    = pkg,
                isAbstract     = modality == ProtoBuf.Modality.ABSTRACT,
                functions      = readFunctions(cls, nr, tt, context = name, onSkip = onSkip),
                typeParameters = cls.typeParameterList.map { nr.getString(it.name) },
                hasZeroArgConstructor = cls.constructorList
                    .firstOrNull { !Flags.IS_SECONDARY.get(it.flags) }
                    ?.valueParameterList?.isEmpty() ?: true,
                hasAbstractProperties = hasAbstractProperties(cls),
            )
        }
    }

    /**
     * Reads one direct subclass of a sealed class into the matching [KmpVariant] subtype: an
     * [KmpVariant.ObjectVariant] for a singleton, [KmpVariant.DataVariant] for a `data class`,
     * or [KmpVariant.ClassVariant] for a plain class (which may itself be `abstract`).
     *
     * `isNested` records whether the variant is declared inside the sealed parent's body —
     * generators need it to emit `Parent.Variant` vs a bare top-level `Variant` reference.
     */
    private fun readVariant(cls: ProtoBuf.Class, nr: NameResolverImpl, tt: TypeTable): KmpVariant {
        val classId  = nr.getClassId(cls.fqName)
        val name     = classId.shortClassName.asString()
        val isNested = classId.isNestedClass
        val kind     = Flags.CLASS_KIND.get(cls.flags)
        val modality = Flags.MODALITY.get(cls.flags)
        val isData   = Flags.IS_DATA.get(cls.flags)

        return when {
            kind == ProtoBuf.Class.Kind.OBJECT -> KmpVariant.ObjectVariant(name, isNested = isNested)
            isData -> KmpVariant.DataVariant(name = name, fields = primaryConstructorFields(cls, nr, tt), isNested = isNested)
            else   -> KmpVariant.ClassVariant(
                name       = name,
                fields     = primaryConstructorFields(cls, nr, tt),
                // Sealed mid-level variants can't be constructed on decode either.
                isAbstract = modality == ProtoBuf.Modality.ABSTRACT || modality == ProtoBuf.Modality.SEALED,
                isNested   = isNested,
            )
        }
    }

    /** Whether [cls] declares any public `abstract` property (blocks JS-implementation). */
    private fun hasAbstractProperties(cls: ProtoBuf.Class): Boolean =
        cls.propertyList.any {
            Flags.VISIBILITY.get(it.flags) == ProtoBuf.Visibility.PUBLIC &&
                Flags.MODALITY.get(it.flags) == ProtoBuf.Modality.ABSTRACT
        }

    // ── Function reading ──────────────────────────────────────────────────────

    /** Reads every public, bridgeable function declared directly on [cls] (see [readFunction]). */
    private fun readFunctions(
        cls: ProtoBuf.Class,
        nr: NameResolverImpl,
        tt: TypeTable,
        context: String = "",
        onSkip: (String) -> Unit = {},
    ): List<KmpFunction> {
        val isData = Flags.IS_DATA.get(cls.flags)
        return cls.functionList.mapNotNull {
            readFunction(it, nr, tt, cls.typeParameterList, isDataClassMember = isData, context = context, onSkip = onSkip)
        }
    }

    /**
     * Reads one function declaration into a [KmpFunction], resolving its [FunctionKind] from
     * the `suspend` modifier and return type.
     *
     * A `Flow<T>`-returning function (including a `suspend fun` returning `Flow<T>`) is
     * normalized to [FunctionKind.FLOW] with [KmpFunction.returnType] set to the unwrapped
     * element type `T`; the `suspend` modifier is otherwise irrelevant once a function is
     * classified as `FLOW`.
     *
     * @param classTypeParams the enclosing class's type parameters (for resolving `T`/`K`/`V`
     *        references in the signature); empty for a top-level function.
     * @param isDataClassMember whether the declaring class is a `data class` — only then are
     *        the compiler-synthesized names ([SKIP_FUNCTION_NAMES], `componentN()`) filtered;
     *        a user-declared `copy()`/`toString()` on any other type is a real API member.
     * @return `null` for non-public functions, extension functions (reported via [onSkip] —
     *         the generated call site would have no receiver), compiler-synthesized data-class
     *         names, and special names like `<init>`.
     */
    private fun readFunction(
        func: ProtoBuf.Function,
        nr: NameResolverImpl,
        tt: TypeTable,
        classTypeParams: List<ProtoBuf.TypeParameter>,
        isDataClassMember: Boolean = false,
        context: String = "",
        onSkip: (String) -> Unit = {},
    ): KmpFunction? {
        if (Flags.VISIBILITY.get(func.flags) != ProtoBuf.Visibility.PUBLIC) return null

        val name = nr.getString(func.name)
        if (name.startsWith("<")) return null
        if (isDataClassMember && (name in SKIP_FUNCTION_NAMES || COMPONENT_REGEX.matches(name))) return null
        if (func.hasReceiverType() || func.hasReceiverTypeId()) {
            val owner = if (context.isEmpty()) name else "$context.$name"
            onSkip("FUNCTION SKIPPED: $owner() — extension functions are not bridged (no receiver at the call site).")
            return null
        }

        val typeParams    = classTypeParams + func.typeParameterList
        val isSuspend     = Flags.IS_SUSPEND.get(func.flags)
        val returnTypeRef = resolveReturnType(func, nr, tt, typeParams)

        val (kind, effectiveReturn) = when {
            returnTypeRef is KmpTypeRef.FlowType -> {
                val element = when (val arg = returnTypeRef.typeArg) {
                    is KmpTypeArg.Invariant     -> arg.type
                    is KmpTypeArg.Covariant     -> arg.type
                    is KmpTypeArg.Contravariant -> arg.type
                    KmpTypeArg.Star             -> KmpTypeRef.Primitive(PrimitiveKind.STRING)
                }
                FunctionKind.FLOW to element
            }
            isSuspend -> FunctionKind.SUSPEND to returnTypeRef
            else      -> FunctionKind.SYNC    to returnTypeRef
        }

        val params = func.valueParameterList.map { param ->
            KmpParam(
                name = nr.getString(param.name),
                type = resolveParamType(param, nr, tt, typeParams),
            )
        }

        return KmpFunction(
            name          = name,
            kind          = kind,
            params        = params,
            returnType    = effectiveReturn,
            isOverridable = Flags.MODALITY.get(func.flags) != ProtoBuf.Modality.FINAL,
        )
    }

    /**
     * Reads one top-level `val`/`var` property as a synthetic zero-parameter
     * [FunctionKind.SYNC] [KmpFunction] with [KmpFunction.isPropertyGetter] set, so generators
     * can emit it as a plain property read (e.g. `Foo.bar`) rather than a function call
     * (`Foo.bar()`).
     *
     * Only used for file-scope (top-level) properties — member properties on classes/objects
     * are not currently read at all (see the `propertyList` limitation noted in the Android
     * bridge verification doc).
     *
     * @return `null` if the property is not public.
     */
    private fun readPropertyAsGetter(
        prop: ProtoBuf.Property,
        nr: NameResolverImpl,
        tt: TypeTable,
    ): KmpFunction? {
        if (Flags.VISIBILITY.get(prop.flags) != ProtoBuf.Visibility.PUBLIC) return null
        val name = nr.getString(prop.name)
        val returnTypeProto = if (prop.hasReturnType()) prop.returnType else tt[prop.returnTypeId]
        val typeRef = readTypeRef(returnTypeProto, nr, tt, emptyList())
        return KmpFunction(
            name            = name,
            kind            = FunctionKind.SYNC,
            params          = emptyList(),
            returnType      = typeRef,
            isPropertyGetter = true,
        )
    }

    // ── Field reading ─────────────────────────────────────────────────────────

    /**
     * Reads the primary constructor's value parameters as [KmpField]s — used for both data
     * class fields and sealed-variant constructor fields.
     *
     * @return an empty list if [cls] has no primary (non-secondary) constructor.
     */
    private fun primaryConstructorFields(
        cls: ProtoBuf.Class,
        nr: NameResolverImpl,
        tt: TypeTable,
    ): List<KmpField> {
        val ctor = cls.constructorList.firstOrNull { !Flags.IS_SECONDARY.get(it.flags) }
            ?: return emptyList()
        return ctor.valueParameterList.map { param ->
            KmpField(
                name = nr.getString(param.name),
                type = resolveParamType(param, nr, tt, emptyList()),
            )
        }
    }

    // ── Type resolution ───────────────────────────────────────────────────────

    /** Resolves a function's return type, following an inline type reference or a type-table id. */
    private fun resolveReturnType(
        func: ProtoBuf.Function,
        nr: NameResolverImpl,
        tt: TypeTable,
        typeParams: List<ProtoBuf.TypeParameter>,
    ): KmpTypeRef {
        val type = if (func.hasReturnType()) func.returnType else tt[func.returnTypeId]
        return readTypeRef(type, nr, tt, typeParams)
    }

    /** Resolves a value parameter's type, following an inline type reference or a type-table id. */
    private fun resolveParamType(
        param: ProtoBuf.ValueParameter,
        nr: NameResolverImpl,
        tt: TypeTable,
        typeParams: List<ProtoBuf.TypeParameter>,
    ): KmpTypeRef {
        val type = if (param.hasType()) param.type else tt[param.typeId]
        return readTypeRef(type, nr, tt, typeParams)
    }

    /**
     * Resolves one klib `ProtoBuf.Type` into a [KmpTypeRef], applying the reader's
     * normalization rules: type aliases are expanded to their underlying type, and generic
     * type-parameter references (`T`, `K`, `V`) become [KmpTypeRef.TypeParam].
     *
     * All other named types are resolved to their fully-qualified name and mapped by
     * [mapToTypeRef].
     */
    private fun readTypeRef(
        type: ProtoBuf.Type,
        nr: NameResolverImpl,
        tt: TypeTable,
        typeParams: List<ProtoBuf.TypeParameter>,
    ): KmpTypeRef {
        val nullable = type.nullable

        // Type aliases need no handling: the serialized type is already the expanded
        // (underlying) type — `abbreviatedType` only records the alias as written in source.

        // Generic type parameter (e.g. T, K, V)
        if (type.hasTypeParameter()) {
            val paramName = typeParams.getOrNull(type.typeParameter)
                ?.let { nr.getString(it.name) } ?: "T"
            return KmpTypeRef.TypeParam(paramName, nullable)
        }

        if (!type.hasClassName()) return KmpTypeRef.ClassRef("kotlin.Any", nullable = nullable)

        val classId  = nr.getClassId(type.className)
        val fqn      = classId.asString()   // e.g. "kotlin/String", "com/example/shared/FixtureUser"
        val typeArgs = type.argumentList.map { arg -> readTypeArg(arg, nr, tt, typeParams) }

        return mapToTypeRef(fqn, typeArgs, nullable)
    }

    /**
     * Resolves one generic type argument, preserving its variance (`out`/`in`/invariant) or
     * resolving to [KmpTypeArg.Star] for a star projection (`*`).
     */
    private fun readTypeArg(
        arg: ProtoBuf.Type.Argument,
        nr: NameResolverImpl,
        tt: TypeTable,
        typeParams: List<ProtoBuf.TypeParameter>,
    ): KmpTypeArg {
        if (arg.projection == ProtoBuf.Type.Argument.Projection.STAR) return KmpTypeArg.Star
        // Type arguments can be stored inline (arg.type) or by ID in the class type table (arg.typeId).
        val type = if (arg.hasType()) arg.type else tt[arg.typeId]
        return when (arg.projection) {
            ProtoBuf.Type.Argument.Projection.OUT -> KmpTypeArg.Covariant(readTypeRef(type, nr, tt, typeParams))
            ProtoBuf.Type.Argument.Projection.IN  -> KmpTypeArg.Contravariant(readTypeRef(type, nr, tt, typeParams))
            else                                  -> KmpTypeArg.Invariant(readTypeRef(type, nr, tt, typeParams))
        }
    }

    /**
     * Maps a fully-qualified Kotlin type name to its [KmpTypeRef] representation.
     *
     * Recognizes built-in primitives, `Unit`, the read-only and mutable collection interfaces
     * (mutable variants normalize to the same [CollectionKind] as their read-only
     * counterparts), and `Flow`/`StateFlow`/`SharedFlow` (and their mutable variants) — all
     * normalized to [KmpTypeRef.FlowType]. Anything else is treated as a user-defined
     * [KmpTypeRef.ClassRef].
     */
    private fun mapToTypeRef(fqn: String, typeArgs: List<KmpTypeArg>, nullable: Boolean): KmpTypeRef = when (fqn) {
        "kotlin/String"  -> KmpTypeRef.Primitive(PrimitiveKind.STRING,  nullable)
        "kotlin/Int"     -> KmpTypeRef.Primitive(PrimitiveKind.INT,     nullable)
        "kotlin/Long"    -> KmpTypeRef.Primitive(PrimitiveKind.LONG,    nullable)
        "kotlin/Double"  -> KmpTypeRef.Primitive(PrimitiveKind.DOUBLE,  nullable)
        "kotlin/Float"   -> KmpTypeRef.Primitive(PrimitiveKind.FLOAT,   nullable)
        "kotlin/Boolean" -> KmpTypeRef.Primitive(PrimitiveKind.BOOLEAN, nullable)
        "kotlin/Byte"    -> KmpTypeRef.Primitive(PrimitiveKind.BYTE,    nullable)
        "kotlin/Short"   -> KmpTypeRef.Primitive(PrimitiveKind.SHORT,   nullable)
        "kotlin/Char"    -> KmpTypeRef.Primitive(PrimitiveKind.CHAR,    nullable)
        "kotlin/Unit"    -> KmpTypeRef.UnitType(nullable)

        "kotlin/collections/List",
        "kotlin/collections/MutableList",
        "kotlin/collections/Collection",
        "kotlin/collections/Iterable"    -> KmpTypeRef.CollectionType(CollectionKind.LIST, typeArgs, nullable)

        "kotlin/collections/Map",
        "kotlin/collections/MutableMap"  -> KmpTypeRef.CollectionType(CollectionKind.MAP, typeArgs, nullable)

        "kotlin/collections/Set",
        "kotlin/collections/MutableSet"  -> KmpTypeRef.CollectionType(CollectionKind.SET, typeArgs, nullable)

        "kotlinx/coroutines/flow/Flow",
        "kotlinx/coroutines/flow/StateFlow",
        "kotlinx/coroutines/flow/SharedFlow",
        "kotlinx/coroutines/flow/MutableStateFlow",
        "kotlinx/coroutines/flow/MutableSharedFlow" ->
            KmpTypeRef.FlowType(
                typeArg  = typeArgs.firstOrNull() ?: KmpTypeArg.Invariant(KmpTypeRef.UnitType()),
                nullable = nullable,
            )

        else -> KmpTypeRef.ClassRef(
            qualifiedName = fqn.replace('/', '.').replace('$', '.'),
            typeArgs      = typeArgs,
            nullable      = nullable,
        )
    }
}
