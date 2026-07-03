/**
 * Data model for the resolved public API surface of a KMP module.
 *
 * A `KlibApiReader` reads the compiled `.klib` artifact and produces a [KmpModule] value.
 * The three platform generators (AndroidGenerator, SwiftGenerator, TypeScriptGenerator)
 * then consume that [KmpModule] to emit native bridge files for each platform.
 *
 * Hierarchy:
 *
 * ```
 * KmpModule
 * └── List<KmpDeclaration>
 *      ├── KmpClass          — concrete or abstract class
 *      ├── KmpInterface      — interface (all members abstract by definition)
 *      ├── KmpObject         — singleton; bridge delegates to its single instance
 *      ├── KmpDataClass      — value-type class carrying named fields
 *      ├── KmpSealedClass    — closed type hierarchy with a fixed set of variants
 *      │    └── List<KmpVariant>
 *      │         ├── DataVariant   — data class subtype (has fields)
 *      │         ├── ObjectVariant — singleton subtype (no fields, e.g. Loading, Empty)
 *      │         └── ClassVariant  — regular class subtype (has constructor fields)
 *      └── KmpEnum           — enumerated constant set
 * ```
 *
 * Every function's parameter and return types are represented as [KmpTypeRef], which captures
 * nullability, generic type arguments (including variance and star projection), and distinguishes
 * between primitives, collections, Flow streams, and user-defined types.
 *
 * ### Normalization guarantees (applied by the klib reader before this model is populated)
 * - `StateFlow<T>` and `SharedFlow<T>` are normalized to [KmpTypeRef.FlowType].
 * - Type aliases are fully resolved to their underlying types.
 * - A `suspend fun` whose return type is `Flow<T>` is treated as [FunctionKind.FLOW];
 *   the `suspend` modifier is discarded.
 * - Variance annotations (`in`/`out`) and star projections are preserved in [KmpTypeArg]
 *   so generators can decide how to handle them per platform.
 */
package bridgegen

// ─── Module root ─────────────────────────────────────────────────────────────

/**
 * All declarations from one `commonMain` `.kt` source file, grouped together.
 *
 * @property fileName Simple name of the source file without the `.kt` extension
 *                    (e.g. `"BridgeTypeFixture"`, `"Greeting"`).
 * @property declarations All public declarations found in this file, in source order.
 */
data class KmpSourceFile(
    val fileName: String,
    val declarations: List<KmpDeclaration>,
)

/**
 * The complete resolved public API surface of a KMP module, ready for code generation.
 *
 * Produced by `KlibApiReader`; consumed by the three platform generators.
 *
 * @property moduleName  The Kotlin module name as declared in the build script (e.g. `"shared"`).
 * @property packageName The root package of the module (e.g. `"com.myapp.shared"`).
 * @property files       Declarations grouped by their originating source file, in source order.
 */
data class KmpModule(
    val moduleName: String,
    val packageName: String,
    val files: List<KmpSourceFile>,
) {
    /** Flat list of every declaration across all source files. */
    val declarations: List<KmpDeclaration> get() = files.flatMap { it.declarations }
}

// ─── Declarations ─────────────────────────────────────────────────────────────

/**
 * A top-level public declaration in the KMP module's `commonMain` source set.
 *
 * Only declaration kinds that are meaningful to generate bridge code for are included;
 * annotation classes and expect/actual infrastructure are excluded by the klib reader.
 */
sealed class KmpDeclaration {

    /**
     * A concrete or abstract Kotlin class.
     *
     * When [isAbstract] is `true` the class cannot be instantiated directly; generators
     * typically emit it as an abstract base type (TypeScript abstract class, Swift open class).
     * When `false` the generators produce a full bridge module that creates and delegates to
     * an instance of this class.
     *
     * @property name        Simple class name (e.g. `"AuthRepository"`).
     * @property packageName Fully qualified package (e.g. `"com.myapp.shared.auth"`).
     * @property isAbstract  Whether the Kotlin declaration carries the `abstract` modifier.
     * @property functions   Public functions declared on this class, in source order.
     * @property docComment  KDoc comment on the class declaration, if present.
     * @property hasZeroArgConstructor Whether the primary constructor takes no parameters —
     *                       a JS-implemented anonymous subclass can only extend such a class.
     * @property hasAbstractProperties Whether any public property is abstract — an anonymous
     *                       subclass would have to override it, which generators cannot emit
     *                       (properties are not read into the model).
     */
    data class KmpClass(
        val name: String,
        val packageName: String,
        val isAbstract: Boolean,
        val functions: List<KmpFunction>,
        val typeParameters: List<String> = emptyList(),
        val docComment: String? = null,
        val hasZeroArgConstructor: Boolean = true,
        val hasAbstractProperties: Boolean = false,
    ) : KmpDeclaration()

    /**
     * A Kotlin `interface` declaration.
     *
     * All functions on an interface are implicitly abstract. Generators typically emit this
     * as a TypeScript `interface`, a Swift `protocol`, or leave it as a Kotlin interface on Android.
     *
     * @property name        Simple interface name (e.g. `"UserRepository"`).
     * @property packageName Fully qualified package.
     * @property functions   Abstract function signatures declared on this interface.
     * @property docComment  KDoc comment on the interface declaration, if present.
     * @property hasAbstractProperties Whether any public property is abstract — a JS-implemented
     *                       anonymous object would have to override it, which generators cannot
     *                       emit (properties are not read into the model).
     */
    data class KmpInterface(
        val name: String,
        val packageName: String,
        val functions: List<KmpFunction>,
        val docComment: String? = null,
        val hasAbstractProperties: Boolean = false,
    ) : KmpDeclaration()

    /**
     * A Kotlin `object` declaration — a singleton with no constructor.
     *
     * Bridge code delegates to the single instance directly (e.g. `MyObject.someFunction()`
     * on Android, `MyObject.shared.someFunction()` on iOS via Kotlin/Native).
     *
     * @property name        Simple object name (e.g. `"AnalyticsApi"`).
     * @property packageName Fully qualified package.
     * @property functions   Public functions on this object, in source order.
     * @property docComment  KDoc comment on the object declaration, if present.
     */
    data class KmpObject(
        val name: String,
        val packageName: String,
        val functions: List<KmpFunction>,
        val docComment: String? = null,
    ) : KmpDeclaration()

    /**
     * A Kotlin `data class` — a value-type class whose primary value is its named fields.
     *
     * Data classes cross the JS bridge as a plain key-value record. Generators emit
     * serialization/deserialization helpers that map between the Kotlin data class and
     * the platform-specific record type (Expo `Record`, Swift `Dictionary`, TypeScript object type).
     *
     * @property name        Simple class name (e.g. `"User"`, `"SearchRequest"`).
     * @property packageName Fully qualified package.
     * @property fields      Constructor parameters in declaration order (the primary constructor).
     * @property functions   Additional public member functions beyond the data class defaults.
     * @property docComment  KDoc comment on the class declaration, if present.
     */
    data class KmpDataClass(
        val name: String,
        val packageName: String,
        val fields: List<KmpField>,
        val functions: List<KmpFunction>,
        val docComment: String? = null,
    ) : KmpDeclaration()

    /**
     * A Kotlin `sealed class` or `sealed interface` — a closed type hierarchy.
     *
     * All direct subclasses are enumerated in [variants]. Sealed types cross the JS bridge as a
     * tagged record: `{ type: "VariantName", ...fields }`. Generators emit discriminated-union
     * types (TypeScript), Swift enums with associated values, and Kotlin sealed classes (no-op
     * on Android since the type is already native).
     *
     * @property name        Simple sealed class name (e.g. `"AuthState"`, `"NetworkResult"`).
     * @property packageName Fully qualified package.
     * @property variants    All direct subtypes, in source order.
     * @property functions   Shared functions declared on the sealed parent (uncommon but valid).
     * @property docComment  KDoc comment on the sealed class declaration, if present.
     */
    data class KmpSealedClass(
        val name: String,
        val packageName: String,
        val variants: List<KmpVariant>,
        val functions: List<KmpFunction>,
        val docComment: String? = null,
    ) : KmpDeclaration()

    /**
     * A Kotlin `enum class`.
     *
     * Enum values travel as their **case-name string** over the JS↔native bridge (e.g.
     * `Direction.NORTH` crosses as `"NORTH"`). Generators emit typed enum conversions on each
     * platform so callers always see the typed enum value, not a raw string.
     *
     * @property name    Simple enum class name (e.g. `"Direction"`, `"Status"`).
     * @property packageName Fully qualified package.
     * @property entries Enum case names in declaration order (e.g. `["NORTH", "SOUTH", "EAST", "WEST"]`).
     * @property docComment KDoc comment on the enum class declaration, if present.
     */
    data class KmpEnum(
        val name: String,
        val packageName: String,
        val entries: List<String>,
        val docComment: String? = null,
    ) : KmpDeclaration()

    /**
     * File-level (top-level) functions and property getters from a single `.kt` source file.
     *
     * On Android, top-level Kotlin declarations compile into a static file facade (`FilenameKt`);
     * on Kotlin/Native they are exposed through the generated framework. Property getters are
     * modelled as zero-param SYNC [KmpFunction] entries with [KmpFunction.isPropertyGetter] = true.
     *
     * @property fileName    Source file name without extension (e.g. `"BridgeTypeFixture"`).
     * @property packageName Fully qualified package (e.g. `"com.example.shared"`).
     * @property functions   Top-level functions (sync, suspend, flow) and property getters.
     */
    data class KmpFileScope(
        val fileName: String,
        val packageName: String,
        val functions: List<KmpFunction>,
    ) : KmpDeclaration()
}

// ─── Sealed class variants ────────────────────────────────────────────────────

/**
 * A single direct subtype of a [KmpDeclaration.KmpSealedClass].
 *
 * The three variant kinds map to the three ways a Kotlin sealed hierarchy can be extended:
 * a `data class` (structured payload), a plain `class` (constructor fields, no data semantics),
 * or an `object` (singleton, no fields).
 */
sealed class KmpVariant {

    /**
     * A `data class` subtype of a sealed class.
     *
     * Carries named, ordered fields and is the most common variant kind.
     * Example: `data class Success(val user: User) : AuthState()`
     *
     * @property name   Simple class name (e.g. `"Success"`).
     * @property fields Primary constructor parameters in declaration order.
     */
    data class DataVariant(
        val name: String,
        val fields: List<KmpField>,
    ) : KmpVariant()

    /**
     * A singleton `object` subtype of a sealed class.
     *
     * Carries no fields. Example: `object Loading : AuthState()`
     *
     * @property name Simple object name (e.g. `"Loading"`, `"Empty"`).
     */
    data class ObjectVariant(
        val name: String,
    ) : KmpVariant()

    /**
     * A regular (non-data) `class` subtype of a sealed class.
     *
     * Has constructor fields but no `data class` semantics (no generated `copy`, `equals`, etc.).
     * Example: `class Retry(val attempt: Int) : NetworkResult()`
     *
     * @property name       Simple class name (e.g. `"Retry"`).
     * @property fields     Constructor parameters in declaration order.
     * @property isAbstract Whether this variant itself is abstract (can be further subclassed
     *                      within the sealed hierarchy).
     */
    data class ClassVariant(
        val name: String,
        val fields: List<KmpField>,
        val isAbstract: Boolean = false,
    ) : KmpVariant()
}

// ─── Fields and functions ─────────────────────────────────────────────────────

/**
 * A named field on a [KmpDeclaration.KmpDataClass] or a [KmpVariant].
 *
 * Corresponds to a primary constructor parameter (`val`/`var`) in Kotlin.
 *
 * @property name The field identifier as declared (e.g. `"userId"`, `"createdAt"`).
 * @property type The fully resolved type of this field.
 */
data class KmpField(
    val name: String,
    val type: KmpTypeRef,
)

/**
 * Distinguishes how a KMP function is bridged to the native layer.
 *
 * The kind is determined from the function's signature by the klib reader:
 * a `Flow<T>` return type (including `StateFlow`/`SharedFlow`) → [FLOW],
 * the `suspend` modifier → [SUSPEND], anything else → [SYNC].
 *
 * A `suspend fun` that also returns `Flow<T>` is normalized to [FLOW]; the `suspend`
 * modifier is discarded since it only awaits the Flow's creation, not its completion.
 */
enum class FunctionKind {

    /** Blocking call. Mapped to a synchronous `Function` in the Expo Module API. */
    SYNC,

    /**
     * Coroutine-suspending call (`suspend fun`). Mapped to `AsyncFunction` in the Expo Module API;
     * resolves a JS Promise when the coroutine completes, or rejects it on exception.
     */
    SUSPEND,

    /**
     * Kotlin `Flow<T>` source. Bridged as a start/stop pair (`startXxx` / `stopXxx`) with an
     * `onXxxUpdate` event emitted for each element. [KmpFunction.returnType] holds the
     * unwrapped element type `T` directly — the `Flow<…>` wrapper is stripped during normalization.
     */
    FLOW,
}

/**
 * A single parameter in a KMP function signature.
 *
 * @property name The parameter identifier as declared in Kotlin (e.g. `"userId"`, `"count"`).
 * @property type The fully resolved type of this parameter.
 */
data class KmpParam(
    val name: String,
    val type: KmpTypeRef,
)

/**
 * Describes one public function on a KMP declaration.
 *
 * @property name       The function's identifier (e.g. `"login"`, `"observeAuthState"`).
 * @property kind       How this function is bridged — sync, suspend, or flow.
 * @property params     Ordered list of parameters; empty for zero-argument functions.
 * @property returnType The fully resolved return type. For [FunctionKind.FLOW] functions this is
 *                      the *element* type — the `Flow<…>` wrapper is stripped during normalization
 *                      so generators work with the concrete element type directly.
 * @property docComment KDoc or line comment immediately preceding the function declaration,
 *                      if present. Generators reformat and re-emit it above the generated function.
 */
data class KmpFunction(
    val name: String,
    val kind: FunctionKind,
    val params: List<KmpParam>,
    val returnType: KmpTypeRef,
    val docComment: String? = null,
    val isPropertyGetter: Boolean = false,
    /** Whether the declaration is `open`/`abstract` (i.e. not `final`) — a JS-implemented
     *  anonymous subclass can only override overridable members. Always true for interface
     *  members; meaningless for top-level functions. */
    val isOverridable: Boolean = true,
) {
    /**
     * Base name for a [FunctionKind.FLOW] function, used to derive event names and start/stop
     * method names in the generated bridge.
     *
     * Strips a trailing `"Flow"` suffix (case-insensitive) when present.
     * Examples: `"authStateFlow"` → `"authState"`, `"ticker"` → `"ticker"`.
     */
    val flowBaseName: String
        get() = if (name.endsWith("Flow", ignoreCase = true)) name.dropLast(4) else name
}

// ─── Type references ──────────────────────────────────────────────────────────

/**
 * A fully resolved reference to a Kotlin type as it appears in a function signature or field.
 *
 * Nullability is tracked on every variant so generators can emit `?` (TypeScript), `?` (Swift
 * optional), or nullable Kotlin types as appropriate.
 */
sealed class KmpTypeRef {

    /**
     * One of Kotlin's built-in primitive types.
     *
     * @property kind     Which primitive (e.g. [PrimitiveKind.STRING], [PrimitiveKind.INT]).
     * @property nullable Whether this type is nullable (e.g. `String?`).
     */
    data class Primitive(
        val kind: PrimitiveKind,
        val nullable: Boolean = false,
    ) : KmpTypeRef()

    /**
     * The `Unit` return type, representing a function that returns no meaningful value.
     *
     * @property nullable Whether declared as `Unit?` (rare but valid Kotlin).
     */
    data class UnitType(
        val nullable: Boolean = false,
    ) : KmpTypeRef()

    /**
     * A standard Kotlin collection type: `List<T>`, `Map<K, V>`, or `Set<T>`.
     *
     * @property kind     Which collection family ([CollectionKind.LIST], [CollectionKind.MAP],
     *                    or [CollectionKind.SET]).
     * @property typeArgs The type arguments in declaration order. `List<T>` has one arg,
     *                    `Map<K, V>` has two, `Set<T>` has one.
     * @property nullable Whether this collection reference is nullable (e.g. `List<String>?`).
     */
    data class CollectionType(
        val kind: CollectionKind,
        val typeArgs: List<KmpTypeArg>,
        val nullable: Boolean = false,
    ) : KmpTypeRef()

    /**
     * A Kotlin `Flow<T>` (or normalized `StateFlow<T>` / `SharedFlow<T>`) stream type.
     *
     * The klib reader normalizes all Flow subtypes to this variant so generators do not need
     * to special-case `StateFlow` or `SharedFlow`.
     *
     * @property typeArg  The element type emitted by the flow.
     * @property nullable Whether the flow reference itself is nullable (e.g. `Flow<Int>?`).
     */
    data class FlowType(
        val typeArg: KmpTypeArg,
        val nullable: Boolean = false,
    ) : KmpTypeRef()

    /**
     * A reference to a user-defined type: a class, interface, sealed class, data class,
     * object, or enum declared in the KMP module or one of its dependencies.
     *
     * @property qualifiedName Fully qualified Kotlin name (e.g. `"com.myapp.shared.AuthState"`).
     * @property typeArgs      Generic type arguments, if any (e.g. `[Invariant(ClassRef("User"))]`
     *                         for `Result<User>`).
     * @property nullable      Whether this reference is nullable (e.g. `User?`).
     */
    data class ClassRef(
        val qualifiedName: String,
        val typeArgs: List<KmpTypeArg> = emptyList(),
        val nullable: Boolean = false,
    ) : KmpTypeRef() {
        /** Simple (unqualified) name derived from [qualifiedName]. */
        val simpleName: String get() = qualifiedName.substringAfterLast('.')
    }

    /**
     * A generic type parameter (e.g. `T`, `K`, `V`) as it appears in a generic function or class.
     *
     * The klib reader preserves type parameters so generators can emit appropriately generic
     * output. Generators that do not support generics can substitute `Any` / `unknown`.
     *
     * @property name     The type parameter name as declared (e.g. `"T"`, `"K"`).
     * @property nullable Whether this type parameter position is nullable (e.g. `T?`).
     */
    data class TypeParam(
        val name: String,
        val nullable: Boolean = false,
    ) : KmpTypeRef()
}

// ─── Type arguments ───────────────────────────────────────────────────────────

/**
 * A single type argument in a generic type application (e.g. the `String` in `List<String>`).
 *
 * Variance is preserved from the klib so generators can decide how to handle it per platform.
 * Most bridge targets (TypeScript, Swift) ignore variance and treat all projections as invariant;
 * Android leaves Kotlin generics as-is.
 */
sealed class KmpTypeArg {

    /**
     * An invariant type argument — the default, no variance annotation.
     *
     * Example: `List<String>` → `Invariant(Primitive(STRING))`.
     *
     * @property type The concrete type filling this argument position.
     */
    data class Invariant(val type: KmpTypeRef) : KmpTypeArg()

    /**
     * A covariant (`out`) type argument — the type is only produced, never consumed.
     *
     * Example: `List<out User>` → `Covariant(ClassRef("…User"))`.
     *
     * @property type The concrete type filling this argument position.
     */
    data class Covariant(val type: KmpTypeRef) : KmpTypeArg()

    /**
     * A contravariant (`in`) type argument — the type is only consumed, never produced.
     *
     * Example: `Comparator<in User>` → `Contravariant(ClassRef("…User"))`.
     *
     * @property type The concrete type filling this argument position.
     */
    data class Contravariant(val type: KmpTypeRef) : KmpTypeArg()

    /**
     * A star projection (`*`) — the type argument is unknown or unconstrained.
     *
     * Equivalent to `out Any?` in Kotlin's type system. Generators typically emit
     * `unknown` (TypeScript), `Any` (Swift / Android).
     */
    object Star : KmpTypeArg()
}

// ─── Supporting enumerations ──────────────────────────────────────────────────

/**
 * Kotlin built-in primitive types that can appear as [KmpTypeRef.Primitive].
 */
enum class PrimitiveKind {
    /** `kotlin.String` */
    STRING,
    /** `kotlin.Int` */
    INT,
    /** `kotlin.Long` */
    LONG,
    /** `kotlin.Double` */
    DOUBLE,
    /** `kotlin.Float` */
    FLOAT,
    /** `kotlin.Boolean` */
    BOOLEAN,
    /** `kotlin.Byte` */
    BYTE,
    /** `kotlin.Short` */
    SHORT,
    /** `kotlin.Char` */
    CHAR,
}

/**
 * Whether a JS implementation of this interface/abstract class can be generated: the platform
 * bridge creates an anonymous subtype overriding every member, which is only possible when the
 * type has no abstract properties, and — for an abstract class — a zero-arg constructor and no
 * `final` member functions. Used by all three generators to decide whether to emit the
 * `create()` / `resolve<Fn>` reverse-bridge surface.
 */
fun KmpDeclaration.isJsImplementable(): Boolean = when (this) {
    is KmpDeclaration.KmpInterface -> !hasAbstractProperties
    is KmpDeclaration.KmpClass ->
        isAbstract && hasZeroArgConstructor && !hasAbstractProperties && functions.all { it.isOverridable }
    else -> false
}

/**
 * Human-readable reason why [isJsImplementable] is false, for skip messages.
 * Returns `null` when the declaration IS JS-implementable.
 */
fun KmpDeclaration.jsImplementabilityGap(): String? = when {
    isJsImplementable() -> null
    this is KmpDeclaration.KmpClass && !hasZeroArgConstructor -> "constructor has parameters"
    this is KmpDeclaration.KmpClass && functions.any { !it.isOverridable } -> "has final member functions"
    else -> "has abstract properties"
}

/**
 * Standard Kotlin collection families representable as [KmpTypeRef.CollectionType].
 *
 * Only the three core read-only interfaces are modelled; mutable variants (`MutableList` etc.)
 * and concrete implementations (`ArrayList` etc.) are normalized to the nearest read-only
 * interface by the klib reader.
 */
enum class CollectionKind {
    /** `kotlin.collections.List<T>` */
    LIST,
    /** `kotlin.collections.Map<K, V>` */
    MAP,
    /** `kotlin.collections.Set<T>` */
    SET,
}
