# 04 — Sealed types

> Fixtures: `FixtureResult`, `FixtureAuthState`, `FixtureShape`, `FixturePayment` ·
> Generated excerpts captured at commit `e471661` · Vocabulary: [00-overview](00-overview.md)

A sealed class or sealed interface crosses the bridge as a **tagged record**: one flat object
with a `type` discriminator string plus the union of every variant's fields (all nullable). TS
turns that into a discriminated union — the idiomatic shape for a `switch`. The four fixtures
each exercise a different wrinkle:

- **`FixtureResult`** — all three variant kinds (object, class, data class) plus an *abstract*
  variant that must fail on decode.
- **`FixtureAuthState`** — mixed object + data-class variants.
- **`FixtureShape`** — variants declared at **file top level** (not nested in the parent).
- **`FixturePayment`** — a **sealed interface** (Swift names its variants differently).

## 1 · Kotlin source

```kotlin
sealed class FixtureResult {
    data class Success(val user: FixtureUser, val code: Int) : FixtureResult()
    object Empty : FixtureResult()
    class Failure(val message: String, val retryable: Boolean) : FixtureResult()
    abstract class Partial(val hint: String) : FixtureResult()   // abstract → can't reconstruct
}

sealed interface FixturePayment {
    data class Card(val last4: String) : FixturePayment
    object Cash : FixturePayment
}

sealed class FixtureShape
data class FixtureCircle(val radius: Double) : FixtureShape()   // top-level variants
data class FixtureSquare(val side: Double) : FixtureShape()
```

## 2 · klib reader

`KmpSealedClass(name, variants)`. Each variant is a `DataVariant`, `ClassVariant`, or
`ObjectVariant`, carrying `isNested` (declared inside the parent's body vs. at file top level)
and, for class variants, `isAbstract`. A `sealed interface` sets `isFromInterface = true`.

```
SEALED CLASS  FixtureResult
  variants:
    ▸ ObjectVariant  Empty
    ▸ ClassVariant    Failure          · message: STRING  · retryable: BOOLEAN
    ▸ ClassVariant(abstract)  Partial  · hint: STRING
    ▸ DataVariant    Success           · user: FixtureUser  · code: INT
```

The reader records the variants **inside** the parent's model and excludes them from the
top-level declaration list — even `FixtureCircle`/`FixtureSquare`, which are file-top-level, are
pulled in as variants of `FixtureShape` rather than standing alone.

## 3 · AndroidGenerator

One flat `Record` unions all variant fields (every one nullable) plus a `type` discriminator:

```kotlin
class FixtureResultRecord : Record {
    @Field var type: String = ""
    @Field var message: String? = null      // Failure
    @Field var retryable: Boolean? = null    // Failure
    @Field var hint: String? = null          // Partial
    @Field var user: FixtureUserRecord? = null   // Success
    @Field var code: Int? = null             // Success
}
```

Encode switches on the Kotlin type; decode switches on the `type` string:

```kotlin
fun FixtureResult.toRecord(): FixtureResultRecord = FixtureResultRecord().also { r ->
    r.type = when (this) {
        is FixtureResult.Empty   -> "Empty"
        is FixtureResult.Failure -> "Failure"
        is FixtureResult.Partial -> "Partial"
        is FixtureResult.Success -> "Success"
    }
    when (this) {
        is FixtureResult.Empty   -> Unit
        is FixtureResult.Failure -> { r.message = message; r.retryable = retryable }
        is FixtureResult.Partial -> { r.hint = hint }
        is FixtureResult.Success -> { r.user = user.toRecord(); r.code = code }
    }
}

fun FixtureResultRecord.toKmp(): FixtureResult = when (type) {
    "Empty"   -> FixtureResult.Empty
    "Failure" -> FixtureResult.Failure(message = message ?: "", retryable = retryable ?: false)
    "Partial" -> error("FixtureResult.Partial is abstract — cannot deserialize")  // ← abstract
    "Success" -> FixtureResult.Success(user = (user ?: FixtureUserRecord()).toKmp(), code = code ?: 0)
    else      -> error("Unknown FixtureResult type: $type")
}
```

Note the two safety behaviors: **abstract variants encode fine but throw on decode** (there's no
concrete class to build), and every non-null field gets a fallback default (`?: ""`, `?: 0`)
because the flat Record makes them all nullable.

## 4 · SwiftGenerator

Same flat struct. Encode uses SKIE's `onEnum(of:)` (case names are the Kotlin variant names,
first letter lowercased):

```swift
fileprivate func toRecord(_ v: FixtureResult) -> FixtureResultRecord {
  var r = FixtureResultRecord()
  switch onEnum(of: v) {
  case .empty:            r.type = "Empty"
  case .failure(let s):   r.type = "Failure"; r.message = s.message; r.retryable = s.retryable
  case .partial(let s):   r.type = "Partial"; r.hint = s.hint
  case .success(let s):   r.type = "Success"; r.user = toRecord(s.user); r.code = s.code
  }
  return r
}
```

Decode is where the **variant-reference rules** show up — this is the most K/N-specific part of
sealed handling:

```swift
// FixtureResult (nested in a sealed CLASS): objects use .shared, abstract throws
case "Empty":   return FixtureResult.Empty.shared
case "Partial": throw NSError(… "FixtureResult.Partial is abstract — cannot deserialize")
case "Success": return FixtureResult.Success(user: try (user ?? …).toKmp(), code: code ?? 0)

// FixturePayment (sealed INTERFACE): variants surface as CONCATENATED names — ObjC protocols
// cannot nest types, so K/N flattens FixturePayment.Card → FixturePaymentCard
case "Card": return FixturePaymentCard(last4: last4 ?? "")
case "Cash": return FixturePaymentCash.shared

// FixtureShape (TOP-LEVEL variants): bare names, no parent prefix
case "FixtureCircle": return FixtureCircle(radius: radius ?? 0.0)
case "FixtureSquare": return FixtureSquare(side: side ?? 0.0)
```

Three distinct reference forms — `Parent.Variant` (nested in a class), `ParentVariant`
(concatenated, nested in an interface), and bare `Variant` (top-level) — all driven by the
reader's `isNested` + `isFromInterface` flags.

## 5 · TsBridgeGenerator

Discriminated unions — the reason JS code can `switch (x.type)` with full field narrowing:

```typescript
export type FixtureResult =
  | { type: "Empty" }
  | { type: "Failure"; message: string; retryable: boolean }
  | { type: "Partial"; hint: string }
  | { type: "Success"; user: FixtureUser; code: number }

export type FixtureShape =
  | { type: "FixtureCircle"; radius: number }
  | { type: "FixtureSquare"; side: number }

export type FixturePayment =
  | { type: "Card"; last4: string }
  | { type: "Cash" }
```

The TS discriminator is the plain Kotlin variant name (`"Card"`), *not* the concatenated Swift
name — TS talks to the wire's `type` string, which the native encoders set identically on both
platforms.

## 6 · RN consumption

```typescript
const result = await someApi.getResult();
switch (result.type) {
  case "Success": return `ok: ${result.user.id} (${result.code})`;  // fields narrowed
  case "Failure": return `err: ${result.message}`;
  case "Empty":   return "empty";
}

// building one to pass IN:
BridgeTypeFixture.fixturePay({ type: "Card", last4: "4242" });   // → "card:4242"
```

Wire payload for a `Success` is the flat tagged record — unused variants' fields simply absent:

```json
{ "type": "Success", "user": { "id": "…", … }, "code": 200 }
```

## 7 · Edges

| Situation | Behavior |
|---|---|
| Abstract variant inbound (`{type:"Partial"}`) | decode throws on both platforms — no concrete class to construct |
| Two variants share a field name with **different types** | flat-record dedup keeps the first occurrence — a real type clash would mis-decode. (No fixture hits this; documented caveat) |
| Unknown `type` string | `error("Unknown … type: …")` / `NSError` |
| Member functions on the sealed parent | `FUNCTION SKIPPED: … — member functions on sealed classes are not bridged.` |
| sealed **interface** vs sealed **class** | only differs on iOS (concatenated variant names) — TS/Android identical |
