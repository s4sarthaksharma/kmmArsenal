# Module: cmpfirst

**Path:** `cmpfirst/`
**Type:** Compose Multiplatform application
**Role:** Demonstrates consuming the KMP `shared` module directly in a Compose Multiplatform UI — no bridge, no React Native. Android and iOS both run the same Compose UI backed by the same Kotlin shared code.

---

## Stack

- Kotlin Multiplatform 2.1.20
- Compose Multiplatform 1.7.3
- Targets: Android + iOS (iosX64, iosArm64, iosSimulatorArm64)
- Consumes `com.example.shared:shared:1.0.0` from Maven Local

---

## What it demonstrates

The `App.kt` composable exercises all three API patterns from `shared.Greeting`:

| Pattern | KMP API used | How it's consumed in CMP |
|---|---|---|
| Sync function | `Greeting.greet()` | Called directly in composition |
| Kotlin Flow | `Greeting.counterFlow()` | `collectAsState(initial = 0)` — auto-updates UI |
| Suspend function | `Greeting.delayedEcho()` | `rememberCoroutineScope()` + `scope.launch { }` |

---

## Key files

- `composeApp/src/commonMain/.../App.kt` — single shared UI composable (runs on both platforms)
- `composeApp/src/androidMain/.../MainActivity.kt` — Android entry point
- `composeApp/src/iosMain/.../MainViewController.kt` — iOS entry point (used from Swift)
- `iosApp/` — Xcode project that wraps the Compose framework

---

## How to run

**Android:**
```bash
cd cmpfirst
./gradlew :composeApp:installDebug
```

**iOS:** Open `iosApp/iosApp.xcodeproj` in Xcode and run.

---

## Notes

- This module does NOT use `kmp-bridge` or `shared-artifacts`. It consumes `shared` directly as a Gradle/KMP dependency.
- `shared` must be published to Maven Local first: `cd shared && ./gradlew publishToMavenLocal`
- Unlike `kmp-bridge`, there is no ObjC/callback bridging here — Compose code can call suspend functions and collect Flows natively since it runs in the Kotlin runtime on both platforms.
