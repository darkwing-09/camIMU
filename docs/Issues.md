# Issues — risks, open questions, VERIFY items

Claude Code: add new entries at the end of the right section with the next ID. When resolved,
move the resolution into the entry (don't delete) and mark it `RESOLVED (date)`.

---

## 1. VERIFY items (facts not yet confirmed; check before relying on them)
| ID | Item | Where it matters | How to verify |
|---|---|---|---|
| V-1 | Latest stable versions of CameraX, ARCore, OpenCV (`org.opencv:opencv` on Maven Central), kotlinx-serialization, coroutines | Tasks 0.2, 1.4, 3.5 | Maven Central / Google Maven; record in Decisions ADR-015 |
| V-2 | Version-catalog key name for Kotlin in the Studio template (`kotlin`?) | Task 0.2 | open `gradle/libs.versions.toml` |
| V-3 | `OpenCVLoader.initLocal()` exists in the chosen OpenCV version (older: `initDebug()`) | Task 3.5 | OpenCV Android SDK sources / javadoc |
| V-4 | OpenCV Java `goodFeaturesToTrack` returns `MatOfPoint` (integer coords) | Task 3.6 | OpenCV Java docs for the version |
| V-5 | ARCore `Config` property names (`updateMode`, `focusMode`, `planeFindingMode`, `lightEstimationMode`) and `ArCoreApk.checkAvailability` transient `UNKNOWN_CHECKING` behaviour | Task 1.4 | ARCore Android API reference |
| V-6 | ARCore `Frame.getTimestamp()` time base vs `elapsedRealtimeNanos` | Task 6.1 | ARCore docs + empirical offset check |
| V-7 | Whether ARCore CPU images (`acquireCameraImage`) are undistorted and what resolution they have on the device | Task 6.1 | ARCore docs; log `imageIntrinsics` and image size |
| V-8 | `SensorEvent.timestamp` uses the `elapsedRealtimeNanos` base on the test phone | Task 2.7 | `TimeSync` runtime check (logged in meta.json) |
| V-9 | Camera `SENSOR_INFO_TIMESTAMP_SOURCE` is REALTIME on the test phone. If UNKNOWN, the monotonic-base assumption holds | Task 3.5 | logged; Task 5.4 cross-correlation residual |
| V-10 | The device honours OIS/EIS OFF and AF OFF requests via Camera2Interop | Task 3.5 | read `CaptureResult` values (optional), compare axis-check stability |
| V-11 | `rCD` table vs SENSOR_ORIENTATION derivation is correct for the test phone | Task 3.8 | axis-check slopes positive ≈ 1 |
| V-12 | `adb pull/push` access to `/sdcard/Android/data/com.camimu.orient/` on the phone's Android version | Tasks 2.9, 5.1 | try it. Fallback below (R-9) |
| V-13 | `android-sdk-platform-tools-common` package name on Kali for udev rules | Before you start | `apt search platform-tools` |
| V-14 | Android Studio bundled JDK path (`/opt/android-studio/jbr`) | Before you start | `ls /opt/android-studio/jbr/bin/java` |
| V-15 | ARCore `hello_ar_java` sample path for the `BackgroundRenderer` pattern | Task 1.4 | github.com/google-ar/arcore-android-sdk |
| V-16 | Whether ARCore uses the magnetometer internally | Docs/claims only | Not verifiable from the app. We claim compass-free only for the Custom estimator |
| V-17 | Typical gyro noise/bias numbers used as defaults (σ_g = 1.5e-4, σ_bw = 2e-5) | EstimatorConfig | replaced by Task 5.3 measurements |
| V-18 | `Calib3d.findEssentialMat` / `recoverPose` Java overloads in the chosen OpenCV | Task 3.9 (optional) | OpenCV Java docs |

## 2. Risks
| ID | Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|---|
| R-1 | Examiner expects absolute (north) yaw | High | High | PRD non-goal, HUD caption "relative yaw (no compass)", README explanation |
| R-2 | Camera–IMU time offset wrong → vision fights gyro during motion | High | High | Task 5.4; `sigmaTimeOffsetSec` rate-scaled noise; `maxRateForVisionRadS` |
| R-3 | `rCD` sign error → vision increases drift | Medium | High | Task 3.8 axis check before fusion; synthetic test 4.2-1 |
| R-4 | Phone ignores OIS-off → image motion not purely rotational | Medium | Medium | Axis check; inflate `sigmaRotRad`; document per device |
| R-5 | Rolling shutter on fast pans → GATE rejections | Medium | Medium | Rate-gated vision; STRETCH S4 |
| R-6 | Kotlin + OpenCV-Java too slow on the phone | Low–Med | Medium | Budget checks (Task 3.7, 7.2), adaptive load, STRETCH NDK |
| R-7 | Phone not ARCore-supported | Medium | Low | Custom path independent; physical ground truth only |
| R-8 | Gyro sample rate capped (~100 Hz) on some phones | Medium | Low | Real dt everywhere; record rate |
| R-9 | `Android/data` inaccessible via adb on newer Android | Medium | Low | Fallback: `adb shell run-as com.camimu.orient ls files/` (debug builds), or `adb push` to `/data/local/tmp` then `run-as ... cp` |
| R-10 | Thermal throttling in 30-min run | Medium | Medium | Adaptive frame skipping (Task 7.2) |
| R-11 | FOV-estimate intrinsics ±5% before calibration → yaw scale error during vision corrections | High (early) | Medium | Do Task 5.2 before judging accuracy |
| R-12 | Near objects (< 1 m) violate rotation-only model | Medium | Medium | Gyro gating rejects most; documented limitation (S7) |
| R-13 | Version drift between docs and template (e.g. `kotlinOptions` deprecation) | Medium | Low | Follow the template's style; record in ADR-015 |
| R-14 | Claude Code invents an API or skips a verification | Medium | Medium | CLAUDE.md rule 7, per-task verification commands, Prompts.md review prompt |

## 3. Open questions (for the user)
| ID | Question | Default assumption until answered |
|---|---|---|
| Q-1 | Which phone(s) (model, Android version)? ARCore supported? | One mid-range ARCore phone, Android 12+, SENSOR_ORIENTATION 90 |
| Q-2 | Should this folder become its own git repo? It's currently inside the Desktop-level repo (`~/Desktop/.git`) | Create a nested repo here with `git init` at Task 0.1 if approved |
| Q-3 | What ground-truth gear is available (tripod/clamp, printer for protractor, wedges)? | Printed protractor + phone clamp + book wedges |
| Q-4 | Is "turning left = +yaw" acceptable, or do you want compass-style (right = +) in the display? | Left = + (mathematical, ADR-006) |

## 4. Known limitations (by design, MEDIUM)
- Yaw is relative and slowly drifts (bounded only while vision works; no loop closure).
- No landscape mode.
- Vision front-end can't be replayed offline (frames not recorded).
- Accelerometer bias isn't calibrated (constant pitch/roll offsets of ~0.5–1° are possible).
- σ_yaw is a conservative heuristic, not a calibrated covariance.

## 5. Resolved
- V-1 RESOLVED (2026-10-31): versions pinned and verified by a successful build (Decisions ADR-015).
- V-2 RESOLVED: catalog written by hand; Kotlin key is `kotlin`.
- V-3 RESOLVED: `OpenCVLoader.initLocal()` exists in OpenCV 4.10.0 (compiles; called in `OpenCvInit`).
- V-4 RESOLVED: Java `goodFeaturesToTrack` takes `MatOfPoint` (compiles in `FeatureTracker`).
- V-5 RESOLVED: ARCore 1.44 `Config` properties `updateMode/focusMode/planeFindingMode/lightEstimationMode` compile.
- V-13/V-14 RESOLVED: JDK 17 is `/usr/lib/jvm/java-17-openjdk-amd64` (set in gradle.properties); udev only matters once a phone is attached.
- V-15 RESOLVED: own `BackgroundRenderer` written (no sample code copied).
- Design issue found by the synthetic session: vision bias observation during large in-window rotation → fixed (ADR-019).

## 6. Still open (need the phone)
- No phone has been connected yet (`adb devices` was empty), so every [DEVICE] check is pending: V-6, V-7, V-8, V-9, V-10, V-11, V-12.
- First device run: follow Progress.md "Device checklist".
