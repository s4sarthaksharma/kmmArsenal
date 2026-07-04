# 07 — Suspend functions

> Fixture: `FixtureAsyncApi` (suspend members) · Generated excerpts captured at commit
> `e471661` · Vocabulary: [00-overview](00-overview.md)

A `suspend fun` becomes an Expo `AsyncFunction`, which resolves a JS `Promise`. The whole design
goal is **the promise settles exactly once** — resolved on success, rejected on exception, and
rejected (never left hanging) if the owning scope is cancelled mid-flight (e.g. `destroy()`
during an in-flight call). On Android that guarantee is the generated `launchSettled` helper.

## 1 · Kotlin source

```kotlin
class FixtureAsyncApi {
    fun greet(name: String): String = "Hi, $name"          // sync, for contrast
    suspend fun fetchUser(id: String): FixtureUser = …
    suspend fun fetchNullableUser(id: String): FixtureUser? = null
    suspend fun deleteUser(id: String): Unit = Unit         // Unit → void
    // … flows: see 08
}
```

## 2 · klib reader

The `suspend` modifier sets `kind = SUSPEND`. (A `suspend fun` returning `Flow<T>` would be
reclassified `FLOW` — suspend is only meaningful when the return is a plain value.)

```
CLASS  FixtureAsyncApi
  functions:
    [SYNC]     greet(name: STRING) → STRING
    [SUSPEND]  fetchUser(id: STRING) → FixtureUser
    [SUSPEND]  fetchNullableUser(id: STRING) → FixtureUser?
    [SUSPEND]  deleteUser(id: STRING) → Unit
```

Because this class has suspend (and flow) members, its Android module uses the **`InstanceHolder`**
variant — each instance owns a `CoroutineScope` so `destroy()` cancels exactly that instance's
work:

```kotlin
private class InstanceHolder(val instance: FixtureAsyncApi) {
    val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    val flowJobs = mutableMapOf<FlowKey, Job>()
}
Function("destroy") { instanceId: String -> instances.remove(instanceId)?.scope?.cancel() }
```

## 3 · AndroidGenerator

Every suspend function is an `AsyncFunction` with a trailing `promise: Promise`, delegating to
the per-module `launchSettled` helper:

```kotlin
AsyncFunction("fetchUser") { instanceId: String, id: String, promise: Promise ->
  val holder = instances[instanceId] ?: error("Instance not found: $instanceId")
  launchSettled(holder.scope, promise, "FETCH_USER_ERROR") { holder.instance.fetchUser(id).toRecord() }
}
AsyncFunction("fetchNullableUser") { instanceId: String, id: String, promise: Promise ->
  launchSettled(holder.scope, promise, "FETCH_NULLABLE_USER_ERROR") { holder.instance.fetchNullableUser(id)?.toRecord() }
}
AsyncFunction("deleteUser") { instanceId: String, id: String, promise: Promise ->
  launchSettled(holder.scope, promise, "DELETE_USER_ERROR") { holder.instance.deleteUser(id) }   // Unit → resolves undefined
}
```

The helper is the exactly-once machine:

```kotlin
private fun launchSettled(scope: CoroutineScope, promise: Promise, errorTag: String, block: suspend () -> Any?) {
  val settled = AtomicBoolean(false)
  val job = scope.launch {
    try {
      val result = block()
      if (settled.compareAndSet(false, true)) promise.resolve(result)
    } catch (e: Exception) {
      if (settled.compareAndSet(false, true)) promise.reject(errorTag, e.message, e)
    }
  }
  job.invokeOnCompletion { cause ->                          // fires even if the block never ran
    if (cause != null && settled.compareAndSet(false, true)) {
      promise.reject(errorTag, "Cancelled: ${cause.message}", Exception(cause))
    }
  }
}
```

The `invokeOnCompletion` branch is the subtle part: if the scope is already cancelled when
`launch` is called, the block never executes — a plain `scope.launch { … }` would silently drop
it and leave the JS promise pending forever. The completion handler rejects instead.

The return value is converted the same as any sync return — `.toRecord()` for a data class,
`?.toRecord()` for a nullable one, nothing for `Unit`.

## 4 · SwiftGenerator

iOS wraps a `Task` that resolves/rejects the Expo `Promise`:

```swift
AsyncFunction("fetchUser") { (instanceId: String, id: String, promise: Promise) in
  guard let inst = self.instances[instanceId] else { fatalError("Instance not found: \(instanceId)") }
  Task { [weak self] in
    guard let self else { return }
    do {
      let result = try await toRecord(inst.fetchUser(id: id))   // SKIE turns suspend into async
      promise.resolve(result)
    } catch {
      promise.reject(error)
    }
  }
}
```

SKIE exposes each Kotlin `suspend fun` as a Swift `async throws` function; the generated `Task`
bridges that to Expo's promise. (Kotlin exceptions surface here as caught Swift errors — the
same mechanism that flows rely on, see [08](08-flows.md) §4.)

## 5 · TsBridgeGenerator

Each suspend function is a method returning a `Promise`:

```typescript
greet(name: string): string { return _FixtureAsyncApi.greet(this._handle, name) }   // sync, for contrast
fetchUser(id: string): Promise<FixtureUser> { return _FixtureAsyncApi.fetchUser(this._handle, id) }
fetchNullableUser(id: string): Promise<FixtureUser | null> { … }
deleteUser(id: string): Promise<void> { … }                                          // Unit → void
```

## 6 · RN consumption

```typescript
const api = FixtureAsyncApi.create();
try {
  const user = await api.fetchUser("test-id");     // resolves a FixtureUser record
  await api.deleteUser("test-id");                 // resolves undefined
} catch (e) {
  // e.code === "FETCH_USER_ERROR", e.message === the Kotlin exception message
}
api.destroy();   // if a fetch were in flight, its promise rejects with "Cancelled: …"
```

Rejection shape: Expo surfaces the `errorTag` as the error `code` and the Kotlin exception
message as `message`.

## 7 · Edges

| Situation | Behavior |
|---|---|
| Kotlin throws | promise rejects with `<FN_NAME>_ERROR` code + the exception message |
| `destroy()` during an in-flight call | that call's promise rejects `"Cancelled: …"` — never hangs (the `invokeOnCompletion` path) |
| `Unit` return | resolves `undefined` / `void` |
| nullable return | `null` resolves as `null` |
| > 8 params (incl. handle + promise) | skipped ([01](01-primitives.md) §7) |
| suspend returning `Flow<T>` | reclassified FLOW, not SUSPEND — see [08](08-flows.md) |
