# 03 — Data classes

> Fixtures: `FixtureUser`, `FixtureAddress`, `FixtureTeam` · Generated excerpts captured at
> commit `e471661` · Vocabulary: [00-overview](00-overview.md)

A data class crosses the bridge as a **plain key-value record**. Each platform generates a codec
pair around a wire type: an Expo `Record` class (Android/iOS) or a TS object type. `FixtureUser`
is the maximal example — every primitive kind, an enum field, a nullable nested data class, and
all three collection kinds.

## 1 · Kotlin source

```kotlin
data class FixtureAddress(
    val street: String,
    val city: String,
    val zip: String?,           // nullable primitive
)

data class FixtureUser(
    val id: String,
    val age: Int,
    val score: Double,
    val active: Boolean,
    val byteFlag: Byte,
    val longId: Long,
    val initial: Char,
    val ratio: Float,
    val status: FixtureStatus,          // ClassRef → enum
    val address: FixtureAddress?,       // ClassRef, nullable, nested record
    val tags: List<String>,
    val metadata: Map<String, Int>,
    val aliases: Set<String>,
)
```

(`FixtureTeam` nests records *inside* collections — `List<FixtureUser>`,
`Map<String, FixtureUser>` — covered under [05-collections](05-collections.md).)

## 2 · klib reader

`KmpDataClass(name, fields)` — the fields are the **primary constructor** parameters, in order.
Member functions on data classes are not bridged (skipped loudly), and compiler synthetics
(`copy`, `componentN`, `equals`…) are filtered by the reader.

```
DATA CLASS  FixtureUser
  fields:
    · id       : STRING          · status   : FixtureStatus
    · age      : INT             · address  : FixtureAddress?
    · byteFlag : BYTE            · tags     : LIST<STRING>
    · longId   : LONG            · metadata : MAP<STRING, INT>
    · initial  : CHAR            · aliases  : SET<STRING>
    · score/active/ratio …
```

## 3 · AndroidGenerator

Three artifacts per data class. First the `Record` — the JS→Kotlin direction, where Expo
populates `@Field`s from the JS object (every field needs a default; note the wire widenings
from [01](01-primitives.md)):

```kotlin
class FixtureUserRecord : Record {
    @Field var id: String = ""
    @Field var age: Int = 0
    @Field var byteFlag: Int = 0                       // Byte → Int on the wire
    @Field var longId: Double = 0.0                    // Long → Double
    @Field var initial: String = ""                    // Char → 1-char String
    @Field var status: String = ""                     // enum → case name
    @Field var address: FixtureAddressRecord? = null   // nested record type
    @Field var tags: List<String> = emptyList()
    @Field var metadata: Map<String, Int> = emptyMap()
    @Field var aliases: List<String> = emptyList()     // Set → List (JS has no Set)
    // … score/active/ratio pass through
}
```

Then the two converters:

```kotlin
fun FixtureUserRecord.toKmp() = FixtureUser(
    byteFlag = byteFlag.toByte(),                      // narrow back
    longId   = longId.toLong(),
    initial  = initial.first(),
    status   = FixtureStatus.valueOf(status),          // throws on unknown name
    address  = address?.toKmp(),                       // ?-chained nested decode
    aliases  = aliases.let { r0 -> r0.toSet() },       // restore Set-ness
    /* … identity fields … */
)

fun FixtureUser.toRecord(): FixtureUserRecord = FixtureUserRecord().also {
    it.status  = status.name
    it.address = address?.toRecord()
    it.aliases = aliases.let { r0 -> r0.toList() }
    /* … mirror image … */
}
```

At use sites: parameters are typed `FixtureUserRecord` + `.toKmp()`, returns append
`.toRecord()` — see `FixtureParamsApi.saveUser` in [06](06-classes-objects.md).

## 4 · SwiftGenerator

Same three artifacts; the differences are all Kotlin/Native interop:

```swift
struct FixtureUserRecord: Record {
  @Field var age: Int32 = 0          // Swift-native widths in the @Field
  @Field var byteFlag: Int8 = 0
  @Field var longId: Int64 = 0       // Expo handles the JS-number conversion
  @Field var initial: String = ""
  @Field var status: String = ""
  @Field var address: FixtureAddressRecord? = nil
  @Field var aliases: [String] = []
  // …
}

fileprivate func toRecord(_ v: FixtureUser) -> FixtureUserRecord {
  var r = FixtureUserRecord()
  r.initial  = String(decoding: [v.initial], as: UTF16.self)  // K/N Char is unichar (UInt16)
  r.status   = v.status.name
  r.address  = v.address.map { toRecord($0) }
  r.metadata = v.metadata.mapValues { v in Int32(v.intValue) } // SKIE boxes map values → unbox
  r.aliases  = Array(v.aliases)
  /* … */
}

fileprivate extension FixtureUserRecord {
  func toKmp() throws -> FixtureUser {   // throws: enum decode + nested toKmp can throw
    return FixtureUser(
      initial:  initial.utf16.first ?? 0,
      status:   try decodeFixtureStatus(status),
      address:  try address?.toKmp(),
      metadata: metadata.mapValues { v in KotlinInt(value: v) },  // re-box for K/N
      aliases:  Set(aliases),
      /* … */
    )
  }
  func __toDict() -> [String: Any?] { /* Record → plain dictionary, for sendEvent payloads */ }
}
```

Two iOS-only facts: collection *elements* arrive from SKIE boxed (`KotlinInt`) and need
unbox/re-box maps, and every Record also gets `__toDict()` because Expo's iOS `sendEvent`
accepts plain dictionaries, not Record structs — flow events use it ([08](08-flows.md)).

## 5 · TsBridgeGenerator

```typescript
export type FixtureUser = {
  id: string
  age: number
  byteFlag: number
  longId: number
  initial: string
  status: string                    // ← raw wire shape, not FixtureStatus
  address: FixtureAddress | null
  tags: string[]
  metadata: { [key: string]: number }
  aliases: string[]                 // ← Set arrives as an array
  // … score/active/ratio: number/boolean/number
}
```

Record fields carry the **raw wire shape** (`status: string`, not the TS enum) because the type
describes exactly what crosses the bridge; wrapper *signatures* upgrade enums to the typed form.
Comparing still works either way since the enum is string-backed ([02](02-enums.md)).

## 6 · RN consumption

```typescript
const user = await api.fetchUser("test-id");
// user is a plain JS object — the literal wire payload:
{
  "id": "test-id", "age": 30, "score": 5.0, "active": true,
  "byteFlag": 1, "longId": 100, "initial": "F", "ratio": 1.0,
  "status": "ACTIVE",
  "address": null,
  "tags": [], "metadata": {}, "aliases": []
}

// passing one IN is symmetric — hand any object matching the type:
api.saveUser({ ...user, age: 31 });
```

No wrapper class, no methods, no identity — two fetches of the same conceptual user produce two
unrelated JS objects. Equality is structural, on you.

## 7 · Edges

| Situation | Behavior |
|---|---|
| Fieldless data class | `DATA CLASS SKIPPED: … — no fields, no Record generated.` |
| Member functions on a data class | `FUNCTION SKIPPED: X.fn() — member functions on data classes are not bridged.` |
| Missing field in an inbound JS object | Expo fills the `@Field` default (`""`, `0`, `false`, empty collection) — silent, not an error |
| `Long` field beyond 2⁵³ | precision loss ([01](01-primitives.md)) |
| Nested record cycles (`A` containing `A`) | would recurse in codecs — don't; not guarded |
| Cross-file references | fine — generators import the Record types across generated files |
