# 00 — Pipeline overview & model vocabulary

> Excerpts captured at commit `e471661`. See [README](README.md) for the staleness policy.

This doc gives you (a) the 60-second pipeline recap so every other doc can skip it, and
(b) the **model vocabulary** — the `ApiModel.kt` types that every doc's §2 ("klib reader")
excerpt is expressed in.

## The pipeline in one pass

```
shared/ (KMP commonMain)
   │  ./gradlew publishToMavenLocal        ← manual step; produces the metadata .klib
   ▼
KlibApiReader        parses klib protobuf → KmpModule (the model below)
   │
   ├─► AndroidGenerator   → kmp-bridge/android/…/<File>Module.kt    (Expo Kotlin modules)
   ├─► SwiftGenerator     → kmp-bridge/ios/<File>Module.swift       (Expo Swift modules)
   └─► TsBridgeGenerator  → kmp-bridge/src/<File>.ts                (TS types + wrappers)
   │
GeneratePlatformBridgesTask   orchestrates the three, writes expo-module.config.json
   │
scripts/push-bridges.sh       builds the XCFramework (injecting bridgeCollectFlow via
   │                          scripts/bridgegen.init.gradle), copies AAR + generated files
   ▼
kmp-bridge (npm) ──yalc──► expofirst / expoSecond
```

Key facts the type docs rely on:

- **One KMP source file → one generated file per platform.** All fixture shapes below live in
  `BridgeTypeFixture.kt`, so their generated homes are `BridgeTypeFixtureModule.kt`,
  `BridgeTypeFixtureModule.swift`, and `BridgeTypeFixture.ts`.
- The reader never sees Kotlin source (only compiled klib metadata), and the generators never
  see the klib (only the model). The model is the contract.
- `GeneratePlatformBridgesTask` also warns on duplicate simple names and scans the *generated
  text* for module class names to build `expo-module.config.json` (android and apple lists are
  scanned separately because the two generators legitimately skip different things).
- Unbridgeable shapes never fail the build — they are **skipped loudly** (`>> [Android|iOS|TS|Reader]
  … SKIPPED: …` log lines + a comment in the generated output). Each doc's §7 lists its shape's
  skip reasons.

Full theory: [ARCHITECTURE.md](../shared-artifacts/buildSrc/src/main/kotlin/bridgegen/ARCHITECTURE.md)
(§8 covers the task and script in depth).

## Model vocabulary (ApiModel.kt)

Everything the reader produces is a `KmpModule` → `KmpSourceFile` → list of `KmpDeclaration`.
The dump tool (`./gradlew dumpKmpModel …` → `shared-artifacts/build/kmp-model-dump.txt`)
renders this model; the §2 excerpts in every doc are taken from it.

### Declaration kinds (`KmpDeclaration`)

| Kind | Kotlin origin | Bridged as | Doc |
|---|---|---|---|
| `KmpEnum` | `enum class` | case-name strings + TS string enum | [02](02-enums.md) |
| `KmpDataClass` | `data class` | Record codec (`toKmp`/`toRecord`) + TS object type | [03](03-data-classes.md) |
| `KmpSealedClass` | `sealed class` / `sealed interface` | flat tagged Record + TS discriminated union | [04](04-sealed-types.md) |
| `KmpClass` (concrete) | `class` with functions | instance module: `create()`/`destroy()` + handle | [06](06-classes-objects.md) |
| `KmpClass` (`isAbstract`) | `abstract class` | registry module + reverse bridge | [10](10-interfaces-abstract.md) |
| `KmpInterface` | `interface` | registry module + reverse bridge | [10](10-interfaces-abstract.md) |
| `KmpObject` | `object` | singleton module (no lifecycle) | [06](06-classes-objects.md) |
| `KmpFileScope` | top-level fns/props of one file | `<File>`-named module, static calls | [09](09-file-scope.md) |

Fields worth knowing: `KmpClass.ctorFields` (primary-constructor params — threaded through the
reverse bridge's `create()`), `KmpClass.abstractProps`/`KmpInterface.abstractProps` (abstract
properties a JS implementation must supply), `KmpClass.typeParameters` (generics, [11](11-generics.md)),
`KmpVariant.isNested` (whether a sealed variant is `Parent.Variant` or top-level).

### Function kinds (`KmpFunction.kind`)

| Kind | Determined by | Bridged as | Doc |
|---|---|---|---|
| `SYNC` | neither of the below | Expo `Function` — synchronous call | [06](06-classes-objects.md) |
| `SUSPEND` | `suspend` modifier | Expo `AsyncFunction` — JS Promise | [07](07-suspend.md) |
| `FLOW` | return type is `Flow<T>` (incl. `StateFlow`/`SharedFlow`; a `suspend fun` returning Flow is FLOW too) | start/stop + 3 events; TS `subscribe` | [08](08-flows.md) |

For FLOW, `returnType` holds the *element* type — the `Flow<…>` wrapper is stripped by the
reader. `isPropertyGetter` marks top-level `val`/`var` read as synthetic zero-param functions.
`isAbstractMember`/`isOverridable` drive the JS-implementation rules in [10](10-interfaces-abstract.md).

### Type references (`KmpTypeRef`)

| Ref | Example | Notes |
|---|---|---|
| `Primitive(kind, nullable)` | `STRING`, `INT?` | 9 kinds — see [01](01-primitives.md) for each one's wire shape |
| `UnitType` | `Unit` return | crosses as nothing/void |
| `CollectionType(kind, typeArgs, nullable)` | `LIST<STRING>`, `MAP<STRING, INT>` | LIST/MAP/SET; mutable variants normalize here |
| `FlowType(typeArg, nullable)` | `Flow<Int>` | normalized from all 5 Flow variants; unwrapped for FLOW functions |
| `ClassRef(qualifiedName, typeArgs, nullable)` | `FixtureUser`, `FixtureStatus` | any user-defined type; the generators decide its meaning by looking the simple name up in the module's enum/data/sealed/interface/abstract name sets |
| `TypeParam(name, nullable)` | `T` | generic parameter — erased at the bridge, see [11](11-generics.md) |

Type *arguments* (`KmpTypeArg`) preserve variance (`Invariant`/`Covariant`/`Contravariant`/`Star`),
but generators read them through `typeOrNull()` which ignores variance — `List<out FixtureUser>`
bridges identically to `List<FixtureUser>`; `*` becomes `unknown`/`Any`.

### Reader normalizations (already applied in every §2 excerpt)

- typealiases are **expanded** (the klib stores the expansion — `FixtureUserId` arrives as `STRING`)
- `MutableList`/`Collection`/`Iterable` → LIST; `MutableMap` → MAP; `MutableSet` → SET
- `StateFlow`/`SharedFlow`/mutable variants → `FlowType`
- data-class compiler synthetics (`copy`, `componentN`, …) dropped — on data classes only
- non-public declarations, annotation classes, `expect` classes dropped
- **skipped loudly**: extension functions (no receiver at the call site), functions with
  function-typed parameters (lambdas have no wire representation)
