# bridge-docs — type journeys through the KMP → React Native bridge

Every document here traces one API **shape** from `shared/src/commonMain/kotlin/com/example/shared/BridgeTypeFixture.kt`
through the complete pipeline, with real excerpts at every station:

```
Kotlin source → klib reader (model) → AndroidGenerator → SwiftGenerator → TsBridgeGenerator → RN app
```

For pipeline *theory* (why the stages exist, the full conversion rules, the K/N + SKIE rule
inventory) see [`shared-artifacts/buildSrc/src/main/kotlin/bridgegen/ARCHITECTURE.md`](../shared-artifacts/buildSrc/src/main/kotlin/bridgegen/ARCHITECTURE.md).
These docs are the *practice*: concrete, one worked example per shape.

## How to read a doc

Every numbered doc has the same seven stations:

| § | Station | What you see |
|---|---|---|
| 1 | Kotlin source | the fixture declaration as written |
| 2 | klib reader | how the reader classifies it — real `kmp-model-dump.txt` excerpt |
| 3 | AndroidGenerator | generated Kotlin (Expo module) excerpt + why each line exists |
| 4 | SwiftGenerator | generated Swift excerpt + the Kotlin/Native + SKIE constraints behind it |
| 5 | TsBridgeGenerator | generated TypeScript types + wrappers |
| 6 | RN consumption | app-side usage + the literal wire payload JSON |
| 7 | Edges | skip messages this shape can trigger, limitations |

## The documents

| Doc | Shapes covered | Fixtures |
|---|---|---|
| [00-overview](00-overview.md) | pipeline recap + the model vocabulary every §2 uses | — |
| [01-primitives](01-primitives.md) | all 9 primitives, nullability, wire widenings | FixturePrimitivesApi |
| [02-enums](02-enums.md) | enum classes — the case-name-string contract | FixtureStatus |
| [03-data-classes](03-data-classes.md) | data classes → Record codecs, nesting | FixtureUser, FixtureAddress, FixtureTeam |
| [04-sealed-types](04-sealed-types.md) | sealed classes/interfaces → tagged records | FixtureResult, FixtureAuthState, FixtureShape, FixturePayment |
| [05-collections](05-collections.md) | List/Set/Map, nesting, non-String-key skip | FixtureCollectionsApi |
| [06-classes-objects](06-classes-objects.md) | instance lifecycle, singletons, complex params | FixtureParamsApi, FixtureAnalytics |
| [07-suspend](07-suspend.md) | suspend fns → Promises, exactly-once settlement | FixtureAsyncApi |
| [08-flows](08-flows.md) | Flow → subscribe, the 3 events, iOS error story | FixtureAsyncApi flows, failing/finite streams |
| [09-file-scope](09-file-scope.md) | top-level fns/props, typealias, name collisions | BridgeTypeFixture file scope, FixtureNamedIface |
| [10-interfaces-abstract](10-interfaces-abstract.md) | registries, reverse bridge, JS implementations | FixtureRepository, FixtureBaseProcessor family |
| [11-generics](11-generics.md) | type erasure, runtime conversion, `create<T>()` | FixtureGenericApi |

## Staleness policy

Generated-code excerpts are **snapshots** — each doc's header states the commit they were
captured at. They do not update themselves: after a generator change, re-run the pipeline and
refresh any excerpt the change touched (the doc headers tell you where each excerpt came from).
The *shapes* of the conversions change rarely; the excerpts are illustrative even when a few
lines drift.

Source artifacts the excerpts come from:

- model: `shared-artifacts/build/kmp-model-dump.txt` (`./gradlew dumpKmpModel -Pkmp…`)
- Android: `kmp-bridge/android/src/main/java/expo/modules/kmpbridge/*.kt`
- iOS: `kmp-bridge/ios/*Module.swift`
- TS: `kmp-bridge/src/*.ts`
- app usage: `expofirst/src/app/index.tsx`
