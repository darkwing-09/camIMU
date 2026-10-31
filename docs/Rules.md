# Rules — coding conventions for CamIMU-Orient

These rules apply to every line Claude Code writes. `CLAUDE.md` has the short version.

## 1. Forbidden APIs (hard rule)
Never reference, in any source file (`*.kt, *.java, *.xml, *.kts`):

| Forbidden | Why |
|---|---|
| `Sensor.TYPE_MAGNETIC_FIELD`, `TYPE_MAGNETIC_FIELD_UNCALIBRATED` | magnetometer |
| `Sensor.TYPE_ROTATION_VECTOR`, `TYPE_GEOMAGNETIC_ROTATION_VECTOR` | fused with magnetometer |
| `Sensor.TYPE_ORIENTATION` | deprecated, magnetometer-based |
| `SensorManager.getRotationMatrix(...)`, `SensorManager.getInclination(...)` | take geomagnetic input |
| `SensorManager.getOrientation(...)` | conventionally used with magnetic data; we have our own `AngleConverter` |
| `android.hardware.GeomagneticField` | magnetic model |
| `android.hardware.sensor.compass` feature | |

Allowed sensors: `TYPE_GYROSCOPE_UNCALIBRATED` (preferred), `TYPE_GYROSCOPE` (fallback), `TYPE_ACCELEROMETER`.
`TYPE_GAME_ROTATION_VECTOR` is allowed by the spec but **not used** (ADR-014). Don't add it without a Decisions.md entry.
Enforced by `scripts/check_forbidden_apis.sh`. Runtime evidence: `registeredSensorTypes` in every `meta.json`.

## 2. Math conventions
- **Quaternion:** Hamilton, stored `[w, x, y, z]`, `qAB` maps B-frame vectors into frame A. Composition `qAC = qAB * qBC`.
  Re-normalize after every integration/correction. Canonical form `w ≥ 0` when comparing or logging.
- **Rotation matrices:** `rAB` maps B → A. Row-major storage in `Mat3`.
- **Body-rate integration:** right-multiply: `qWD = qWD * Quaternion.exp(omegaD * dt)`.
- **Frames:** always name them in identifiers: `qWD`, `qWO`, `rCD`, `rotD1D2`, `omegaD`, `upD`. See Architecture §4.
- **Angles:** radians internally. Wrap yaw with `AngleMath.wrapPi` to (−π, π]. Compare angles with `AngleMath.angleDiff`, never plain subtraction.
- **Clamp before `asin`/`acos`**: `asin(x.coerceIn(-1.0, 1.0))`.
- **Yaw sign:** turning left (CCW from above) = positive.
- **Units in names** at boundaries: `...Deg`, `...Ms`, `...Ns`, `...Sec`, `...Hz`, `...Px`, `...RadS`, `...Ms2`.

## 3. Time
- Timestamps are `Long` nanoseconds on the `SystemClock.elapsedRealtimeNanos()` clock (after `TimeSync`).
- `dt = (t2 - t1) * 1e-9`: subtract as `Long` **first**, then convert to `Double`. Never store absolute ns in `Float`/`Double`.
- Never assume a fixed sample rate. Drop samples with `dt <= 0`. Flag `dt > maxGapSec`.
- Camera frame time on IMU clock: `tNs = tCam (+ clock conversion) + timeOffsetNs`.

## 4. Module boundaries
- `:core` must not import `android.*`, `androidx.*`, `org.opencv.*`, `com.google.ar.*`. (The JVM module won't compile if it does.)
- `:app` may import everything, but math lives in `:core`. If you write a formula in `:app`, move it to `:core` and test it.
- `:replay` depends only on `:core`.

## 5. Threads
- `OrientationEstimator` methods are called **only** on the `imu` thread. Other threads post work via `imuHandler.post {}`.
- No blocking calls (I/O, locks held long, `Thread.sleep`, `runBlocking`) on `imu`, `vision`, GL or main threads.
- Shared mutable data: only `GyroHistory` (`@Synchronized`) and `@Volatile` snapshots. No other shared mutable state.
- UI gets data from `StateFlow`; never call estimator methods from Compose.

## 6. Error handling
- Programming errors (bad arguments, impossible states) → `require`/`check` (crash in debug; these are bugs).
- Runtime conditions (no gyro, ARCore unsupported, camera unavailable, missing calibration) → typed result or UI message. Never crash, never silently ignore.
- Sensor/vision anomalies (NaN, empty tracks, failed RANSAC) → reject that measurement, count it, log at most once per second, continue.
- Never `catch (e: Exception) {}` with an empty body. At minimum `Log.w` with context.
- Check every `Vec3/Quaternion` result from vision for `isFinite()` before using it.

## 7. Logging
- Tags: `CamIMU.<Area>` e.g. `CamIMU.Imu`, `CamIMU.Vision`, `CamIMU.Ar`, `CamIMU.Log`, `CamIMU.Calib`.
- Logcat: lifecycle events, configuration, errors, and per-second summaries only. **Never per sample or per frame.**
- Data goes to CSV via `SessionLogger` (bounded queue, off-thread).
- CSV numbers: `Locale.ROOT`, full precision for raw data (`toString()`), 4 decimals for degrees is fine.

## 8. Kotlin style
- Official Kotlin style (`kotlin.code.style=official`). 4-space indent, max line ~140.
- Prefer `val`, immutable `data class`es, pure functions. Use `object` for stateless helpers.
- Files ≤ ~250 lines; one public class per file, named like the file.
- KDoc on every public `:core` declaration: purpose, units, frames.
- No magic numbers: put tunables in `EstimatorConfig`/`VisionConfig`; true constants (`PI`, χ² values) as named `const val`.
- No reflection, no annotation processors (except kotlinx-serialization).
- Compose: stateless widgets taking plain values; state hoisted to the screen.

## 9. Tests
- Every `:core` class has a test file `<Class>Test.kt` in the mirrored package.
- Tests are deterministic: seeded `java.util.Random(seed)`, no wall clock.
- Floating comparisons with explicit tolerances (`assertEquals(expected, actual, 1e-9)`), angles via `angleDiff`.
- Test names describe behaviour: `fun leftTurnIncreasesYaw()`.
- A failing test is fixed in code, not by loosening tolerances, unless the tolerance was wrong (then say why in the commit).

## 10. Dependencies
- Only those listed in Implementation.md. Adding a library needs a Decisions.md ADR.
- Versions live in `gradle/libs.versions.toml`. Never hard-code a version in a module build file.
- If an API is unknown/uncertain: check sources/docs, or write `UNKNOWN - verify` in Issues.md. Never invent it.

## 11. Git
- One task = one commit (message suggested in the task). Conventional prefixes: `feat`, `fix`, `test`, `docs`, `build`, `chore`, `perf`, `refactor`.
- Never commit `sessions/`, `local.properties`, `build/`, `.venv/`.
- `./scripts/verify.sh` must pass before committing.
