# Module: shared

**Path:** `shared/`
**Type:** Kotlin Multiplatform library
**Role:** Single source of truth for all shared business logic. Published to Maven Local for consumption by other modules in this repo.

---

## Gradle coordinates

```
group   = com.example.shared
artifact = shared
version  = 1.0.0
```

iOS framework name: `Shared`

---

## Targets

| Target | Output |
|---|---|
| `androidTarget` | AAR published to Maven Local (release variant) |
| `iosX64`, `iosArm64`, `iosSimulatorArm64` | XCFramework (static, name: `Shared`) |

---

## Key source files

### `commonMain/kotlin/com/example/shared/`

**`Greeting.kt`**
The primary demo class. Exposes three patterns:
- `greet(name: String): String` — simple sync function
- `counterFlow(): Flow<Int>` — infinite Flow emitting incrementing ints every 1 second
- `suspend delayedEcho(text: String, delayMs: Long): String` — suspend function that delays then returns the input

**`Platform.kt`**
`expect class Platform()` with an `actual` in each platform providing `val name: String` (e.g. "Android", "iOS").

---

## How to publish

```bash
cd shared
./gradlew publishToMavenLocal
```

This must be run before building `shared-artifacts` or `cmpfirst`.

---

## Notes

- Does NOT contain SKIE. SKIE belongs in `shared-artifacts` where the final XCFramework is compiled.
- `Flow<T>` is exported as `AsyncSequence` by SKIE in `shared-artifacts`, so no callback wrapper class is needed in this module.
