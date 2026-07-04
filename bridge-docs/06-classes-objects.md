# 06 — Classes, objects & the instance lifecycle

> Fixtures: `FixtureParamsApi` (concrete class), `FixtureAnalytics` (object) ·
> Generated excerpts captured at commit `e471661` · Vocabulary: [00-overview](00-overview.md)

This doc covers the two "container" shapes and the **sync function** conversions that ride on
them. A concrete `class` becomes an *instance-based* module (`create()`/`destroy()` + a handle);
an `object` becomes a *singleton* module (no lifecycle — calls go straight to the shared
instance). `FixtureParamsApi` also demonstrates every complex-type parameter direction (record,
sealed, list, map, nullable) since sync params are where inbound conversion lives.

## 1 · Kotlin source

```kotlin
class FixtureParamsApi {                                   // concrete → instance-based
    fun saveUser(user: FixtureUser): FixtureUser = user
    fun saveNullableUser(user: FixtureUser?): String = user?.id ?: "null-user"
    fun describeResult(result: FixtureResult): String = when (result) { … }
    fun saveAll(users: List<FixtureUser>): Int = users.size
    fun saveTeam(team: FixtureTeam): FixtureTeam = team
    fun tagUsers(usersByTag: Map<String, FixtureUser>): List<String> = usersByTag.keys.toList()
}

object FixtureAnalytics {                                  // object → singleton
    fun track(event: String): Unit = Unit
    suspend fun flush(): Boolean = true                    // see 07
    fun events(): Flow<String> = flow { … }                // see 08
}
```

## 2 · klib reader

```
CLASS   FixtureParamsApi          OBJECT  FixtureAnalytics
  functions:                        functions:
    [SYNC]  saveUser(user: FixtureUser) → FixtureUser    [SYNC]     track(event: STRING) → Unit
    [SYNC]  describeResult(result: FixtureResult) → …    [SUSPEND]  flush() → BOOLEAN
    [SYNC]  tagUsers(usersByTag: MAP<STRING, …>) → …     [FLOW]     events() → STRING
    …
```

`KmpClass` (concrete → instance-based bridging) vs `KmpObject` (singleton). A class with **no
functions** is skipped (`CLASS SKIPPED: … — no functions to bridge.`).

## 3 · AndroidGenerator

**Concrete class** — an `instances` map, a `create()` returning a UUID handle, a `destroy()`,
and every function taking `instanceId` as its first param:

```kotlin
class FixtureParamsApiModule : Module() {
  private val instances = ConcurrentHashMap<String, FixtureParamsApi>()

  override fun definition() = ModuleDefinition {
    Name("FixtureParamsApi")

    Function("create") {
      val id = UUID.randomUUID().toString()
      instances[id] = FixtureParamsApi()
      id
    }
    Function("destroy") { instanceId: String -> instances.remove(instanceId) }

    Function("saveUser") { instanceId: String, user: FixtureUserRecord ->
      (instances[instanceId] ?: error(…)).saveUser(user.toKmp()).toRecord()   // in .toKmp, out .toRecord
    }
    Function("describeResult") { instanceId: String, result: FixtureResultRecord ->
      (instances[instanceId] ?: error(…)).describeResult(result.toKmp())      // sealed param
    }
    Function("saveTeam") { instanceId: String, team: FixtureTeamRecord ->
      (instances[instanceId] ?: error(…)).saveTeam(team.toKmp()).toRecord()
    }
  }
}
```

(When a class has any suspend/flow function the plain `instances` map is replaced by an
`InstanceHolder` that pairs each instance with its own `CoroutineScope` — see
[07](07-suspend.md)/[08](08-flows.md). `FixtureParamsApi` is sync-only so it stays simple.)

**Object** — no `instances`, no `create`/`destroy`; calls go to the singleton by name:

```kotlin
class FixtureAnalyticsModule : Module() {
  override fun definition() = ModuleDefinition {
    Name("FixtureAnalytics")
    Function("track") { event: String -> FixtureAnalytics.track(event) }
    // flush (suspend) and events (flow) also here — see 07 / 08
  }
}
```

## 4 · SwiftGenerator

Same two structures. Concrete class:

```swift
public class FixtureParamsApiModule: Module {
  private var instances: [String: FixtureParamsApi] = [:]
  public func definition() -> ModuleDefinition {
    Name("FixtureParamsApi")
    Function("create") { let id = UUID().uuidString; self.instances[id] = FixtureParamsApi(); return id }
    Function("destroy") { (instanceId: String) in self.instances.removeValue(forKey: instanceId) }

    Function("saveUser") { (instanceId: String, user: FixtureUserRecord) throws in
      guard let inst = self.instances[instanceId] else { fatalError(…) }
      return toRecord(try inst.saveUser(user: try user.toKmp()))     // throws: toKmp can throw
    }
  }
}
```

Object uses `let analytics = FixtureAnalytics.shared` and calls through it (`.shared` is how K/N
exposes Kotlin singletons).

## 5 · TsBridgeGenerator

**Concrete class** → a wrapper class with a private `_handle`:

```typescript
export class FixtureParamsApi {
  /** @internal */ readonly _handle: string
  private constructor(handle: string) { this._handle = handle }
  static create(): FixtureParamsApi { return new FixtureParamsApi(_FixtureParamsApi.create()) }
  destroy(): void { _FixtureParamsApi.destroy(this._handle) }

  saveUser(user: FixtureUser): FixtureUser { return _FixtureParamsApi.saveUser(this._handle, user) }
  describeResult(result: FixtureResult): string { return _FixtureParamsApi.describeResult(this._handle, result) }
  tagUsers(usersByTag: { [key: string]: FixtureUser }): string[] { … }
}
```

**Object** → a flat const (no lifecycle):

```typescript
export const FixtureAnalytics = {
  track: (event: string): void => _FixtureAnalytics.track(event),
  flush: (): Promise<boolean> => _FixtureAnalytics.flush(),        // 07
  subscribeEvents: (handlers) => { … },                            // 08
};
```

## 6 · RN consumption

```typescript
import { FixtureParamsApi, FixtureAnalytics } from "kmp-bridge/src/BridgeTypeFixture";

const api = FixtureParamsApi.create();               // native instance created, handle held
api.describeResult({ type: "Success", user, code: 200 });  // → "success:200"
api.tagUsers({ a: user });                                 // → ["a"]
api.destroy();                                             // REQUIRED — releases the instance

FixtureAnalytics.track("open");                            // singleton, no create/destroy
```

The wrapper's `_handle` is an opaque UUID string; the wire payload for a method call is
`["<handle>", ...converted args]`.

## 7 · Edges

| Situation | Behavior |
|---|---|
| No `destroy()` | native instance leaks — the map entry lives forever. No auto-GC (improvement-plan T5) |
| Method on a destroyed handle | `error("Instance not found: …")` / `fatalError` → surfaces as a JS error |
| Class with zero functions | `CLASS SKIPPED: … — no functions to bridge.` (still gets a TS type if it's also a data/sealed type) |
| Object with zero functions | `OBJECT SKIPPED: …` |
| > 8 params (incl. handle) | that function skipped ([01](01-primitives.md) §7) |
| Two classes with the same simple name in different packages | duplicate-name warning at generation; conversions may resolve to the wrong type |
