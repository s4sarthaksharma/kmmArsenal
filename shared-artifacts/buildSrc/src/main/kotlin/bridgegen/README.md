# bridgegen — KMP → React Native bridge generator

bridgegen reads the compiled metadata of a Kotlin Multiplatform module and generates the full
native + TypeScript bridge for React Native (Expo Modules), so the KMP public API can be called
from JS on Android and iOS without writing any bridge code by hand.

This is the **brief** overview. For the line-by-line deep dive of every stage, see
[ARCHITECTURE.md](./ARCHITECTURE.md). For the consumer-facing guide (how to *use* the generated
`kmp-bridge` npm package in an app), see [`kmp-bridge/README.md`](../../../../../../kmp-bridge/README.md).

## Pipeline at a glance

```
shared/ (KMP commonMain)
   │  ./gradlew publishToMavenLocal            ← produces the metadata .klib
   ▼
KlibApiReader.kt        reads klib protobuf metadata → KmpModule (ApiModel.kt)
   │
   ├──► AndroidGenerator.kt   → kmp-bridge/android/src/main/java/…/<File>Module.kt   (Expo Kotlin modules)
   ├──► SwiftGenerator.kt     → kmp-bridge/ios/<File>Module.swift                    (Expo Swift modules)
   └──► TsBridgeGenerator.kt  → kmp-bridge/src/<File>.ts                             (TS types + wrappers)
   │
GeneratePlatformBridgesTask.kt   orchestrates the three generators, writes expo-module.config.json
   │
scripts/push-bridges.sh          builds XCFramework, copies AAR + generated files into kmp-bridge
   │  (--publish → yalc push)
   ▼
kmp-bridge (npm package)  →  consumer apps (expofirst, expoSecond)
```

One KMP **source file** produces one generated file per platform, named `<File>Module.kt`,
`<File>Module.swift`, and `<File>.ts`.

## Who does what

| Class / script | Responsibility |
|---|---|
| `KlibApiReader` | Parses klib protobuf metadata for the target package into a `KmpModule`. Applies all normalization (Flow variants → `FlowType`, typealiases expanded, mutable collections → read-only, `suspend fun` returning `Flow` → FLOW kind). Skips extension functions loudly. |
| `ApiModel.kt` | The shared data model (`KmpModule` → `KmpSourceFile` → `KmpDeclaration` → `KmpFunction`/`KmpTypeRef`). Also the shared predicates all generators agree on: `isJsImplementable()`, `proxiedSuspendFunctions()`, `containsTypeParam()`, `usesNonStringKeyMap()`. |
| `AndroidGenerator` | Emits Expo Kotlin modules: `Record` codecs for data/sealed classes, `Function`/`AsyncFunction`/flow start-stop triplets, instance registries for classes/interfaces, the reverse bridge (`create`/`call<Fn>` events/`resolve<Fn>`), and the runtime `__toWire` helper for generic positions. |
| `SwiftGenerator` | Same surface in Swift, plus everything Kotlin/Native + SKIE impose: ObjC selector mangling, `KotlinInt`-style boxing, `description()`/`doCopy` renames, `onEnum(of:)` sealed dispatch, runtime protocol conformance for JS-implemented interfaces. |
| `TsBridgeGenerator` | Emits the TS types (enums, record object types, sealed discriminated unions) and the `requireNativeModule` wrappers (handle-based classes, flat const objects, listeners, generics via `create<T>()`). |
| `GeneratePlatformBridgesTask` | Gradle task tying it together; cleans stale output, warns on duplicate simple names, writes `expo-module.config.json` with **separate** android/apple module lists scanned from the actual generated output. |
| `push-bridges.sh` | The 5-step pipeline per consumer in `registry.json`: build XCFramework → copy it → generate bridges → copy AAR → done. `--publish` adds `yalc push`. |

## Type conversion matrix

What each Kotlin type becomes **on the wire** (the value that actually crosses the JS bridge):

| Kotlin type | Android wire (Expo param/@Field) | Swift wire | TypeScript | Notes |
|---|---|---|---|---|
| `String`, `Boolean` | `String`, `Boolean` | `String`, `Bool` | `string`, `boolean` | as-is |
| `Int` | `Int` | `Int32` | `number` | |
| `Long` | `Double` | `Double` (param) / `Int64` (@Field) | `number` | JS has no 64-bit int; precision beyond 2⁵³ is lost |
| `Double`, `Float` | same | same | `number` | |
| `Byte`, `Short` | `Int` | `Int8`/`Int16` (@Field), `Int32` (param) | `number` | |
| `Char` | `String` (1 char) | `String` ↔ `unichar` | `string` | |
| `enum class` | `String` (case name) | `String` (case name) | TS string enum | decoded via `valueOf` / `decode<Enum>()` (throws on unknown) |
| `data class` | `<Name>Record` (Expo Record) | `<Name>Record` struct | `type <Name> = {…}` | via generated `toKmp()`/`toRecord()` codecs |
| `sealed class`/`sealed interface` | flat `<Name>Record` + `type` discriminator | same | discriminated union `{ type: "Variant"; … }` | all variant fields unioned & nullable; abstract variants throw on decode |
| `object` | singleton module | `.shared` singleton module | flat const wrapper | no create/destroy |
| top-level fns/props | `<File>Kt`-style module | `<File>Kt` facade calls | flat const wrapper | `Kt` suffix only when the name collides |
| `class` (concrete, with fns) | instance map keyed by UUID handle | same | wrapper class with `_handle`; `create()`/`destroy()` | |
| `interface` / `abstract class` | `<Name>Registry` (id-keyed holders) | `_instances` static map | wrapper class; JS-implementable via `create(...)` + `call<Fn>` events + `resolve<Fn>` | sync members can't be JS-implemented |
| `List<T>` / `Set<T>` | `List<wire(T)>` | `[wire(T)]` | `wire(T)[]` | Sets become JS arrays; converted back with `.toSet()` |
| `Map<String, V>` | `Map<String, wire(V)>` | `[String: wire(V)]` | `{ [key: string]: wire(V) }` | **non-String keys are skipped loudly** |
| `Flow<T>` (return/property) | `start<Name>`/`stop<Name>` + `on<Name>Update`/`Error`/`Complete` events | same (ObjC `collect` via `__FlowCollector`) | ref-counted `subscribe<Name>({next, error?, complete?})` | element converted like a return value; error/complete are terminal |
| `suspend fun` | `AsyncFunction` + `launchSettled` | `AsyncFunction` + `Task` | `Promise<…>` | promise settles exactly once, incl. on `destroy()` |
| generic `T` (erased position) | `Any` + runtime `__toWire(value)` | `Any` + runtime `__toWire(value)` | real generic `T` via `create<T>()` | see limitations below |

## Running it

```bash
# 1. Whenever shared/src changes (REQUIRED before generating — stale klib = broken bridge):
cd shared && ./gradlew publishToMavenLocal

# 2. Generate + copy everything into kmp-bridge:
cd shared-artifacts && bash scripts/push-bridges.sh            # add --publish to yalc-push to apps

# 3. Manual compile checks (there is NO automatic gate — run these after generator changes):
cd expofirst/android && ./gradlew :kmp-bridge:compileDebugKotlin        # Android
cd expofirst/ios && pod install && xcodebuild -workspace expofirst.xcworkspace \
    -scheme KmpBridge -sdk iphonesimulator build                        # iOS
cd expofirst && npx tsc --noEmit                                        # TypeScript
```

Generated output is **gitignored** (`kmp-bridge/.gitignore`) — commits contain only
generator/reader/fixture changes, never generated files.

## What gets skipped (loudly)

The reader and generators never fail the build on an unbridgeable shape — they emit a
`>> [Android|iOS|TS|Reader] … SKIPPED: …` log line and a comment in the generated output.
Current skip reasons: extension functions, member functions on data/sealed classes,
functions with > 8 params (Expo DSL limit, including the synthetic `instanceId`),
`Map` with non-String keys, zero-function classes/objects/file-scopes, fieldless data classes,
`create()` for types with `Flow`/generic-typed abstract properties, and (iOS only)
functions whose param/return types have no Swift bridge representation.

## Known limitations

- **Member properties** on classes/objects are invisible (only top-level properties and
  *abstract* properties are read). Improvement plan T3.
- **Inherited members** from supertypes are not bridged (no supertype resolution in the reader).
- **Generic `T` parameters** *into* KMP pass through unconverted — primitives work, records don't.
- **iOS `__toWire`** can only convert record types declared in the same source file
  (Swift codecs are `fileprivate`).
- Registry/instance maps grow until `destroy()` is called — no automatic GC hook yet. Plan T5.
- **`StateFlow`/`SharedFlow`-typed returns** would not compile on iOS: the error-aware collect
  path converts via `SkieKotlinFlow(...)`, which only accepts plain `SkieSwiftFlow`. Plain
  `Flow<T>` (all current fixtures) is fine.
- Flow function names merely *ending* in "flow" get that suffix stripped case-insensitively
  (`overflow` → `over`) — avoid such names.
