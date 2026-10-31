# Implementation Plan — CamIMU-Orient (MEDIUM difficulty)

This is the step-by-step build plan. Claude Code does **one task per session/prompt**
(see `docs/Prompts.md`). Every task builds on the previous **verified** task.

Conventions used below:
- **[DEVICE]**: needs the physical phone. Claude Code builds and installs, then STOPS and hands the user the manual steps.
- **VERIFY**: a fact (version, API detail) that must be checked before relying on it.
- All formulas reference `docs/Architecture.md` (§ numbers). Names match `docs/DataModel.md`.
- Paths: `core/src/main/kotlin/com/camimu/orient/core/...` is abbreviated `core:/...`,
  `app/src/main/java/com/camimu/orient/...` is `app:/...`, tests `core:test/...` =
  `core/src/test/kotlin/com/camimu/orient/core/...`.

---

## Build status (2026-10-31)
All tasks in Phases 0–7 have been **implemented in code** (Task 3.9 essential-matrix check deliberately skipped, it is optional).
Verified on the laptop: `./scripts/verify.sh` passes (forbidden-API guard, 83 JVM tests, APK build, lint with no issues),
and a synthetic session (`:replay --synthesize`) checks the full chain: estimator vs truth median |Δyaw| 0.11°, pitch/roll RMSE 0.15°/0.17°,
time offset recovered 11.5 ms (true 12 ms), axis-check slopes 1.000.
**Pending:** every [DEVICE] step (no phone was attached). See docs/Progress.md "Device checklist".
Deviations from the plan below are recorded in docs/Decisions.md ADR-018 … ADR-023 (manual Gradle setup, bias Jacobian,
`seq` ordering, REC restarts the session, palette, ARCore clock).

## Contents
- [Before you start](#before-you-start)
- [Phase 0 — Setup](#phase-0--setup)
- [Phase 1 — Math core + ARCore baseline](#phase-1--math-core--arcore-baseline)
- [Phase 2 — IMU-only custom estimator](#phase-2--imu-only-custom-estimator)
- [Phase 3 — Vision front-end](#phase-3--vision-front-end)
- [Phase 4 — Vision fusion + obstacle robustness](#phase-4--vision-fusion--obstacle-robustness)
- [Phase 5 — Calibration](#phase-5--calibration)
- [Phase 6 — Benchmark mode (custom vs ARCore)](#phase-6--benchmark-mode-custom-vs-arcore)
- [Phase 7 — Validation + polish](#phase-7--validation--polish)
- [Replay / offline testing](#replay--offline-testing)
- [Tuning guide](#tuning-guide)
- [Calibration guide (MEDIUM level)](#calibration-guide-medium-level)
- [Troubleshooting](#troubleshooting)
- [STRETCH (not in the main path)](#stretch-not-in-the-main-path)

---

## Before you start

### Hardware
- An Android phone (Android 8.0 / API 26 or newer) with a **gyroscope** (most phones do. Check
  with any "sensor test" app, or later with our app's sensor list in `meta.json`).
- **ARCore support** for Phases 1 and 6: open the Play Store and search for *Google Play Services for AR*.
  If it installs, the device is supported. Official list: https://developers.google.com/ar/devices (VERIFY current URL).
  No ARCore? Phases 2–5 and 7 still work. Phase 1 becomes "skip" and Phase 6 is replaced by physical ground truth only.
- A USB data cable (charge-only cables are a classic time-waster).
- For ground truth (Phase 7): a protractor printed on A4 (or a floor with taped angle lines), a
  small tripod/phone clamp, and wedges or books for 10°/20°/30° tilts (measure them with a phone-free
  inclinometer or trigonometry: `angle = atan(height/length)`).

### Laptop (your machine: Kali Linux, Android SDK found at `~/Android/Sdk`, `adb` and `gradle` on PATH)
1. **Android Studio** (latest stable) from https://developer.android.com/studio. Extract to `/opt/android-studio`
   and run `/opt/android-studio/bin/studio.sh`. Let it update the SDK: install **SDK Platform** for the
   template's compileSdk and **Android SDK Platform-Tools**. You do **not** need the NDK or CMake.
2. **JDK 17** for command-line Gradle. Android Studio bundles one:
   `export JAVA_HOME=/opt/android-studio/jbr` (VERIFY path inside your install) in `~/.zshrc`.
   Check: `$JAVA_HOME/bin/java -version` → 17 or 21.
3. **adb**: make sure the `adb` on PATH is the SDK one: `which adb; adb version`. You have
   `~/.local/bin/adb`. If its version differs from `~/Android/Sdk/platform-tools/adb`, you get
   "adb server version doesn't match". Prefer `export PATH=$HOME/Android/Sdk/platform-tools:$PATH`.
4. **USB permissions (Linux):** `sudo apt install android-sdk-platform-tools-common` (provides udev rules. VERIFY package name on Kali),
   then `sudo usermod -aG plugdev $USER`, log out/in.
5. **Python** for analysis/calibration: from the project root
   `python3 -m venv .venv && . .venv/bin/activate && pip install -r tools/requirements.txt` (file created in Task 2.10).

### Phone setup
1. Settings → About phone → tap **Build number** 7×. That enables Developer options.
2. Developer options → **USB debugging** ON. (Optional: "Stay awake" ON.)
3. Plug in, accept the RSA fingerprint prompt on the phone. `adb devices` must show `<serial>  device`.
4. Wireless alternative (Android 11+): Developer options → Wireless debugging → *Pair with code* →
   `adb pair <ip>:<port>` then `adb connect <ip>:<port>`.

### Why not the emulator?
Emulators synthesize sensor values and a fake camera. Our whole problem is real gyro bias,
real noise, real timestamps and real images, so the emulator is useless here. Use it never. Instead:
- **No-device testing** = `./gradlew :core:test`. All math/fusion lives in the pure-JVM `:core`
  module and runs in seconds on the laptop.
- **Offline realism** = record a session once on the phone, then **replay** it on the laptop with
  `:replay` as many times as you like (see [Replay](#replay--offline-testing)).

### Library versions (VERIFY all at Task 0.1/0.2)
Use Android Studio's **New Project template** values for AGP, Kotlin, Compose BOM, compileSdk/targetSdk
(they are current by construction). For the rest, these are versions known to exist in early 2025.
Check Maven Central / Google Maven for the latest stable before adding:

| Library | Coordinates | Known version (VERIFY latest stable) |
|---|---|---|
| CameraX | `androidx.camera:camera-core / camera-camera2 / camera-lifecycle / camera-view` | 1.4.1 |
| ARCore | `com.google.ar:core` | 1.47.0 |
| OpenCV Android | `org.opencv:opencv` (Maven Central, published since 4.9.0, VERIFY) | 4.10.0 |
| kotlinx-serialization-json | `org.jetbrains.kotlinx:kotlinx-serialization-json` | 1.7.3 |
| kotlinx-coroutines-android | `org.jetbrains.kotlinx:kotlinx-coroutines-android` | 1.9.0 |
| JUnit 4 | `junit:junit` | 4.13.2 |
| Serialization plugin | `org.jetbrains.kotlin.plugin.serialization` | = your Kotlin version |
| Kotlin JVM plugin | `org.jetbrains.kotlin.jvm` | = your Kotlin version |

How to check a version: open `https://central.sonatype.com/artifact/org.opencv/opencv` or
`https://maven.google.com/web/index.html` (CameraX, ARCore). Record the chosen versions in
`docs/Decisions.md` (ADR-015) when done.

---

## Phase 0 — Setup

### Task 0.1 — Create the Android project (USER + Claude)
**Goal.** A compiling Compose app named CamIMU-Orient in this folder.
**Why.** Android Studio's template gives correct, current Gradle/AGP/Kotlin/Compose versions.
Hand-writing these is the #1 way to lose a day to version mismatches.

**Files.** Generated by the template: `settings.gradle.kts`, `build.gradle.kts`, `gradle/libs.versions.toml`,
`gradle/wrapper/*`, `gradlew`, `app/build.gradle.kts`, `app/src/main/AndroidManifest.xml`, `app:/MainActivity.kt`, `app:/ui/theme/*`.
Plus a `.gitignore` you create/extend.

**As built:** the Gradle project was written by hand (ADR-018) against cached AGP 8.3.2 / Kotlin 1.9.24; the template route below remains valid for a fresh machine.

**Steps.**
1. USER: Android Studio → New Project → **Empty Activity** (Compose). Name `CamIMU-Orient`,
   package `com.camimu.orient`, **Save location = this folder's parent so the project lands in this folder**
   (simplest: create in a temp location, then move all generated files into this folder next to `project.md`).
   Language Kotlin, Minimum SDK **API 26**, build config language **Kotlin DSL** (with version catalog).
2. USER: let Gradle sync finish once in Android Studio. Then close it or keep it open, either is fine.
3. Claude: verify `./gradlew :app:assembleDebug` works from the terminal.
4. Claude: make sure `.gitignore` contains: `.gradle/ build/ */build/ local.properties .idea/ *.iml .venv/ captures/ .externalNativeBuild/ .cxx/ sessions/ *.apk`.
5. Git: this folder is currently **inside the Desktop-level git repo** (`git rev-parse --show-toplevel` = `~/Desktop`).
   Recommended: `git init` in this folder (nested repo) so the project has its own history. Only do this if the user agrees (see Issues.md Q-2).

**Verify.**
```bash
./gradlew :app:assembleDebug     # BUILD SUCCESSFUL
ls app/build/outputs/apk/debug/app-debug.apk
```
**Done when.** APK builds from the terminal. `minSdk = 26` in `app/build.gradle.kts`.
**Pitfalls.** `JAVA_HOME` not set → "Unsupported class file major version" or "JAVA_HOME is not set".
`local.properties` missing `sdk.dir` → create it: `sdk.dir=/home/darkwing/Android/Sdk`.
**Commit.** `chore: scaffold Android project from Studio template (minSdk 26)`

---

### Task 0.2 — Add `:core` and `:replay` modules, align JVM targets
**Goal.** Three Gradle modules: `:app` (Android), `:core` (pure Kotlin/JVM library), `:replay` (JVM CLI).
**Why.** Pure-JVM code can't accidentally import Android or OpenCV, and its tests run in seconds
without a phone. That is the backbone of reliable step-by-step building.

**Files.**
- `settings.gradle.kts` (add `include(":core", ":replay")`)
- `build.gradle.kts` (root: plugin aliases `apply false`)
- `gradle/libs.versions.toml` (add entries below)
- `core/build.gradle.kts`, `core/src/main/kotlin/com/camimu/orient/core/CoreInfo.kt`, `core/src/test/kotlin/com/camimu/orient/core/CoreInfoTest.kt`
- `replay/build.gradle.kts`, `replay/src/main/kotlin/com/camimu/orient/replay/ReplayMain.kt`
- `app/build.gradle.kts` (add `implementation(project(":core"))`, JVM 17, abiFilters)
- `gradle.properties` (ensure `kotlin.code.style=official`)

**Steps.**
1. Add to `libs.versions.toml` (keep the template's existing keys; the Kotlin version key is usually `kotlin`, VERIFY):
```toml
[versions]
kotlinxSerialization = "1.7.3"   # VERIFY latest stable
coroutines = "1.9.0"             # VERIFY
junit = "4.13.2"                 # template may already define this

[libraries]
kotlinx-serialization-json = { group = "org.jetbrains.kotlinx", name = "kotlinx-serialization-json", version.ref = "kotlinxSerialization" }
kotlinx-coroutines-android = { group = "org.jetbrains.kotlinx", name = "kotlinx-coroutines-android", version.ref = "coroutines" }

[plugins]
kotlin-jvm = { id = "org.jetbrains.kotlin.jvm", version.ref = "kotlin" }
kotlin-serialization = { id = "org.jetbrains.kotlin.plugin.serialization", version.ref = "kotlin" }
```
2. Root `build.gradle.kts`: add `alias(libs.plugins.kotlin.jvm) apply false` and `alias(libs.plugins.kotlin.serialization) apply false`.
3. `core/build.gradle.kts`:
```kotlin
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}
kotlin { jvmToolchain(17) }
dependencies {
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.junit)
}
```
4. `replay/build.gradle.kts`:
```kotlin
plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}
kotlin { jvmToolchain(17) }
application { mainClass.set("com.camimu.orient.replay.ReplayMainKt") }
tasks.named<JavaExec>("run") { workingDir = rootProject.projectDir }   // relative paths resolve from repo root
dependencies {
    implementation(project(":core"))
    testImplementation(libs.junit)
}
```
5. `app/build.gradle.kts`: inside `android {}` set
```kotlin
compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
kotlinOptions { jvmTarget = "17" }     // or kotlin { jvmToolchain(17) }; follow the template's style
defaultConfig { ndk { abiFilters += listOf("arm64-v8a") } }   // smaller APK (OpenCV ships native libs). No NDK install needed.
```
   and add `implementation(project(":core"))`.
6. `CoreInfo.kt`: `object CoreInfo { const val VERSION = "0.1.0" }`. `CoreInfoTest` asserts it is not blank.
7. `ReplayMain.kt`: `fun main(args: Array<String>) { println("replay: core ${CoreInfo.VERSION}") }`.

**Verify.**
```bash
./gradlew :core:test :replay:run :app:assembleDebug
# expect: CoreInfoTest PASSED, "replay: core 0.1.0", BUILD SUCCESSFUL
```
**Done when.** All three modules build. `:app` can reference `CoreInfo.VERSION`.
**Pitfalls.** Template uses Java 11 for `:app`. If `:core` targets 17 and `:app` 11, you get
"Cannot inline bytecode built with JVM target 17 into bytecode built with JVM target 11". Align both to 17.
`kotlinOptions {}` is deprecated in newer Kotlin Gradle plugins: use `kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }` if the build warns (VERIFY with your Kotlin version).
**Commit.** `build: add pure-JVM :core and :replay modules, align JVM 17`

---

### Task 0.3 — Forbidden-API guard + verify script
**Goal.** A script that fails the build when compass/magnetometer APIs appear in source, and one `verify.sh` that runs every check.
**Why.** The "no compass" rule is the project's defining constraint. A grep guard makes it provable
(you can show the examiner the script and its output) and stops Claude Code from "helpfully" using `TYPE_ROTATION_VECTOR`.

**Files.** `scripts/check_forbidden_apis.sh`, `scripts/verify.sh` (both `chmod +x`).

**Steps.** Create exactly:
```bash
#!/usr/bin/env bash
# scripts/check_forbidden_apis.sh — fails if compass/magnetometer APIs appear in source.
set -euo pipefail
cd "$(dirname "$0")/.."
PATTERN='\bTYPE_MAGNETIC_FIELD(_UNCALIBRATED)?\b|\bTYPE_ROTATION_VECTOR\b|\bTYPE_GEOMAGNETIC_ROTATION_VECTOR\b|\bTYPE_ORIENTATION\b|getRotationMatrix[[:space:]]*\(|\bgetOrientation[[:space:]]*\(|getInclination[[:space:]]*\(|\bGeomagneticField\b|android\.hardware\.sensor\.compass'

scan() {  # $@ = directories
  grep -rnE --include='*.kt' --include='*.java' --include='*.xml' --include='*.kts' "$PATTERN" "$@"
}

if [[ "${1:-}" == "--self-test" ]]; then
  tmp=$(mktemp -d); trap 'rm -rf "$tmp"' EXIT
  echo 'val s = Sensor.TYPE_ROTATION_VECTOR' > "$tmp/Bad.kt"
  echo 'val s = Sensor.TYPE_GAME_ROTATION_VECTOR; val m = getRotationMatrixFromVector' > "$tmp/Ok.kt"
  hits=$(scan "$tmp" | wc -l)
  if [[ "$hits" -eq 1 ]] && scan "$tmp" | grep -q Bad.kt; then echo "self-test OK"; exit 0; fi
  echo "self-test FAILED (hits=$hits)"; exit 1
fi

dirs=(); for d in app/src core/src replay/src; do [[ -d "$d" ]] && dirs+=("$d"); done
if [[ ${#dirs[@]} -eq 0 ]]; then echo "forbidden-API check: no source dirs yet"; exit 0; fi
if scan "${dirs[@]}"; then
  echo "FAIL: forbidden compass/magnetometer API found (lines above)"; exit 1
fi
echo "OK: no forbidden sensor APIs in ${dirs[*]}"
```
```bash
#!/usr/bin/env bash
# scripts/verify.sh — run before every commit.
set -euo pipefail
cd "$(dirname "$0")/.."
./scripts/check_forbidden_apis.sh --self-test
./scripts/check_forbidden_apis.sh
./gradlew :core:test :replay:build :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
echo "verify.sh: ALL CHECKS PASSED"
```
Note: `getRotationMatrixFromVector` is not matched (the pattern needs `getRotationMatrix` followed by `(`).
`TYPE_GAME_ROTATION_VECTOR` (gyro+accel only) is not matched, though we don't use it in the main path either (ADR-014).

**Verify.**
```bash
./scripts/check_forbidden_apis.sh --self-test   # "self-test OK"
./scripts/verify.sh                              # ends with "ALL CHECKS PASSED"
```
**Done when.** Both scripts pass. Temporarily adding `Sensor.TYPE_ROTATION_VECTOR` to `MainActivity.kt` makes `verify.sh` fail (try it, then revert).
**Pitfalls.** macOS grep lacks `\b` in ERE, but we're on GNU grep, fine. Don't scan `docs/` (the docs name the forbidden APIs on purpose).
**Commit.** `chore: add forbidden compass-API guard and verify script`

---

### Task 0.4 — Manifest, permissions, app shell with modes
**Goal.** Portrait-locked app with a Home screen listing modes (ARCore, Custom, Custom IMU-only, Benchmark, Calibrate, Settings), a camera-permission flow and keep-screen-on.
**Why.** The rest of the project plugs screens into this shell. Portrait lock makes the output frame `O` fixed (Architecture §4.1), which removes a whole class of rotation bugs.

**Files.** `app/src/main/AndroidManifest.xml`, `app:/MainActivity.kt`, `app:/ui/AppScreen.kt`, `app:/ui/HomeScreen.kt`, `app:/ui/PermissionGate.kt`.

**Steps.**
1. Manifest:
```xml
<uses-permission android:name="android.permission.CAMERA" />
<uses-feature android:name="android.hardware.camera" android:required="true" />
<uses-feature android:name="android.hardware.sensor.gyroscope" android:required="true" />
<uses-feature android:name="android.hardware.sensor.accelerometer" android:required="true" />
<application ...>
    <!-- AR Optional: app installs on non-ARCore phones; ARCore mode is disabled there. VERIFY in ARCore docs -->
    <meta-data android:name="com.google.ar.core" android:value="optional" />
    <activity android:name=".MainActivity" android:exported="true"
              android:screenOrientation="portrait" ...>
```
2. `AppScreen` enum: `HOME, ARCORE, CUSTOM, CUSTOM_IMU_ONLY, BENCHMARK, CALIBRATE, SETTINGS`.
   Navigation = `var screen by rememberSaveable { mutableStateOf(AppScreen.HOME) }` + `when(screen)`.
   No navigation library (fewer moving parts). Back button: `BackHandler { screen = HOME }`.
3. `PermissionGate(content)`: uses `rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission())`
   for `Manifest.permission.CAMERA`. Shows a "Camera permission needed" message + button if denied.
4. `MainActivity.onCreate`: `window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)`.
5. Placeholder screens show their name and a Back button.

**Verify.**
```bash
./scripts/verify.sh
./gradlew :app:installDebug && adb shell am start -n com.camimu.orient/.MainActivity   # [DEVICE]
```
**Done when.** [DEVICE] App opens in portrait and doesn't rotate with the phone. Home shows 6 buttons. Opening Custom asks for camera permission once.
**Pitfalls.** `screenOrientation` lint warnings on large screens are fine for this project (suppress with `tools:ignore="LockedOrientationActivity"`).
**Commit.** `feat(app): portrait app shell with modes and camera permission flow`

### ✅ Checkpoint 0 (manual)
- `./scripts/verify.sh` passes. App installs and launches on the phone in portrait.
- `adb devices` lists the phone. `adb logcat -s CamIMU` works (empty is fine).

---

## Phase 1 — Math core + ARCore baseline

> Concept: every angle bug comes from frames and conventions. We write the math once, in
> `:core`, test it hard, and reuse it for ARCore, our estimator, replay and the simulator.

### Task 1.1 — `Vec3`, `Mat3`, `AngleMath`
**Goal.** Immutable 3-vector and 3×3 matrix with the operations we need, plus angle helpers.
**Why.** Small, readable math types beat a heavyweight library for a 3-D rotation problem, and they work identically on phone and laptop.

**Files.** `core:/math/Vec3.kt`, `core:/math/Mat3.kt`, `core:/math/AngleMath.kt`, `core:test/math/Vec3Test.kt`, `core:test/math/Mat3Test.kt`, `core:test/math/AngleMathTest.kt`.

**Signatures.**
```kotlin
data class Vec3(val x: Double, val y: Double, val z: Double) {
    operator fun plus(o: Vec3) = Vec3(x + o.x, y + o.y, z + o.z)
    operator fun minus(o: Vec3) = Vec3(x - o.x, y - o.y, z - o.z)
    operator fun times(s: Double) = Vec3(x * s, y * s, z * s)
    operator fun div(s: Double) = Vec3(x / s, y / s, z / s)
    operator fun unaryMinus() = Vec3(-x, -y, -z)
    operator fun get(i: Int): Double = when (i) { 0 -> x; 1 -> y; 2 -> z; else -> throw IndexOutOfBoundsException("$i") }
    infix fun dot(o: Vec3) = x * o.x + y * o.y + z * o.z
    infix fun cross(o: Vec3) = Vec3(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x)
    fun norm(): Double = sqrt(this dot this)
    fun normalized(): Vec3 { val n = norm(); require(n > 1e-12) { "zero vector" }; return this / n }
    fun isFinite() = x.isFinite() && y.isFinite() && z.isFinite()
    companion object { val ZERO = Vec3(0.0, 0.0, 0.0); val EX = Vec3(1.0, 0.0, 0.0); val EY = Vec3(0.0, 1.0, 0.0); val EZ = Vec3(0.0, 0.0, 1.0) }
}

/** Row-major 3x3 matrix. m[r*3+c]. Immutable (array is copied on construction). */
class Mat3 private constructor(private val m: DoubleArray) {
    operator fun get(r: Int, c: Int): Double = m[r * 3 + c]
    operator fun times(o: Mat3): Mat3
    operator fun times(v: Vec3): Vec3
    fun transpose(): Mat3
    fun det(): Double
    fun col(c: Int): Vec3
    fun row(r: Int): Vec3
    fun approxEquals(o: Mat3, tol: Double = 1e-9): Boolean
    companion object {
        val IDENTITY: Mat3
        fun of(vararg rowMajor: Double): Mat3          // require(size == 9)
        fun fromCols(c0: Vec3, c1: Vec3, c2: Vec3): Mat3
        fun diag(a: Double, b: Double, c: Double): Mat3
        fun rotX(rad: Double): Mat3; fun rotY(rad: Double): Mat3; fun rotZ(rad: Double): Mat3   // right-handed
    }
}

object AngleMath {
    const val DEG = PI / 180.0
    fun deg(rad: Double) = rad / DEG
    fun rad(deg: Double) = deg * DEG
    /** Wrap to (−π, π]. */
    fun wrapPi(a: Double): Double { var r = (a + PI) % (2 * PI); if (r <= 0) r += 2 * PI; return r - PI }
    /** Smallest signed difference a − b, wrapped. */
    fun angleDiff(a: Double, b: Double) = wrapPi(a - b)
}
```
Rotation matrices: `rotZ(θ) = [[c,−s,0],[s,c,0],[0,0,1]]`, `rotY(θ) = [[c,0,s],[0,1,0],[−s,0,c]]`, `rotX(θ) = [[1,0,0],[0,c,−s],[0,s,c]]`.

**Tests (minimum).** cross(EX,EY)=EZ. `rotZ(π/2)·EX = EY`. `det(rotX(0.3)·rotY(−1.2)) = 1`. transpose·self = I.
`wrapPi(π) = π`, `wrapPi(−π) = π`, `wrapPi(3π) = π`, `wrapPi(−0.1) = −0.1`, `wrapPi(7.0) ≈ 7 − 2π`.
`angleDiff(rad(179), rad(−179)) ≈ rad(−2)`. `normalized()` on ZERO throws.

**Verify.** `./gradlew :core:test` → all pass.
**Done when.** Tests above pass. Every function has KDoc with units.
**Pitfalls.** Kotlin `%` keeps the sign of the dividend, hence the `if (r <= 0)` fix-up. Test `−π` explicitly.
**Commit.** `feat(core): Vec3, Mat3 and angle helpers with tests`

---

### Task 1.2 — `Quaternion` (Hamilton, [w,x,y,z])
**Goal.** Quaternion type with product, rotate, exp/log, matrix conversions.
**Why.** Quaternions represent any 3-D rotation without gimbal lock, and the gyro integrates neatly as `q ⊗ Exp(ω dt)`.
Concept: a unit quaternion `(cos θ/2, sin θ/2 · axis)` is "rotate by θ about axis".

**Files.** `core:/math/Quaternion.kt`, `core:test/math/QuaternionTest.kt`.

**Skeleton (formulas from Architecture §4.2).**
```kotlin
/** Hamilton unit quaternion [w, x, y, z]. qAB maps B-frame vectors into A. */
data class Quaternion(val w: Double, val x: Double, val y: Double, val z: Double) {
    operator fun times(q: Quaternion) = Quaternion(
        w * q.w - x * q.x - y * q.y - z * q.z,
        w * q.x + x * q.w + y * q.z - z * q.y,
        w * q.y - x * q.z + y * q.w + z * q.x,
        w * q.z + x * q.y - y * q.x + z * q.w,
    )
    fun conj() = Quaternion(w, -x, -y, -z)
    fun norm() = sqrt(w * w + x * x + y * y + z * z)
    fun normalized(): Quaternion { val n = norm(); require(n > 1e-12); return Quaternion(w / n, x / n, y / n, z / n) }
    /** Same rotation with w ≥ 0 (q and −q are the same rotation). */
    fun canonical() = if (w < 0) Quaternion(-w, -x, -y, -z) else this
    fun rotate(v: Vec3): Vec3 {
        val u = Vec3(x, y, z); val t = (u cross v) * 2.0
        return v + t * w + (u cross t)          // v + 2w(u×v) + 2u×(u×v)
    }
    fun toMatrix(): Mat3                         // formula in Architecture §4.2
    /** Rotation vector (rad). */
    fun log(): Vec3 {
        val q = canonical(); val s = sqrt(q.x * q.x + q.y * q.y + q.z * q.z)
        if (s < 1e-12) return Vec3(2 * q.x, 2 * q.y, 2 * q.z)
        val k = 2 * atan2(s, q.w) / s
        return Vec3(q.x * k, q.y * k, q.z * k)
    }
    /** Rotation angle between this and o, rad, in [0, π]. */
    fun angleTo(o: Quaternion): Double = (conj() * o).log().norm()
    companion object {
        val IDENTITY = Quaternion(1.0, 0.0, 0.0, 0.0)
        fun exp(phi: Vec3): Quaternion {
            val th = phi.norm()
            if (th < 1e-8) return Quaternion(1.0, phi.x / 2, phi.y / 2, phi.z / 2).normalized()
            val s = sin(th / 2) / th
            return Quaternion(cos(th / 2), phi.x * s, phi.y * s, phi.z * s)
        }
        fun fromAxisAngle(axis: Vec3, angleRad: Double) = exp(axis.normalized() * angleRad)
        fun fromMatrix(m: Mat3): Quaternion      // Shepperd's method, see below
    }
}
```
Shepperd (`t = m00+m11+m22`):
```
if t > 0:            s = 2√(t+1);          w = s/4;          x = (m21−m12)/s; y = (m02−m20)/s; z = (m10−m01)/s
elif m00>m11, m22:   s = 2√(1+m00−m11−m22); w = (m21−m12)/s; x = s/4;         y = (m01+m10)/s; z = (m02+m20)/s
elif m11>m22:        s = 2√(1+m11−m00−m22); w = (m02−m20)/s; x = (m01+m10)/s; y = s/4;         z = (m12+m21)/s
else:                s = 2√(1+m22−m00−m11); w = (m10−m01)/s; x = (m02+m20)/s; y = (m12+m21)/s; z = s/4
return normalized().canonical()
```

**Tests.** `exp(EZ·π/2).rotate(EX) ≈ EY`. `q.toMatrix()·v ≈ q.rotate(v)` for 100 random q,v (seeded `Random(1)`).
`fromMatrix(q.toMatrix()) ≈ q.canonical()` for 100 random q, **including** 180° rotations (w≈0) about X, Y, Z and (1,1,0)/√2.
`exp(log(q)) ≈ q.canonical()`. `log(exp(φ)) ≈ φ` for ‖φ‖ < π, and for tiny φ = 1e-10. Composition `(a*b).rotate(v) = a.rotate(b.rotate(v))`.
Integrating `ω = (0,0,1) rad/s` for 1 s in 200 steps of `q = q * exp(ω·dt)` gives 1 rad about Z (± 1e-9).

**Verify.** `./gradlew :core:test`
**Done when.** All tests pass, including the 180° cases.
**Pitfalls.** Mixing `[x,y,z,w]` (ARCore, Android `getQuaternionFromVector`) with our `[w,x,y,z]`. Forgetting `normalized()` after integrating (the norm drifts). `log()` without the `w<0` flip returns the "long way round" angle.
**Commit.** `feat(core): Hamilton quaternion with exp/log and matrix conversion`

---

### Task 1.3 — `Frames` + `AngleConverter`
**Goal.** Fixed frame rotations and the q → yaw/pitch/roll conversion (and its inverse).
**Why.** This defines what "yaw/pitch/roll" *means* for the whole project (Architecture §4.3–4.4). Every later number is read through it.

**Files.** `core:/frames/Frames.kt`, `core:/frames/AngleConverter.kt`, `core:/model/AttitudeAngles.kt`, `core:test/frames/AngleConverterTest.kt`, `core:test/frames/FramesTest.kt`.

**Signatures.**
```kotlin
data class AttitudeAngles(val yawRad: Double, val pitchRad: Double, val rollRad: Double, val isVertical: Boolean) {
    val yawDeg get() = AngleMath.deg(yawRad); val pitchDeg get() = AngleMath.deg(pitchRad); val rollDeg get() = AngleMath.deg(rollRad)
}

object Frames {
    /** Device → output-camera (portrait back camera). rDO = diag(1,−1,−1). */
    val Q_DO = Quaternion(0.0, 1.0, 0.0, 0.0)
    /** ARCore world (Y up) → our world (Z up). */
    val Q_WA = Quaternion(cos(PI / 4), sin(PI / 4), 0.0, 0.0)
    /** ARCore OpenGL camera (x right, y up, −z view) → output camera O. */
    val Q_GO = Quaternion(0.0, 1.0, 0.0, 0.0)
    /** Reference pose: portrait upright, camera looking along +X_W. Columns r=−Y, d=−Z, f=+X. */
    val R_WO0: Mat3 = Mat3.of(0.0, 0.0, 1.0,  -1.0, 0.0, 0.0,  0.0, -1.0, 0.0)
    /** rCD (device → raw camera image frame) for a back camera with this SENSOR_ORIENTATION (0/90/180/270). */
    fun rCDForSensorOrientation(deg: Int): Mat3
    fun qWOFromDevice(qWD: Quaternion): Quaternion = qWD * Q_DO
    /** ARCore displayOrientedPose rotation [x,y,z,w] as ARCore gives it → qWO. */
    fun qWOFromArCore(xyzw: FloatArray): Quaternion =
        Q_WA * Quaternion(xyzw[3].toDouble(), xyzw[0].toDouble(), xyzw[1].toDouble(), xyzw[2].toDouble()) * Q_GO
}

object AngleConverter {
    /** Raw (un-referenced) yaw of the optical axis, rad. */
    fun yawRaw(qWO: Quaternion): Double
    /** Architecture §4.4. verticalPitchRad default = 80° in rad. */
    fun toAngles(qWO: Quaternion, yawRefRad: Double, verticalPitchRad: Double): AttitudeAngles
    /** Inverse: rWO = Rz(yaw)·Ry(−pitch)·R_WO0·Rz(−roll). For tests and the simulator. */
    fun fromAngles(yawRad: Double, pitchRad: Double, rollRad: Double): Quaternion
}
```
`rCDForSensorOrientation` returns the matrices listed in Architecture §4.3 (90 → `[[0,−1,0],[−1,0,0],[0,0,−1]]`, 270 → `[[0,1,0],[1,0,0],[0,0,−1]]`, 0 → `diag(1,−1,−1)`, 180 → `diag(−1,1,−1)`). Any other value → `IllegalArgumentException`.

**Math (`toAngles`).**
```
R = qWO.toMatrix();  r = R.col(0); d = R.col(1); f = R.col(2)
yaw   = wrapPi(atan2(f.y, f.x) − yawRef)
pitch = asin(f.z.coerceIn(−1.0, 1.0))
roll  = atan2(r.z, −d.z)
isVertical = |pitch| > verticalPitchRad
```

**Tests.** (deg tolerance 1e-6 unless noted)
1. `toAngles(fromQuat(R_WO0), 0)` → (0, 0, 0).
2. Round-trip `toAngles(fromAngles(y,p,r)) == (y,p,r)` on a grid y∈{−170,−90,0,45,179}, p∈{−60,−10,0,30,79}, r∈{−170,−45,0,20,175}.
3. Turning left: `fromAngles(0,0,0)` pre-multiplied by `rotZ(+90°)` → yaw = +90.
4. Pitch: rotating the camera up 30° → pitch +30. Roll: right side raised 20° → roll +20 (check `r.z > 0`).
5. Wrap: yaw 179° then +2° → −179°. `yawRef` = 10° → output yaw shifted by −10°.
6. Vertical: pitch 85° → `isVertical = true`; no NaN for pitch exactly ±90°.
7. Device mapping: device upright portrait with camera along +X → `rWD = [[0,0,−1],[−1,0,0],[0,1,0]]` → `qWOFromDevice` gives angles (0,0,0).
8. `rCDForSensorOrientation(x).det() == 1` and orthonormal for all four values.
9. ARCore mapping: ARCore identity camera (OpenGL camera looking along −Z_A, Y_A up) → our f = +Y_W → yaw = +90°, pitch 0, roll 0.

**Verify.** `./gradlew :core:test`
**Done when.** All 9 test groups pass.
**Pitfalls.** Using `R.row()` instead of `R.col()`. Getting the sign of yaw backward (left turn must be +). ARCore quaternion order `[x,y,z,w]`.
**Commit.** `feat(core): frame definitions and yaw/pitch/roll converter`

---

### Task 1.4 — ARCore session + camera background `[DEVICE]`
**Goal.** ARCore mode shows the live camera through ARCore with a correctly managed session.
**Why.** ARCore (visual-inertial odometry by Google) gives a strong **reference** orientation fast. Later it is our benchmark.
Concept: ARCore owns the camera and renders it into an OpenGL texture. That's why we need a `GLSurfaceView`.

**Files.** `app/build.gradle.kts`, `gradle/libs.versions.toml` (ARCore), `app:/ar/ArCoreAvailability.kt`, `app:/ar/ArCoreSessionController.kt`, `app:/ar/BackgroundRenderer.kt`, `app:/ui/ArCoreScreen.kt`.

**Dependencies.** `arcore = "1.47.0"` (VERIFY) → `implementation(libs.arcore)` with `arcore = { group = "com.google.ar", name = "core", version.ref = "arcore" }`.

**Steps.**
1. `ArCoreAvailability.check(activity): ArStatus` using `ArCoreApk.getInstance().checkAvailability(context)`.
   If `SUPPORTED_NOT_INSTALLED`/`SUPPORTED_APK_TOO_OLD` → `ArCoreApk.getInstance().requestInstall(activity, true)`.
   If unsupported → ARCore and Benchmark buttons disabled on Home with the text "ARCore not supported".
   `checkAvailability` can return `UNKNOWN_CHECKING`: re-check after 200 ms (VERIFY behaviour).
2. `ArCoreSessionController(activity)`: owns `Session`. Configure:
   ```kotlin
   val config = Config(session).apply {
       updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
       focusMode = Config.FocusMode.FIXED
       planeFindingMode = Config.PlaneFindingMode.DISABLED
       lightEstimationMode = Config.LightEstimationMode.DISABLED
   }   // VERIFY property names against the ARCore version you use
   session.configure(config)
   ```
   `resume()` / `pause()` follow the screen lifecycle (`DisposableEffect` + `LifecycleEventObserver`). `close()` on dispose.
3. `BackgroundRenderer` (≈120 lines, GLES20): creates an OES external texture, draws a full-screen quad.
   Pattern follows ARCore's `hello_ar_java` sample `BackgroundRenderer` (Apache-2.0. VERIFY path in the arcore-android-sdk repo):
   ```kotlin
   class BackgroundRenderer {
       var textureId = -1; private set
       fun createOnGlThread() { /* glGenTextures, bind GL_TEXTURE_EXTERNAL_OES, CLAMP_TO_EDGE, LINEAR, compile shaders */ }
       fun draw(frame: Frame) {
           if (frame.hasDisplayGeometryChanged())
               frame.transformCoordinates2d(Coordinates2d.OPENGL_NORMALIZED_DEVICE_COORDINATES, quadCoords,
                                            Coordinates2d.TEXTURE_NORMALIZED, quadTexCoords)
           if (frame.timestamp == 0L) return      // no image yet
           /* glDisable depth, use program, bind texture, draw TRIANGLE_STRIP */
       }
   }
   ```
   Fragment shader: `#extension GL_OES_EGL_image_external : require` + `uniform samplerExternalOES sTexture;`.
4. `GLSurfaceView.Renderer`: `onSurfaceCreated` → `renderer.createOnGlThread()`, `session.setCameraTextureName(renderer.textureId)`.
   `onSurfaceChanged(w,h)` → `session.setDisplayGeometry(Surface.ROTATION_0, w, h)` (portrait locked).
   `onDrawFrame` → `val frame = session.update(); renderer.draw(frame)`. Catch `CameraNotAvailableException` → show error.
5. `ArCoreScreen` hosts the `GLSurfaceView` via `AndroidView`, with `setEGLContextClientVersion(2)`, `preserveEGLContextOnPause = true`, `renderMode = RENDERMODE_CONTINUOUSLY`.

**Verify.**
```bash
./scripts/verify.sh && ./gradlew :app:installDebug
```
[DEVICE] Open ARCore mode → camera image fills the screen, upright, not stretched. Leave and re-enter the screen 5× → no crash (`adb logcat -s CamIMU AndroidRuntime`).
**Done when.** Live ARCore camera view, clean pause/resume, unsupported devices handled.
**Pitfalls.** Calling `session.update()` before `setCameraTextureName` → black screen or exception. Not calling `session.pause()` on `ON_PAUSE` → camera stays locked for other apps.
Calling ARCore from the UI thread instead of the GL thread. Forgetting `frame.timestamp == 0` check → garbage on first frame.
**Commit.** `feat(ar): ARCore session lifecycle and camera background rendering`

---

### Task 1.5 — ARCore pose → yaw/pitch/roll + reset yaw + numeric HUD `[DEVICE]`
**Goal.** Show ARCore's yaw/pitch/roll in degrees with tracking state and a Reset-yaw button.
**Why.** This is the working Phase-1 deliverable and the reference you'll compare against.

**Files.** `core:/model/ArCoreReading.kt`, `app:/ar/ArCorePoseReader.kt`, `app:/ui/NumericReadout.kt`, `app:/ui/ArCoreScreen.kt` (modify), `core:/model/Confidence.kt`.

**Signatures.**
```kotlin
enum class Confidence { HIGH, MEDIUM, LOW }
enum class ArTracking { TRACKING, PAUSED, STOPPED }
data class ArCoreReading(val tNs: Long, val tArCoreNs: Long, val qWO: Quaternion, val tracking: ArTracking,
                         val failureReason: String, val angles: AttitudeAngles)

class ArCorePoseReader(private val verticalPitchRad: Double) {
    @Volatile var yawRefRad = 0.0
    private var resetRequested = true            // first TRACKING frame sets yaw = 0
    fun requestResetYaw() { resetRequested = true }
    /** GL thread. */
    fun read(frame: Frame): ArCoreReading
}
```
**Steps.**
1. In `read`: `val cam = frame.camera`, map `cam.trackingState` → `ArTracking`. If TRACKING:
   `val xyzw = FloatArray(4); cam.displayOrientedPose.getRotationQuaternion(xyzw, 0)`,
   `qWO = Frames.qWOFromArCore(xyzw)`. If `resetRequested`: `yawRefRad = AngleConverter.yawRaw(qWO)`, clear flag.
   `angles = AngleConverter.toAngles(qWO, yawRefRad, verticalPitchRad)`.
   `tNs = SystemClock.elapsedRealtimeNanos()` (display time), `tArCoreNs = frame.timestamp`.
2. Publish to a `MutableStateFlow<ArCoreReading?>`, collected in Compose with `collectAsStateWithLifecycle()` (or `collectAsState()`).
3. Confidence: TRACKING → HIGH, PAUSED → LOW (show `trackingFailureReason`), STOPPED → LOW.
4. `NumericReadout(yawDeg, pitchDeg, rollDeg, isVertical)`: large monospace text, one decimal. Shows "—" for yaw/roll when `isVertical`.
   Caption under yaw: "relative to start".
5. Reset-yaw button calls `requestResetYaw()`.

**Verify.** `./scripts/verify.sh && ./gradlew :app:installDebug`, then the manual steps in Checkpoint 1.
**Done when.** Numbers match the physical tests in Checkpoint 1.
**Pitfalls.** Using `camera.pose` (sensor-oriented, landscape) instead of `displayOrientedPose` → roll off by ±90°.
Reading the pose when not TRACKING (stale/identity). Recomputing `yawRef` every frame.
**Commit.** `feat(ar): ARCore yaw/pitch/roll readout with reset and tracking confidence`

---

### Task 1.6 — HUD widgets: artificial horizon, heading dial, confidence badge, warnings
**Goal.** Reusable Compose widgets driven by `AttitudeAngles` + `Confidence` + warnings, used by all modes.
**Why.** A visual horizon/dial makes sign errors obvious instantly (much faster than reading numbers).

**Files.** `app:/ui/ArtificialHorizon.kt`, `app:/ui/HeadingDial.kt`, `app:/ui/ConfidenceBadge.kt`, `app:/ui/WarningsBanner.kt`, `app:/ui/HudOverlay.kt`, `app:/ui/ArCoreScreen.kt` (use `HudOverlay`), `core:/model/OrientationWarning.kt`.

**Signatures.**
```kotlin
enum class OrientationWarning { INITIALIZING, INIT_NOT_STATIC, CAMERA_BLOCKED, LOW_TEXTURE, MOVING_TOO_FAST,
                                VERTICAL, ACCEL_DISTURBED, SENSOR_GAP, TRACKING_LOST }
@Composable fun ArtificialHorizon(pitchDeg: Double, rollDeg: Double, modifier: Modifier = Modifier)
@Composable fun HeadingDial(yawDeg: Double, frozen: Boolean, modifier: Modifier = Modifier)
@Composable fun ConfidenceBadge(confidence: Confidence, sigmaYawDeg: Double?, modifier: Modifier = Modifier)
@Composable fun WarningsBanner(warnings: Set<OrientationWarning>, modifier: Modifier = Modifier)
@Composable fun HudOverlay(angles: AttitudeAngles?, confidence: Confidence, sigmaYawDeg: Double?,
                           warnings: Set<OrientationWarning>, onResetYaw: () -> Unit, modifier: Modifier = Modifier)
```
**Steps.**
1. Horizon (`Canvas`, 160 dp circle, clipped): `rotate(degrees = (-rollDeg).toFloat())`, then translate the
   sky/ground split by `pitchDeg · pxPerDeg` (pxPerDeg = radius/45) **downward when pitch > 0** (looking up shows more sky).
   Draw pitch ladder lines every 10°. Fixed aircraft symbol in the centre.
2. Dial: ring with ticks every 10°, labels every 30° ("0" at top). Rotate the ring by `+yawDeg` so that turning
   left (yaw +) moves the "0" mark to the right, like a real heading indicator. Caption: "relative yaw (no compass)".
3. Badge: green/amber/red pill with text `HIGH ±0.8°`. `sigmaYawDeg == null` → no ± text (ARCore).
4. Warnings: human text, e.g. CAMERA_BLOCKED → "Camera blocked: using gyro only".

**Verify.** `./scripts/verify.sh`. Add a Compose `@Preview` for each widget with fixed angles. [DEVICE] ARCore mode shows all widgets.
**Done when.** Tilting the phone right (right side down) tilts the drawn horizon the natural way, as on an aircraft attitude indicator.
**Pitfalls.** Compose `rotate` uses degrees clockwise-positive. Double-check the horizon sign with the phone, not by reasoning alone.
**Commit.** `feat(ui): artificial horizon, heading dial, confidence badge and warnings`

### ✅ Checkpoint 1 (manual, ARCore mode) — expected numbers
1. Phone upright portrait, camera at a textured wall/horizon, press Reset: yaw 0.0, pitch ≈ 0 ± 2°, roll ≈ 0 ± 2°.
2. Tilt camera up onto a 30° wedge: pitch ≈ +30 ± 2°. Tilt down: negative.
3. Raise the right edge 20°: roll ≈ +20 ± 2°.
4. Turn left 90° on the printed protractor: yaw ≈ +90 ± 3°. Turn right back: ≈ 0 ± 3°.
5. Cover the camera: tracking PAUSED within ~1 s, badge red, warning shown. Uncover: recovers.
6. Point straight down at the floor: yaw/roll show "—" (vertical).
If 2–4 have wrong **signs**, fix `Frames` (Task 1.3 tests should have caught it, so add the failing case as a new test).

---

## Phase 2 — IMU-only custom estimator

> Concept: the gyro tells you how fast you rotate (integrate it → orientation), but it has a
> bias that makes the angle drift. Gravity (accelerometer) fixes pitch/roll, never yaw. In this
> phase yaw is gyro-only, which is the baseline the camera will improve in Phase 4.

### Task 2.1 — Data model + configs (+ JSON)
**Goal.** All core data classes and `EstimatorConfig` / `VisionConfig` with defaults from DataModel.md, serializable to JSON.
**Why.** One typed config object makes tuning reproducible: the same JSON drives the phone and the replay.

**Files.** `core:/model/ImuSample.kt`, `core:/model/OrientationOutput.kt`, `core:/model/VisionMeasurement.kt`, `core:/model/VisionUpdateOutcome.kt`, `core:/model/GrayFrame.kt`, `core:/model/OcclusionState.kt`, `core:/config/EstimatorConfig.kt`, `core:/config/VisionConfig.kt`, `core:/config/CalibrationData.kt`, `core:/config/ConfigJson.kt`, `core:test/config/ConfigJsonTest.kt`.

**Steps.**
1. Copy the data classes and fields **exactly** from `docs/DataModel.md` §1–§3 (names, units, defaults).
2. Annotate config classes with `@Serializable`. `ConfigJson`:
```kotlin
object ConfigJson {
    val json = Json { prettyPrint = true; ignoreUnknownKeys = true; encodeDefaults = true }
    fun encode(c: EstimatorConfig): String = json.encodeToString(EstimatorConfig.serializer(), c)
    fun decodeEstimator(s: String): EstimatorConfig = json.decodeFromString(EstimatorConfig.serializer(), s)
    // same for VisionConfig, CalibrationData
}
```
3. Add `init { require(...) }` validation to configs (e.g. `kTilt >= 0`, `0 < visionAttitudeGain <= 1`, `windowMinSec < windowMaxSec`).

**Tests.** Round-trip encode/decode equals the default. Decoding `{}` gives defaults. Decoding with an unknown key works. An invalid value throws.
**Verify.** `./gradlew :core:test`
**Done when.** Tests pass. Every field has a KDoc with its unit.
**Pitfalls.** `Vec3`/`Quaternion` in `@Serializable` classes need `@Serializable` too (or keep configs to primitives, which is recommended).
**Commit.** `feat(core): data model and serializable estimator/vision configs`

---

### Task 2.2 — `ImuSampleAssembler` + `GyroHistory`
**Goal.** Merge separate gyro/accel events into `ImuSample`s, and keep a 2-s raw-gyro ring buffer that can integrate rotation between any two timestamps.
**Why.** Android delivers gyro and accel as separate events at slightly different times. The vision step later needs "how much did the gyro say we rotated between frame A and frame B?"

**Files.** `core:/fusion/ImuSampleAssembler.kt`, `core:/fusion/GyroHistory.kt`, `core:test/fusion/ImuSampleAssemblerTest.kt`, `core:test/fusion/GyroHistoryTest.kt`.

**Signatures.**
```kotlin
class ImuSampleAssembler(private val maxAccelAgeNs: Long = 50_000_000L) {
    fun onAccel(tNs: Long, accel: Vec3)
    /** Returns a sample per gyro event, or null if no accel has arrived yet. */
    fun onGyro(tNs: Long, gyroRaw: Vec3, osBias: Vec3?): ImuSample?
}

/** Thread-safe ring buffer of raw gyro samples (written by imu thread, read by vision thread). */
class GyroHistory(private val capacity: Int = 512) {
    @Synchronized fun add(tNs: Long, omegaRaw: Vec3)
    @Synchronized fun latestTimeNs(): Long?
    /** Rotation qD1D2 (D at t2 → D at t1) from integrating (ω_raw − bias) over [t1, t2]. Null if not covered. */
    @Synchronized fun integrate(t1Ns: Long, t2Ns: Long, bias: Vec3): Quaternion?
}
```
**Math (`integrate`).** Treat ω(t) as piecewise linear between samples. Collect the knots
`t1, all sample times in (t1,t2), t2`. ω at `t1`/`t2` is linearly interpolated. For each sub-interval
`[ta, tb]`: `q ← q ⊗ Exp(((ω(ta)+ω(tb))/2 − bias) · (tb−ta)·1e-9)`. Return `q.normalized()`. Requires
`oldest ≤ t1 < t2 ≤ newest`, else null. If `t1 > t2` → `require` fails.

Implementation: parallel arrays `LongArray(capacity)` + `DoubleArray(capacity*3)`, head index, count. Binary search for `t1`.

**Tests.** Assembler: gyro before any accel → null; accel age > 50 ms → `accelValid=false`. History: constant ω=(0,0,0.5) rad/s at 200 Hz for 2 s → `integrate(t0+0.25s, t0+1.25s, ZERO).log() ≈ (0,0,0.5)` (1e-9);
with bias=(0,0,0.5) → identity; times between samples (interpolation) exact for constant ω; out-of-range → null; ring overwrite keeps the newest `capacity` samples.
**Verify.** `./gradlew :core:test`
**Done when.** Tests pass.
**Pitfalls.** Off-by-one at ring wrap. Using `Int` for ns → overflow (always `Long`). Multiplying ns by 1e-9 in `Float` (precision loss: ns since boot ~1e13 needs `Double`/`Long`, so always subtract **before** converting).
**Commit.** `feat(core): IMU sample assembler and thread-safe gyro history`

---

### Task 2.3 — `StaticDetector` + `AttitudeInitializer`
**Goal.** Detect when the phone is still. Initialize bias, local gravity and a gravity-aligned attitude with yaw = 0.
**Why.** While static, the true rotation rate is zero, so the mean gyro reading **is** the bias. That's the cheapest, strongest bias measurement there is.

**Files.** `core:/fusion/StaticDetector.kt`, `core:/fusion/AttitudeInitializer.kt`, `core:test/fusion/StaticDetectorTest.kt`, `core:test/fusion/AttitudeInitializerTest.kt`.

**Signatures.**
```kotlin
/** Sliding window over the last windowSec of samples. */
class StaticDetector(private val windowSec: Double, private val gyroStdThr: Double, private val accelStdThr: Double) {
    fun add(s: ImuSample)
    fun isStatic(): Boolean                    // window full AND std(|ω|) per axis < gyroStdThr AND std(a) per axis < accelStdThr
    fun meanGyro(): Vec3; fun meanAccel(): Vec3; fun windowDurationSec(): Double
    fun reset()
}
data class InitResult(val qWD: Quaternion, val bias: Vec3, val biasVar: Double, val gLocal: Double, val yawRefRad: Double, val wasStatic: Boolean)
class AttitudeInitializer(private val cfg: EstimatorConfig) {
    /** Feed samples; returns non-null once initialized (static for staticInitSec, or initTimeoutSec elapsed). */
    fun add(s: ImuSample): InitResult?
}
```
**Math.** Architecture §5.2 (build `rDW` from `z_D` = mean-accel direction and the horizontal projection of `f_D = (0,0,−1)`).
Use Welford's running mean/variance (or recompute over a small ArrayDeque, since the window is ≤ 400 samples).

**Tests.** Synthetic static samples (noise σ=0.002 rad/s, seeded) with bias (0.01, −0.02, 0.005) → `InitResult.bias` within 1e-3. Phone flat on table (accel = (0,0,9.81)) → pitch = −90° (camera looks down), `isVertical`.
Phone upright portrait (accel = (0, 9.81, 0)) → angles (0,0,0). Tilted 20° up → pitch +20. Moving samples (sine rotation 1 rad/s) → no init until timeout, then `wasStatic=false`.
**Verify.** `./gradlew :core:test`
**Done when.** Tests pass.
**Pitfalls.** Remember the accelerometer at rest reads **+9.81 upward** (specific force), not −9.81. Phone flat on its back → `a = (0,0,+9.81)`.
**Commit.** `feat(core): static detection and gravity-aligned attitude initialization`

---

### Task 2.4 — `AttitudeFilter` (gyro integration + complementary tilt correction)
**Goal.** Propagate `qWD` with bias-corrected gyro and correct pitch/roll from gravity (Architecture §5.3).
**Why.** Concept: a *complementary filter* trusts the gyro at high frequency (fast, smooth) and the accelerometer at low frequency (no drift). `kTilt` sets the crossover (τ = 1/kTilt seconds).

**Files.** `core:/fusion/AttitudeFilter.kt`, `core:test/fusion/AttitudeFilterTest.kt`.

**Skeleton.**
```kotlin
class AttitudeFilter(private val cfg: EstimatorConfig, initial: Quaternion, private val gLocal: Double) {
    var qWD: Quaternion = initial; private set
    var lastAccelWeight = 0.0; private set
    private var prev: ImuSample? = null

    /** Returns dt used (s), or 0.0 if the sample was dropped (dt ≤ 0). */
    fun step(s: ImuSample, bias: Vec3): Double {
        val p = prev ?: run { prev = s; return 0.0 }
        val dt = (s.tNs - p.tNs) * 1e-9
        if (dt <= 0.0) return 0.0                       // duplicate / out of order
        val omega = (p.gyroRaw + s.gyroRaw) * 0.5 - bias
        val up = qWD.conj().rotate(Vec3.EZ)             // ĝ_D
        var corr = Vec3.ZERO
        if (s.accelValid) {
            val an = s.accel.norm()
            val w = (1.0 - abs(an - gLocal) / cfg.accelGateMs2).coerceIn(0.0, 1.0) *
                    (if (omega.norm() < cfg.maxRateForTiltRadS) 1.0 else 0.0)
            lastAccelWeight = w
            if (w > 0.0 && an > 1e-6) corr = ((s.accel / an) cross up) * (cfg.kTilt * w)
        }
        qWD = (qWD * Quaternion.exp((omega + corr) * dt)).normalized()
        prev = s
        return dt
    }
    fun applyCorrection(qRight: Quaternion) { qWD = (qWD * qRight).normalized() }   // used by vision (Phase 4)
}
```
**Tests.**
1. Constant ω about device y (= world up when upright) 0.5 rad/s for 4 s, no accel correction effect on yaw → yaw = +2 rad wrapped (1e-6). Proves gravity can't change yaw.
2. Start with a 10° pitch error, static accel → error < 0.5° after `5/kTilt` seconds (exponential convergence, τ = 1/kTilt).
3. Linear acceleration of 3 m/s² (|‖a‖−g| > gate) → weight 0, attitude unaffected.
4. dt jitter (random 3–7 ms) with constant ω gives the same final angle as uniform dt (1e-6). Proves real dt is used.
5. Duplicate timestamp sample → dropped, no NaN.
**Verify.** `./gradlew :core:test`
**Done when.** Tests pass.
**Pitfalls.** Cross-product order: it's `â × ĝ` (measured × predicted). Reversed order makes the filter diverge (test 2 catches it).
Using `qWD.rotate` instead of `qWD.conj().rotate` for world→device.
**Commit.** `feat(core): complementary attitude filter with gravity tilt correction`

---

### Task 2.5 — `BiasKalman`, `YawUncertainty`, `ConfidenceModel`
**Goal.** 3-axis diagonal Kalman filter on gyro bias (ZRU now, vision later), conservative σ_yaw, and the confidence level.
**Why.** Concept: a Kalman filter blends a prediction ("bias barely changes") with noisy measurements, each weighted by its variance. Diagonal = three independent 1-D filters, simple enough to verify by hand.

**Files.** `core:/fusion/BiasKalman.kt`, `core:/fusion/YawUncertainty.kt`, `core:/fusion/ConfidenceModel.kt`, tests for each in `core:test/fusion/`.

**Signatures.**
```kotlin
class BiasKalman(initialBias: Vec3, initialVar: Double, private val sigmaBw: Double, private val chi2Gate: Double) {
    var bias: Vec3 = initialBias; private set
    var variance: Vec3 = Vec3(initialVar, initialVar, initialVar); private set   // P_x, P_y, P_z (rad²/s²)
    fun predict(dt: Double)                                         // P_i += σ_bw² dt
    /** z rad/s, r = measurement variance per axis (rad²/s²). Returns Mahalanobis² and whether accepted. */
    fun update(z: Vec3, r: Double): Pair<Double, Boolean>
    /** Vertical-axis bias variance for the device-frame up vector. */
    fun verticalVariance(upD: Vec3): Double = upD.x * upD.x * variance.x + upD.y * upD.y * variance.y + upD.z * upD.z * variance.z
}
class YawUncertainty(private val sigmaG: Double) {
    var varianceRad2 = 0.0; private set
    var timeSinceVisionSec = 0.0; private set
    fun step(dt: Double, pBv: Double, extraVar: Double = 0.0)        // Architecture §5.5
    fun onVisionAccepted(sigmaVis2: Double)
    fun reset()
    val sigmaRad get() = sqrt(varianceRad2)
}
object ConfidenceModel {
    fun level(visionAgeSec: Double?, driftDegPerMin: Double, warnings: Set<OrientationWarning>, cfg: EstimatorConfig): Confidence
}
```
**Math.** Architecture §5.4, §5.5, §7. ZRU measurement variance: `r = σ_g² / T_window`.
Kalman scalar update: `K = P/(P+R); b += K(z−b); P = (1−K)P`. Gate on `d² = Σ(z_i−b_i)²/(P_i+R)` vs `chi2Gate3 = 11.34`.

**Tests.** Constant true bias, 100 noisy measurements → estimate within 3σ of truth and `P` decreasing monotonically.
Outlier `z` 1 rad/s away → rejected, state unchanged. `predict` grows `P` linearly. YawUncertainty: with `pBv = 0` after 100 s, `σ = σ_g·√100`.
With `pBv = (1e-3)²` and no vision for 10 s → `σ ≈ sqrt(σ_g²·10 + 1e-6·100)`. Confidence: never HIGH when `visionAgeSec == null`. INITIALIZING → LOW.
**Verify.** `./gradlew :core:test`
**Done when.** Tests pass.
**Pitfalls.** Mixing variance and std (σ vs σ²) in `R`. Applying `predict` with dt in ns.
**Commit.** `feat(core): gyro-bias Kalman filter, yaw uncertainty and confidence model`

---

### Task 2.6 — `ImuSimulator` + `OrientationEstimator` (IMU-only) + synthetic tests
**Goal.** The estimator facade (init → filter → bias KF → output), validated end-to-end against a simulated trajectory with known truth.
**Why.** Synthetic data has exact ground truth, so you can prove the filter works *before* touching a phone. Every later tuning question can be asked here first.

**Files.** `core:/sim/ImuSimulator.kt`, `core:/fusion/OrientationEstimator.kt`, `core:test/fusion/OrientationEstimatorImuTest.kt`.

**Signatures.**
```kotlin
data class SimResult(val samples: List<ImuSample>, val truthQWD: List<Pair<Long, Quaternion>>)
class ImuSimulator(private val rateHz: Double = 200.0, private val bias: Vec3, private val gyroNoiseDensity: Double,
                   private val accelNoiseStd: Double, private val seed: Long = 7L, private val gravity: Double = 9.81) {
    /** omegaTrueD(t): body rate in device frame (rad/s). linAccW(t): linear acceleration in world (m/s²). */
    fun simulate(durationSec: Double, q0: Quaternion, omegaTrueD: (Double) -> Vec3, linAccW: (Double) -> Vec3 = { Vec3.ZERO }): SimResult
}

class OrientationEstimator(private val cfg: EstimatorConfig, val gyroHistory: GyroHistory = GyroHistory()) {
    val isInitialized: Boolean
    val bias: Vec3
    fun onImu(s: ImuSample): OrientationOutput           // always returns; before init: initialized=false, warning INITIALIZING
    fun onVision(m: VisionMeasurement): VisionUpdateOutcome   // Phase 4; until then returns NOT_IMPLEMENTED reason
    fun setVisionState(state: OcclusionState, nowNs: Long)    // Phase 4; no-op until then
    fun resetYaw()                                       // yawRef = current yawRaw; σ_yaw = 0
}
```
**Simulator math.** Truth: integrate `q ← q ⊗ Exp(ω_true(t)·h)` with 10 substeps per sample.
`gyro = ω_true + bias + N(0, σ_n²)` per axis with `σ_n = gyroNoiseDensity · sqrt(rateHz)`.
`accel = qWDᵀ ⊗ (g·EZ + linAccW(t)) + N(0, accelNoiseStd²)`. Timestamps start at `1_000_000_000_000L` ns (realistic magnitude) with optional jitter.

**`onImu` flow.**
```
gyroHistory.add(s.tNs, s.gyroRaw)
if !initialized: res = initializer.add(s) ?: return INITIALIZING output; build AttitudeFilter, BiasKalman, set yawRef; return
dt = filter.step(s, biasKf.bias);  if dt == 0 → return last output
biasKf.predict(dt)
staticDetector.add(s); if staticDetector.isStatic() && zruAllowed(): biasKf.update(meanGyro, σ_g²/T) once per window (then reset detector)
up = qWD.conj().rotate(EZ);  yawUnc.step(dt, biasKf.verticalVariance(up), gapExtraVar)
angles = AngleConverter.toAngles(Frames.qWOFromDevice(qWD), yawRef, verticalPitchRad)
warnings = {VERTICAL if angles.isVertical, ACCEL_DISTURBED if filter.lastAccelWeight < 0.5, SENSOR_GAP if recent gap, ...}
confidence = ConfidenceModel.level(...)
```
**Tests (synthetic, seeded).**
1. Static 2 s + 60 s static, bias (0.01, −0.005, 0.02) rad/s, noise 1.5e-4 rad/s/√Hz: |yaw| < 0.2° at 60 s (ZRU learns bias), pitch/roll error < 0.1°.
2. 2 s static, then yaw rotation 0.5 rad/s for 6 s, then static: final yaw error vs truth < 1°.
3. 2 s static, then sinusoidal tilt (pitch ±20°, 0.5 Hz) for 30 s: pitch/roll RMSE < 0.5°.
4. With linear accel bursts (walking-like 2 m/s² at 2 Hz): pitch/roll RMSE < 2°.
5. `resetYaw()` mid-run → next output yaw = 0 ± 1e-9.
**Verify.** `./gradlew :core:test`
**Done when.** All five scenarios pass.
**Pitfalls.** Comparing yaw without wrapping (`angleDiff`). ZRU running during slow constant rotation (test 2 must not let ZRU fire while rotating; std-based detection alone won't notice a slow constant rate, so also require `‖meanGyro − bias‖ < zruMaxRateRadS`).
**Commit.** `feat(core): IMU-only orientation estimator validated on synthetic trajectories`

---

### Task 2.7 — `ImuSource` + `TimeSync` (Android) `[DEVICE]`
**Goal.** Register uncalibrated gyro + accel at ~200 Hz on a dedicated thread, check the sensor clock, and measure the real rate.
**Why.** Concept: `SensorEvent.timestamp` is in ns, normally on the `elapsedRealtimeNanos()` clock. We verify that instead of assuming it, because the camera must share the same clock.

**Files.** `app:/sensors/ImuSource.kt`, `app:/sensors/TimeSync.kt`, `app:/sensors/RateMeter.kt`, `app:/ui/CustomImuScreen.kt` (temporary debug text: rate, clock, sensor names).

**Skeleton.**
```kotlin
class ImuSource(context: Context, private val onSample: (ImuSample) -> Unit) : SensorEventListener {
    private val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val thread = HandlerThread("imu", Process.THREAD_PRIORITY_URGENT_DISPLAY).apply { start() }
    val handler = Handler(thread.looper)
    private val assembler = ImuSampleAssembler()
    val timeSync = TimeSync()
    val registeredTypes = mutableSetOf<Int>()
    val gyroRate = RateMeter(); val accelRate = RateMeter()

    fun start(samplingPeriodUs: Int) {
        val gyro = sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE_UNCALIBRATED) ?: sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
            ?: error("No gyroscope")
        val acc = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: error("No accelerometer")
        sm.registerListener(this, gyro, samplingPeriodUs, handler); registeredTypes += gyro.type
        sm.registerListener(this, acc, samplingPeriodUs, handler); registeredTypes += acc.type
    }
    override fun onSensorChanged(e: SensorEvent) {          // imu thread
        val t = timeSync.toElapsedRealtimeNs(e.timestamp)
        val v = e.values                                    // framework may reuse this array → copy now
        when (e.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> { accelRate.tick(t); assembler.onAccel(t, Vec3(v[0].toDouble(), v[1].toDouble(), v[2].toDouble())) }
            Sensor.TYPE_GYROSCOPE_UNCALIBRATED -> { gyroRate.tick(t)
                assembler.onGyro(t, Vec3(v[0].toDouble(), v[1].toDouble(), v[2].toDouble()),
                                 Vec3(v[3].toDouble(), v[4].toDouble(), v[5].toDouble()))?.let(onSample) }
            Sensor.TYPE_GYROSCOPE -> { gyroRate.tick(t)
                assembler.onGyro(t, Vec3(v[0].toDouble(), v[1].toDouble(), v[2].toDouble()), null)?.let(onSample) }
        }
    }
    override fun onAccuracyChanged(s: Sensor, a: Int) {}
    fun stop() = sm.unregisterListener(this)
    fun quit() { stop(); thread.quitSafely() }
}

class TimeSync {
    enum class Clock { UNKNOWN, ELAPSED_REALTIME, MONOTONIC }
    @Volatile var sensorClock = Clock.UNKNOWN; private set
    /** First 50 events: d = elapsedRealtimeNanos() − t. If median d ∈ [0, 100 ms] → ELAPSED_REALTIME.
     *  Else if (nanoTime() − t) ∈ [0, 100 ms] → MONOTONIC, convert with offset = elapsedRealtimeNanos() − nanoTime(). */
    fun toElapsedRealtimeNs(sensorTs: Long): Long
}
```
**Steps.** Start in `CustomImuScreen`'s `DisposableEffect`, stop on dispose. Show: gyro/accel rate (Hz),
`sensorClock`, sensor names/vendors, `registeredTypes` (must be a subset of {GYROSCOPE_UNCALIBRATED, GYROSCOPE, ACCELEROMETER}).
Log once: `Log.i("CamIMU.Imu", ...)`.

**Verify.** `./scripts/verify.sh && ./gradlew :app:installDebug` → [DEVICE] open Custom IMU-only.
Expected: gyro ≈ 190–210 Hz (device-dependent; some phones cap at ~100 Hz, so record it), accel similar, clock = ELAPSED_REALTIME.
**Done when.** User confirms the numbers and they are noted in Progress.md.
**Pitfalls.** Keeping a reference to `e.values` (gets overwritten). Registering on the main thread (UI jank delays samples). Requesting < 5000 µs on Android 12+ needs `HIGH_SAMPLING_RATE_SENSORS` permission. We stay at 5000 µs (200 Hz) so we don't need it.
**Commit.** `feat(sensors): IMU capture on dedicated thread with clock check and rate meter`

---

### Task 2.8 — `EstimatorRunner` + Custom IMU-only HUD `[DEVICE]`
**Goal.** Run `OrientationEstimator` on the IMU thread and show its output with the HUD widgets.
**Why.** This enforces the single-writer threading model (Architecture §3): the estimator only ever runs on one thread, so it needs no locks.

**Files.** `app:/pipeline/EstimatorRunner.kt`, `app:/ui/CustomImuScreen.kt` (replace debug-only UI with `HudOverlay` + small debug panel).

**Skeleton.**
```kotlin
class EstimatorRunner(cfg: EstimatorConfig, private val logger: SessionLogger? = null) {
    val gyroHistory = GyroHistory(512)
    private val estimator = OrientationEstimator(cfg, gyroHistory)
    private val _output = MutableStateFlow<OrientationOutput?>(null)
    val output: StateFlow<OrientationOutput?> = _output
    @Volatile var latestBias: Vec3 = Vec3.ZERO; private set
    lateinit var imuHandler: Handler
    private var lastPublishNs = 0L
    private val publishPeriodNs = (1e9 / cfg.uiPublishHz).toLong()
    @Volatile var lastImuTNs = 0L; private set

    /** imu thread */
    fun onImu(s: ImuSample) {
        val out = estimator.onImu(s)
        lastImuTNs = s.tNs; latestBias = out.gyroBias
        logger?.logImu(s); logger?.logEst(out)
        if (s.tNs - lastPublishNs >= publishPeriodNs) { _output.value = out; lastPublishNs = s.tNs }
    }
    fun postResetYaw() = imuHandler.post { estimator.resetYaw(); logger?.logEvent(lastImuTNs, "RESET_YAW", "") }
}
```
Wire: `val runner = EstimatorRunner(cfg); val imu = ImuSource(ctx, runner::onImu); runner.imuHandler = imu.handler; imu.start(cfg.imuSamplingPeriodUs)`.
Hold the objects in a `remember {}` + `DisposableEffect` (or a small `ViewModel`).

**Verify.** `./scripts/verify.sh && ./gradlew :app:installDebug`. [DEVICE] steps:
1. Lay the phone still on a table at launch → "Initializing" for ~2 s → angles appear (pitch ≈ −90 → vertical flag, since the camera looks down/up).
2. Hold upright against a wall/book stack, press Reset → (0,0,0) ± 1°.
3. Rotate left 90° quickly and back: yaw +90 then ≈ 0 (gyro-only error a few degrees is normal).
**Done when.** HUD tracks the phone smoothly at ≥ 30 Hz. No ANR. Reset works.
**Pitfalls.** Calling `estimator.resetYaw()` from the UI thread (data race): always `imuHandler.post`. Emitting every sample to `StateFlow` (200 Hz recomposition) is wasteful, so use the throttle.
**Commit.** `feat(pipeline): estimator runner on IMU thread with HUD output`

---

### Task 2.9 — `SessionLogger` (CSV + meta.json)
**Goal.** Buffered, off-thread logging of `imu.csv`, `est.csv`, `events.csv` and `meta.json`, with a Start/Stop logging button.
**Why.** Logs make the project scientific: every claim ("drift is 1.3°/min") comes from a file you can re-analyze and replay.

**Files.** `core:/io/CsvSchema.kt` (column headers + row formatters, shared with replay), `core:/model/SessionMeta.kt`, `app:/logging/SessionLogger.kt`, `app:/ui/CustomImuScreen.kt` (button), `core:test/io/CsvSchemaTest.kt`.

**Steps.**
1. `CsvSchema`: one object per file with `HEADER` and `fun format(...): String`. Columns exactly as `docs/DataModel.md` §4.
   Use `Locale.ROOT` / plain `toString()` (never locale-formatted decimals: a German phone writes `0,5`).
2. `SessionLogger(dir: File)`: `ArrayBlockingQueue<LogRow>(20_000)`. `offer()` from producers (drop + count if full).
   A daemon thread `log` drains, formats, writes with `BufferedWriter`, flushes every `loggerFlushMs`.
   `close()` drains, writes `meta.json` (with `droppedRows`), closes files.
3. Session dir: `context.getExternalFilesDir("sessions")/<yyyyMMdd_HHmmss>_<mode>/`.
4. `meta.json` contains `SessionMeta` (DataModel §5): device model, SDK, configs, calibration, measured rates, `registeredSensorTypes`, `sensorClock`.
5. `est.csv` decimation: `estLogDecimation` (default 1 = every IMU sample).

**Tests (core).** Header column counts equal formatted row field counts. Number formatting uses `.`. Warnings set formats as `A|B` and empty set as ``.
**Verify.** `./scripts/verify.sh`, then [DEVICE] record 30 s and pull:
```bash
adb shell ls /sdcard/Android/data/com.camimu.orient/files/sessions/
adb pull /sdcard/Android/data/com.camimu.orient/files/sessions/ ./sessions/
head -3 sessions/*/imu.csv; wc -l sessions/*/imu.csv     # ≈ 30 s × rate rows
```
(If `adb pull` from `Android/data` is denied on your Android version, VERIFY. Fallback: `adb shell run-as com.camimu.orient` with internal storage, see Issues.md.)
**Done when.** Files exist, headers match DataModel.md, `droppedRows = 0` for 30 s.
**Pitfalls.** Writing on the IMU thread. Forgetting `close()` on screen exit → truncated last second. Locale decimal commas.
**Commit.** `feat(logging): buffered CSV session logger with meta.json`

---

### Task 2.10 — `:replay` CLI + `analyze_log.py` (basic)
**Goal.** Re-run a recorded session through `OrientationEstimator` on the laptop and plot results.
**Why.** Tuning on the phone means re-recording every time. Replay lets you change a parameter and see the effect in seconds, on exactly the same data.

**Files.** `core:/io/SessionReader.kt`, `replay/src/main/kotlin/com/camimu/orient/replay/ReplayMain.kt`, `replay/src/test/kotlin/com/camimu/orient/replay/ReplayDeterminismTest.kt`, `tools/requirements.txt`, `tools/analyze_log.py`, `tools/README.md`.

**Steps.**
1. `SessionReader(dir)`: reads `imu.csv`, `events.csv`, (later) `vision.csv` into lists. Merges them into one time-ordered
   event stream: IMU samples; RESET_YAW events applied **after** the IMU sample with `t_ns ==` event `t_ns`;
   vision measurements applied after the IMU sample with `t_ns == t_applied_ns` (Phase 4).
2. `ReplayMain` args: `--session <dir>` (required), `--config <json>` (default: config inside `meta.json`), `--out <dir>` (default `<session>/replay/`),
   `--no-vision`. Writes `est_replay.csv` (same schema as `est.csv`) and prints a summary (samples, duration, final bias, final yaw).
3. `ReplayDeterminismTest`: simulate a session with `ImuSimulator`, write CSVs with `CsvSchema`, replay twice → identical output, and equal to direct estimator output (tolerance 1e-9).
4. `tools/requirements.txt`: `numpy>=1.24`, `pandas>=2.0`, `matplotlib>=3.7`, `scipy>=1.10`, `opencv-python>=4.8`.
5. `tools/analyze_log.py <session_dir> [--est est.csv|replay/est_replay.csv] [--t0 s --t1 s]`: plots yaw/pitch/roll vs time,
   bias, sigma_yaw, confidence. Computes yaw drift (°/min) = slope of `np.polyfit(t_min, unwrap(yaw), 1)` over `[t0,t1]`.
   Saves `analysis/*.png` and `analysis/metrics.json`.

**Verify.**
```bash
./gradlew :replay:test
./gradlew :replay:run --args="--session sessions/<your_30s_session>"
python3 tools/analyze_log.py sessions/<your_30s_session> --est replay/est_replay.csv
```
Expected: replayed yaw/pitch/roll match on-device `est.csv` within 1e-6° (only floating-point noise).
**Done when.** Determinism test passes, real-session replay matches the device.
**Pitfalls.** `:replay:run` working directory (fixed in Task 0.2). Applying events at the wrong position in the stream → replay mismatch. Unwrapping yaw before fitting drift.
**Commit.** `feat(replay): offline session replay CLI and analysis script`

### ✅ Checkpoint 2 (manual, Custom IMU-only) — expected numbers
Record each as a session and run `analyze_log.py`. Note results in Progress.md.
1. **Rate:** gyro ≈ 200 Hz (record actual).
2. **Static 5 min on a table** (S1): pitch/roll std < 0.1°. Yaw drift ≤ 0.5°/min (ZRU active). Bias converged (plot flat).
3. **Wedges 10°/20°/30°** (camera up): pitch error ≤ 1° each. Roll on wedges: ≤ 1°.
4. **Handheld slow pan ±60° for 2 min, then return to a marked start:** report return error (gyro-only; expect 1–10°, depending on the device). This is the baseline the camera must beat.
5. **Replay** of (2) reproduces the device output (≤ 1e-6°).

---

## Phase 3 — Vision front-end

> Concept: for a camera that only rotates, a far-away point's *direction* (bearing) rotates by
> exactly the camera rotation. Track many points, convert pixels to bearings, find the one
> rotation that explains most of them (RANSAC), and ignore points that disagree (moving people).

### Task 3.1 — `CameraModel` (intrinsics, undistort, bearings, projection)
**Goal.** Pixel ↔ unit-bearing conversions with Brown–Conrady distortion (Architecture §6.2).
**Why.** Rotation is estimated on bearings, not pixels. Wrong `fx` directly scales every vision rotation, which shows up as a yaw *scale* error.

**Files.** `core:/model/CameraIntrinsics.kt`, `core:/vision/CameraModel.kt`, `core:test/vision/CameraModelTest.kt`.
**Signatures.**
```kotlin
@Serializable data class CameraIntrinsics(val width: Int, val height: Int, val fx: Double, val fy: Double, val cx: Double, val cy: Double,
    val k1: Double = 0.0, val k2: Double = 0.0, val p1: Double = 0.0, val p2: Double = 0.0, val k3: Double = 0.0,
    val source: IntrinsicsSource = IntrinsicsSource.FOV_ESTIMATE)
enum class IntrinsicsSource { CALIBRATED, FOV_ESTIMATE, ARCORE }
class CameraModel(val k: CameraIntrinsics) {
    fun bearing(u: Double, v: Double): Vec3          // undistort (5 fixed-point iterations) then normalize(x, y, 1)
    fun project(b: Vec3): Pair<Double, Double>?      // no distortion; null if b.z <= 1e-6
    fun distort(x: Double, y: Double): Pair<Double, Double>   // forward model, for tests
}
```
**Tests.** Zero distortion: `project(bearing(u,v)) == (u,v)`. Principal point → bearing (0,0,1). With k1=−0.2, k2=0.05:
`distort(undistort(p)) ≈ p` within 0.01 px across the image. Edge pixels don't produce NaN.
**Verify.** `./gradlew :core:test`  **Done when.** Tests pass.
**Pitfalls.** Intrinsics belong to one resolution. Using 4032×3024 calibration with a 640×480 stream without scaling (scale fx,cx by W ratio and fy,cy by H ratio, only when aspect ratios match).
**Commit.** `feat(core): pinhole camera model with Brown–Conrady undistortion`

---

### Task 3.2 — `SymmetricEigen` + `WahbaSolver` (Horn)
**Goal.** Best-fit rotation between two sets of bearings (Architecture §6.5).
**Why.** Concept: Wahba's problem = "which rotation best maps these directions onto those?" Horn showed the answer is the top eigenvector of a 4×4 matrix. Pure Kotlin, fully testable.

**Files.** `core:/math/SymmetricEigen.kt`, `core:/vision/WahbaSolver.kt`, `core:test/math/SymmetricEigenTest.kt`, `core:test/vision/WahbaSolverTest.kt`.
**Skeleton (cyclic Jacobi).**
```kotlin
object SymmetricEigen {
    data class Result(val values: DoubleArray, val vectors: Array<DoubleArray>)   // vectors[row][col], column j = eigenvector j
    fun jacobi(a: Array<DoubleArray>, maxSweeps: Int = 50, tol: Double = 1e-15): Result {
        val n = a.size
        val m = Array(n) { a[it].copyOf() }
        val v = Array(n) { i -> DoubleArray(n) { j -> if (i == j) 1.0 else 0.0 } }
        repeat(maxSweeps) {
            var off = 0.0
            for (p in 0 until n) for (q in p + 1 until n) off += m[p][q] * m[p][q]
            if (off < tol) return Result(DoubleArray(n) { m[it][it] }, v)
            for (p in 0 until n) for (q in p + 1 until n) {
                if (abs(m[p][q]) < 1e-300) continue
                val theta = (m[q][q] - m[p][p]) / (2 * m[p][q])
                val t = (if (theta >= 0) 1.0 else -1.0) / (abs(theta) + sqrt(theta * theta + 1))
                val c = 1 / sqrt(t * t + 1); val s = t * c
                for (k in 0 until n) { val kp = m[k][p]; val kq = m[k][q]; m[k][p] = c * kp - s * kq; m[k][q] = s * kp + c * kq }
                for (k in 0 until n) { val pk = m[p][k]; val qk = m[q][k]; m[p][k] = c * pk - s * qk; m[q][k] = s * pk + c * qk }
                for (k in 0 until n) { val kp = v[k][p]; val kq = v[k][q]; v[k][p] = c * kp - s * kq; v[k][q] = s * kp + c * kq }
            }
        }
        return Result(DoubleArray(n) { m[it][it] }, v)
    }
}
object WahbaSolver {
    /** Returns q21 (R21 maps frame-1 bearings to frame-2 bearings), or null if < 2 points or degenerate. */
    fun solve(b1: List<Vec3>, b2: List<Vec3>, weights: DoubleArray? = null): Quaternion?
}
```
`solve`: build `S_ab = Σ w b1_a b2_b`, build `N` (Architecture §6.5), `jacobi(N)`, take the column with the largest eigenvalue as `[w,x,y,z]`, normalize, canonical.
Degenerate if the two largest eigenvalues differ by < 1e-12·|λmax| (e.g. all points identical).

**Tests.** Jacobi: random symmetric 4×4 (seeded) → `V·diag(λ)·Vᵀ ≈ A` (1e-10), `VᵀV ≈ I`. Wahba: 50 random bearings in a 60° cone,
random rotation up to 30° → recovered within 1e-9 rad (noise-free). Exactly 2 non-parallel points → exact. Pixel noise 0.5 px at fx=500 with 100 points → error < 0.05°. All-identical points → null.
**Verify.** `./gradlew :core:test`  **Done when.** Tests pass.
**Pitfalls.** Building `S` as `b2 b1ᵀ` instead of `b1 b2ᵀ` → returns the inverse rotation (the test with a known rotation catches it). Taking the smallest instead of the largest eigenvalue.
**Commit.** `feat(core): Jacobi eigen-solver and Horn's Wahba rotation solver`

---

### Task 3.3 — `RotationRansac` + `VisionQuality`
**Goal.** Robust rotation from bearing pairs with outliers, and the quality score / validity decision.
**Why.** Concept: RANSAC repeatedly fits a model to a tiny random sample and keeps the model most points agree with. Moving people are a minority that disagrees, so they get ignored (obstacle layer 2).

**Files.** `core:/vision/RotationRansac.kt`, `core:/vision/VisionQuality.kt`, `core:/sim/BearingSimulator.kt`, `core:test/vision/RotationRansacTest.kt`, `core:test/vision/VisionQualityTest.kt`.
**Signatures.**
```kotlin
data class RansacResult(val q21: Quaternion?, val inlierMask: BooleanArray, val nInliers: Int, val rmsErrRad: Double, val iterations: Int)
class RotationRansac(private val cfg: VisionConfig, seed: Long = 42L) {
    private val rnd = java.util.Random(seed)
    /** thresholdRad = cfg.ransacThresholdPx / fx. */
    fun estimate(b1: List<Vec3>, b2: List<Vec3>, thresholdRad: Double): RansacResult
}
data class QualityResult(val quality: Double, val valid: Boolean, val inlierRatio: Double, val spread: Double, val reason: VisionRejectReason?)
object VisionQuality {
    /** cellOfInlier: grid cell index (0 until gridCols*gridRows) of each inlier in the *current* image. */
    fun evaluate(nInliers: Int, nTrackedBeforeGating: Int, inlierCells: IntArray, cfg: VisionConfig): QualityResult
}
class BearingSimulator(seed: Long) {
    /** n static points in a cone of halfFovRad, rotated by q21, pixel noise, plus outlier/moving-cluster options. */
    fun pairs(n: Int, q21: Quaternion, fx: Double, noisePx: Double, outlierFrac: Double = 0.0,
              movingFrac: Double = 0.0, movingQ21: Quaternion = Quaternion.IDENTITY, halfFovRad: Double = 0.5): Pair<List<Vec3>, List<Vec3>>
}
```
**Math.** Architecture §6.5. Adaptive iteration count `N = min(maxIter, ceil(ln(1−0.99)/ln(1−w²)))`, recomputed when the best inlier count improves. Min iterations 10.
**Tests.** 150 points, 30% random outliers → error < 0.05° and outliers flagged. 150 points, 35% "moving cluster" with a different rotation (a walking crowd) → recovers the static rotation.
60% moving → `VisionQuality` returns `valid=false` (inlier ratio below `minInlierRatio`), which is the **majority rule**. Same seed → identical result. 0 or 1 points → `q21 = null`.
**Verify.** `./gradlew :core:test`  **Done when.** Tests pass.
**Pitfalls.** Minimal samples with nearly parallel bearings (unstable). Reject if angle < 2°. Using `acos` without clamping (NaN at 1+1e-16).
**Commit.** `feat(core): RANSAC rotation estimation and vision quality scoring`

---

### Task 3.4 — `GridBucketer` + `GyroFlowPredictor`
**Goal.** Spread features evenly (bucketing) and predict where each feature should move given the gyro (gating).
**Why.** Bucketing stops one big obstacle from owning all features (layer 3). Gyro prediction rejects tracks that move differently from the camera's own rotation, e.g. a passing person (layer 1).

**Files.** `core:/vision/GridBucketer.kt`, `core:/vision/GyroFlowPredictor.kt`, tests in `core:test/vision/`.
**Signatures.**
```kotlin
data class Px(val u: Double, val v: Double)
class GridBucketer(private val width: Int, private val height: Int, private val cols: Int, private val rows: Int) {
    fun cellOf(p: Px): Int                                   // clamp to image, row-major cell index
    /** candidates sorted by quality (best first). Returns the subset to add so that each cell holds ≤ maxPerCell incl. existing. */
    fun select(candidates: List<Px>, existing: List<Px>, maxPerCell: Int, maxTotal: Int): List<Px>
}
class GyroFlowPredictor(private val camera: CameraModel, private val rCD: Mat3) {
    /** rotD1D2 = gyro rotation D(t2)→D(t1). Returns predicted pixel in frame 2, or null if behind camera. */
    fun predict(p1: Px, rotD1D2: Quaternion): Px?
    fun gatePx(rotD1D2: Quaternion, cfg: VisionConfig): Double   // gateBasePx + gateRateFactor·fx·angle
}
```
**Math.** Architecture §6.4: `rC1C2 = rCD·R(rotD1D2)·rCDᵀ`, `b2 = rC1C2ᵀ·b1`, `p2 = project(b2)`.
**Tests.** Bucketer: 1000 candidates in one corner → at most `maxPerCell` selected there; existing tracks count toward the cap. Predictor: identity rotation → same pixel;
device rotation about y_D (pan left in portrait) by +2° with SENSOR_ORIENTATION 90 → the image features move in the direction that the synthetic camera (BearingSimulator with the same rCD) produces. Use the simulator as the oracle, not intuition.
**Verify.** `./gradlew :core:test`  **Done when.** Tests pass.
**Commit.** `feat(core): grid bucketing and gyro-predicted feature flow`

---

### Task 3.5 — OpenCV + CameraX analysis stream + camera controls `[DEVICE]`
**Goal.** Live 640×480 gray frames with correct timestamps on the vision thread, stabilization off, and an intrinsics estimate.
**Why.** Concept: CameraX's `ImageAnalysis` hands you each frame (`ImageProxy`) in YUV. The Y plane *is* the grayscale image. OIS/EIS must be off because they secretly rotate/shift the image and break the rotation-only model.

**Files.** `gradle/libs.versions.toml`, `app/build.gradle.kts`, `app:/vision/OpenCvInit.kt`, `app:/camera/FrameSource.kt`, `app:/camera/CameraXFrameSource.kt`, `app:/camera/YPlaneCopier.kt`, `app:/camera/CameraInfoReader.kt`, `app:/ui/CustomScreen.kt` (preview + debug text).
**Dependencies.** `cameraX = "1.4.1"` (VERIFY) → `camera-core`, `camera-camera2`, `camera-lifecycle`, `camera-view`. `opencv = "4.10.0"` (VERIFY) → `org.opencv:opencv`.
**Skeleton.**
```kotlin
object OpenCvInit { fun ensure(): Boolean = OpenCVLoader.initLocal() }   // VERIFY: 4.9+ uses initLocal(); older: initDebug()

interface FrameSource { fun start(onFrame: (GrayFrame) -> Unit); fun stop() }

object YPlaneCopier {
    /** Copies the Y plane into a tightly packed array (handles rowStride padding). */
    fun copy(image: ImageProxy, out: ByteArray) {
        val plane = image.planes[0]; val buf = plane.buffer; val rs = plane.rowStride
        val w = image.width; val h = image.height
        if (rs == w) { buf.rewind(); buf.get(out, 0, w * h) }
        else for (row in 0 until h) { buf.position(row * rs); buf.get(out, row * w, w) }
    }
}

@OptIn(ExperimentalCamera2Interop::class)
class CameraXFrameSource(private val ctx: Context, private val owner: LifecycleOwner, private val previewView: PreviewView,
                         private val vcfg: VisionConfig, private val timeOffsetNs: Long) : FrameSource {
    val visionExecutor: ExecutorService = Executors.newSingleThreadExecutor { Thread(it, "vision") }
    override fun start(onFrame: (GrayFrame) -> Unit) {
        val providerFuture = ProcessCameraProvider.getInstance(ctx)
        providerFuture.addListener({
            val provider = providerFuture.get()
            val res = ResolutionSelector.Builder()
                .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                .setResolutionStrategy(ResolutionStrategy(Size(vcfg.analysisWidth, vcfg.analysisHeight),
                                       ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)).build()
            val ab = ImageAnalysis.Builder().setResolutionSelector(res)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            val pb = Preview.Builder().setResolutionSelector(res)
            for (b in listOf(Camera2Interop.Extender(ab), Camera2Interop.Extender(pb))) {
                b.setCaptureRequestOption(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_OFF)
                 .setCaptureRequestOption(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF)
                 .setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
                 .setCaptureRequestOption(CaptureRequest.LENS_FOCUS_DISTANCE, 0f)          // infinity; ignored on fixed-focus lenses
            }
            val analysis = ab.build()
            var buf = ByteArray(0)
            analysis.setAnalyzer(visionExecutor) { img ->
                try {
                    val n = img.width * img.height; if (buf.size != n) buf = ByteArray(n)
                    YPlaneCopier.copy(img, buf)
                    onFrame(GrayFrame(tCamNs = img.imageInfo.timestamp, tNs = cameraToImuNs(img.imageInfo.timestamp),
                                      width = img.width, height = img.height, data = buf))
                } finally { img.close() }                      // ALWAYS close, or the stream stalls
            }
            val preview = pb.build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
            provider.unbindAll()
            provider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
        }, ContextCompat.getMainExecutor(ctx))
    }
    override fun stop() { /* unbindAll on main thread; visionExecutor.shutdown() */ }
}
```
`GrayFrame.data` is reused between frames. Consumers (the tracker) must finish with it inside the callback (they do: everything runs on `vision`).

`CameraInfoReader` (via `Camera2CameraInfo.from(camera.cameraInfo).getCameraCharacteristic(...)`) reads:
`SENSOR_ORIENTATION`, `SENSOR_INFO_TIMESTAMP_SOURCE` (REALTIME vs UNKNOWN), `LENS_INFO_AVAILABLE_FOCAL_LENGTHS`,
`SENSOR_INFO_PHYSICAL_SIZE`, `LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION`, `CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES`, and `LENS_INTRINSIC_CALIBRATION` (may be null; log only).
FOV intrinsics estimate (until Task 5.2): `fx = fy = f_mm / physicalWidth_mm · analysisWidth`, `cx = W/2`, `cy = H/2`, source `FOV_ESTIMATE` (rough, maybe ±5%, VERIFY per device).
`cameraToImuNs(t)`: if timestamp source is REALTIME → `t + timeOffsetNs`. If UNKNOWN → `t + (elapsedRealtimeNanos() − nanoTime()) + timeOffsetNs` (assumes a monotonic base, VERIFY. Task 5.4 corrects any residual).

Debug text on screen: actual analysis size, fps, sensor orientation, timestamp source, OIS available/disabled, intrinsics, `frameAgeMs = elapsedRealtimeNanos() − tNs` (expect 20–60 ms).
**Verify.** `./scripts/verify.sh && ./gradlew :app:installDebug` → [DEVICE] Custom screen shows preview and the debug text.
**Done when.** ~30 fps, size 640×480 (or the closest, recorded), `frameAgeMs` positive and < 100, OpenCV init true.
**Pitfalls.** Not closing `ImageProxy`. Assuming `rowStride == width`. Preview at 16:9 while analysis is 4:3 (different crops, so the overlay won't line up; we force 4:3 for both). Using deprecated `setTargetResolution`.
Some devices ignore OIS-off (log the resulting `CaptureResult` if possible; note in Issues).
**Commit.** `feat(camera): CameraX gray analysis stream with stabilization off and OpenCV init`

---

### Task 3.6 — `FeatureTracker` (OpenCV) + instrumented test `[DEVICE for test]`
**Goal.** Bucketed Shi–Tomasi detection + pyramidal KLT with gyro-seeded initial flow + forward-backward check, keeping anchor positions.
**Why.** KLT (Lucas–Kanade) finds where each small image patch moved. Seeding it with the gyro's prediction lets it survive fast pans.

**Files.** `app:/vision/FeatureTracker.kt`, `core:/vision/Track.kt`, `app/src/androidTest/java/com/camimu/orient/vision/FeatureTrackerTest.kt`.
**Signatures.**
```kotlin
data class Track(val id: Long, val anchor: Px, val prev: Px, val cur: Px, val age: Int)
data class TrackFrameResult(val tracks: List<Track>, val nBeforeFb: Int, val nFbOk: Int, val replenished: Int)
class FeatureTracker(private val cfg: VisionConfig, width: Int, height: Int) {
    /** gray: CV_8UC1 Mat valid only during this call. predict: maps prev px → predicted cur px (null = no prediction). */
    fun process(gray: Mat, predict: ((Px) -> Px?)?): TrackFrameResult
    fun reanchorAll()                       // anchor := cur for every live track (called by AnchorWindow on close)
    fun reset()
    fun release()                           // release all Mats
}
```
**Steps.**
1. Keep `prevGray: Mat` (copy via `gray.copyTo(prevGray)`).
2. Track: `prevPts = MatOfPoint2f(*prev.map { Point(u,v) })`, `nextPts` pre-filled with predictions (or prev) and flag
   `Video.OPTFLOW_USE_INITIAL_FLOW`; `Video.calcOpticalFlowPyrLK(prevGray, gray, prevPts, nextPts, status, err, Size(winSize, winSize), maxLevel, TermCriteria(TermCriteria.COUNT + TermCriteria.EPS, 30, 0.01), flags, 1e-4)`.
3. Backward: `calcOpticalFlowPyrLK(gray, prevGray, nextPts, backPts, status2, err2, ...)`, keep if both statuses = 1, round-trip < `fbThresholdPx` and inside image.
4. Replenish if `tracks.size < replenishFraction · targetTracks`: mask = 255 Mat with black circles (`Imgproc.circle(mask, pt, minDistancePx, Scalar(0.0), -1)`) around tracks;
   `Imgproc.goodFeaturesToTrack(gray, corners /*MatOfPoint*/, detectMaxCorners, qualityLevel, minDistancePx, mask, blockSize, false, 0.04)`;
   then `GridBucketer.select(...)` → new tracks with `anchor = cur = prev = p`, `age = 0`.
   The Java binding returns integer `MatOfPoint` (VERIFY). Optionally refine with `Imgproc.cornerSubPix`.
5. Reuse Mats (fields). Never allocate a new `Mat` per frame without `release()`.
6. Instrumented test: synthesize a random-texture 640×480 Mat, shift it by (+5, −3) px with `Imgproc.warpAffine`, `process` twice → median track displacement ≈ (5, −3) ± 0.2 px, ≥ 100 tracks.

**Verify.** `./scripts/verify.sh`. [DEVICE] `./gradlew :app:connectedDebugAndroidTest` → FeatureTrackerTest passes.
**Done when.** Instrumented test passes on the phone.
**Pitfalls.** Native memory leaks (Mats are not GC-tracked fully: call `release()`), `goodFeaturesToTrack` with an empty mask Mat of the wrong type (must be CV_8UC1, same size), mixing up `(x=u, y=v)` with `(row, col)`.
**Commit.** `feat(vision): bucketed KLT feature tracker with forward-backward check`

---

### Task 3.7 — `VisionPipeline` (per-frame) + debug overlay + `vision_frames.csv` `[DEVICE]`
**Goal.** On each frame: track → gyro-gate → frame-pair RANSAC → per-frame stats, an inlier/outlier overlay, and timing.
**Why.** Before fusing, you must *see* that vision rotation agrees with the gyro. The overlay shows obstacle rejection working live (red dots on a walking person).

**Files.** `app:/vision/VisionPipeline.kt`, `app:/ui/FeatureOverlay.kt`, `app:/ui/CustomScreen.kt`, `core:/io/CsvSchema.kt` (add `VisionFrames`), `app:/logging/SessionLogger.kt` (add method).
**Signatures.**
```kotlin
data class FrameDebug(val tNs: Long, val inliers: List<Px>, val outliers: List<Px>, val gatedOut: List<Px>, val width: Int, val height: Int)
class VisionPipeline(private val vcfg: VisionConfig, private val camera: CameraModel, private val rCD: Mat3,
                     private val gyroHistory: GyroHistory, private val biasProvider: () -> Vec3,
                     private val logger: SessionLogger?, private val onMeasurement: (VisionMeasurement) -> Unit,
                     private val onState: (OcclusionState) -> Unit) {
    val debug = MutableStateFlow<FrameDebug?>(null)
    /** vision thread */
    fun onFrame(f: GrayFrame)
    fun release()
}
```
**Per-frame algorithm.**
```
mat.put(0, 0, f.data)
qGyro = gyroHistory.integrate(prevT, f.tNs, biasProvider())     // may be null at start → no prediction
tr    = tracker.process(mat, qGyro?.let { q -> { p -> predictor.predict(p, q) } })
gate  = qGyro?.let { predictor.gatePx(it, vcfg) }
keep  = tracks where |cur − predict(prev)| ≤ gate (all if qGyro == null)
pair  = ransac.estimate(bearing(prev), bearing(cur), vcfg.ransacThresholdPx / fx)
rotD_pair = rCDᵀ · R(pair.q21)ᵀ · rCD                           // device frame, D2 → D1
log vision_frames row (DataModel §4); publish FrameDebug every 2nd frame
procMs measured with SystemClock.elapsedRealtimeNanos()
```
(Window measurements come in Task 4.1. For now `onMeasurement` is never called.)
Overlay mapping (raw sensor image → portrait PreviewView, `FILL_CENTER`), for SENSOR_ORIENTATION 90:
`(u', v') = (H − 1 − v, u)` (upright W'=H, H'=W), then `s = max(viewW/W', viewH/H')`, `x = (u' − W'/2)·s + viewW/2`, `y = (v' − H'/2)·s + viewH/2`.
For 270: `(u', v') = (v, W − 1 − u)`.
**Verify.** [DEVICE] Custom screen: green dots on static scenery, red on a person walking past, `procMs` shown. Record 60 s while panning slowly, then
`python3 tools/analyze_log.py <session> --vision-vs-gyro` (add this option: compares frame-pair `rv` with `GyroHistory`-equivalent integration from imu.csv).
**Done when.** Median vision processing ≤ 20 ms, ≥ 80 tracks in a textured room, and **frame-pair rotation agrees with gyro**: median |rv_vis − rv_gyro| < 0.1° per frame during a slow pan (after Task 3.8 confirms signs).
**Pitfalls.** Overlay drawn in raw image coordinates (rotated 90° from the preview). Logging allocations per frame (use the logger's queue). Using the bias at frame time vs now (fine either way; it's slowly varying).
**Commit.** `feat(vision): per-frame vision pipeline with gyro gating, RANSAC and debug overlay`

---

### Task 3.8 — Axis / extrinsic sanity check (`rCD`) `[DEVICE]`
**Goal.** Prove that the vision rotation, mapped to the device frame, has the **same axes and signs** as the gyro.
**Why.** A wrong sign in `rCD` makes vision *fight* the gyro: drift gets worse instead of better, and every downstream tuning effort is wasted. This 10-minute check prevents that.

**Files.** `tools/axis_check.py`, `docs/Issues.md` (record result), `core:/frames/Frames.kt` only if a fix is needed (+ a new test reproducing it).
**Steps.**
1. USER: in Custom mode with logging on, rotate the phone ~5 times back and forth about **each** device axis in turn (x: tilt top edge toward/away from you; y: turn left/right; z: rotate like a steering wheel), ~3 s per axis, at a textured scene 2+ m away.
2. `tools/axis_check.py <session>`: for each frame pair compute gyro rotvec over the same interval from `imu.csv`; per axis print the
   correlation and the regression slope `rv_vis_i ≈ s_i · rv_gyro_i`.
**Expected.** Correlation > 0.9 on all axes, slopes in **0.95–1.05** (positive!). Slope ≈ −1 on an axis → sign error in `rCD` for that axis. Swapped axes → rows mixed (high cross-correlation). Slope consistently e.g. 0.92 → `fx` estimate ~8% off (fixed by Task 5.2).
**Done when.** All three slopes positive and within 0.9–1.1 (FOV-estimate intrinsics), and the result is noted in Progress.md.
**Pitfalls.** Testing near objects (translation parallax). Testing too fast (blur). Keep it slow (≤ 60°/s).
**Commit.** `test(tools): gyro-vs-vision axis sanity check`

### Task 3.9 — (OPTIONAL) Essential-matrix cross-check
**Goal.** When translation is significant (walking near objects), compare our rotation-only estimate with `Calib3d.findEssentialMat` + `Calib3d.recoverPose`, logging the difference.
**Why.** Educational: shows when the rotation-only assumption breaks. Not required for "done".
**Files.** `app:/vision/EssentialCheck.kt` (debug-only, behind a setting). Use `Calib3d.findEssentialMat(pts1, pts2, K, Calib3d.RANSAC, 0.999, 1.0)` and `Calib3d.recoverPose(E, pts1, pts2, K, R, t)` (VERIFY Java overloads in OpenCV 4.10).
Log `angle(R_ess, R_rot)` per window into `vision_frames.csv` column `ess_diff_deg` (empty if disabled).
**Done when.** In pure rotation the difference is < 0.2° (E degenerates, so expect occasional failures); walking past near objects shows larger differences.
**Commit.** `feat(vision): optional essential-matrix cross-check`

### ✅ Checkpoint 3 (manual) — expected numbers
- 30 fps analysis, vision median ≤ 20 ms (record actual + phone model), ≥ 80 tracks indoors.
- Axis check: 3 positive slopes in 0.9–1.1, correlation > 0.9.
- Frame-pair agreement median < 0.1°/frame in slow pans.
- Person walking through view: their features turn red (gated/outliers) in the overlay.

---

## Phase 4 — Vision fusion + obstacle robustness

### Task 4.1 — `AnchorWindow` → `VisionMeasurement`
**Goal.** Accumulate tracks from an anchor frame for up to 1 s, then emit one rotation measurement (anchor → current) in the device frame (Architecture §6.6).
**Why.** A gyro bias of 0.01°/s causes only 0.0003° of error per 33 ms frame, which is invisible under vision noise. Over 1 s it's 30× larger, and vision noise stays about the same. Longer baseline = better bias observability.

**Files.** `core:/vision/AnchorWindow.kt`, `core:test/vision/AnchorWindowTest.kt`, `app:/vision/VisionPipeline.kt` (call it), `app:/vision/FeatureTracker.kt` (re-anchor hook).
**Signatures.**
```kotlin
class AnchorWindow(private val cfg: VisionConfig) {
    /** Called every frame. Returns a CloseDecision when the window should close. */
    fun onFrame(tNs: Long, nAnchorTracksAlive: Int): Boolean
    fun start(tNs: Long, nAnchorTracks: Int)
    fun reset()
    val anchorTNs: Long?
}
/** Pure function used by VisionPipeline on close. */
fun buildMeasurement(tAnchorNs: Long, tCurNs: Long, tCamCurNs: Long, q21: Quaternion, rCD: Mat3, q: QualityResult, nInliers: Int, rmsErrRad: Double): VisionMeasurement
// rotD1D2 = fromMatrix(rCDᵀ · R(q21)ᵀ · rCD)
```
**Close rule.** `elapsed ≥ windowMaxSec` OR (`alive/anchorCount < windowMinSurvival` AND `elapsed ≥ windowMinSec`). On close: RANSAC on (anchorPx → curPx) of surviving tracks, quality check,
emit if valid, then `tracker.reanchorAll()` + `window.start(now, nTracks)`. If `elapsed < windowMinSec` and survival collapses (fast pan) → reset without a measurement.
**Tests.** Close timing at max; early close on survival drop; no emission under min duration; `buildMeasurement` maps a known camera rotation back to the known device rotation (use `BearingSimulator` + `rCDForSensorOrientation(90)`).
**Verify.** `./gradlew :core:test`, then `./scripts/verify.sh`.
**Done when.** Tests pass. On device, `vision.csv` rows appear ~1/s with a static textured scene (logging added in 4.3; for now Log.d count).
**Commit.** `feat(vision): anchor-window rotation measurements`

---

### Task 4.2 — Estimator vision update (bias KF + gate + attitude nudge)
**Goal.** Implement `OrientationEstimator.onVision` (Architecture §6.7) and prove on synthetic data that vision bounds yaw drift.
**Why.** This is the heart of the project: the camera supplies what the compass normally would, a reference that keeps yaw from drifting.

**Files.** `core:/fusion/OrientationEstimator.kt`, `core:/sim/VisionMeasurementSimulator.kt`, `core:test/fusion/OrientationEstimatorVisionTest.kt`.
**Skeleton.**
```kotlin
fun onVision(m: VisionMeasurement): VisionUpdateOutcome {
    if (!isInitialized) return reject(VisionRejectReason.NOT_INITIALIZED)
    if (occlusionState != OcclusionState.OK) return reject(VisionRejectReason.BLOCKED)
    val dt = (m.t2Ns - m.t1Ns) * 1e-9
    val rotRaw  = gyroHistory.integrate(m.t1Ns, m.t2Ns, Vec3.ZERO) ?: return reject(VisionRejectReason.NO_GYRO_COVERAGE)
    val rotCorr = gyroHistory.integrate(m.t1Ns, m.t2Ns, biasKf.bias)!!
    val meanRate = rotRaw.log().norm() / dt
    if (meanRate > cfg.maxRateForVisionRadS) return reject(VisionRejectReason.TOO_FAST)
    val zBias = (m.rotD1D2.conj() * rotRaw).log() / dt
    val sigmaVis2 = (cfg.sigmaRotRad.pow(2) + (meanRate * cfg.sigmaTimeOffsetSec).pow(2)) / m.quality.coerceAtLeast(1e-3)
    val (d2, ok) = biasKf.update(zBias, sigmaVis2 / (dt * dt))
    if (!ok) return reject(VisionRejectReason.GATE, d2, zBias)
    val e = (m.rotD1D2.conj() * rotCorr).log()
    filter.applyCorrection(Quaternion.exp(e * -cfg.visionAttitudeGain))
    yawUnc.onVisionAccepted(sigmaVis2); lastVisionAcceptedNs = m.t2Ns
    return VisionUpdateOutcome(accepted = true, reason = null, mahalanobis2 = d2, zBias = zBias)
}
```
`VisionMeasurementSimulator`: from `ImuSimulator` truth, build `rotD1D2_true` over 1-s windows, add rotation noise `N(0, σ²)` (rad), optional outlier windows.
**Tests.**
1. 2 s static init, then 5 min slow sinusoidal yaw (±40°, 0.05 Hz), bias **changing** by a random walk (σ_bw ×5 the config value) and **no ZRU possible**:
   IMU-only yaw error at 5 min > 3°, with vision (σ=3e-4 rad) error < 1°.
2. 10% outlier windows (rotation off by 5°) → rejected by gate (`reason=GATE`), final error still < 1.5°.
3. Measurement with `quality = 0.2` changes bias less than `quality = 1.0`.
4. No measurement accepted when `meanRate > maxRateForVisionRadS`.
5. Camera "blocked" from 60 s to 90 s (no measurements) → σ_yaw grows during the gap and confidence drops to MEDIUM/LOW. After measurements resume, back to HIGH within 2 s of sim time.
**Verify.** `./gradlew :core:test`
**Done when.** All five pass.
**Pitfalls.** Wrong order inside `Log(rotVisᵀ · rotRaw)` → bias estimate with the wrong sign → drift *doubles* (test 1 catches it). Updating attitude with the raw instead of the corrected gyro rotation.
**Commit.** `feat(core): vision-aided gyro bias estimation and attitude correction`

---

### Task 4.3 — Wire vision → estimator, `vision.csv`, replay with vision `[DEVICE]`
**Goal.** Hand window measurements from the vision thread to the IMU thread, log them with the exact application point, and replay them deterministically.
**Why.** Thread hand-off keeps the single-writer rule. Logging `t_applied_ns` makes replay reproduce the device bit-for-bit, so tuning offline is trustworthy.

**Files.** `app:/pipeline/EstimatorRunner.kt` (`postVision`), `app:/ui/CustomScreen.kt` (assemble: ImuSource + CameraXFrameSource + VisionPipeline + EstimatorRunner + HudOverlay + FeatureOverlay), `core:/io/CsvSchema.kt` (`Vision`), `core:/io/SessionReader.kt`, `replay/.../ReplayMain.kt`.
**Steps.**
1. `fun postVision(m: VisionMeasurement) = imuHandler.post { val tApplied = lastImuTNs; val o = estimator.onVision(m); logger?.logVision(m, o, tApplied) }`.
2. `fun postVisionState(s: OcclusionState, tNs: Long) = imuHandler.post { estimator.setVisionState(s, tNs); logger?.logEvent(tNs, "OCCLUSION_STATE", s.name) }`.
3. Replay inserts each vision row right after the IMU sample whose `t_ns == t_applied_ns`, and occlusion events the same way. `--no-vision` skips both.
4. Replay test: simulated session with vision → replay equals direct run (1e-9).
**Verify.** `./scripts/verify.sh && ./gradlew :replay:test`. [DEVICE] record 2 min of slow panning. `./gradlew :replay:run --args="--session sessions/<s>"` → matches `est.csv` within 1e-6°. Then `--no-vision` → shows the gyro-only drift for comparison.
**Done when.** Replay matches the device. With vs without vision differ visibly in yaw drift.
**Commit.** `feat(pipeline): fuse vision measurements on IMU thread with replayable logs`

---

### Task 4.4 — Occlusion / low texture / fast rotation / ZRU-vision agreement
**Goal.** Detect blocked camera and texture starvation, pause vision cleanly, and let ZRU fire only if vision agrees the phone is still.
**Why.** FR-5: when the camera can't see, the app must say so and fall back to gyro-only, never pretend.

**Files.** `core:/vision/OcclusionDetector.kt`, `core:test/vision/OcclusionDetectorTest.kt`, `app:/vision/VisionPipeline.kt` (image std via `Core.meanStdDev`), `core:/fusion/OrientationEstimator.kt` (ZRU gate).
**Signatures.**
```kotlin
// enum class OcclusionState { OK, LOW_TEXTURE, BLOCKED }  — already defined in Task 2.1 (core:/model/OcclusionState.kt)
class OcclusionDetector(private val cfg: VisionConfig) {
    /** Returns the debounced state (state must persist occlusionDebounceSec before switching). */
    fun update(tNs: Long, nTracked: Int, imageStd: Double): OcclusionState
}
```
Rules: Architecture §6.8. On any transition away from OK: `AnchorWindow.reset()`, `tracker.reset()` when BLOCKED. On return to OK: re-detect, new anchor.
ZRU agreement: the estimator remembers the last frame-pair vision rate (passed via `setVisionRate(radS, tNs)`). ZRU allowed only if vision is not OK **or** that rate < `zruMaxVisionRateRadS`.
Fast rotation: if the gyro rate > `maxRateForVisionRadS`, add warning MOVING_TOO_FAST (vision rejects with TOO_FAST).
**Tests.** Debounce timing; BLOCKED when std < 8 regardless of tracks; LOW_TEXTURE with std OK but < 20 tracks; recovery to OK after debounce.
**Verify.** `./gradlew :core:test`, then [DEVICE]: cover lens 10 s → "Camera blocked" within ~0.5 s; badge LOW after 3 s; uncover → back to OK within ~1 s.
**Done when.** Tests pass, device behaviour as described.
**Commit.** `feat(vision): occlusion and low-texture detection with clean vision pause`

---

### Task 4.5 — Full confidence + warnings in HUD
**Goal.** HUD shows confidence (HIGH/MEDIUM/LOW), σ_yaw, drift σ (°/min) and human-readable warnings, all from `OrientationOutput`.
**Files.** `core:/fusion/ConfidenceModel.kt` (finalize per Architecture §7), `core:test/fusion/ConfidenceModelTest.kt`, `app:/ui/CustomScreen.kt`, `app:/ui/ConfidenceBadge.kt`.
**Tests.** Truth table over (visionAge, drift, warnings) as in Architecture §7.
**Verify.** `./scripts/verify.sh`. [DEVICE] textured scene → HIGH after ~5–20 s (needs a few vision windows and the bias variance to shrink). Cover → LOW. Look straight down → LOW + "Looking straight up/down".
**Done when.** Behaviour matches the table.
**Commit.** `feat(ui): confidence and warnings driven by estimator state`

### ✅ Checkpoint 4 (manual, Custom mode) — expected numbers (MEDIUM targets, see PRD §5)
| Test | Expected |
|---|---|
| S1 static 5 min, textured scene | yaw drift ≤ 0.5°/min, pitch/roll std < 0.1° |
| Slow handheld pan ±60°, 2 min, return to mark | drift ≤ 2°/min; return error ≤ 3° |
| 360° slow turn on the protractor, return to 0 | ≤ 4° |
| Person walking through view (S5, 1–3 people) | yaw error increase vs. empty scene ≤ 1° |
| Cover lens 10 s (S8) | LOW within 3 s; recovery (vision accepted again) < 2 s after uncover; no crash |
| Replay `--no-vision` vs normal | vision run has clearly lower drift |
Shortfalls are OK if documented with analysis (Issues.md + Progress notes).

---

## Phase 5 — Calibration

### Task 5.1 — `CalibrationData` file + loader
**Goal.** Load per-device calibration (`calibration.json`) at startup, with clear priority: CALIBRATED > ARCORE (benchmark only) > FOV_ESTIMATE.
**Files.** `core:/config/CalibrationData.kt` (exists from 2.1; finalize), `app:/calibration/CalibrationStore.kt`, `app:/ui/SettingsScreen.kt` (show source + values), `core:test/config/CalibrationDataTest.kt`.
**Steps.** Path: `context.getExternalFilesDir(null)/calibration.json`. Missing → defaults + FOV estimate. Wrong resolution → scale (same aspect) or ignore with a warning. Values are copied into `meta.json` of every session.
Push from laptop: `adb push calibration.json /sdcard/Android/data/com.camimu.orient/files/calibration.json` (VERIFY on your Android version).
**Done when.** Settings shows "Intrinsics: CALIBRATED (fx=…)" after pushing a file. Bad JSON → error message + fallback, no crash.
**Commit.** `feat(calibration): per-device calibration file with source priority`

### Task 5.2 — Camera intrinsics (capture screen + `tools/calibrate_intrinsics.py`) `[DEVICE]`
**Goal.** Calibrate `fx, fy, cx, cy, k1, k2, p1, p2, k3` at the **analysis** resolution.
**Why.** Concept: intrinsics turn pixels into angles. A 3% error in `fx` makes vision see a 90° turn as 87.3°, and the vision correction would then pull yaw wrong.
**Files.** `app:/calibration/CalibrationCaptureScreen.kt`, `tools/calibrate_intrinsics.py`, `tools/README.md`.
**Steps.**
1. Print a 9×6 inner-corner checkerboard (OpenCV's `pattern.png`, or generate one), tape it flat to a board.
2. Capture screen: same CameraX config as Custom mode (same resolution, 4:3, OIS/EIS/AF off). A "Capture" button saves the current `GrayFrame` as PNG (`calib_###.png`) in the session dir (convert via a `Bitmap` with ALPHA_8/grey → `Bitmap.compress(PNG)`, or OpenCV `Imgcodecs.imwrite`).
3. USER: capture 20–30 images: board at different positions (corners of the image too!), tilts ±30–45°, distances.
4. `python3 tools/calibrate_intrinsics.py sessions/<s> --cols 9 --rows 6 --square-mm 25 --out calibration.json`:
   `cv2.findChessboardCorners` → `cv2.cornerSubPix` → `cv2.calibrateCamera` → write JSON (CalibrationData schema, `source: CALIBRATED`), print RMS reprojection error and per-image errors; drop images with error > 2× median and recalibrate.
**Expected.** RMS < 0.5 px (good), < 1.0 px (acceptable). `fx` within ~10% of the FOV estimate. `cx, cy` within ~5% of the centre.
**Done when.** `calibration.json` pushed. Axis check (Task 3.8) slopes now 0.98–1.02.
**Pitfalls.** Calibrating at a different resolution/crop than the analysis stream. Autofocus on during calibration (changes focal length). All images centred (distortion poorly constrained).
**Commit.** `feat(calibration): intrinsics capture screen and OpenCV calibration script`

### Task 5.3 — Gyro noise from a static recording (`tools/allan_variance.py`)
**Goal.** Measure `gyroNoiseDensity` (σ_g) and `biasRandomWalk` (σ_bw) for your phone instead of guessing.
**Why.** Concept: an Allan deviation plot shows noise vs averaging time. The slope −½ part is white noise (angle random walk), the bottom is bias instability, the slope +½ part is bias random walk.
**Files.** `tools/allan_variance.py`.
**Steps.** USER: record ≥ 20 min (60 min ideal) in Custom IMU-only mode with the phone on a soft cloth on a table, nobody touching it.
Script: overlapping Allan deviation per axis of `gx, gy, gz` for τ from 1/rate to T/10 (log-spaced). Fit:
`N` = σ(τ) on the −½ slope line evaluated at τ = 1 s → `gyroNoiseDensity ≈ N` (rad/s/√Hz).
`K` = the +½ slope line evaluated at τ = 3 s → `biasRandomWalk ≈ K` (rad/s/√s). If no +½ region is visible, use `B/√(T_corr)` with `B` = min(σ)/0.664 and `T_corr` = τ at the minimum (rough, and say so).
Write values into `calibration.json` (`gyroNoiseDensity`, `biasRandomWalk`) and a PNG plot.
**Expected (typical phone MEMS, VERIFY on yours).** N ≈ 1e-4 – 5e-4 rad/s/√Hz, bias instability ≈ 1e-5 – 1e-4 rad/s.
**Done when.** Plot and values saved. Replay of S1 with the new values doesn't get worse.
**Commit.** `feat(tools): Allan variance analysis for gyro noise parameters`

### Task 5.4 — Camera–IMU time offset (`tools/estimate_time_offset.py`)
**Goal.** Estimate the constant `timeOffsetNs` (t_d) between camera and IMU timestamps.
**Why.** At 2 rad/s, a 10 ms offset is a 1.1° disagreement between gyro and vision, which looks exactly like a bias and gets "corrected" wrongly.
**Files.** `tools/estimate_time_offset.py`.
**Steps.** USER: Custom mode, `timeOffsetNs = 0`, textured scene ≥ 2 m away, 30 s of **varied** shaking rotations (fast-ish, 1–3 rad/s, not too fast to blur).
Script: vision rate at frame-pair midpoint `t_c = (t_cam_prev + t_cam)/2`: `|rv|/(t_cam − t_cam_prev)` from `vision_frames.csv` (raw camera times).
Gyro rate `|ω_raw − b|` from `imu.csv` (b from est.csv). Resample both to 1 kHz. For shifts `s ∈ [−60, +60] ms` (1 ms steps):
`c(s) = corr(|ω_vis|(t), |ω_gyro|(t + s))`. `t_d = argmax c` refined with a parabola through the 3 best points. Print peak correlation.
**Sign convention.** A camera sample at camera time `t_c` happened at IMU time `t_c + t_d`. The app computes `tNs = tCam(+clock offset) + timeOffsetNs`.
**Expected.** Clear single peak, correlation > 0.8, |t_d| typically < 30 ms (VERIFY on your phone). Re-run on a second recording: results within ~2 ms.
**Done when.** `timeOffsetNs` stored. Re-recorded fast shaking: frame-pair agreement improves vs t_d = 0.
**Commit.** `feat(tools): camera-IMU time offset estimation by rate cross-correlation`

### ✅ Checkpoint 5 — expected numbers
Intrinsics RMS < 0.5 px. Axis-check slopes 0.98–1.02. `t_d` repeatable within 2 ms. Re-run Checkpoint 4 rows 2–3: equal or better.

---

## Phase 6 — Benchmark mode (custom vs ARCore)

### Task 6.1 — `ArCoreFrameSource` (ARCore CPU images → our pipeline) `[DEVICE]`
**Goal.** In Benchmark mode, ARCore owns the camera. Its CPU images feed our `VisionPipeline` while our IMU estimator runs in parallel.
**Why.** Two apps can't own the camera at once. Reusing ARCore's CPU image lets both estimators see the **same** frames, which makes a fair comparison.
**Files.** `app:/ar/ArCoreFrameSource.kt`, `app:/ui/BenchmarkScreen.kt`.
**Steps.**
1. In `onDrawFrame` (GL thread): `try { frame.acquireCameraImage().use { img -> ... } } catch (e: NotYetAvailableException) {}`.
   Copy plane 0 (Y) honoring `rowStride` into a buffer **only if** the vision executor is idle (an `AtomicBoolean busy`), then submit to the vision executor.
   (`android.media.Image` is `AutoCloseable`. Close it on the GL thread before returning.)
2. Intrinsics: `frame.camera.imageIntrinsics` → `focalLength`, `principalPoint`, `imageDimensions` → `CameraIntrinsics(source = ARCORE)`, zero distortion (VERIFY whether ARCore CPU images are undistorted).
3. Timestamps: `frame.timestamp` time base is not documented as `elapsedRealtimeNanos` (VERIFY). Estimate `arcoreOffsetNs = median(elapsedRealtimeNanos() − frame.timestamp)` over the first 30 frames, minus the typical latency (keep it simple: treat that latency as part of t_d and re-run Task 5.4's script on a benchmark recording to get `arcoreTimeOffsetNs`).
4. CPU image resolution is ARCore-chosen (often 640×480, VERIFY). Use its real size for `VisionConfig` grids.
**Done when.** Benchmark screen runs both. FeatureOverlay works on ARCore frames. Vision median ≤ 20 ms. ARCore still TRACKING.
**Pitfalls.** Holding the Image past the frame → `ResourceExhaustedException`. Doing vision on the GL thread → ARCore frame rate drops.
**Commit.** `feat(ar): feed ARCore CPU images into the custom vision pipeline`

### Task 6.2 — Comparison panel + `arcore.csv`
**Goal.** Show custom vs ARCore angles and their differences live, and log ARCore readings.
**Files.** `app:/ui/ComparisonPanel.kt`, `app:/ui/BenchmarkScreen.kt`, `core:/io/CsvSchema.kt` (`ArCore`), `app:/logging/SessionLogger.kt`.
**Steps.** Reset yaw resets **both** (`runner.postResetYaw()` + `poseReader.requestResetYaw()`). Panel: three rows `custom | arcore | Δ` with Δ computed via `AngleMath.angleDiff`. A small 30-s scrolling line chart of Δyaw (Compose Canvas).
**Done when.** Panel updates ≥ 10 Hz, `arcore.csv` logged with DataModel columns.
**Commit.** `feat(ui): custom-vs-ARCore comparison panel and ARCore logging`

### Task 6.3 — Full `analyze_log.py` metrics + benchmark report
**Goal.** Compute every TestPlan metric from session files.
**Files.** `tools/analyze_log.py`, `tools/report.py` (aggregates multiple sessions into `docs/Results.md` tables).
**Metrics.** Interpolate ARCore to est timestamps (`np.interp` on unwrapped yaw): RMSE/max of Δpitch, Δroll, Δyaw. Drift via linear fit. Return-to-start error (mean yaw in the first vs last 2 s of marked static segments, from `events.csv` MARK events; add a "Mark" button in the HUD that logs `MARK`). Recovery time after occlusion (OCCLUSION_STATE OK → first accepted vision). 2σ coverage: fraction of |Δyaw vs truth| ≤ 2·σ_yaw.
**Done when.** `python3 tools/report.py sessions/*` produces `docs/Results.md` tables.
**Commit.** `feat(tools): benchmark metrics and results report`

### ✅ Checkpoint 6 — expected numbers
2-min slow pan in a textured room, both reset together: median |Δyaw| ≤ 3°, pitch/roll RMSE vs ARCore ≤ 1.5°. ARCore is a reference, not truth: big disagreements need physical ground truth (protractor) to decide who's right.

---

## Phase 7 — Validation + polish

### Task 7.1 — Settings screen (persisted parameters)
**Goal.** Edit key parameters (kTilt, visionAttitudeGain, sigmaRotRad, gate settings, maxPerCell, logging decimation, debug overlay toggle) and persist them.
**Files.** `app:/settings/SettingsRepository.kt` (SharedPreferences storing `ConfigJson` strings), `app:/ui/SettingsScreen.kt`.
**Steps.** Load on app start; changes apply on the next session start (simpler than hot-swapping a running estimator). "Reset to defaults" button. Show calibration source.
**Done when.** Changed values appear in the next session's `meta.json`.
**Commit.** `feat(settings): persisted estimator and vision parameters`

### Task 7.2 — Performance + 30-minute stability `[DEVICE]`
**Goal.** Meet the budget (Architecture §10) and run 30 min without crash, leak or thermal collapse.
**Steps.**
1. Show/log `procMs` p50/p95. If p50 > 20 ms: lower `maxPerCell` 4 → 3, then `detectMaxCorners`, then analysis 640×480 → 480×360 (recalibrate intrinsics or scale them).
2. Adaptive: if p95 over the last 60 frames > 30 ms, skip every other frame (log `ADAPT` event).
3. USER: 30-min run (S12) in Custom mode with logging. Watch memory: `adb shell dumpsys meminfo com.camimu.orient | grep TOTAL` at 0/10/20/30 min.
**Expected.** No crash. Memory growth < 50 MB over 30 min. `droppedRows = 0`. Frame rate stays ≥ 20 fps after thermal warm-up.
**Done when.** Numbers recorded in Progress.md.
**Commit.** `perf(vision): adaptive load and long-run stability fixes`

### Task 7.3 — Scenario matrix S1–S12 + results `[DEVICE]`
**Goal.** Execute TestPlan §4 scenarios, analyze, write `docs/Results.md` (measured numbers vs targets, honest shortfalls).
**Steps.** Follow TestPlan.md exactly. One session per scenario, named `S5_3people`, etc. Run `tools/report.py`. Write the per-scenario analysis (what failed and why).
**Done when.** All S1–S12 rows have numbers (or "not run: reason"). Targets met, or shortfalls explained.
**Commit.** `docs: scenario matrix results`

### Task 7.4 — Final docs + cleanup
**Goal.** README accurate, all docs consistent with the code, dead code removed, forbidden-API audit output saved.
**Steps.** `./scripts/check_forbidden_apis.sh > docs/forbidden_api_audit.txt`. Include `registeredSensorTypes` from a real `meta.json` in README as runtime evidence. Update Decisions.md with any changes. Second device test (S13) if available.
**Done when.** Definition of done in PRD §7 checked.
**Commit.** `docs: final documentation and compass-free audit`

### ✅ Final checkpoint
PRD §7 checklist all ticked (or explicitly waived with reason).

---

## Replay / offline testing

**What gets replayed.** `imu.csv` (every IMU sample), `vision.csv` (window measurements with `t_applied_ns`), `events.csv` (RESET_YAW, OCCLUSION_STATE).
Replay re-runs **fusion** with any `EstimatorConfig`. It does **not** re-run the vision front-end (frames aren't recorded at MEDIUM level), so vision parameters
(`VisionConfig`) must be tuned live. Rotation measurements already logged are reused as-is. (Recording frames is in STRETCH S-X1.)

**Workflow.**
```bash
mkdir -p tuning
./gradlew :replay:run --args="--session sessions/S2_pan --out sessions/S2_pan/replay_default"
# copy the config from meta.json, edit one parameter
python3 -c "import json;m=json.load(open('sessions/S2_pan/meta.json'));json.dump(m['estimatorConfig'],open('tuning/kTilt0.2.json','w'),indent=2)"
#   edit tuning/kTilt0.2.json → "kTilt": 0.2
./gradlew :replay:run --args="--session sessions/S2_pan --config tuning/kTilt0.2.json --out sessions/S2_pan/replay_kTilt0.2"
python3 tools/analyze_log.py sessions/S2_pan --est replay_kTilt0.2/est_replay.csv
```
**Rules.** Change one parameter at a time. Keep a "tuning set" of 4–5 sessions (S1 static, S2 pan, S4 walking, S5 people, S8 covered) and check every candidate config on all of them, because a change that helps S2 can hurt S4.
**Synthetic first.** For questions like "does a higher `visionAttitudeGain` cause overshoot?", write a `:core` test with `ImuSimulator` + `VisionMeasurementSimulator` before touching real data.

---

## Tuning guide

| Symptom | Likely parameter | Change | Side effect |
|---|---|---|---|
| Yaw drifts steadily even with good texture | vision bias learning too weak | lower `sigmaRotRad` (3e-4 → 2e-4), raise `windowMaxSec` (1.0 → 1.5) | more sensitivity to bad windows |
| Yaw drifts steadily, `vision.csv` shows many `GATE` rejections | measurements inconsistent | check rCD (Task 3.8), t_d (5.4), intrinsics (5.2) **first**; then raise `sigmaRotRad` | — |
| Yaw drifts only while camera blocked | expected (gyro-only) | longer static init, ensure ZRU fires (`staticGyroStdThr`), calibrated σ_bw | — |
| Yaw jumps/steps every ~1 s | attitude nudge too strong or noisy windows | lower `visionAttitudeGain` (0.5 → 0.2), raise `minInlierRatio` | slower correction |
| Yaw noisy/jittery at rest | vision noise into attitude | lower `visionAttitudeGain`, raise `minInliers` | — |
| Yaw lags behind real motion | should not happen (gyro drives it) | check dt units, midpoint integration, display throttle (`uiPublishHz`) | — |
| Pitch/roll drift while walking/shaking | accel contamination | lower `kTilt` (0.5 → 0.2), lower `accelGateMs2` (1.0 → 0.5) | slower recovery after bumps |
| Pitch/roll slow to settle after a bump | tilt correction too weak | raise `kTilt` (0.5 → 1.0) | more walking sensitivity |
| Pitch/roll wrong by a constant 1–2° | accelerometer bias | (STRETCH: 6-position accel calibration). Document. | — |
| Moving people still pull yaw | gating too loose | lower `gateBasePx` (4 → 3), raise `minInlierRatio` (0.4 → 0.5), raise `minSpread` | fewer accepted windows in clutter |
| Too few accepted windows in normal rooms | gating/quality too strict | raise `gateBasePx`, lower `minInliers` (15 → 10) | — |
| Confidence never reaches HIGH | thresholds vs real σ_bw | check `biasRandomWalk` (Task 5.3); HIGH needs vision age < 1.5 s and drift σ < 1°/min | — |
| Vision too slow | front-end load | lower `maxPerCell`, `detectMaxCorners`, `kltMaxLevel` (3 → 2) | fewer tracks |

Order of attack: **geometry first** (rCD, intrinsics, t_d), then **noise parameters from data** (Allan), then the **gains** last.

---

## Calibration guide (MEDIUM level)

| Item | Method (task) | When | Required? |
|---|---|---|---|
| Camera intrinsics | Checkerboard, `calibrate_intrinsics.py` (5.2) | once per phone model & resolution | yes for accuracy targets |
| Gyro bias | 2-s static init + ZRU + vision KF (2.3, 2.5, 4.2) | every start, continuous | automatic |
| Gyro noise params | Allan variance (5.3) | once per phone | recommended |
| Camera–IMU rotation `rCD` | Known axis flip from SENSOR_ORIENTATION + sign check (3.8) | once per phone | yes (sign check) |
| Camera–IMU time offset t_d | Rate cross-correlation (5.4) | once per phone (re-check after OS updates) | yes for fast motion |
| Small rCD misalignment, gyro scale, accel bias | — | — | STRETCH |

---

## Troubleshooting

| Symptom | Likely cause | Fix |
|---|---|---|
| `./gradlew` fails "JAVA_HOME is not set" / class file version error | JDK not configured | `export JAVA_HOME=/opt/android-studio/jbr` (VERIFY path) |
| "SDK location not found" | no `local.properties` | `echo "sdk.dir=$HOME/Android/Sdk" > local.properties` |
| `adb devices` shows `unauthorized` | RSA prompt not accepted | Replug, accept on phone; `adb kill-server; adb start-server` |
| `adb devices` shows `no permissions` | udev rules | Install platform-tools udev rules, add to `plugdev`, relogin |
| "adb server version doesn't match" | two adb binaries | Use only the SDK's platform-tools adb on PATH |
| Black ARCore screen | `setCameraTextureName` not called before `update()`, or session not resumed | See Task 1.4 order |
| ARCore roll off by 90° | used `camera.pose` instead of `displayOrientedPose` | Task 1.5 |
| Yaw sign reversed vs expectation | convention: left turn = + | That's by design (Architecture §4.4). If the test disagrees, fix Frames + add a test |
| Pitch/roll slowly converge to wrong values | `â × ĝ` order reversed, or accel units (g vs m/s²) | Task 2.4 tests |
| Angles explode / NaN | dt in ns, not normalizing q, `acos`/`asin` without clamp | Rules.md math section |
| IMU rate ~50 Hz | listener registered with `SENSOR_DELAY_UI`, or thread busy | use 5000 µs and the `imu` HandlerThread |
| Camera stream freezes after a few frames | `ImageProxy.close()` missing | Task 3.5 `finally { close() }` |
| App crashes on OpenCV call | OpenCV not initialized | `OpenCvInit.ensure()` before first `Mat` |
| Native memory grows | `Mat`s not released | reuse Mats; `release()` in `FeatureTracker.release()` |
| Overlay dots offset/rotated | raw-image coords drawn on rotated preview | Task 3.7 mapping |
| Vision makes drift **worse** | `rCD` sign error, or bias residual sign flipped | Task 3.8 axis check; Task 4.2 test 1 |
| Many `NO_GYRO_COVERAGE` rejections | camera timestamps not on IMU clock, or t_d huge | check `TimeSync` / timestamp source; Task 5.4 |
| Many `GATE` rejections during fast pans | time offset or rolling shutter | calibrate t_d; lower `maxRateForVisionRadS`; STRETCH rolling shutter |
| Yaw good at rest, bad during walking near furniture | translation parallax on near points | accepted limitation; gating removes most; S7 documents it |
| Logs have commas as decimals | locale formatting | `Locale.ROOT` in CsvSchema |
| Replay doesn't match device | events applied at wrong index, different config | use `t_applied_ns`; replay with `meta.json` config |
| `adb pull` of Android/data denied | Android version restriction | use `run-as` fallback (Issues.md) |

---

## STRETCH (not in the main path)

Do these only after Phase 7 is done (or if a target is clearly missed and the analysis says one of these is the fix).

### S1 — Stochastic cloning / full error-state Kalman filter (ESKF)
**What.** Replace "complementary tilt + diagonal bias KF + attitude nudge" with a 6-state ESKF (`δθ`, `δb_g`) whose state is augmented at each anchor frame with a clone of the attitude error (`δθ_anchor`). The vision measurement then constrains the *relative* rotation between the clone and now, properly correlated with the bias.
**Math sketch.** Error dynamics `δθ' = −[ω]×δθ − δb_g − n_g`, `δb_g' = n_bg`; `F = I + A·dt` with `A = [[−[ω]×, −I],[0, 0]]`; clone at anchor: `P_aug = [[P, P·Jᵀ],[J·P, J·P·Jᵀ]]` with `J = [I 0]`. Residual `r = Log(R_visᵀ · R_clone,ᵀ R_now)`, Jacobian w.r.t. `[δθ_clone, δθ_now, δb]`. Joseph-form update.
**Why stretch.** Correct and more accurate, but 6×6/9×9 matrix code and Jacobian bookkeeping are much harder to verify.
**Done when.** Synthetic test 4.2-1 error ≤ 50% of the MEDIUM estimator's error, and replay on the tuning set is not worse.

### S2 — Keyframe relocalization (drift-bounded yaw / loop closure)
**What.** Store keyframes (features + ORB descriptors + `qWD` at capture, ≤ 30). When the view overlaps a stored keyframe (descriptor match + RANSAC ≥ N inliers), measure rotation relative to the keyframe. That gives an absolute-ish yaw correction against the world frame, without chain accumulation. Returning to the start view cancels drift.
**Tools.** `ORB.create()`, `BFMatcher(NORM_HAMMING)` + ratio test (OpenCV Java, VERIFY APIs), Horn/RANSAC from Task 3.3.
**Done when.** 360° return-to-start error ≤ 2° (project.md's original target).

### S3 — Semantic masking
**What.** A lightweight segmentation model (e.g. MediaPipe Image Segmenter or a TFLite DeepLab variant, VERIFY current packages/models) at 5–10 Hz masks `person`/`vehicle`/`sky` pixels. The tracker skips detection there and drops tracks entering the mask.
**Done when.** S6 (70% obstacle coverage by people) keeps vision valid where MEDIUM falls back to gyro-only, with no new yaw error.

### S4 — Rolling-shutter modelling
**What.** Each image row is exposed at `t_row = t_frame + (v/H)·t_readout`. Use per-feature timestamps when integrating gyro rotation for gating/measurement, or estimate `t_readout` (VERIFY whether `SENSOR_ROLLING_SHUTTER_SKEW` is exposed via CaptureResult on your device).
**Done when.** S3 (fast pans ≥ 120°/s) accepted vision windows increase and GATE rejections drop, without accuracy loss.

### Other stretch ideas
- **S-X1 frame recording** (save 320×240 gray frames as a raw file) so the vision front-end can be replayed offline too.
- **Landscape support** (rotate `qDO` with display rotation).
- **Online t_d estimation** (add t_d to the filter state).
- **rCD fine misalignment** (estimate a small rotation from the axis-check data by Wahba on gyro vs vision rotation vectors).
- **NDK/C++ port** of the tracker if Kotlin+OpenCV-Java can't hit the budget.
- **iOS port** (CoreMotion `xArbitraryZVertical`, never the magnetic frames).
