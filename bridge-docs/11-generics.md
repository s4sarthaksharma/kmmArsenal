# 11 — Generics

> Fixture: `FixtureGenericApi<T>` · Generated excerpts captured at commit `e471661` ·
> Vocabulary: [00-overview](00-overview.md)

A generic class is the one shape where the native side genuinely **cannot know the type** — `T`
is erased to `Any`/`AnyObject` at the bridge. The solution has two halves: a **runtime**
converter (`__toWire`) that inspects the actual value's class on the way out, and a **caller
asserted** TS generic (`create<T>()`) that types the JS surface. It works for returns, flow
elements, and collection elements; it does **not** work for `T`-typed parameters going *into*
Kotlin.

## 1 · Kotlin source

```kotlin
class FixtureGenericApi<T> {
    fun get(): T = "generic_value" as T           // T return
    fun getOrNull(): T? = null                    // nullable T
    fun wrap(): List<T> = emptyList()             // T in a collection
    fun observe(): Flow<T> = flow { … }           // T as flow element
    suspend fun fetch(): T = "fetched_generic" as T
    fun getUser(): T = FixtureUser(…) as T        // runtime T = a data class
    fun wrapUsers(): List<T> = listOf(getUser())
}
```

## 2 · klib reader

The class carries `typeParameters = ["T"]`; every `T` position is `KmpTypeRef.TypeParam("T")`.
`containsTypeParam()` on any signature is what later tells the generators to emit the runtime
`__toWire` helper.

```
CLASS  FixtureGenericApi
  functions:
    [SYNC]     get() → T
    [SYNC]     wrapUsers() → LIST<T>
    [FLOW]     observe() → T
    [SUSPEND]  fetch() → T
```

(The model doesn't record the concrete `T` per call site — there isn't one; erasure is inherent.)

## 3 · AndroidGenerator

The generic class instantiates as `FixtureGenericApi<Any>`, and every `T`-typed position routes
through a file-level **runtime converter**. Return: `.let { r0 -> __toWire(r0) }`; collection
element: `.map { e1 -> __toWire(e1) }`:

```kotlin
Function("getUser") { instanceId: String ->
  (instances[instanceId] ?: error(…)).instance.getUser().let { r0 -> __toWire(r0) }
}
Function("wrapUsers") { instanceId: String ->
  (instances[instanceId] ?: error(…)).instance.wrapUsers().let { r0 -> r0.map { e1 -> __toWire(e1) } }
}
```

`__toWire` dispatches on the value's **actual runtime class** — the one thing available when the
static type is gone. It's emitted once per file that needs it, covering every data/sealed/enum
type in the module:

```kotlin
private fun __toWire(value: Any?): Any? = when (value) {
    is FixtureUser   -> value.toRecord()      // every data + sealed class
    is FixtureResult -> value.toRecord()
    is FixtureStatus -> value.name            // every enum
    is LightColor    -> value.name
    is List<*> -> value.map { __toWire(it) }
    is Set<*>  -> value.map { __toWire(it) }
    is Map<*, *> -> value.mapValues { (_, v) -> __toWire(v) }
    else -> value                             // primitives & unknowns pass through
}
```

So `getUser()` returning a `FixtureUser` at runtime is converted to a full record even though the
static type was just `T`.

## 4 · SwiftGenerator

Same idea — a `fileprivate func __toWire(_:)` switching on the dynamic type — with one
constraint: the Swift codecs are `fileprivate`, so it can only convert record/sealed types
**declared in the same source file** (all of `BridgeTypeFixture.kt`'s are, so it's fine here);
plus all enums (via `.name`, which needs no codec). Flow returns of `T` also go through
`SkieKotlinOptionalFlow(...)` — an unbounded `T` has upper bound `Any?`, so SKIE surfaces it as
the optional flow type. See [ARCHITECTURE §6.6](../shared-artifacts/buildSrc/src/main/kotlin/bridgegen/ARCHITECTURE.md).

## 5 · TsBridgeGenerator

The class becomes a **real TS generic**; the caller asserts `T` at `create`, and `T`-typed
members return it. The native side stays erased — correctness rests on `__toWire` actually
producing what `T` claims:

```typescript
export class FixtureGenericApi<T = unknown> {
  /** @internal */ readonly _handle: string
  private _observeState = { active: false, count: 0 }
  private constructor(handle: string) { this._handle = handle }
  static create<T = unknown>(): FixtureGenericApi<T> {
    return new FixtureGenericApi<T>(_FixtureGenericApi.create())
  }
  destroy(): void { this._observeState.active = false; _FixtureGenericApi.destroy(this._handle) }

  get(): T { return _FixtureGenericApi.get(this._handle) }
  wrap(): T[] { … }
  fetch(): Promise<T> { … }
  subscribeObserve(handlers: { next: (value: T) => void; … }): { remove } { … }
}
```

(A `T` in scope resolves to the parameter name in `toTsType`; a `T` *not* in a generic wrapper's
scope would fall back to `unknown`.)

## 6 · RN consumption

```typescript
const api = FixtureGenericApi.create<FixtureUser>();   // caller asserts T
const u = api.getUser();      // typed FixtureUser — runtime __toWire produced a real record:
// { "id": "generic_user", "age": 33, "status": "ACTIVE", … }
const list = api.wrapUsers(); // FixtureUser[]
api.subscribeObserve({ next: (v) => … });              // v typed as FixtureUser
api.destroy();

const strApi = FixtureGenericApi.create<string>();
strApi.get();                 // "generic_value" — primitive passes through __toWire untouched
```

The assertion is a promise *you* make — `create<FixtureUser>()` on an instance whose `T` is
actually something else would still type-check but return mismatched data. The native side has
no way to enforce it.

## 7 · Edges

| Situation | Behavior |
|---|---|
| `T`-typed **parameter** into Kotlin | only primitives pass through; a JS record is **not** reconstructed to a KMP type (there's no way to know which `toKmp` to run) — documented limitation |
| iOS cross-file record type in a `T` position | not converted — the Swift `__toWire` only sees same-file `fileprivate` codecs |
| wrong `create<T>()` assertion | type-checks but returns mismatched data — no runtime enforcement |
| primitive `T` | passes through `__toWire` unchanged |
| `T` return that's a data class | fully converted to a record at runtime (`getUser` demonstrates) |
