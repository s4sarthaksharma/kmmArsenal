# Module: shared-artifacts

**Path:** `shared-artifacts/`
**Type:** KMP packaging / distribution module
**Role:** Has NO business logic of its own. Its sole purpose is to pull the published `shared` klib from Maven Local, compile it into a distributable XCFramework (iOS) and resolve its AAR (Android), then push both artifacts into registered consumer modules.

---

## Why this module exists

`shared` publishes a klib to Maven Local. Consumer native modules (like `kmp-bridge`) need pre-built platform artifacts:
- iOS needs an `.xcframework`
- Android needs a `.aar`

`shared-artifacts` acts as the build factory that produces these artifacts on demand for any number of consumers, driven by `registry.json`.

---

## Configuration

All KMP coordinates are passed in as Gradle properties so this module is reusable for any KMP library:

| Property | Default | Meaning |
|---|---|---|
| `-PkmpGroup` | `com.example.shared` | Maven group of the KMP library |
| `-PkmpArtifact` | `shared` | Maven artifact ID |
| `-PkmpVersion` | `1.0.0` | Version |
| `-PkmpFrameworkName` | `Shared` | iOS framework name (used in XCFramework task name) |

---

## Key files

**`registry.json`**
A JSON array of relative paths to consumer modules. Currently: `["../kmp-bridge"]`.
Each consumer's `package.json` must have a `kmp` field with `group`, `artifact`, `version`, `frameworkName`.

**`scripts/push-bridges.sh`**
The main automation script. For each entry in `registry.json`:
1. Reads KMP metadata from the consumer's `package.json`
2. Runs `resolveAndroidAar` → copies `.aar` to `{consumer}/android/libs/`
3. Runs `assemble{FrameworkName}ReleaseXCFramework` → copies `.xcframework` to `{consumer}/ios/Frameworks/`
4. Optionally runs `npm run push:local` (yalc push) if `--publish` flag is passed

```bash
bash scripts/push-bridges.sh            # build + copy artifacts only
bash scripts/push-bridges.sh --publish  # build + copy + yalc push
```

**`src/commonMain/.../Artifacts.kt`**
Empty placeholder — the KMP Gradle plugin requires at least one source file in `commonMain`.

---

## Gradle tasks

| Task | What it does |
|---|---|
| `resolveAndroidAar` | Downloads/copies the AAR to `build/outputs/android/` |
| `assemble{Name}ReleaseXCFramework` | Compiles the XCFramework (release) for all 3 iOS slices |

---

## SKIE

`co.touchlab.skie` v0.10.13 is applied here. Because the XCFramework is compiled in this module (not in `shared`), this is the correct location — SKIE rewrites the Swift interface at framework compilation time.

---

## Notes

- `shared` must be published to Maven Local before running any task here.
- `src/commonMain/.../Artifacts.kt` is intentionally empty — do not add logic here.
- The `sharedAar` configuration uses `isTransitive = false` to grab only the AAR without pulling transitive deps.
