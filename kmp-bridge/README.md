# kmp-bridge

Expo native module that exposes the KMP `shared` module's public API to React Native. All the
code in `src/`, `android/src/`, and `ios/*.swift` is **generated** — do not edit it; it is
overwritten on every regeneration. This README is for app developers *using* the package. If
you want to know how the code is generated, see
[`shared-artifacts/buildSrc/src/main/kotlin/bridgegen/README.md`](../shared-artifacts/buildSrc/src/main/kotlin/bridgegen/README.md).

## Install (local development)

The package is distributed to the example apps via [yalc](https://github.com/wclr/yalc):

```bash
# One-time in this directory:
npm run publish:local          # yalc publish

# In the app (already done for expofirst/expoSecond):
yalc add kmp-bridge && npm install

# After every regeneration (or use push-bridges.sh --publish which does it for you):
npm run push:local             # yalc push
```

Then rebuild the native app — the package contains native code, so JS-only reloads are not
enough after a regeneration:

```bash
npx expo run:android
cd ios && pod install && cd .. && npx expo run:ios
```

## Importing

There is one TS module per KMP **source file**, imported by file name:

```typescript
import { Calculator } from "kmp-bridge/src/Calculator";
import { TrafficLight, LightColor } from "kmp-bridge/src/TrafficLight";
import { FixtureUser, FixtureStatus, FixtureRepository } from "kmp-bridge/src/BridgeTypeFixture";
```

## One-time KMP module requirement (iOS flow errors)

Kotlin/Native only delivers an exception across the ObjC boundary when the throwing function
declares it via `@Throws` — kotlinx's `Flow.collect` declares nothing, so on iOS a failing flow
would terminate the app instead of firing the `error` handler. The bridged KMP module must
therefore contain this one support function (any file in `commonMain`; app code never calls it,
and the generator keeps it out of the bridged API):

```kotlin
import kotlinx.coroutines.flow.Flow
import kotlin.coroutines.cancellation.CancellationException

@Throws(CancellationException::class, Throwable::class)
suspend fun bridgeCollectFlow(flow: Flow<*>, onEach: (Any?) -> Unit) {
    flow.collect { onEach(it) }
}
```

The generation pipeline fails with this snippet if the module bridges flows without it.

## Usage by KMP shape

### Top-level functions and `object` singletons → plain const

```typescript
import { GreetingKt } from "kmp-bridge/src/Greeting";
GreetingKt.greet("world");            // top-level fun greet(name: String)
```

No lifecycle — call directly.

### Classes → handle-based wrappers with `create()` / `destroy()`

```typescript
const ticker = TickerService.create();   // creates a native instance, returns a wrapper
// … use it …
ticker.destroy();                        // REQUIRED — releases the native instance
```

`destroy()` also cancels any in-flight suspend calls (their promises reject) and stops the
instance's flows. **Instances are never garbage-collected natively** — every `create()` without
a matching `destroy()` leaks a native object. In React, pair them in an effect:

```typescript
useEffect(() => {
  const svc = TickerService.create();
  ref.current = svc;
  return () => { svc.destroy(); };
}, []);
```

### `suspend fun` → Promise

```typescript
const result = await worker.doWork(42);   // rejects with <NAME>_ERROR on Kotlin exception
```

Promises settle exactly once — including rejection if you `destroy()` the instance while the
call is in flight.

### `Flow<T>` → `subscribe<Name>(handlers)`

A Kotlin `fun secondsFlow(): Flow<Int>` (or a `Flow`-typed property) becomes one method,
named after the function with any trailing `Flow` stripped:

```typescript
const sub = ticker.subscribeSeconds({
  next:     (value)   => setSeconds(value),
  error:    (message) => setState(`stream failed: ${message}`),   // optional
  complete: ()        => setState("stream ended"),                // optional
});
// …
sub.remove();   // ONE call: detaches handlers, and stops native collection when
                // this was the last subscriber
```

Semantics:

- Subscriptions are **reference-counted**: the first `subscribe` starts the native collection,
  later subscribers join the live stream (no restart, no replay of missed values), and the
  last `remove()` stops it.
- **`error` and `complete` are terminal and mutually exclusive** — exactly one fires per
  started stream (unless you `remove()`/`destroy()` first, which fires neither). After a
  terminal event the stream is dead; a new `subscribe` starts a fresh collection.
- Flow functions with parameters take them before the handlers
  (`subscribeGreeting(prefix, { next })`); when joining an already-live stream the
  parameters are ignored — the first subscriber's arguments won.

### `data class` → plain object, `enum class` → string enum

```typescript
const user: FixtureUser = repo.getUser();       // plain JS object
if (user.status === FixtureStatus.ACTIVE) { … } // enum is a string-backed TS enum
```

Wire conversions to be aware of: Kotlin `Long` arrives as a JS `number` (precision beyond
2^53 is lost), `Char` as a 1-character string, `Set<T>` as an array. Passing a data class *into*
KMP works the same way — pass a plain object matching the type.

### `sealed class` / `sealed interface` → discriminated union

```typescript
const payment: FixturePayment = api.getPayment();
switch (payment.type) {
  case "Card":  return payment.last4;     // fields are typed per variant
  case "Cash":  return "cash";
}
```

### Interfaces and abstract classes → handles, callable from both sides

An interface instance returned by KMP arrives as an opaque wrapper — call its methods normally:

```typescript
const resource = api.currentResource();   // KMP-implemented instance behind a handle
await resource.load();
resource.destroy();                       // releases the registry entry, not the KMP object
```

You can also **implement** an interface/abstract class *in JS* and hand it to KMP:

```typescript
const impl = FixtureNamedIface.create();  // native anonymous impl proxying to JS
impl.addCallPingListener(({ callId }) => {
  impl.resolvePing(callId, "pong from JS");   // answer each suspend call by callId
});
// pass `impl` into any KMP function taking a FixtureNamedIface — when KMP calls
// ping(), your listener fires and your resolvePing() completes the suspend.
```

Abstract-class constructor parameters and abstract property values become `create(...)`
arguments. Restrictions: **sync** members cannot be JS-implemented (JS can't block the caller)
and throw if invoked on a JS-backed instance; **Flow** members likewise.

### Generic classes → `create<T>()`

```typescript
const api = FixtureGenericApi.create<FixtureUser>();
const u = api.getUser();       // typed FixtureUser; records are converted at runtime
const list = api.wrapUsers();  // FixtureUser[]
```

The type argument is *your assertion* — the native side is type-erased and converts values by
their runtime type. Returned records/enums/collections come out correctly converted; passing
records **into** `T`-typed parameters is not supported (primitives only).

## Rules of thumb

1. Every `create()` needs a `destroy()`.
2. Every `subscribe<Flow>()` needs its subscription's `.remove()` (one call cleans up
   everything). Remove subscriptions before calling `destroy()`.
3. Don't edit anything in `src/`, `android/src/`, or generated `ios/*.swift` — regenerate
   instead (`cd shared-artifacts && bash scripts/push-bridges.sh --publish`), and remember to
   `./gradlew publishToMavenLocal` in `shared/` first if Kotlin code changed.
4. If a function you expect is missing from the wrappers, check the generator logs — it was
   skipped loudly with a reason (extension function, >8 params, non-String Map keys, …).
