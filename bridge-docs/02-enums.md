# 02 — Enums

> Fixture: `FixtureStatus` · Generated excerpts captured at commit `e471661` ·
> Vocabulary: [00-overview](00-overview.md)

The contract in one sentence: **an enum crosses the bridge as its case-name string** —
`FixtureStatus.ACTIVE` is `"ACTIVE"` on the wire, everywhere, in both directions.

## 1 · Kotlin source

```kotlin
enum class FixtureStatus { ACTIVE, INACTIVE, PENDING }
```

## 2 · klib reader

```
ENUM  FixtureStatus
  entries: ACTIVE · INACTIVE · PENDING
```

`KmpEnum(name, entries)` — just the case names, in declaration order. An enum generates no
module of its own; it only matters where *other* signatures reference it. Each generator keeps a
module-wide `enumNames` set and recognizes `ClassRef("…FixtureStatus")` against it.

## 3 · AndroidGenerator

No standalone artifact. At every use site:

- **return / flow element**: `.name` appended — `instance.fixtureActiveStatus.name`
- **parameter**: typed `String`, decoded with `FixtureStatus.valueOf(status)` (throws
  `IllegalArgumentException` on an unknown name → surfaces as a JS error)
- **Record field** (`FixtureUser.status`): `@Field var status: String = ""`, encoded
  `it.status = status.name`, decoded `status = FixtureStatus.valueOf(status)`
- nullable: `?.name` / `status?.let { FixtureStatus.valueOf(it) }`

Example from `FixtureRepository` (enum parameter into a suspend function):

```kotlin
AsyncFunction("findByStatus") { instanceId: String, status: String, promise: Promise ->
  val holder = FixtureRepositoryRegistry.get(instanceId)
  launchSettled(holder.scope, promise, "FIND_BY_STATUS_ERROR") {
    holder.instance.findByStatus(FixtureStatus.valueOf(status))
  }
}
```

## 4 · SwiftGenerator

Same contract, but decoding needs a generated helper — Kotlin/Native exposes enum cases as
class members and SKIE adds `allCases`/`name`, so the file gets **one throwing decoder per enum
actually used**:

```swift
fileprivate func decodeFixtureStatus(_ raw: String) throws -> FixtureStatus {
  guard let value = FixtureStatus.allCases.first(where: { $0.name == raw }) else {
    throw NSError(domain: "BridgeError", code: 0,
                  userInfo: [NSLocalizedDescriptionKey: "Unknown FixtureStatus: \(raw)"])
  }
  return value
}
```

Use sites mirror Android: returns append `.name`; parameters call `try decodeFixtureStatus(raw)`
(which is why enum-taking generated `Function`s are marked `throws` on iOS — the generator
derives the `throws` clause from the conversions it actually emitted).

## 5 · TsBridgeGenerator

The enum becomes a **string-backed TS enum**, so values are simultaneously type-safe and
literally the wire strings:

```typescript
export enum FixtureStatus {
  ACTIVE = "ACTIVE",
  INACTIVE = "INACTIVE",
  PENDING = "PENDING",
}
```

Wrapper signatures use the enum type (`wrapperMode`); *record fields* use plain `string`
(the raw wire shape — see [03](03-data-classes.md) §5 for why):

```typescript
findByStatus(status: FixtureStatus): Promise<number> { … }   // wrapper param: typed
fixtureActiveStatus: (): FixtureStatus => …                  // wrapper return: typed
// but in `export type FixtureUser`:  status: string          // record field: raw wire
```

## 6 · RN consumption

```typescript
import { FixtureStatus, FixtureRepository } from "kmp-bridge/src/BridgeTypeFixture";

await repo.findByStatus(FixtureStatus.ACTIVE);   // "ACTIVE" goes over the wire
const s = BridgeTypeFixture.fixtureActiveStatus(); // ← "ACTIVE"; === FixtureStatus.ACTIVE
```

Because the TS enum is string-backed, `s === FixtureStatus.ACTIVE` and `s === "ACTIVE"` are both
true — switch statements work on either form. Wire payload: the bare string `"ACTIVE"`.

## 7 · Edges

| Situation | Behavior |
|---|---|
| Unknown name inbound (JS sends `"BOGUS"`) | Android: `IllegalArgumentException` from `valueOf` → JS error. iOS: generated decoder throws `Unknown FixtureStatus: BOGUS` |
| Renaming an enum case in Kotlin | silently breaks stored/hardcoded JS strings — the wire contract is the *name*, treat renames as breaking API changes |
| Enum as Map key | falls under the non-String-key map rule → skipped ([05](05-collections.md) §7) |
| Enum in a record field | typed `string` in TS, not `FixtureStatus` — compare against `FixtureStatus.X` still works (string enum) |
