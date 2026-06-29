# kmmArsenal — Project Overview

A monorepo that serves as an experimentation/learning ground for Kotlin Multiplatform Mobile (KMM) patterns. Each module explores a different way to consume or distribute a shared KMP library.

---

## Module Map

| Module | Type | Role |
|---|---|---|
| [`shared`](./shared.md) | KMP library | Source of truth — all shared business logic lives here |
| [`shared-artifacts`](./shared-artifacts.md) | KMP packaging | Pulls `shared` from Maven Local, builds XCFramework + extracts AAR for distribution |
| [`kmp-bridge`](./kmp-bridge.md) | Expo native module | Bridges KMP → React Native via Expo Modules API; distributed via yalc |
| [`cmpfirst`](./cmpfirst.md) | Compose Multiplatform app | Consumes `shared` directly (no bridge) — demonstrates KMP in a CMP UI |
| `expofirst` / `expoSecond` | React Native / Expo apps | Consumers of `kmp-bridge` via yalc |

---

## Full Data Flow

```
shared  (Kotlin source, Maven group: com.example.shared:shared:1.0.0)
  │
  └─ ./gradlew publishToMavenLocal
       │
       ├─► cmpfirst/composeApp  (consumes shared directly as a Gradle dependency)
       │
       └─► shared-artifacts  (packaging module)
                │
                ├─ ./gradlew resolveAndroidAar
                │       └─► kmp-bridge/android/libs/shared.aar
                │
                └─ ./gradlew assemble{FrameworkName}ReleaseXCFramework
                        └─► kmp-bridge/ios/Frameworks/Shared.xcframework
                                │
                                └─ npm run push:local  (yalc push)
                                        └─► expofirst / expoSecond
```

The automation script `shared-artifacts/scripts/push-bridges.sh` runs the full pipeline (build + copy artifacts into consumers) in one command. Pass `--publish` to also yalc-push.

---

## Key Versions

| Tool | Version |
|---|---|
| Kotlin | 2.1.20 |
| AGP (Android Gradle Plugin) | 8.12.0 |
| Compose Multiplatform | 1.7.3 |
| kotlinx-coroutines-core | 1.9.0 |

---

## SKIE

`co.touchlab.skie` v0.10.13 is applied to `shared-artifacts`. The XCFramework exposes `suspend fun` as Swift `async throws` and `Flow<T>` as `AsyncSequence`. It lives in `shared-artifacts` (not `shared`) because that is where the XCFramework is compiled.
