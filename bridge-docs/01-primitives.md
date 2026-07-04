# 01 — Primitives

> Fixture: `FixturePrimitivesApi` · Generated excerpts captured at commit `e471661` ·
> Vocabulary: [00-overview](00-overview.md)

JS has exactly three scalar types on the wire — `string`, `number`, `boolean` — while Kotlin has
nine primitives. This doc shows what each one becomes at every station and where precision or
identity is deliberately traded away.

## The wire table (the whole story in one view)

| Kotlin | Android bridge type | Swift bridge type | TS | Wire notes |
|---|---|---|---|---|
| `String` | `String` | `String` | `string` | as-is |
| `Int` | `Int` | `Int32` | `number` | as-is |
| `Long` | **`Double`** | **`Double`** (param) / `Int64` (@Field) | `number` | JS numbers are IEEE doubles — integers beyond 2⁵³ lose precision |
| `Double` | `Double` | `Double` | `number` | as-is |
| `Float` | `Float` | `Float` | `number` | as-is |
| `Boolean` | `Boolean` | `Bool` | `boolean` | as-is |
| `Byte` | **`Int`** | `Int32` (param) / `Int8` (@Field) | `number` | widened; narrowed back with `.toByte()` |
| `Short` | **`Int`** | `Int32` (param) / `Int16` (@Field) | `number` | widened; narrowed back with `.toShort()` |
| `Char` | **`String`** | **`String`** | `string` | 1-character string; back with `.first()` / `.utf16.first` |
| any `X?` | `X?` | `X?` (nullable primitives box to `KotlinInt` etc. at the K/N call site) | `X \| null` | `null` crosses as `null` |

## 1 · Kotlin source

```kotlin
class FixturePrimitivesApi {
    // All 9 primitive kinds as parameters in one function
    fun allPrimitives(s: String, i: Int, l: Long, d: Double, f: Float,
                      b: Boolean, by: Byte, sh: Short, c: Char): Unit = Unit

    // Each primitive as a distinct return type
    fun returnString(): String = ""
    fun returnLong(): Long = 0L
    fun returnChar(): Char = 'a'
    // … returnInt/Double/Float/Boolean/Byte/Short analogous

    // Nullable primitives
    fun nullableParam(s: String?, i: Int?): String? = s
    fun returnNullableInt(): Int? = null
}
```

## 2 · klib reader

Each primitive resolves to `KmpTypeRef.Primitive(kind, nullable)`; the class itself is a
concrete `KmpClass` (instance-based bridging — see [06](06-classes-objects.md) for the
create/destroy lifecycle this implies):

```
CLASS  FixturePrimitivesApi
  functions:
    [SYNC]  allPrimitives(s: STRING, i: INT, l: LONG, d: DOUBLE, f: FLOAT,
                          b: BOOLEAN, by: BYTE, sh: SHORT, c: CHAR) → Unit
    [SYNC]  returnLong() → LONG
    [SYNC]  returnChar() → CHAR
    [SYNC]  nullableParam(s: STRING?, i: INT?) → STRING?
    …
```

## 3 · AndroidGenerator

Most primitives pass through untouched. The interesting lines:

```kotlin
Function("returnLong") { instanceId: String ->
  (instances[instanceId] ?: error("Instance not found: $instanceId")).returnLong()
}                                     // Long return: Expo converts Long→JS number itself

Function("returnChar") { instanceId: String ->
  (instances[instanceId] ?: error("…")).returnChar().toString()
}                                     // Char → 1-char String (JS has no char type)

Function("nullableParam") { instanceId: String, s: String?, i: Int? ->
  (instances[instanceId] ?: error("…")).nullableParam(s, i)
}                                     // nullable primitives keep natural Kotlin types
```

A `Long` *parameter* (see `Calculator.addLongs` or `AsyncWorker.waitAndFlag`) arrives typed
`Double` and converts with `.toLong()` — the generator's `toCallArg` rule.

**And one function is missing entirely:**

```kotlin
// BRIDGE SKIPPED: allPrimitives(9 params) — Expo Function DSL supports max 8 parameters.
```

Expo's `Function` DSL has overloads up to 8 parameters, and instance-based classes spend one on
the synthetic `instanceId` — so `allPrimitives`'s 9 own params + handle = 10 > 8. The skip is a
comment in the generated file plus a `>> [Android] BRIDGE SKIPPED:` log line.

## 4 · SwiftGenerator

```swift
Function("returnLong") { (instanceId: String) in
  guard let inst = self.instances[instanceId] else { fatalError("Instance not found: \(instanceId)") }
  return inst.returnLong()            // K/N exports Long as Int64; Expo → JS number
}

Function("returnChar") { (instanceId: String) in
  guard let inst = self.instances[instanceId] else { fatalError("…") }
  return inst.returnChar().description   // K/N Char is unichar (UInt16) → 1-char String
}

Function("nullableParam") { (instanceId: String, s: String?, i: Int32?) in
  guard let inst = self.instances[instanceId] else { fatalError("…") }
  return inst.nullableParam(s: s, i: i.map { KotlinInt(value: $0) })
}
```

The last line is the iOS-only quirk: **Kotlin/Native cannot represent a nullable primitive as a
bare ObjC scalar**, so SKIE expects the boxed `KotlinInt?` (`KotlinBoolean?`, `KotlinByte?`,
`KotlinShort?` likewise) — the generator wraps at the call site. `allPrimitives` is skipped on
iOS too (same param-count rule; logged as `>> [iOS] FUNCTION SKIPPED`).

## 5 · TsBridgeGenerator

```typescript
returnLong(): number { return _FixturePrimitivesApi.returnLong(this._handle) }
returnByte(): number { return _FixturePrimitivesApi.returnByte(this._handle) }
returnChar(): string { return _FixturePrimitivesApi.returnChar(this._handle) }
nullableParam(s: string | null, i: number | null): string | null {
  return _FixturePrimitivesApi.nullableParam(this._handle, s, i)
}
```

Everything numeric is `number`; `Char` is `string`; nullability is `| null` (never `undefined` —
the wire carries explicit nulls).

## 6 · RN consumption

```typescript
import { FixturePrimitivesApi } from "kmp-bridge/src/BridgeTypeFixture";

const api = FixturePrimitivesApi.create();
api.returnLong();               // → 0            (JS number)
api.returnChar();               // → "a"          (1-char string)
api.nullableParam("x", null);   // → "x"          (null crosses as null)
api.returnNullableInt();        // → null
api.destroy();
```

Wire payloads are the raw JS scalars — a call like `nullableParam("x", null)` puts exactly
`["<handle>", "x", null]` through the Expo bridge.

## 7 · Edges

| Situation | Behavior |
|---|---|
| > 8 effective params (incl. `instanceId`) | function skipped on all three platforms (`allPrimitives` demonstrates) |
| `Long` beyond ±2⁵³ | silently loses precision — JS numbers are doubles. No error, by design |
| `Char` from a multi-char JS string | Android takes `.first()`; empty string throws (`first()` on empty) |
| nullable primitive into iOS | boxed (`KotlinInt(value:)`) at the generated call site — invisible to JS |
