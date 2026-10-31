# Test Plan — CamIMU-Orient

Three layers: **unit** (`:core`, laptop, seconds), **synthetic** (`:core` simulators with exact truth),
**real device** (scenarios with physical ground truth or ARCore reference). Plus **replay** tests that
tie device logs back to the laptop.

---

## 1. Unit tests (`./gradlew :core:test`)

| Class | Must-have cases | Task |
|---|---|---|
| `Vec3`, `Mat3`, `AngleMath` | cross/dot identities, rotX/Y/Z on basis vectors, det=1, wrapPi at ±π and 3π, angleDiff across ±180°, normalize(ZERO) throws | 1.1 |
| `Quaternion` | product associativity, rotate vs matrix, exp/log round-trip incl. tiny and ~π angles, fromMatrix at 180° rotations, integration of constant ω | 1.2 |
| `Frames`, `AngleConverter` | reference pose = (0,0,0), round trip on a grid, left turn = +yaw, pitch up = +, right side up = +roll, yawRef, wrap, vertical flag, no NaN at ±90°, rCD det/orthonormal, ARCore mapping | 1.3 |
| `ConfigJson` | round trip, `{}` → defaults, unknown key ignored, invalid → throws | 2.1 |
| `ImuSampleAssembler`, `GyroHistory` | no accel → null, stale accel flag, constant-ω integration exact, bias cancels, interpolation, out-of-range null, ring wrap | 2.2 |
| `StaticDetector`, `AttitudeInitializer` | bias recovery, flat/upright/tilted init angles, timeout path | 2.3 |
| `AttitudeFilter` | gravity cannot change yaw, tilt convergence with τ=1/kTilt, accel gate, dt jitter invariance, duplicate timestamps | 2.4 |
| `BiasKalman`, `YawUncertainty`, `ConfidenceModel` | convergence, gate rejects outlier, predict growth, σ formulas, confidence truth table | 2.5, 4.5 |
| `CsvSchema` | header/row field counts, `.` decimals, warnings formatting | 2.9 |
| `CameraModel` | project∘bearing identity, principal point, distort∘undistort < 0.01 px | 3.1 |
| `SymmetricEigen`, `WahbaSolver` | reconstruction, orthonormal V, exact rotation recovery, 2-point exact, noisy accuracy, degenerate → null | 3.2 |
| `RotationRansac`, `VisionQuality` | 30% outliers, 35% moving cluster, 60% moving → invalid, determinism, empty input | 3.3 |
| `GridBucketer`, `GyroFlowPredictor` | per-cell cap incl. existing, identity prediction, prediction matches simulator | 3.4 |
| `AnchorWindow`, `buildMeasurement` | close timing, early close, min duration, frame mapping | 4.1 |
| `OcclusionDetector` | debounce, BLOCKED vs LOW_TEXTURE, recovery | 4.4 |

Instrumented (`./gradlew :app:connectedDebugAndroidTest`, phone required): `FeatureTrackerTest` (synthetic shift recovered ±0.2 px), Task 3.6.

## 2. Synthetic tests (`:core`, `ImuSimulator`, `BearingSimulator`, `VisionMeasurementSimulator`)
| ID | Scenario | Pass |
|---|---|---|
| SY-1 | Static 60 s, bias (0.01,−0.005,0.02) rad/s | |yaw| < 0.2°, pitch/roll err < 0.1° |
| SY-2 | Yaw 0.5 rad/s for 6 s | final yaw err < 1° |
| SY-3 | Pitch ±20° sine 0.5 Hz, 30 s | pitch/roll RMSE < 0.5° |
| SY-4 | Walking-like linear accel 2 m/s² @ 2 Hz | pitch/roll RMSE < 2° |
| SY-5 | 5 min slow yaw, bias random walk ×5, vision σ=3e-4 | IMU-only err > 3°, fused err < 1° |
| SY-6 | SY-5 + 10% outlier windows | outliers rejected (GATE), err < 1.5° |
| SY-7 | SY-5 with 30 s vision gap | σ_yaw grows in gap, confidence ≤ MEDIUM, back to HIGH ≤ 2 s after |
| SY-8 | Replay determinism | replay == direct (1e-9) |

## 3. Replay tests
- `ReplayDeterminismTest` (synthetic session written with `CsvSchema`, read back, replayed twice).
- Device check: replaying any recorded session with its `meta.json` config reproduces `est.csv` within 1e-6° (Tasks 2.10, 4.3).

## 4. Real-device scenario matrix
Record each as a separate session named `S<n>_<label>`. Hold still 2 s at the start, press **Reset yaw**, use **Mark** at reference poses.
Distances: textured indoor scene 2–5 m unless stated.

| # | Scenario | Procedure | Ground truth / reference | Metric | Pass (MEDIUM) |
|---|---|---|---|---|---|
| S1 | Static 5 min | Phone in a clamp/tripod facing a textured wall | stationary = 0 change | yaw drift (linear fit), pitch/roll std | ≤ 0.5°/min; std < 0.1° |
| S2 | Slow pan 360° and back | Tripod head over printed protractor; rotate 360° left at ~30°/s, return to 0, Mark | protractor 0° mark | return-to-start error | ≤ 4° |
| S3 | Fast pan ≥ 120°/s | ±90° swings ×10 by hand, return to Mark | Mark pose | return error; % vision windows accepted | ≤ 6°; report |
| S4 | Walking | Walk 2 min in a corridor holding phone upright, return to start pose, Mark | Mark pose + wedge check at end | pitch/roll RMSE vs ARCore; return error | ≤ 2.5°; report |
| S5 | Moving people (1, 3, 10 if possible) | Tripod, slow ±60° pans while people walk through | ARCore (Benchmark) + Mark | Δyaw vs ARCore; error vs empty-scene run | increase ≤ 1° (1–3 people); report 10 |
| S6 | Large obstacle 30/50/70% | Hold a board/person covering that share of the view during slow pans | Mark | accepted windows; error | 30%: no change; 50%: report; 70%: vision rejected, gyro fallback (expected) |
| S7 | Near obstacles < 1 m | Pan near furniture/walls | ARCore + Mark | error vs S2 | report (parallax limitation) |
| S8 | Camera covered 10 s | Cover lens with a finger/cap mid-pan, uncover | Mark before/after | time to LOW; recovery time; yaw error accrued | LOW ≤ 3 s; recovery < 2 s; no crash |
| S9 | Low light | Dim room (lights off, screen light only) | Mark | accepted windows; drift | report; confidence must drop if vision fails |
| S10 | Textureless wall / fog | Point at blank wall 1 m | Mark | LOW_TEXTURE detected | warning shown within 1 s |
| S11 | Through vertical | Sweep from horizon to straight up/down and back | wedge at end | no NaN/crash; yaw/roll "—" when vertical; angles correct after | pass/fail |
| S12 | 30-min run | Normal handheld use + tripod pauses | meminfo | crash, memory growth, droppedRows, fps | no crash; < 50 MB; 0; ≥ 20 fps |
| S13 | Second device (optional) | Repeat S1, S2, S8 | as above | as above | report |

Pitch/roll physical truth: wedges of 10°, 20°, 30° (angle = atan(height/length), measure with a ruler) with the phone on a flat table edge. Repeat each 3× (`MARK` at each).

## 5. Ground-truth methods
| Method | Gives | Accuracy (approx.) | How |
|---|---|---|---|
| Printed protractor + tripod head | yaw at marks | ±1° (alignment-limited) | Print a 360° protractor on A4, center under tripod axis, align phone edge to marks |
| Taped floor lines | yaw repeatability | ±1–2° | Tape 0°/90°/180° lines; stand on a center mark |
| Wedges / inclinometer | pitch/roll | ±0.5° | Measured wedges; verify with a spirit level |
| ARCore pose (Benchmark) | dense relative reference | usually ~1–2° (VERIFY on device) | Not truth. Use for trends and shapes |
| Return-to-start (Mark) | loop error | ±1° | Same physical pose at start and end |

## 6. Metrics (computed by `tools/analyze_log.py` / `tools/report.py`)
- **RMSE / max** of pitch, roll vs truth or ARCore (interpolated to est timestamps, angles compared with wrap-safe differences).
- **Yaw drift (°/min)**: slope of a linear fit to unwrapped yaw over a stated static/slow interval.
- **Return-to-start error (°)**: mean yaw over 1 s after the final Mark minus mean over 1 s after the first Mark.
- **Recovery time (s)**: OCCLUSION_STATE → OK until the next accepted vision window.
- **Accepted-window rate (%)**: accepted / total rows in `vision.csv`.
- **Vision processing time**: p50/p95 of `proc_ms`.
- **2σ coverage**: fraction of samples with |yaw error| ≤ 2·σ_yaw (ground-truth sessions only).
- **Latency (rough)**: film the phone screen and a physical pointer with a 240-fps phone/camera during a sharp stop; count frames between physical stop and HUD stop. Optional, report as an estimate.

## 7. Pass/fail summary
A scenario passes when its row in §4 meets the MEDIUM threshold. The project passes when PRD §7 is complete.
Failures are acceptable only with a written analysis in `docs/Results.md` (cause, evidence plot, what would fix it, e.g. STRETCH S2).
