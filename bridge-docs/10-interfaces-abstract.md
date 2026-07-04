# 10 — Interfaces & abstract classes

> Fixtures: `FixtureRepository`, `FixtureMarker`, `FixtureBaseProcessor` /
> `FixtureConfiguredProcessor` / `FixtureStatefulProcessor` / `FixtureStreamingProcessor`,
> `FixtureNamedResource` · Generated excerpts captured at commit `e471661` ·
> Vocabulary: [00-overview](00-overview.md)

The most involved shape, because it bridges **both directions**:

- **KMP-implemented** — a concrete instance produced by Kotlin (returned from another function)
  is registered and handed to JS as an opaque handle; JS method calls dispatch back through the
  registry.
- **JS-implemented** — JS calls `create()`, which builds an anonymous Kotlin subtype whose
  suspend methods **proxy back to JS**: each call emits a `call<Fn>` event and blocks on a
  `CompletableDeferred` until JS calls the matching `resolve<Fn>`.

Interfaces and abstract classes share this machinery (both have no directly-constructible
instance). A **zero-function interface** (`FixtureMarker`) is the exception — it's just a
type-only TS `interface` with no runtime module.

## 1 · Kotlin source

```kotlin
interface FixtureRepository {
    fun findById(id: String): FixtureUser                 // SYNC — KMP-implemented only
    suspend fun fetchById(id: String): FixtureUser        // SUSPEND — proxies to JS
    suspend fun countAll(): Int                           // numeric resolve wire contract
    suspend fun findByStatus(status: FixtureStatus): Int  // enum in the call<Fn> payload
    fun observeAll(): Flow<List<FixtureUser>>             // FLOW
}
interface FixtureMarker                                   // zero functions → type-only

abstract class FixtureConfiguredProcessor(val label: String) {   // ctor param → create(label)
    fun describe(): String = "processor:$label"           // concrete → inherited, not proxied
    abstract suspend fun run(input: String): String       // abstract → proxied
}
abstract class FixtureStatefulProcessor {
    abstract val state: String                            // abstract property → create(state)
    abstract suspend fun run(input: String): String
}
abstract class FixtureStreamingProcessor {
    abstract val updates: Flow<String>                    // Flow property → NOT JS-implementable
    abstract suspend fun run(input: String): String
}
```

## 2 · klib reader

`KmpInterface` / `KmpClass(isAbstract = true)`. Constructor params land in `ctorFields`, abstract
properties in `abstractProps`, and `isAbstractMember`/`isOverridable` mark which functions a JS
subtype must override vs inherit. The shared predicate `isJsImplementable()` decides whether the
`create()`/`resolve` surface is emitted at all.

```
INTERFACE  FixtureRepository        ABSTRACT CLASS  FixtureConfiguredProcessor
  [SYNC]     findById(id) → FixtureUser    [SYNC]     describe() → STRING      (concrete)
  [SUSPEND]  fetchById(id) → FixtureUser   [SUSPEND]  run(input) → STRING      (abstract)
  [SUSPEND]  countAll() → INT
  [FLOW]     observeAll() → LIST<FixtureUser>
INTERFACE  FixtureMarker  (none)   ← zero functions
```

## 3 · AndroidGenerator

Each type gets an internal **registry** (id-keyed holders with a scope, flow jobs, and
`pendingCalls`) plus a `Module`:

```kotlin
internal object FixtureRepositoryRegistry {
  class Holder(val instance: FixtureRepository) {
    val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    val flowJobs = mutableMapOf<FlowKey, Job>()
    val pendingCalls = ConcurrentHashMap<String, CompletableDeferred<Any?>>()
  }
  private val holders = ConcurrentHashMap<String, Holder>()
  fun register(instance: FixtureRepository): String { … }   // KMP-implemented: hand JS a handle
  fun registerWithId(id: String, instance: …) { … }         // JS-implemented: create() path
  fun get(instanceId: String): Holder = …
}
```

**KMP-implemented direction** — a function returning the interface registers it and returns the
id (this is what `toReturnSuffix` does for an interface-typed return):

```kotlin
Function("getRepository") { instanceId: String ->
  (instances[instanceId] ?: error(…)).instance.getRepository().let { FixtureRepositoryRegistry.register(it) }
}
```

**JS-implemented direction** — `create()` builds the anonymous subtype. Constructor params
thread through (`FixtureConfiguredProcessor(label)`); abstract suspend members proxy via events;
concrete members are inherited (never overridden):

```kotlin
Function("create") { label: String ->
  val instanceId = UUID.randomUUID().toString()
  val emitEvent = { eventName: String, body: Map<String, Any?> -> sendEvent(eventName, body) }
  val impl = object : FixtureConfiguredProcessor(label) {       // ctor arg threaded
    override suspend fun run(input: String): String {           // only the abstract member
      val holder = FixtureConfiguredProcessorRegistry.get(instanceId)
      val callId = UUID.randomUUID().toString()
      val deferred = CompletableDeferred<Any?>()
      holder.pendingCalls[callId] = deferred
      try {
        emitEvent("callRun", mapOf("instanceId" to instanceId, "callId" to callId, "input" to input))
        return (deferred.await() as String)                     // resolveWireContract cast
      } finally {
        holder.pendingCalls.remove(callId)                      // abandoned awaits must not leak
      }
    }
    // describe() is NOT here — inherited from the real KMP class
  }
  FixtureConfiguredProcessorRegistry.registerWithId(instanceId, impl)
  instanceId
}
```

`resolve<Fn>` completes the deferred; its parameter type is the return type's wire form (the
**wire contract** — param type and cast come from one `when` so they can't drift):

```kotlin
Function("resolveFetchById") { instanceId: String, callId: String, result: FixtureUserRecord ->
  FixtureRepositoryRegistry.get(instanceId).pendingCalls.remove(callId)?.complete(result)
}
Function("resolveCountAll") { instanceId: String, callId: String, result: Int ->   // Int, not Record
  FixtureRepositoryRegistry.get(instanceId).pendingCalls.remove(callId)?.complete(result)
}
```

Sync members of a JS-implemented instance throw `UnsupportedOperationException` (JS can't block
synchronously); abstract properties become constructor-supplied `val` overrides.

## 4 · SwiftGenerator

Same registry/reverse-bridge design, but the JS-impl class is built differently by type:

- **abstract class** → a real Swift subclass; abstract suspend members override SKIE's
  `__`-prefixed completion-handler form; ctor args go to `super.init`; abstract properties become
  stored `@objc var` backings supplied at init.
- **interface** → Swift's static conformance checker fights SKIE, so the impl is a plain
  `NSObject` whose methods carry explicit `@objc(selector)` attributes, and conformance is added
  at runtime with `class_addProtocol` (`objc_getProtocol("<Framework><Name>")`), then force-cast
  `as! any <Name>`.

`_pendingCalls` is a static map on the module; `resolve<Fn>` completes it. Full detail:
[ARCHITECTURE §6.5](../shared-artifacts/buildSrc/src/main/kotlin/bridgegen/ARCHITECTURE.md).

## 5 · TsBridgeGenerator

A handle wrapper class with `_wrap` (for KMP-returned instances), `create()` (only when
JS-implementable), the forward methods, and — per proxied suspend member — an
`addCall<Fn>Listener` + `resolve<Fn>` pair:

```typescript
export class FixtureRepository {
  /** @internal */ readonly _handle: string
  private _observeAllState = { active: false, count: 0 }
  private constructor(handle: string) { this._handle = handle }
  static _wrap(handle: string): FixtureRepository { return new FixtureRepository(handle) }
  static create(): FixtureRepository { return FixtureRepository._wrap(_FixtureRepository.create()) }
  destroy(): void { this._observeAllState.active = false; _FixtureRepository.destroy(this._handle) }

  findById(id: string): FixtureUser { … }                    // forward: SYNC
  fetchById(id: string): Promise<FixtureUser> { … }          // forward: SUSPEND
  countAll(): Promise<number> { … }
  subscribeObserveAll(handlers): { remove } { … }            // forward: FLOW (see 08)

  // reverse bridge — implement the interface FROM JS:
  addCallFetchByIdListener(handler: (event: { callId: string; id: string }) => void) {
    return _FixtureRepository.addListener('callFetchById', (e: any) => {
      if (e.instanceId === this._handle) handler({ callId: e.callId, id: e.id })
    })
  }
  resolveFetchById(callId: string, result: FixtureUser): void { … }
}

export interface FixtureMarker {}                             // zero-function → type only
```

## 6 · RN consumption

**KMP-implemented** (received, called):

```typescript
const repo = api.getRepository();          // opaque handle wrapped as FixtureRepository
const user = await repo.fetchById("u1");   // dispatches into the Kotlin instance
repo.destroy();
```

**JS-implemented** (create, answer callbacks) — this is the reverse bridge in action:

```typescript
const repo = FixtureRepository.create();
repo.addCallFetchByIdListener(({ callId, id }) => {
  // KMP called fetchById(id) — answer it:
  repo.resolveFetchById(callId, { id, age: 1, /* …a FixtureUser… */ });
});
// hand `repo` to any KMP function taking a FixtureRepository; its fetchById() now runs in JS.
```

Wire protocol for one proxied call: KMP emits `callFetchById { instanceId, callId, id }`; JS
replies via `resolveFetchById(handle, callId, <FixtureUser record>)`.

## 7 · Edges

| Situation | Behavior |
|---|---|
| Zero-function interface (`FixtureMarker`) | type-only TS `interface {}`, no runtime module |
| Flow-typed abstract property (`FixtureStreamingProcessor.updates`) | **not JS-implementable** — `CREATE SKIPPED: … — cannot be JS-implemented (abstract properties with no wire representation: updates).` KMP-implemented direction still works |
| Sync member on a JS-implemented instance | throws `UnsupportedOperationException` — JS can't block synchronously |
| Flow member on a JS-implemented instance | throws — JS-implemented flows not yet supported |
| ctor params + abstract props > 8 | `CREATE SKIPPED: … — constructor params + abstract properties exceed 8 parameters` |
| concrete member of an abstract class | inherited from the real KMP class, not proxied to JS |
| abandoned `resolve` (JS never answers) | the awaiting call hangs until `destroy()` cancels the scope; `pendingCalls` entry is cleaned in `finally` |
