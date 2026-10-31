# Decisions (ADR log)

Format: context → decision → consequences. Add new ADRs at the end. Never delete; mark superseded ones.

---

**ADR-001 — Kotlin + OpenCV Java API, no NDK/C++.** *Accepted.*
Context: MEDIUM difficulty, the developer is learning Android, and Claude Code must verify each step. project.md proposed C++/NDK for vision.
Decision: All code in Kotlin. Vision uses the OpenCV Android SDK's Java bindings (KLT, Shi–Tomasi). RANSAC/Wahba are written in Kotlin.
Consequences: simpler build (no CMake/JNI). Some JNI overhead per OpenCV call (small: a few calls per frame). If the vision budget is missed, an NDK port is STRETCH.

**ADR-002 — Three modules: `:core` (pure JVM), `:app`, `:replay`.** *Accepted.*
Decision: All math/fusion/vision geometry in `:core` with JUnit tests. Android/OpenCV/ARCore only in `:app`.
Consequences: fast, phone-free testing. The compiler enforces the boundary. Replay reuses the exact same estimator code.

**ADR-003 — Estimator = complementary tilt filter + diagonal bias Kalman filter + vision attitude nudge (not a full ESKF).** *Accepted.*
Context: brief asks for Madgwick/complementary for pitch/roll and a *simplified* Kalman filter for yaw/bias.
Decision: Mahony-style proportional tilt correction (`e = â × ĝ`, gain `kTilt`). A 3-axis diagonal KF on gyro bias fed by ZRU and vision. Vision also removes a fraction (`visionAttitudeGain`) of the window attitude error.
Why not Madgwick: equivalent quality here, and the Mahony error term has an intuitive time constant and provably no yaw component.
Consequences: ~200 lines, each piece testable alone. No cross-covariances (bias/attitude), so σ_yaw is a heuristic (ADR-011). A full ESKF with cloning is STRETCH S1.

**ADR-004 — Vision measurements over an anchor window (≤ 1 s), not frame-to-frame.** *Accepted.*
Context: bias × 33 ms is far below per-frame vision noise, so frame-to-frame measurements barely observe bias.
Decision: Tracks keep their anchor-frame position. One measurement per window (anchor → current), then re-anchor.
Consequences: ~30× better bias signal-to-noise. Measurements are independent (no reuse). Fast pans shorten windows. Not the same as keyframe relocalization (that stays STRETCH S2).

**ADR-005 — Wahba via Horn's quaternion method + Jacobi eigen-solver in Kotlin; essential matrix optional.** *Accepted.*
Decision: Rotation-only model on bearings, 2-point RANSAC, Horn refinement. `findEssentialMat` only as an optional cross-check (Task 3.9).
Consequences: pure-JVM testable, no SVD library. The essential matrix degenerates under pure rotation (the common handheld case), so it isn't on the main path.

**ADR-006 — Portrait-locked UI. Output frame O = upright portrait back camera. project.md §5.3 angle formulas.** *Accepted.*
Decision: `screenOrientation=portrait`. `qWO = qWD ⊗ [0,1,0,0]`. yaw = atan2(f_y,f_x) − ref, pitch = asin f_z, roll = atan2(r_z, −d_z). Yaw positive = turning left (right-handed about up).
Consequences: no display-rotation bugs. Landscape is STRETCH. The yaw sign may feel reversed to compass users, so the HUD caption explains it.

**ADR-007 — Conventions: Hamilton `[w,x,y,z]`, `qAB` maps B→A, rad internal, ns `Long` timestamps on elapsedRealtime.** *Accepted.*
Consequences: one convention everywhere. ARCore's `[x,y,z,w]` is converted in exactly one function (`Frames.qWOFromArCore`).

**ADR-008 — Phase order: ARCore baseline first. Benchmark via ARCore CPU images.** *Accepted.*
Context: ARCore and CameraX can't both own the camera.
Decision: ARCORE mode = ARCore only. BENCHMARK mode = ARCore session whose CPU images (`acquireCameraImage`) feed our vision pipeline while our IMU estimator runs in parallel. CUSTOM mode = CameraX.
Consequences: fair comparison on identical frames. ARCore timestamps need offset alignment (VERIFY, Issues V-6).

**ADR-009 — Per-stream CSV files + `meta.json` instead of one wide CSV.** *Accepted.*
Context: streams have different rates (IMU 200 Hz, frames 30 Hz, windows ~1 Hz, ARCore 30 Hz).
Consequences: no sparse columns. Replay merges by timestamp, and `t_applied_ns` keeps vision application points exact.

**ADR-010 — Replay in Kotlin (`:replay`), analysis/calibration in Python.** *Accepted.*
Decision: one estimator implementation (no Python re-implementation drift). Python only for plotting, metrics, Allan variance, intrinsics and time offset.
Consequences: replay is bit-identical to the device (within floating noise). The developer uses their Python strength for analysis.

**ADR-011 — Confidence from vision recency + bias uncertainty. σ_yaw is a conservative heuristic.** *Accepted.*
Context: project.md defined confidence from σ_yaw thresholds, but relative-yaw σ grows forever without an absolute reference, so the badge would turn red in normal use.
Decision: HIGH needs recent accepted vision (< 1.5 s) and drift σ < 1°/min. σ_yaw is still displayed and logged, and its 2σ coverage is validated (TestPlan §6).
Consequences: the badge reflects "is the estimate currently being corrected?", which is what users need.

**ADR-012 — MEDIUM accuracy targets relax 360° return-to-start (≤ 4°, ≤ 6° with people).** *Accepted.*
Context: without keyframe relocalization, chained windows accumulate intrinsics/scale and noise errors.
Consequences: project.md's ≤ 2° is a STRETCH S2 target. Stated honestly in PRD §5.

**ADR-013 — minSdk 26, compile/target from the Studio template, arm64-v8a only, JVM 17.** *Accepted.*
Consequences: covers practically all ARCore phones. Smaller APK (OpenCV native libs for one ABI). 32-bit phones unsupported.

**ADR-014 — No `TYPE_GAME_ROTATION_VECTOR` in the main path.** *Accepted.*
Context: allowed by the spec (gyro+accel only) but redundant with ARCore as a reference and makes the "own algorithm" audit muddier.
Consequences: the registered sensor list is exactly {gyro (uncal), accel}.

**ADR-015 — Project created from the Android Studio template. Extra library versions verified at task time.** *Accepted.*
Decision: AGP/Kotlin/Compose/SDK versions come from the template. CameraX/ARCore/OpenCV/serialization versions are checked on Maven before adding and recorded here:
*Superseded in part by ADR-018* (the project was scaffolded by hand, not from the template). Versions actually used
(all resolved and built on 2026-10-31; chosen to match the installed SDK platform 34 and the locally cached AGP/Kotlin):
- Gradle 8.9 (wrapper), JDK 17 (`/usr/lib/jvm/java-17-openjdk-amd64`, set in `gradle.properties`)
- AGP 8.3.2, Kotlin 1.9.24, Compose compiler 1.5.14, Compose BOM 2024.06.00
- compileSdk / targetSdk 34, minSdk 26, build-tools 34.0.0
- CameraX 1.3.4, ARCore 1.44.0, OpenCV 4.10.0 (Maven Central), kotlinx-serialization-json 1.6.3, coroutines 1.8.1
- activity-compose 1.9.0, core-ktx 1.13.1, JUnit 4.13.2, androidx.test runner 1.5.2 / ext-junit 1.1.5
Newer versions exist (e.g. CameraX 1.6.x, ARCore 1.57, OpenCV 4.14). Upgrading needs compileSdk 35+ installed, so do it as one deliberate task.

**ADR-016 — Prefer `TYPE_GYROSCOPE_UNCALIBRATED`.** *Accepted.*
Context: the calibrated gyro has an OS-applied, opaque bias correction that can jump.
Decision: use raw uncalibrated rates and estimate bias ourselves. Log the OS's bias estimate (`os_bg*`) for comparison. Fall back to `TYPE_GYROSCOPE` if unavailable.

**ADR-017 — Intrinsics: calibrate the analysis stream directly. FOV estimate as fallback.** *Accepted.*
Context: mapping `LENS_INTRINSIC_CALIBRATION` (active-array coords) to a cropped/scaled stream is error-prone.
Consequences: calibration must be redone if analysis resolution changes. FOV estimate (±5%) suffices only for early phases.

**ADR-018 — Hand-written Gradle project instead of the Studio template.** *Accepted.*
Context: the build was done entirely from the terminal; only SDK platform 34 was installed and the system JDK is 25 (unsupported by Gradle 8.9).
Decision: write the Gradle files by hand against locally cached AGP 8.3.2 / Kotlin 1.9.24, and pin `org.gradle.java.home` to JDK 17.
Consequences: builds offline-friendly and reproducibly on this machine. On another machine, edit or remove `org.gradle.java.home`.

**ADR-019 — Vision bias observation uses the window's bias Jacobian H.** *Accepted.*
Context: `z = Log(rotVisᵀ rotRaw)/Δt` assumes little rotation inside the window. With handheld motion (~1 rad/window) the error
exceeds the measurement noise, and the χ² gate rejected about 30% of windows in a synthetic test.
Decision: `z = H⁻¹·Log(rotVisᵀ rotRaw)` with `H = Σ R(D_k→D_2)ᵀ dt` from `GyroHistory.integrateWithBiasJacobian`, noise `σ²·diag(H⁻¹H⁻ᵀ)`.
Consequences: 100% of synthetic windows accepted. Covered by `BiasJacobianTest`.

**ADR-020 — Global `seq` column for estimator actions.** *Accepted.*
Context: a yaw reset and a vision update can land after the same IMU sample, and replay must apply them in device order.
Decision: `vision.csv` and `events.csv` carry `seq` (−1 for informational events). Replay sorts by (`t_applied_ns`, `seq`).

**ADR-021 — REC restarts the session.** *Accepted.*
Decision: pressing REC creates a fresh session (2-s init) with logging from the first sample, so every recording replays exactly.

**ADR-022 — HUD palette from the open-design.ai "HUD" design system.** *Accepted.*
Base #0A0A0A / #111316 / #1E2328 with phosphor green #00FF41 and lime #7FFF00 (https://open-design.ai/systems/hud/), plus amber #FFB000
and red #FF3B30 for MEDIUM/LOW confidence. Monospace typography throughout.

**ADR-023 — ARCore time base detected like the IMU clock.** *Accepted.*
`frame.timestamp` is tested against elapsedRealtime, then System.nanoTime, on the first CPU image. The fallback is arrival − 40 ms. Any residual constant
goes into `calibration.json` `arcoreTimeOffsetNs` (estimate with `estimate_time_offset.py` on a BENCHMARK recording).

---

## Council summary (Step 1, kept for reference)
- **Disagreement 1, frame-to-frame vs longer baseline:** CV engineer wanted frame-to-frame (simplest). Sensor-fusion engineer showed bias is unobservable at 33 ms. Resolved with ADR-004.
- **Disagreement 2, ESKF vs simpler filter:** Fusion engineer preferred the ESKF. Workflow architect and QA preferred small, independently testable parts. Resolved with ADR-003, ESKF as STRETCH S1.
- **Disagreement 3, how to benchmark against ARCore:** Android engineer flagged the camera-ownership conflict. Resolved with ADR-008 (ARCore CPU images).
- **Disagreement 4, confidence definition:** QA flagged that σ_yaw-only confidence turns red over time. Resolved with ADR-011.
