# 09 — File-scope declarations

> Fixtures: `BridgeTypeFixture.kt` top-level functions/properties, `FixtureNamedIface.kt` ·
> Generated excerpts captured at commit `e471661` · Vocabulary: [00-overview](00-overview.md)

Top-level functions and properties of a `.kt` file have no owning class. bridgegen groups them
into one synthetic module named after the file, calls into them statically, and models
properties as zero-parameter getters. This doc also covers three file-scope-only wrinkles:
**typealias** resolution, **Flow-typed properties**, and the **`Kt`-suffix name collision**.

## 1 · Kotlin source

```kotlin
// file-level properties
val fixtureVersion: String = "1.0.0"
val fixtureActiveStatus: FixtureStatus = FixtureStatus.ACTIVE
val fixtureNullableUser: FixtureUser? = null
val fixtureCounterStream: Flow<Int> = flow { … }        // Flow-typed property

// typealias — must resolve to its expansion
typealias FixtureUserId = String
fun fixtureEchoUserId(id: FixtureUserId): FixtureUserId = id

fun fixtureDescribeShape(shape: FixtureShape): String = …   // sealed param
```

And the collision fixture (`FixtureNamedIface.kt`), whose file name equals its interface:

```kotlin
interface FixtureNamedIface { suspend fun ping(): String }
fun fixtureNamedIfaceHello(): String = "hello-from-file-scope"
```

## 2 · klib reader

Top-level members become a `KmpFileScope(fileName, functions)`. Properties are read as synthetic
**zero-param functions** with `isPropertyGetter = true`; a Flow-typed property becomes a FLOW
function (unwrapped element type). The typealias is already expanded in the klib — `FixtureUserId`
arrives as `STRING`, never as its own type.

```
FILE SCOPE  1_shared
  functions:
    [SYNC]  fixtureEchoUserId(id: STRING) → STRING        ← typealias resolved to STRING
    [SYNC]  fixtureDescribeShape(shape: FixtureShape) → STRING
    [SYNC]  fixtureVersion() → STRING                     ← property getter
    [SYNC]  fixtureActiveStatus() → FixtureStatus         ← property getter (enum)
    [SYNC]  fixtureNullableUser() → FixtureUser?          ← property getter (nullable record)
    [FLOW]  fixtureCounterStream() → INT                  ← Flow-typed property
```

Extension functions (e.g. `String.fixtureShout()`, present in the source) are **skipped loudly**
— there's no receiver at the generated call site.

## 3 · AndroidGenerator

The module is named after the file; calls go through the package FQN (Kotlin resolves the file
facade). Property getters emit **no `()`**:

```kotlin
Function("fixtureEchoUserId") { id: String ->
  com.example.shared.fixtureEchoUserId(id)          // typealias was String all along
}
Function("fixtureDescribeShape") { shape: FixtureShapeRecord ->
  com.example.shared.fixtureDescribeShape(shape.toKmp())
}
Function("fixtureVersion") {
  com.example.shared.fixtureVersion                 // property read — no ()
}
Function("fixtureActiveStatus") {
  com.example.shared.fixtureActiveStatus.name       // enum property → .name
}
Function("startFixtureCounterStream") { … }         // Flow property → start/stop/events (see 08)
```

## 4 · SwiftGenerator

Kotlin/Native compiles top-level declarations into a `<FileName>Kt` facade class; the generated
module calls static members on it (`BridgeTypeFixtureKt.fixtureEchoUserId(id:)`,
`BridgeTypeFixtureKt.fixtureVersion` for the property). Otherwise identical in shape to Android.

## 5 · TsBridgeGenerator — and the `Kt` collision

A file scope becomes a flat const named after the file; property getters are exposed as
zero-arg functions:

```typescript
export const BridgeTypeFixture = {
  fixtureEchoUserId: (id: string): string => _BridgeTypeFixture.fixtureEchoUserId(id),
  fixtureVersion: (): string => _BridgeTypeFixture.fixtureVersion(),
  fixtureActiveStatus: (): FixtureStatus => _BridgeTypeFixture.fixtureActiveStatus(),
  fixtureNullableUser: (): FixtureUser | null => _BridgeTypeFixture.fixtureNullableUser(),
  subscribeFixtureCounterStream: (handlers) => { … },      // Flow property → subscribe (see 08)
};
```

**The collision:** in `FixtureNamedIface.kt`, the interface `FixtureNamedIface` claims the module
name `FixtureNamedIface`, so the file-scope module must be disambiguated — it gets a `Kt` suffix,
consistently on all three platforms (the TS `requireNativeModule` name must match the native
registration):

```typescript
const _FixtureNamedIfaceKt = requireNativeModule('FixtureNamedIfaceKt');   // file scope
const _FixtureNamedIface   = requireNativeModule('FixtureNamedIface');     // the interface

export class FixtureNamedIface { … }                        // interface wrapper (see 10)
export const FixtureNamedIfaceKt = {                        // ← Kt-suffixed file scope
  fixtureNamedIfaceHello: (): string => _FixtureNamedIfaceKt.fixtureNamedIfaceHello(),
};
```

The suffix decision is computed once (`takenNames`) and every generator applies it identically —
Android/Swift emit `FixtureNamedIfaceKtModule`, TS requires `'FixtureNamedIfaceKt'`.

## 6 · RN consumption

```typescript
import { BridgeTypeFixture, FixtureNamedIfaceKt } from "kmp-bridge/src/…";

BridgeTypeFixture.fixtureVersion();          // → "1.0.0"       (property, called as a function)
BridgeTypeFixture.fixtureActiveStatus();     // → "ACTIVE"      (=== FixtureStatus.ACTIVE)
BridgeTypeFixture.fixtureEchoUserId("u1");   // → "u1"          (typealias was just String)
FixtureNamedIfaceKt.fixtureNamedIfaceHello();// → "hello-from-file-scope"
```

## 7 · Edges

| Situation | Behavior |
|---|---|
| Extension function at file scope | `FUNCTION SKIPPED: X.fn() — extension functions are not bridged` |
| Function with a lambda parameter | `FUNCTION SKIPPED: … — function-typed parameters are not bridged` |
| File name == a class/interface/object name in it | file-scope module gets a `Kt` suffix (all platforms) |
| Property getter | called as a zero-arg function in JS (`fixtureVersion()`, not `fixtureVersion`) |
| `var` property | still read-only over the bridge — only the getter is bridged (no setter) |
| Flow-typed property | full flow treatment ([08](08-flows.md)) — `subscribe<Name>` |
| typealias in a signature | resolves to the underlying type; the alias name never appears |
