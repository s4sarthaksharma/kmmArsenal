# Module: kmp-bridge

**Path:** `kmp-bridge/`
**Type:** Expo native module (npm package)
**Role:** Bridges the KMP `shared` module to React Native. Wraps the native Android/iOS KMP APIs in the Expo Modules API so React Native apps can call them from JavaScript/TypeScript.

---

## npm package

```json
{
  "name": "kmp-bridge",
  "version": "0.1.0",
  "kmp": {
    "group": "com.example.shared",
    "artifact": "shared",
    "version": "1.0.0",
    "frameworkName": "Shared"
  }
}
```

The `kmp` field is read by `shared-artifacts/scripts/push-bridges.sh` to know which Maven artifact to pull and which XCFramework to build.

---

## Distribution

Uses **yalc** for local development distribution (no private npm registry needed).

```bash
# From shared-artifacts — build artifacts + push to yalc store + push into consumers
bash scripts/push-bridges.sh --publish

# In a consumer React Native app
yalc add kmp-bridge
```

---

## JavaScript API (`index.ts`)

| Function | Returns | Notes |
|---|---|---|
| `greet(name)` | `string` | Sync call into KMP |
| `greetAsync(name)` | `Promise<string>` | Async variant of greet |
| `startCounter()` | `void` | Starts emitting `onCounterUpdate` events |
| `stopCounter()` | `void` | Stops the counter |
| `delayedEcho(text, delayMs)` | `Promise<string>` | Bridges KMP `suspend delayedEcho()` |

Event: `onCounterUpdate` — payload `{ value: number }` — emitted every ~1 second while counter is running.

---

## Android (`android/src/main/java/expo/modules/kmpbridge/KmpBridgeModule.kt`)

- Expo module using `ModuleDefinition` DSL
- Instantiates `Greeting` directly; holds a module-owned `CoroutineScope`
- `startCounter` launches a coroutine that collects `greeting.counterFlow()` and forwards values as Expo events; stored in `counterJob` so it can be cancelled by `stopCounter`
- `delayedEcho` bridges the suspend function by launching a coroutine and resolving the `Promise` manually

Native dependency: `android/libs/shared.aar` (copied here by `shared-artifacts`)

---

## iOS (`ios/KmpBridgeModule.swift`)

- Expo module using `ModuleDefinition` DSL
- `startCounter` creates a `Task` that uses `for await value in greeting.counterFlow()` — possible because SKIE exports `Flow<T>` as `AsyncSequence`; stored in `counterTask` for cancellation
- `delayedEcho` uses `Task { try await }` — SKIE exports suspend funs as Swift `async throws`

Native dependency: `ios/Frameworks/Shared.xcframework` (copied here by `shared-artifacts`)

---

## Notes

- No callback wrapper class needed — SKIE in `shared-artifacts` makes `Flow<T>` an `AsyncSequence` and `suspend fun` a Swift `async throws`, so both are consumed natively.
- Pre-built artifacts are committed to the repo (`android/libs/`, `ios/Frameworks/`) so consumer apps don't need to build from source.
