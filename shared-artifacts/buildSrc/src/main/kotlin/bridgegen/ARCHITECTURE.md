# bridgegen architecture — the deep walkthrough

This document explains what every stage of the pipeline does, class by class, decision by
decision. Read [README.md](./README.md) first for the one-page overview; this file assumes you
have it. Section 8 contains end-to-end traces of real fixtures showing the exact generated
output on all three platforms and *why* each line exists.

Contents:

1. [The pipeline and its coupling model](#1-the-pipeline-and-its-coupling-model)
2. [Stage 0 — the KMP module and the klib](#2-stage-0--the-kmp-module-and-the-klib)
3. [Stage 1 — KlibApiReader](#3-stage-1--klibapireader)
4. [Stage 2 — ApiModel (the contract between reader and generators)](#4-stage-2--apimodel)
5. [Stage 3a — AndroidGenerator](#5-stage-3a--androidgenerator)
6. [Stage 3b — SwiftGenerator](#6-stage-3b--swiftgenerator)
7. [Stage 3c — TsBridgeGenerator](#7-stage-3c--tsbridgegenerator)
8. [Stage 4 — GeneratePlatformBridgesTask and push-bridges.sh](#8-stage-4--orchestration)
9. [End-to-end traces](#9-end-to-end-traces)
10. [Known limitations and their causes](#10-known-limitations)

---

## 1. The pipeline and its coupling model

```
shared (KMP commonMain sources)
  │ publishToMavenLocal                     [manual prerequisite]
  ▼
metadata .klib in ~/.m2  ──────────────┐
                                       │ same build must also produce:
KlibApiReader ──► KmpModule            │   • shared.aar        (Android binary)
  │                                    │   • Shared.xcframework (iOS binary)
  ├─► AndroidGenerator ─► .kt   ───────┤
  ├─► SwiftGenerator   ─► .swift ──────┤  generated code calls INTO these binaries
  └─► TsBridgeGenerator ─► .ts         │
                                       ▼
push-bridges.sh copies klib-derived code + AAR + XCFramework into kmp-bridge/
```

The stages are loosely coupled through two clean interfaces — the **klib** (the reader never
sees Kotlin source, only compiled metadata) and the **`KmpModule` model** (the generators never
see the klib). The npm package boundary is the third: apps only see TS wrappers.

The tight coupling is **temporal**: the klib metadata, the AAR, and the XCFramework must all
come from the *same* `shared` build. Nothing enforces this — if you edit `shared/src` and rerun
`push-bridges.sh` without `publishToMavenLocal`, the generated code references a stale API
against a stale binary and fails at compile or runtime. (Improvement plan T6.1 targets this.)

The second hidden coupling is the **wire contract**: all three generators independently derive
what each type looks like on the wire (Section 4's matrix in README). They must agree — a
`FixtureUser` encoded by Android as `{status: "ACTIVE"}` must be what TS types and what Swift
decodes. Historically all cross-platform drift bugs came from these three re-derivations
disagreeing; improvement plan T7 is the refactor that would centralize it.

---

## 2. Stage 0 — the KMP module and the klib

`shared/` is a normal KMP module. Nothing in it knows bridgegen exists. The single
requirement is `./gradlew publishToMavenLocal`, which publishes (among other artifacts) the
**commonMain metadata klib** — a directory containing protobuf-serialized descriptors of every
declaration: classes with flags (visibility, modality, class kind, `IS_DATA`), functions with
typed parameters, properties, sealed-subclass lists, type tables. This is the same metadata
format the Kotlin compiler itself consumes; bridgegen parses it with the
`org.jetbrains.kotlin` libraries (`resolveSingleFileKlib`, `parseModuleHeader`,
`parsePackageFragment`) that ship on buildSrc's classpath.

Key property of klib metadata: **type aliases are already expanded**. The serialized type *is*
the underlying type; the alias name is only recorded in `abbreviatedType` as a "how it was
written" note. The reader therefore does nothing for typealiases (a `typealias FixtureUserId =
String` parameter arrives as `kotlin/String`). Also: klib metadata carries **no KDoc**, which is
why every `docComment` field in the model is perpetually `null`.

## 3. Stage 1 — KlibApiReader

`KlibApiReader.read(klibFile, targetPackage, sourceDir, onSkip)` → `KmpModule`.

### 3.1 Grouping declarations by source file

A klib package fragment corresponds to one `.kt` source file, identified by a *part name*
(an opaque string like `1_shared`). The reader:

1. Iterates `header.packageFragmentNameList`, keeping only `targetPackage` and subpackages.
2. For each fragment, collects `class_List` (classes/interfaces/objects) and the `package`
   sub-message's `functionList`/`propertyList` (top-level declarations) into a `PartData`
   keyed by part name. Each entry keeps its own `NameResolverImpl` (string table) and
   `TypeTable` — these are **per-fragment**, not global; resolving a name with the wrong
   resolver produces garbage, which is why `ClassEntry`/`TopLevelEntry` carry theirs along.
3. Resolves human-readable file names by scanning `sourceDir` with two regexes
   (`DECL_NAME_REGEX` for `class|interface|object`, `TOP_LEVEL_DECL_REGEX` for `fun|val|var`)
   into a `simpleName → fileName` map. This is *only* for naming output files — no type
   information is parsed from source. Fallback: the raw part name.

### 3.2 Classification (`readDeclaration`)

Dispatch order matters:

```
non-public / expect          → dropped (null)
ANNOTATION_CLASS             → dropped
ENUM_CLASS                   → KmpEnum(entries = case names)
modality == SEALED           → KmpSealedClass          ← checked BEFORE interface!
kind == INTERFACE            → KmpInterface(functions, abstractProps)
OBJECT / COMPANION_OBJECT    → KmpObject
IS_DATA flag                 → KmpDataClass(fields = primary ctor params)
else                         → KmpClass(isAbstract, ctorFields, abstractProps, typeParameters)
```

**SEALED before INTERFACE** is load-bearing: a `sealed interface` has `kind == INTERFACE` but
must bridge as a closed tagged-record hierarchy, not as a registry-backed interface. The
`isFromInterface` flag is kept because Kotlin/Native cannot nest types inside an ObjC protocol —
Swift sees the variants as concatenated names (`FixturePaymentCard`, not `FixturePayment.Card`);
see §6.3.

**Sealed variants** are resolved from `sealedSubclassFqNameList` (the compiler-recorded list,
which also covers variants declared at file top level) with a fallback to `nestedClassNameList`
for older metadata. Each variant records `isNested` so generators can emit `Parent.Variant` vs
bare `Variant`. Variant FQNs are collected into `sealedVariantFqns` and **excluded from the
top-level declaration list** — a sealed subtype only ever appears inside its parent's codec.

### 3.3 Function reading (`readFunction`)

Filters, in order:

- non-public → dropped silently
- name starts with `<` (`<init>` etc.) → dropped
- on **data classes only**: `hashCode`/`equals`/`toString`/`copy`/`componentN` → dropped
  (a user-declared `toString()` on any *other* type is real API — see `FixtureNamedOverrides`)
- has a receiver type (extension function) → dropped **with an `onSkip` message**, because the
  generated call site would have no receiver to call it on

Kind resolution: return type is `FlowType` → `FLOW` (element type unwrapped into `returnType`,
`suspend` discarded — it only awaited the Flow's *creation*); else `suspend` flag → `SUSPEND`;
else `SYNC`. Two modality bits ride along for the JS-implementation logic: `isOverridable`
(not FINAL) and `isAbstractMember` (ABSTRACT).

**Top-level properties** (`readPropertyAsGetter`) become synthetic zero-param `KmpFunction`s
with `isPropertyGetter = true` so generators emit a property *access* (`Foo.bar`) instead of a
call (`Foo.bar()`). A `Flow`-typed property classifies as `FLOW` exactly like a Flow-returning
function — this is how `fixtureCounterStream: Flow<Int>` gets its start/stop/listener triplet.
Member properties on classes/objects are **not read at all** (the known T3 gap), with one
exception: **abstract** properties (`readAbstractProperties`) are read into `abstractProps`
because the JS-implementation `create(...)` must supply values for them.

### 3.4 Type resolution (`readTypeRef` → `mapToTypeRef`)

Every protobuf `Type` resolves to a `KmpTypeRef`:

- `hasTypeParameter()` → `TypeParam(name)` — the index is looked up in the enclosing class's +
  function's type-parameter list (`classTypeParams + func.typeParameterList`).
- Known FQNs map to `Primitive(kind)`, `UnitType`, `CollectionType` (with `MutableList` /
  `Collection` / `Iterable` normalizing to LIST, etc.), or `FlowType` (all five Flow variants
  normalize here).
- Everything else → `ClassRef(qualifiedName)` with `$` (nested-class separator) replaced by `.`.

Type *arguments* preserve variance (`Invariant`/`Covariant`/`Contravariant`/`Star`) — but note
generators access them via `typeOrNull()`, which deliberately erases variance, because variance
never changes what value crosses the wire. Types can be stored inline or by ID in the type
table (`arg.hasType() ? arg.type : tt[arg.typeId]`) — every resolution site handles both.

## 4. Stage 2 — ApiModel

`ApiModel.kt` is pure data plus the **shared predicates** — the single place where a judgment
call is made once so the three generators cannot disagree on it:

| Helper | Question it answers | Used for |
|---|---|---|
| `KmpFunction.flowBaseName` | `authStateFlow` → `authState` | event/start/stop naming. Case-insensitive suffix strip — `overflow` → `over`, documented footgun |
| `KmpTypeArg.typeOrNull()` | the projected type regardless of variance | every generator type-walk; prevents `List<out User>` degrading to `Any` |
| `containsTypeParam()` | does this type mention `T` anywhere (incl. nested in collections/Flow/class args)? | whether a file needs the runtime `__toWire` helper |
| `usesNonStringKeyMap()` | any param/return contain a Map with a concrete non-String key? | skip: JS objects are string-keyed. Star-projected keys get benefit of the doubt |
| `isBridgeableAsCreateArg()` | can this abstract property be a `create()` argument? | false for `Flow`/generic-typed properties (no wire representation) |
| `isJsImplementable()` | can the platform build an anonymous subtype? | interface: all abstractProps bridgeable; class: `isAbstract` + same. Gates the whole `create`/`resolve` surface on all three platforms |
| `jsImplementabilityGap()` | human-readable reason when not | skip messages |
| `proxiedSuspendFunctions()` | which suspend members call back into JS? | interfaces: **all** suspend members (iOS's ObjC-runtime conformance cannot inherit Kotlin default impls, so all platforms proxy for consistency); abstract classes: only `isAbstractMember` ones — concrete members are inherited from the real KMP class |

The declaration hierarchy itself is documented exhaustively in the file's KDoc; the short
version: `KmpModule` → `KmpSourceFile` → `KmpDeclaration` (7 kinds) → `KmpFunction` /
`KmpField` / `KmpProperty`, all typed by `KmpTypeRef` (6 kinds).

## 5. Stage 3a — AndroidGenerator

`generateFile(sourceFile, module, kmpPackageName, androidPackage, onSkip)` returns one complete
Kotlin file. Note the name sets (`enumNames`, `dataClassNames`, `sealedNames`, `interfaceNames`,
`abstractNames`) are computed from **the whole module**, not the current file — a function in
file A can return a data class from file B, and the conversion must still be recognized (the
required cross-file imports are collected by `collectClassRefImports`).

Output file layout, in order:

1. header + `package` + sorted imports
2. `Record` classes + `toKmp()`/`toRecord()` for each data class
3. flat Record codec for each sealed class
4. `__toWire` helper (only if some declaration touches a type param)
5. one `Module()` class per bridgeable class/object/file-scope
6. one Registry + `Module()` per interface/abstract class

### 5.1 Data-class Records

Three artifacts per data class (see trace §9.1):

- `class <Name>Record : Record { @Field var … }` — the JS→Kotlin direction; Expo populates the
  fields from the JS object. Every field needs a default (Expo requirement) —
  `toRecordFieldDefault` supplies type-appropriate zeros.
- `fun <Name>Record.toKmp()` — Record → real KMP type, reversing the wire encodings
  (`toKmpFieldConversion`: `Double→Long`, `String→Char` via `.first()`, `valueOf` for enums,
  nested `.toKmp()`, element-wise collection conversion).
- `fun <Name>.toRecord()` — KMP → Record for return values (`toRecordAssignment`).

The wire types (`toRecordFieldType`) encode the JS-imposed widenings: `Long`→`Double`
(JS numbers), `Byte`/`Short`→`Int`, `Char`→`String`, `Set<T>`→`List<T>` (JS has no Set on the
wire; restored with `.toSet()` on the way back in).

### 5.2 Sealed codecs

All variant fields across the hierarchy are **unioned into one flat Record**, every field
nullable, plus a `type: String` discriminator. `toRecord()` switches on the variant with `is`
checks; `toKmp()` switches on the `type` string and reconstructs the concrete variant, using
`fromSealedRecordField` to substitute fallback defaults for the always-nullable Record fields.
Two sharp edges, both deliberate: fields sharing a name across variants are deduplicated by
first occurrence (types diverging across variants would collide), and abstract variants encode
fine but `error(…)` on decode (no concrete class to construct).

### 5.3 Module bodies (`buildModuleBody`)

Three shapes by declaration kind:

- **object** → calls go to the singleton by type name (`MyObject.someFn()`).
- **file scope** → calls go through the package FQN (`com.example.shared.someFn()`); Kotlin
  resolves the file facade. Module name gets a `Kt` suffix **only** when it collides with a
  class/object/interface name in the same file (`takenNames` includes `interfaceDecls` — an
  interface claims its name even with zero functions).
- **class** → instance-based: a `create()` `Function` returning a UUID handle, a `destroy(id)`,
  and an `instances` ConcurrentHashMap. When the class has any suspend/Flow function
  (`useHolder`), instances are wrapped in an `InstanceHolder` pairing the instance with its own
  `CoroutineScope` (+ per-instance `flowJobs` map) so `destroy()` cancels exactly that
  instance's in-flight work in one call.

Generic classes (`typeParameters.isNotEmpty()`) instantiate as `Name<Any, …>` — Android
generation has no way to recover concrete type arguments (erasure); §5.6 handles the
consequences.

### 5.4 The three function emitters

- **SYNC** → `Function("name") { instanceId?, params… -> instance.name(args)$ret }` where
  `$ret` is `toReturnSuffix`: `.name` for enums, `.toRecord()` for data/sealed, registry
  `.let { XRegistry.register(it) }` for interface/abstract returns (opaque handle out),
  element-wise `.let{}` chains for collections, `__toWire` for type params. Property getters
  drop the `()`.
- **SUSPEND** → `AsyncFunction` delegating to the generated `launchSettled` helper: an
  `AtomicBoolean` + `invokeOnCompletion` guarantee the trailing `promise` settles **exactly
  once** — resolved on success, rejected with `<NAME>_ERROR` on exception, and rejected (not
  silently dropped) when the scope is cancelled before/while running — e.g. `destroy()` during
  an in-flight call. A plain `scope.launch` on a cancelled scope would never run the block and
  leave the JS promise pending forever; that was a real bug this helper fixed.
- **FLOW** → a `start<Base>`/`stop<Base>` pair plus **three** events per flow:
  `on<Base>Update` (values), `on<Base>Error` (`{message}`), `on<Base>Complete` (terminals —
  exactly one of the two fires per started stream). `start` cancels any previous job for this
  `FlowKey`, then launches the collect wrapped in try/catch: values emit updates, a normal
  return emits complete, an exception emits error — but `CancellationException` **rethrows**
  so `stop`/`destroy` fire neither terminal. Instance-based variants add `"instanceId"` to
  every payload so JS listeners can filter. Function params thread through `start` only;
  `stop` cancels by key regardless of start args and is null-safe (registry `find`, holder
  `?: return`). Flow-typed *properties* reuse this with `invoke = ""` (property access).

Every emitter first checks the two skip conditions (param count > 8 including the synthetic
`instanceId`; non-String-key Map) and emits a `// BRIDGE SKIPPED:` comment + `onSkip` instead
of broken code.

### 5.5 Interface/abstract registry + reverse bridge (`buildInterfaceModuleBody`)

An interface has no concrete type to instantiate, so instances live in a generated
`internal object <Name>Registry` — id-keyed `Holder`s carrying the instance, a scope, per-flow
jobs, and `pendingCalls` (below). The registry serves **both directions**:

- **KMP-implemented**: another bridged function returns a `FixtureNamedIface` → the return
  suffix registers it and hands JS the id. JS method calls dispatch through
  `Registry.get(id).instance`.
- **JS-implemented** (only when `isJsImplementable()`): `Function("create")` builds an
  `object : <Iface>` anonymous impl. For an abstract class the ctor args pass to `super(...)`
  and only abstract members are overridden; abstract property values are **hoisted into
  `val __<name>` locals first** because an override's initializer cannot reference the create()
  parameter of the same name (the member declaration shadows it). Each proxied suspend method:
  generates a `callId`, parks a `CompletableDeferred` in `pendingCalls`, emits a
  `call<Fn>` event (params converted to wire form — event payloads cross the bridge like any
  value), `await()`s, and casts the result back via `resolveWireContract`. The
  `try/finally { pendingCalls.remove(callId) }` prevents abandoned awaits leaking entries.
  JS answers by calling the generated `Function("resolve<Fn>") { instanceId, callId, result }`.

`resolveWireContract` is the pattern to copy when adding wire-contract logic: it returns the
`resolve<Fn>` **parameter type** and the deferred **cast expression** from one `when` table, so
the two sides physically cannot drift. Sync members of a JS-implemented instance `throw
UnsupportedOperationException` (JS cannot block synchronously); Flow members likewise (not yet
designed).

### 5.6 `__toWire` — the generic-erasure escape hatch

Static conversion needs the static type; a `T`-typed return has none (`Any` after erasure). So
any file whose functions/fields mention a type param gets one file-private helper that
dispatches on the **runtime** class:

```kotlin
private fun __toWire(value: Any?): Any? = when (value) {
    is FixtureUser -> value.toRecord()      // every data + sealed class in the module
    is FixtureStatus -> value.name          // every enum
    is List<*> -> value.map { __toWire(it) }
    is Set<*> -> value.map { __toWire(it) }
    is Map<*, *> -> value.mapValues { (_, v) -> __toWire(v) }
    else -> value
}
```

`toJsElemConversion`/`toReturnSuffix`/flow emission all route `TypeParam` positions through it.
On Android it can convert *any* module type (all `toRecord()`s are top-level in the same
package); on iOS it cannot (see §6.6).

## 6. Stage 3b — SwiftGenerator

Structurally a mirror of AndroidGenerator (records → sealed codecs → `__toWire` → modules →
interface modules → enum decode helpers), but roughly half its content is encoded knowledge of
**how Kotlin/Native exports Kotlin to ObjC/Swift, post-SKIE**. That knowledge is the most
fragile part of the whole system — a Kotlin or SKIE upgrade can shift these rules. Inventory:

### 6.1 Naming and selectors

- SKIE drops the framework prefix: Kotlin `FixtureUser` is Swift `FixtureUser`, not
  `SharedFixtureUser` (`toSwiftNativeType`).
- Members colliding with NSObject are renamed: `toString()` → `description()`, `copy()` →
  `doCopy()` (`toSwiftMemberName`, applied at every dispatch call site).
- Kotlin `object` singletons surface as `.shared` (`FixtureMarker.shared`).
- Top-level declarations live on the `<FileName>Kt` facade class as class-level members.
- ObjC selector for a suspend function: **zero-param** → `name` + `WithCompletionHandler:`;
  with params → `name` + each param name capitalized + `:`… + `completionHandler:`. Getting
  this exactly right matters because JS-implemented interfaces answer ObjC dispatch by
  selector (verified against the framework's `Shared.h` when this was fixed).

### 6.2 Boxing

ObjC blocks can't take a nilable primitive (`Int32?` is not representable), so Kotlin/Native
boxes: completion handlers deliver `KotlinInt`/`KotlinLong(longLong:)`/`KotlinBoolean(bool:)`/
`KotlinByte(char:)`/`KotlinShort(short:)`/`KotlinDouble(double:)`/`KotlinFloat(float:)`
(`toSwiftCompletionContract` — the completion type and the `Any?`→boxed conversion live in one
pair, same anti-drift pattern as Android's `resolveWireContract`). Nullable primitive
*parameters* box too (`KotlinInt(value:)`, `toSwiftCallArgWithPrefix`), and collection
*elements* arriving from SKIE come boxed and need unboxing (`v.intValue`,
`Int64(truncating: v)` — `singleElemToRecordConv`) or re-boxing on the way in
(`singleElemToKmpConv`).

### 6.3 Sealed classes

`toRecord` switches with SKIE's `onEnum(of: v)`, whose case names are the Kotlin variant names
lower-cased (`case .card(let s):`). On decode, the variant type reference depends on
declaration shape: top-level variant → bare name; nested in a sealed **class** → `Parent.Variant`;
nested in a sealed **interface** → concatenated `ParentVariant` (ObjC protocols cannot nest
types, so K/N flattens the name). Object variants decode to `.shared`.

### 6.4 Suspend dispatch and SKIE

Calling suspend members on registered instances uses SKIE's `async` translation inside a
`Task { do { promise.resolve(try await …) } catch { promise.reject(error) } }`. When the return
type is a `TypeParam` the call goes through `skie(inst)` to pick SKIE's typed overload.
JS-*implementing* a suspend member is the reverse: on an abstract class you override SKIE's
`__`-prefixed completion-handler form (`override func __fetch(completionHandler:)`); on an
interface you can't subclass at all — see 6.5.

### 6.5 JS-implemented interfaces: runtime protocol conformance

Swift's static checker fights SKIE's transformed protocols, so the generated `<Name>JsImpl` for
an *interface* sidesteps it entirely: it's a plain `NSObject` subclass whose methods carry
explicit `@objc(selector)` attributes matching the K/N selectors (6.1), plus `@objc var`s for
abstract properties, and a one-time `objc_getProtocol("\(framework)\(name)")` +
`class_addProtocol` registration. The instance is then force-cast `as! any <Name>` — the ObjC
runtime accepts it because conformance was added dynamically. Abstract *classes* don't need the
trick (plain subclass, `super.init(ctorArgs)`, stored `_prop` backings behind `override var`).

Suspend proxying parks the completion in a static `_pendingCalls: [String: (Any?, Error?) -> Void]`
map on the module class; `resolve<Fn>` looks it up by `callId`, converts the JS result
(`toSwiftResolveParamType`/`toSwiftResolveConversion` — again one contract pair), and invokes it.

### 6.6 Swift-specific divergences from Android

- **Bridgeability filter**: Android bridges any `ClassRef` (unknown ones degrade to `Any?`);
  Swift *skips* functions whose param/return types have no Swift representation
  (`isSwiftBridgeable`), because an `Any` that is actually an unexported Kotlin type won't
  compile or crash politely. This is why the android/apple module lists in
  `expo-module.config.json` legitimately differ.
- **`throws` derivation**: a generated `Function` is marked `throws` **iff** one of its emitted
  conversions contains `try` (`needsThrows` inspects the actual conversion prefixes — signature
  and body cannot drift). Enum decodes throw (`decode<Enum>` on unknown name), record `toKmp()`
  throws; param conversions that throw inside a flow-start closure are hoisted into
  `let __x = try …` locals so the `Task` body stays non-throwing.
- **`__toDict()`**: Expo's `sendEvent` on iOS needs plain `[String: Any?]`, not Record structs,
  so every Record gets a `__toDict()` and flow/event payloads use `toRecord(value).__toDict()`.
  Android has no equivalent because Kotlin Records serialize fine there.
- **`__toWire` scope**: the Swift codecs (`toRecord`/`toKmp`) are `fileprivate`, so the Swift
  `__toWire` can only convert record/sealed types **declared in the same source file** (plus
  all enums — `.name` needs no codec). Cross-file record types in erased positions pass through
  unconverted. Known limitation.
- **Flow collection goes through the KMP module's `bridgeCollectFlow`.** Kotlin/Native only
  delivers an exception across the ObjC boundary when the throwing function declares it via
  `@Throws` — kotlinx's `Flow.collect` declares nothing, so *every* Swift-side collection path
  terminates the process on a failing flow (verified empirically: SKIE `for await` is
  non-throwing by type, SKIE's `collect` extension dies in `StandaloneCoroutine.
  handleJobException`, the raw completion-handler form dies in
  `Kotlin_ObjCExport_createContinuationArgumentImpl`). The only fix is catching *in Kotlin*:
  the framework must contain a `@Throws`-declared
  `suspend fun bridgeCollectFlow(flow: Any, onEach: (Any?) -> Unit)` support function. The KMP
  module carries nothing for it — `push-bridges.sh` injects it into the XCFramework build only,
  via `scripts/bridgegen.init.gradle` (`-I`), and verifies the built framework's swiftinterface
  contains it (the klib/AAR never need it, so manual `publishToMavenLocal` is unaffected; if the
  function ever does land in a klib, the reader's function-typed-parameter rule keeps it out of
  the bridged surface). The parameter is typed `Any`
  rather than `Flow<*>` so SKIE does not transform it — generated Swift passes
  `SkieKotlinFlow(...)` / `SkieKotlinOptionalFlow(...)` (nullable or generic elements), which
  *is* a kotlinx `Flow` on the Kotlin side. The exception arrives as a caught Swift error →
  `on<Base>Error`; normal return → `on<Base>Complete`; `Task.isCancelled` suppresses both on
  stop/destroy. Raw values arrive in their ObjC representation (boxed primitives are NSNumber
  subclasses and cross `sendEvent` as-is), so only record/sealed/enum elements need conversion
  (`toSwiftFlowRawValueExpr`). Tasks tracked per instance in `_flowTasks`. Consequence: a
  `StateFlow`/`SharedFlow`-typed return would not compile (the converters only accept
  `SkieSwiftFlow`) — plain `Flow` returns only.

## 7. Stage 3c — TsBridgeGenerator

One `.ts` file per source file, two halves: **type declarations** (usable without ever touching
native) then **runtime wrappers** (gated on anything being bridgeable).

Types: enums → string-backed TS enums (`RED = "RED"`); data classes → `export type X = {…}`
object types; sealed → discriminated unions (`| { type: "Card"; last4: string }`);
zero-function interfaces → empty marker `interface`.

Runtime wrappers, one `const _X = requireNativeModule('X')` per module. **The TS `takenNames`
must agree with the native generators'** — it counts classes + objects + *all* interfaces and
abstracts (even zero-function ones, since the native registry module always claims the name);
a mismatch would make `requireNativeModule` look up a name no native module registered.

- **object / file scope** → flat `export const X = { fn: (…) => _X.fn(…) }`.
- **class** → `export class X { readonly _handle; static create(): X; destroy() }`. Generic KMP
  classes become *real* TS generics: `export class X<T = unknown>` with
  `static create<T = unknown>(): X<T>` — the caller asserts `T` at create time
  (`FixtureGenericApi.create<FixtureUser>()`) and methods typed `T` flow through
  (`toTsType(typeParams = …)` resolves `TypeParam` to the name when in scope, `unknown`
  otherwise). The native side stays erased; correctness relies on the runtime `__toWire`
  conversion producing what `T` claims.
- **interface / abstract class** → same handle class plus `_wrap(handle)` (used when a native
  return value is an opaque id), `create(...)` (only when `isJsImplementable`; ctorFields +
  abstractProps become parameters; interface-typed args unwrap to `._handle`), and per proxied
  suspend fn: `addCall<Fn>Listener` (filters events by `instanceId === this._handle`) and
  `resolve<Fn>(callId, result)`.
- **Flow fns** → one **ref-counted** `subscribe<Base>(params…, {next, error?, complete?})`
  returning `{remove()}`: the first subscriber starts the native collection, later subscribers
  join the live stream (their start params are ignored), the last `remove()` stops it.
  Terminal events flip the shared per-flow `{active, count}` state (an instance field on class
  wrappers, a module-level const for flat wrappers) so the next subscribe restarts a dead
  stream. Instance variants filter all three events on `instanceId === this._handle`;
  `destroy()` resets the flow states first. The ref-count is per *wrapper object* — two
  wrappers around the same handle would fight over start/stop (documented caveat).
- Interface-typed parameters/returns everywhere unwrap/rewrap handles
  (`p._handle` in, `X._wrap(id)` out — the TS mirror of Android's `toCallArg`/`toReturnSuffix`).

Detail worth knowing: union element types parenthesize (`(string | null)[]`), and Map key types
degrade to `string` unless `string`/`number` (TS index-signature restriction).

## 8. Stage 4 — orchestration

### GeneratePlatformBridgesTask

Inputs: `klibDir`, `sourceDir`, `kmpPackageName`, `frameworkName`, `androidPackage`. Output
dirs are `@Internal` (untracked) — this task runs on demand via the script, never in the app's
incremental graph. Flow:

1. `KlibApiReader.read(...)`, with reader skips logged as `>> [Reader] …`.
2. **Duplicate-simple-name warning**: generator name-sets are keyed by simple name, so two
   `User` classes in different subpackages would silently pick one conversion — warn early.
3. Delete stale generated files (Android `*.kt`; iOS `*.swift` except the hand-written
   `KmpBridgeModule.swift`; TS wholesale).
4. Per source file: run all three generators; write non-blank output.
5. Scan the **actual generated text** for module class names — Android via
   `^class (\w+Module)\s*:\s*Module\(\)`, Swift via `^(public )?class (\w+Module)\s*:\s*Module\b`
   — and write `expo-module.config.json` with the two lists **separately**. They differ
   legitimately (e.g. a zero-function marker interface generates an Android module but is
   skipped on iOS); listing a class that doesn't exist breaks Expo autolinking at app compile
   (`ExpoModulesProvider.swift: cannot find 'FixtureMarkerModule'`).

### push-bridges.sh

Reads `registry.json` (list of consumer package paths). Per consumer, its `package.json`'s
`kmp` block (`group`/`artifact`/`version`/`frameworkName`) parameterizes everything:

```
[1/5] cd shared && ./gradlew assemble<FW>ReleaseXCFramework   (SKIE applied here)
[2/5] copy <FW>.xcframework → consumer/ios/Frameworks/
[3/5] ./gradlew generatePlatformBridges -Pkmp…                (the task above)
[4/5] ./gradlew resolveAndroidAar + copy → consumer/android/libs/<artifact>.aar
[5/5] done; --publish additionally runs `npm run push:local` (yalc push)
```

There are **no compile gates** in the script (removed deliberately) — the three manual checks
in README §Running-it are the verification story until CI (plan T1.3) exists. The script's one
stated prerequisite is `publishToMavenLocal` in `shared/` — forget it and you get the stale-klib
failure mode from §1.

## 9. End-to-end traces

Real fixtures, real generated output (abridged to the relevant lines), with the "why" attached.

### 9.1 Data class: `FixtureUser`

```kotlin
data class FixtureUser(
    val id: String, val age: Int, val score: Double, val active: Boolean,
    val byteFlag: Byte, val longId: Long, val initial: Char, val ratio: Float,
    val status: FixtureStatus,       // enum
    val address: FixtureAddress?,    // nested data class, nullable
    val tags: List<String>, val metadata: Map<String, Int>, val aliases: Set<String>,
)
```

**Android** — note every non-trivial line is a wire rule from §5.1:

```kotlin
class FixtureUserRecord : Record {
    @Field var byteFlag: Int = 0            // Byte widens to Int on the wire
    @Field var longId: Double = 0.0         // Long → Double (JS number)
    @Field var initial: String = ""         // Char → 1-char String
    @Field var status: String = ""          // enum → case-name String
    @Field var address: FixtureAddressRecord? = null   // nested record type
    @Field var aliases: List<String> = emptyList()     // Set → List on the wire
    // … identity fields elided
}
fun FixtureUserRecord.toKmp() = FixtureUser(
    byteFlag = byteFlag.toByte(),           // narrow back
    longId = longId.toLong(),
    initial = initial.first(),
    status = FixtureStatus.valueOf(status), // throws on unknown name
    address = address?.toKmp(),             // ?-chain: nullable field
    aliases = aliases.let { r0 -> r0.toSet() },  // restore Set-ness
    // …
)
fun FixtureUser.toRecord(): FixtureUserRecord = FixtureUserRecord().also {
    it.status = status.name
    it.aliases = aliases.let { r0 -> r0.toList() }
    // … mirror image
}
```

**Swift** — same shape, different numerics (`age: Int32`, `byteFlag: Int8`, `longId: Int64` in
the @Field — Expo/Swift handles the JS number conversion), and `toKmp()` is `throws` because
the `status` decode and nested `toKmp()` emit `try` (§6.6). The struct also gets `__toDict()`
for event payloads.

**TS** — a plain object type; `status: string` here because record fields carry the raw wire
shape (the typed `FixtureStatus` enum appears in *wrapper* signatures, `wrapperMode = true`).

### 9.2 Flow function: `TickerService.secondsFlow(): Flow<Int>`

Reader: return type `FlowType(Int)` → kind `FLOW`, `returnType = Int`,
`flowBaseName = "seconds"` (suffix stripped).

**Android** (class ⇒ instance-based ⇒ `useHolder` because flows exist):

```kotlin
private enum class FlowKey { SECONDS, STATUS, PULSE }
private class InstanceHolder(val instance: TickerService) {
    val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    val flowJobs = mutableMapOf<FlowKey, Job>()
}
// …
Function("startSeconds") { instanceId: String ->
    val holder = instances[instanceId] ?: error("Instance not found: $instanceId")
    holder.flowJobs[FlowKey.SECONDS]?.cancel()          // restart-safe: at most one collector
    holder.flowJobs[FlowKey.SECONDS] = holder.scope.launch {
        try {
            holder.instance.secondsFlow().collect { value ->
                sendEvent("onSecondsUpdate", mapOf("instanceId" to instanceId, "value" to value))
            }
            sendEvent("onSecondsComplete", mapOf("instanceId" to instanceId))
        } catch (e: CancellationException) {
            throw e     // stop()/destroy() are not terminal events
        } catch (e: Exception) {
            sendEvent("onSecondsError", mapOf("instanceId" to instanceId, "message" to (e.message ?: e.toString())))
        }
    }
}
Function("stopSeconds") { instanceId: String -> /* cancel + remove by key */ }
```

`destroy(instanceId)` cancels the holder's scope → all three flows die with the instance.

**TS** — one ref-counted `subscribe` per flow (see §7 for the counting rules):

```typescript
subscribeSeconds(handlers: { next: (value: number) => void; error?: (message: string) => void; complete?: () => void }): { remove: () => void } {
    const st = this._secondsState                        // { active, count } per flow
    const subs = [
        _TickerService.addListener('onSecondsUpdate',   (e: any) => { if (e.instanceId === this._handle) handlers.next(e.value) }),
        _TickerService.addListener('onSecondsError',    (e: any) => { if (e.instanceId === this._handle) { st.active = false; handlers.error?.(e.message) } }),
        _TickerService.addListener('onSecondsComplete', (e: any) => { if (e.instanceId === this._handle) { st.active = false; handlers.complete?.() } }),
    ]
    st.count++
    if (!st.active) { _TickerService.startSeconds(this._handle); st.active = true }
    // remove(): detach subs, count--, stop native collection when count hits 0
}
```

**Swift** mirrors Android's try/catch shape, but collects through the generated
`__FlowCollector` + `SkieKotlinFlow(inst.secondsFlow()).collect(collector:)` instead of
`for await` — the only path where a failing Kotlin flow is catchable (§6.6).

### 9.3 Interface, both directions: `FixtureNamedIface`

```kotlin
interface FixtureNamedIface { suspend fun ping(): String }
fun fixtureNamedIfaceHello(): String = "hello-from-file-scope"
```

This fixture also demonstrates name collision: the interface claims `FixtureNamedIface`, so the
file-scope module becomes `FixtureNamedIfaceKt` — **consistently on all three platforms**,
because `requireNativeModule('FixtureNamedIfaceKt')` must match a registered native name.

**Android** generates the registry (`Holder` = instance + scope + `pendingCalls`), the
forward dispatch:

```kotlin
AsyncFunction("ping") { instanceId: String, promise: Promise ->
    val holder = FixtureNamedIfaceRegistry.get(instanceId)
    launchSettled(holder.scope, promise, "PING_ERROR") { holder.instance.ping() }
}
```

and the reverse bridge — the generated anonymous impl whose `ping()` *suspends until JS
answers*:

```kotlin
Function("create") {
    val instanceId = UUID.randomUUID().toString()
    val impl = object : FixtureNamedIface {
        override suspend fun ping(): String {
            val holder = FixtureNamedIfaceRegistry.get(instanceId)
            val callId = UUID.randomUUID().toString()
            val deferred = CompletableDeferred<Any?>()
            holder.pendingCalls[callId] = deferred
            try {
                emitEvent("callPing", mapOf("instanceId" to instanceId, "callId" to callId))
                return (deferred.await() as String)     // resolveWireContract cast
            } finally {
                holder.pendingCalls.remove(callId)      // abandoned awaits must not leak
            }
        }
    }
    FixtureNamedIfaceRegistry.registerWithId(instanceId, impl)
    instanceId
}
Function("resolvePing") { instanceId: String, callId: String, result: String ->
    FixtureNamedIfaceRegistry.get(instanceId).pendingCalls.remove(callId)?.complete(result)
}
```

**TS** exposes the full loop:

```typescript
const api = FixtureNamedIface.create()
api.addCallPingListener(({ callId }) => api.resolvePing(callId, "pong from JS"))
// Any KMP code holding this instance and calling ping() now gets "pong from JS".
await api.ping()   // and JS can also invoke it through the same registry
```

**Swift**'s version of the impl is the `NSObject` + `@objc(pingWithCompletionHandler:)` +
`class_addProtocol` construction from §6.5 — same event/`resolvePing` protocol on the wire, so
the TS above is platform-agnostic.

### 9.4 Generic class: `FixtureGenericApi<T>`

```kotlin
class FixtureGenericApi<T> {
    fun getUser(): T = FixtureUser(…) as T
    fun wrapUsers(): List<T> = listOf(getUser())
    fun observe(): Flow<T> = …
}
```

Erasure means the native side instantiates `FixtureGenericApi<Any>` and can't statically
convert returns. The three-layer answer:

- **Android**: `getUser()` returns through `.let { r0 -> __toWire(r0) }`; the runtime `when`
  recognizes the actual `FixtureUser` and calls `.toRecord()`. `wrapUsers()`/`observe()` route
  elements through the same helper.
- **Swift**: same `__toWire` dispatch (`case let v as FixtureUser: return toRecord(v).__toDict()`),
  with the same-file-only caveat from §6.6, and `skie(inst)` for suspend dispatch.
- **TS**: `export class FixtureGenericApi<T = unknown>`;
  `FixtureGenericApi.create<FixtureUser>()` makes `getUser(): T` return the typed record the
  runtime conversion actually produced.

The remaining hole: `T`-typed **parameters** — a JS record passed into a `T` position isn't
converted to a KMP type (nothing knows which `toKmp()` to run); primitives pass through fine.

## 10. Known limitations

| Limitation | Cause | Plan |
|---|---|---|
| Member properties invisible | reader only reads class `propertyList` for *abstract* props | T3 |
| Inherited members not bridged | no supertype resolution in the reader | backlog |
| Registries grow until `destroy()` | no GC hook on TS wrappers | T5 (FinalizationRegistry) |
| `StateFlow`/`SharedFlow` returns fail iOS compile | error-aware collect converts via `SkieKotlinFlow(...)`, which only accepts `SkieSwiftFlow` | backlog |
| subscribe ref-count is per wrapper object | two wrappers around one handle fight over start/stop | documented caveat |
| `T`-typed params into KMP | reverse of `__toWire` would need to know the target type | backlog |
| iOS `__toWire` same-file only | Swift codecs are `fileprivate` | backlog |
| Non-String Map keys skipped | JS objects are string-keyed; a real encoding needs a design | backlog |
| Flow-typed abstract props block `create()` | a Flow has no wire value to pass at create time | backlog (loud skip) |
| Sealed variants sharing a field name with different types | flat-record dedup keeps first occurrence | documented caveat |
| K/N + SKIE rule drift on upgrades | §6's rules are encoded, not queried | T1 snapshots catch it |
