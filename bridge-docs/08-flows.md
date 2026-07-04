# 08 — Flows

> Fixtures: `FixtureAsyncApi` flows (`observeUser`, `observeFailing`), file-scope
> `fixtureFailingStream`/`fixtureFiniteStream` · Generated excerpts captured at commit
> `e471661` · Vocabulary: [00-overview](00-overview.md)

A `Flow<T>` (or a Flow-typed property) is the richest shape. Each flow generates a native
start/stop pair plus **three events** — `on<Name>Update` (each value), `on<Name>Error`
(`{ message }`), `on<Name>Complete` — and collapses on the JS side into **one ref-counted
`subscribe<Name>` method**. The iOS error path required a dedicated Kotlin-side wrapper; that
story is §4.

## The event protocol

| Event | Payload | When |
|---|---|---|
| `on<Name>Update` | `{ value, instanceId? }` | each emitted element (converted like a return value) |
| `on<Name>Error` | `{ message, instanceId? }` | the Kotlin flow threw — **terminal** |
| `on<Name>Complete` | `{ instanceId? }` | the flow finished normally — **terminal** |

Exactly one terminal fires per started stream. `stop`/`destroy` (cancellation) fires **neither**.
`instanceId` is present only for instance-based flows so listeners can filter by handle.

## 1 · Kotlin source

```kotlin
class FixtureAsyncApi {
    fun observeUser(): Flow<FixtureUser> = flow { … }     // record element
    fun observeFailing(): Flow<Int> = flow { emit(1); …; throw IllegalStateException("…") }
}
// file scope:
fun fixtureFailingStream(): Flow<Int> = flow { emit(1); emit(2); throw IllegalStateException("fixture flow failure") }
fun fixtureFiniteStream(): Flow<Int> = flow { for (i in 1..3) { emit(i); delay(500) } }   // completes
```

## 2 · klib reader

A `Flow<T>` return → `kind = FLOW` with `returnType` unwrapped to the **element** type `T` (the
`Flow<…>` wrapper stripped). `flowBaseName` drops a trailing "Flow" for event/method naming.

```
CLASS  FixtureAsyncApi
  functions:
    [FLOW]  observeUser() → FixtureUser
    [FLOW]  observeFailing() → INT
```

## 3 · AndroidGenerator

`start<Name>` launches a collector on the instance's scope; the collect is wrapped so all three
outcomes map to events:

```kotlin
Function("startObserveUser") { instanceId: String ->
  val holder = instances[instanceId] ?: error("Instance not found: $instanceId")
  holder.flowJobs[FlowKey.OBSERVE_USER]?.cancel()                      // restart-safe
  holder.flowJobs[FlowKey.OBSERVE_USER] = holder.scope.launch {
    try {
      holder.instance.observeUser().collect { value ->
        sendEvent("onObserveUserUpdate", mapOf("instanceId" to instanceId, "value" to value.toRecord()))
      }
      sendEvent("onObserveUserComplete", mapOf("instanceId" to instanceId))   // normal end
    } catch (e: CancellationException) {
      throw e                                                          // stop/destroy → no event
    } catch (e: Exception) {
      sendEvent("onObserveUserError", mapOf("instanceId" to instanceId, "message" to (e.message ?: e.toString())))
    }
  }
}
```

The `Events(...)` declaration lists all three names per flow (this one module declares 27 events
— nine flows × three). Rethrowing `CancellationException` is what keeps stop/destroy from
looking like a completion or error.

## 4 · SwiftGenerator — why flows go through `bridgeCollectFlow`

iOS **cannot** use SKIE's `for await` or `collect` directly: Kotlin/Native only delivers an
exception across the ObjC boundary when the throwing function declares `@Throws`, and
`Flow.collect` declares nothing — so a failing flow *terminated the whole app* (verified via
crash reports) instead of throwing. The fix is to catch **in Kotlin**, via a support function
injected into the framework build (see [ARCHITECTURE §6.6](../shared-artifacts/buildSrc/src/main/kotlin/bridgegen/ARCHITECTURE.md);
`scripts/bridgegen.init.gradle` — nothing lives in the KMP source):

```swift
Function("startObserveUser") { (instanceId: String) in
  self.flowTasks[instanceId]?[.observeUser]?.cancel()
  if self.flowTasks[instanceId] == nil { self.flowTasks[instanceId] = [:] }
  self.flowTasks[instanceId]![.observeUser] = Task { [weak self] in
    guard let self, let inst = self.instances[instanceId] else { return }
    do {
      try await bridgeCollectFlow(flow: SkieKotlinFlow(inst.observeUser()), onEach: { raw in
        self.sendEvent("onObserveUserUpdate", ["instanceId": instanceId, "value": (raw as? FixtureUser).map { toRecord($0).__toDict() }])
      })
      self.sendEvent("onObserveUserComplete", ["instanceId": instanceId])
    } catch {
      if !Task.isCancelled {
        self.sendEvent("onObserveUserError", ["instanceId": instanceId, "message": error.localizedDescription])
      }
    }
  }
}
```

Notes: `SkieKotlinFlow(…)` converts SKIE's Swift flow back to the ObjC flow object that *is* a
kotlinx `Flow` on the Kotlin side; the raw element arrives in its ObjC form so records use
`__toDict()` (Expo's iOS `sendEvent` needs plain dictionaries); `Task.isCancelled` suppresses
both terminals on stop/destroy. A `StateFlow`/`SharedFlow` return would fail to compile here
(the converter only accepts plain `SkieSwiftFlow`) — plain `Flow` only.

## 5 · TsBridgeGenerator — ref-counted `subscribe`

One method replaces the old start/stop/listener triplet. The first subscriber starts native
collection, later ones join the live stream, the last `remove()` stops it; terminals flip the
shared per-flow `{active, count}` state so the next subscribe restarts a dead stream:

```typescript
subscribeObserveUser(handlers: { next: (value: FixtureUser) => void; error?: (message: string) => void; complete?: () => void }): { remove: () => void } {
  const st = this._observeUserState
  const subs = [
    _FixtureAsyncApi.addListener('onObserveUserUpdate',   (e: any) => { if (e.instanceId === this._handle) handlers.next(e.value) }),
    _FixtureAsyncApi.addListener('onObserveUserError',    (e: any) => { if (e.instanceId === this._handle) { st.active = false; handlers.error?.(e.message) } }),
    _FixtureAsyncApi.addListener('onObserveUserComplete', (e: any) => { if (e.instanceId === this._handle) { st.active = false; handlers.complete?.() } }),
  ]
  st.count++
  if (!st.active) { _FixtureAsyncApi.startObserveUser(this._handle); st.active = true }
  let removed = false
  return { remove: () => {
    if (removed) return; removed = true
    subs.forEach(s => s.remove())
    st.count--
    if (st.count === 0 && st.active) { _FixtureAsyncApi.stopObserveUser(this._handle); st.active = false }
  } }
}
```

Object/file-scope flows use the identical pattern with a module-level `_X_yState` const instead
of an instance field, and no `instanceId` filter.

## 6 · RN consumption

```typescript
const api = FixtureAsyncApi.create();

const sub = api.subscribeObserveUser({
  next:     (user) => setLiveUser(user),                       // each FixtureUser record
  error:    (msg)  => setState(`stream failed: ${msg}`),       // terminal — optional
  complete: ()     => setState("stream ended"),                // terminal — optional
});
// … later:
sub.remove();          // one call: detaches handlers + stops native collection (last subscriber)
api.destroy();
```

The failing-stream fixtures make the terminals visible: `subscribeFixtureFailingStream` fires
`next(1)`, `next(2)`, then `error("fixture flow failure")`; `subscribeFixtureFiniteStream` fires
`next(1..3)` then `complete()`.

## 7 · Edges

| Situation | Behavior |
|---|---|
| Kotlin flow throws | `on<Name>Error` with the exception message; stream is terminal |
| Flow completes normally | `on<Name>Complete`; terminal |
| `remove()` / `destroy()` mid-stream | native collection cancelled; **no** terminal event fires |
| re-`subscribe` after a terminal | starts a fresh native collection (state was reset) |
| `StateFlow`/`SharedFlow` return | would fail iOS compile — plain `Flow` only (documented limitation) |
| flow name ending in "Flow" | the suffix is stripped case-insensitively (`overflow` → `over`) — avoid such names |
| ref-count is per wrapper object | two wrappers around one handle would fight over start/stop (documented caveat) |
