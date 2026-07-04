# 05 — Collections

> Fixture: `FixtureCollectionsApi` · Generated excerpts captured at commit `e471661` ·
> Vocabulary: [00-overview](00-overview.md)

`List`, `Set`, and `Map` all cross as JS arrays/objects, converted **element-wise** so that
records/enums/primitives inside them follow their own rules recursively. Two structural facts
drive everything: **JS has no Set** (Sets become arrays), and **JS object keys are strings**
(non-String-keyed maps can't be bridged faithfully).

## The wire table

| Kotlin | Android wire | Swift wire | TS | Notes |
|---|---|---|---|---|
| `List<T>` | `List<wire(T)>` | `[wire(T)]` | `wire(T)[]` | element-wise |
| `Set<T>` | `List<wire(T)>` | `[wire(T)]` | `wire(T)[]` | **Set → array**; restored with `.toSet()` inbound |
| `Map<String, V>` | `Map<String, wire(V)>` | `[String: wire(V)]` | `{ [key: string]: wire(V) }` | values converted; keys must be String |
| `List<*>` / `Map<*,*>` | `List<Any?>` / `Map` | `[Any]` / `[…]` | `unknown[]` / `{[key:string]:unknown}` | star → `unknown`/`Any` |
| `List<out T>` | same as `List<T>` | same | same | variance ignored (read via `typeOrNull()`) |
| `Map<Int, V>` | **skipped** | **skipped** | **skipped** | non-String key — skipped on all three |

## 1 · Kotlin source

```kotlin
class FixtureCollectionsApi {
    fun getStringList(): List<String> = emptyList()
    fun getIntSet(): Set<Int> = emptySet()
    fun getStringIntMap(): Map<String, Int> = emptyMap()
    fun getNullableList(): List<String>? = null
    fun getNestedList(): List<List<String>> = emptyList()
    fun getUserMap(): Map<String, List<FixtureUser>> = emptyMap()   // nested: map→list→record
    fun getUserList(): List<FixtureUser> = emptyList()
    fun covariantList(): List<out FixtureUser> = emptyList()        // variance
    fun starList(): List<*> = emptyList<Any>()                      // star projection
    fun starMap(): Map<*, *> = emptyMap<Any, Any>()
    fun badMap(): Map<Int, String> = emptyMap()                     // non-String key
}
```

## 2 · klib reader

`CollectionType(kind, typeArgs, nullable)`. `MutableList`/`Collection`/`Iterable` all normalize
to LIST; variance and star projection are preserved in the type args but the generators read
them through `typeOrNull()`.

```
CLASS  FixtureCollectionsApi
  functions:
    [SYNC]  getIntSet() → SET<INT>
    [SYNC]  getUserMap() → MAP<STRING, LIST<FixtureUser>>
    [SYNC]  covariantList() → LIST<out FixtureUser>
    [SYNC]  starMap() → MAP<*, *>
    [SYNC]  badMap() → MAP<INT, STRING>
```

## 3 · AndroidGenerator

Element-wise `.let { r0 -> … }` conversions, only emitted when the element actually needs
converting (`getStringList` → nothing; `getIntSet` → `.toList()`; `getUserMap` → nested map):

```kotlin
Function("getIntSet") { instanceId: String ->
  (instances[instanceId] ?: error(…)).getIntSet().let { r0 -> r0.toList() }   // Set → List
}

Function("getUserMap") { instanceId: String ->
  (instances[instanceId] ?: error(…)).getUserMap()
    .let { r0 -> r0.mapValues { (_, e1) -> e1.map { e2 -> e2.toRecord() } } }  // map→list→record
}

Function("covariantList") { instanceId: String ->
  (instances[instanceId] ?: error(…)).covariantList().let { r0 -> r0.map { e1 -> e1.toRecord() } }
}                                     // `out` ignored — treated as List<FixtureUser>

Function("starMap") { instanceId: String ->
  (instances[instanceId] ?: error(…)).starMap()   // star → Any?, no conversion
}

// BRIDGE SKIPPED: badMap() — Map with non-String keys is not bridgeable (JS objects are string-keyed).
```

Swift and TS emit the identical skip (`>> [iOS]` / `>> [TS] FUNCTION SKIPPED: … Map with
non-String keys …`) — the `usesNonStringKeyMap()` check runs in all three generators.

## 4 · SwiftGenerator

Same element-wise shape (`.map`/`.mapValues`), with SKIE's primitive unboxing where element
types are boxed:

```swift
Function("getIntSet") { (instanceId: String) in
  guard let inst = … else { fatalError(…) }
  return inst.getIntSet()             // [Int32] — no per-element conversion needed here
}

Function("getUserMap") { (instanceId: String) in
  guard let inst = … else { fatalError(…) }
  return inst.getUserMap().mapValues { v in v.map { i in toRecord(i) } }
}

Function("covariantList") { (instanceId: String) in
  return inst.covariantList().map { i in toRecord(i) }
}
```

## 5 · TsBridgeGenerator

```typescript
getIntSet(): number[] { return _FixtureCollectionsApi.getIntSet(this._handle) }         // Set → array
getNullableList(): string[] | null { … }
getNestedList(): string[][] { … }
getUserMap(): { [key: string]: FixtureUser[] } { … }                                     // nested
covariantList(): FixtureUser[] { … }
starList(): unknown[] { … }
starMap(): { [key: string]: unknown } { … }                                              // star key → string
```

Map key types degrade to `string` unless they're literally `string`/`number` (TS index-signature
restriction) — a star key becomes `[key: string]`.

## 6 · RN consumption

```typescript
const api = FixtureCollectionsApi.create();
api.getIntSet();     // → []            (array, even though Kotlin returns a Set)
api.getUserMap();    // → {}            ({ [k]: FixtureUser[] })
api.destroy();
```

A populated `getUserMap` would put a plain nested object on the wire:
`{ "teamA": [ { "id": "…", … } ] }` — every `FixtureUser` already flattened to its record.

## 7 · Edges

| Situation | Behavior |
|---|---|
| `Map<Int, String>` (`badMap`) | Skipped loudly on **all three** platforms (`usesNonStringKeyMap()` — the Swift check was added so it matches Android/TS instead of emitting an unbridgeable `[Int32: String]`). |
| `Set` | always an array on the wire; order not guaranteed; duplicates collapse on the inbound `.toSet()` |
| star `List<*>`/`Map<*,*>` | elements cross as `Any?`/`unknown` — no conversion, no type safety |
| variance (`in`/`out`) | ignored — bridges identically to the invariant form |
| deeply nested (`Map<String, List<FixtureUser>>`) | fully supported, converted at every level |
