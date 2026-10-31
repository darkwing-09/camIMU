# PRD — CamIMU-Orient (MEDIUM build)

## 1. Summary
An Android app that shows the phone's **yaw, pitch and roll** in real time using only the
**gyroscope, accelerometer and back camera**. No magnetometer or compass. Yaw is **relative** to the start heading
(or the last reset). The app stays usable when people move through the view or the camera is blocked,
and it always shows how confident it is.

Primary audience: the developer (final-year B.Tech AI/ML project) and project examiners. Secondary: anyone needing a compass-free orientation reference.

## 2. Goals
- G1. Accurate pitch/roll (gravity-referenced) and drift-limited relative yaw, live at ≥ 30 Hz.
- G2. Our own estimator ("Custom"): gyro + accel complementary filter, Kalman gyro-bias estimation, and camera rotation measurements, robust to obstacles.
- G3. An ARCore-based reference ("ARCore" mode) and a side-by-side **Benchmark** mode.
- G4. Provably compass-free (source guard + runtime sensor list).
- G5. Measured, honest results: logs, replay, metrics, documented shortfalls.

## 3. Non-goals (MEDIUM scope)
- Absolute (north-referenced) heading. Physically impossible without a compass or another absolute reference.
- Position/translation estimation (no VIO/SLAM output).
- Landscape UI, tablets, iOS, front camera.
- NDK/C++ code.
- Keyframe relocalization / loop closure, semantic segmentation masks, rolling-shutter modelling, full ESKF with stochastic cloning (all **STRETCH**).
- Recording camera frames for offline vision replay (STRETCH).
- Play Store release.

## 4. Requirements

### 4.1 Functional
| ID | Requirement | Phase/Task |
|---|---|---|
| FR-1 | Output yaw/pitch/roll continuously; HUD ≥ 30 Hz, estimator at IMU rate (~200 Hz, device-dependent) | 2.8 |
| FR-2 | Use only gyroscope, accelerometer and camera in the Custom estimator. Forbidden APIs never referenced (Rules §1) | 0.3, all |
| FR-3 | HUD: numeric angles (1 decimal), artificial horizon, relative heading dial, confidence badge with σ_yaw | 1.5, 1.6, 4.5 |
| FR-4 | Moving obstacles must not cause large yaw errors (gyro gating, bucketing, RANSAC, majority rule, Kalman gate) | 3.3, 3.4, 4.2 |
| FR-5 | Blocked/featureless camera → gyro-only fallback, warning, confidence drops; recovers automatically | 4.4, 4.5 |
| FR-6 | "Reset yaw" sets the current heading to 0 | 1.5, 2.8 |
| FR-7 | Log raw and estimated data to CSV per session (+ meta.json) | 2.9, 4.3, 6.2 |
| FR-8 | Benchmark mode comparing Custom vs ARCore live and in logs | 6.1, 6.2 |
| FR-9 | 30-minute continuous run without crash or unbounded memory | 7.2 |
| FR-10 | Offline replay of logged sessions with any estimator config | 2.10, 4.3 |
| FR-11 | Calibration: intrinsics capture, time-offset and gyro-noise tools; per-device calibration file | Phase 5 |
| FR-12 | Vertical-pose handling: yaw/roll shown as "—" when |pitch| > 80° | 1.3, 1.5 |

### 4.2 Non-functional
| ID | Requirement |
|---|---|
| NFR-1 | Motion-to-display latency ≤ 50 ms (estimated: IMU latency + publish throttle + frame. Measured only roughly, see TestPlan §6) |
| NFR-2 | Vision thread median ≤ 20 ms/frame at 640×480 on a mid-range phone |
| NFR-3 | No wakelocks; screen kept on only while the app is in front; camera released on pause |
| NFR-4 | Estimator core deterministic and replayable (same input → same output within 1e-9) |
| NFR-5 | Clear in-app messages whenever accuracy is degraded |
| NFR-6 | All math in a pure-JVM module with unit tests runnable without a phone |

## 5. Accuracy targets (MEDIUM). These are targets, not guarantees
Results depend on the phone's sensors, scene texture and calibration. They are reported honestly per device.

| Metric | Normal textured scene | With moving people (1–3) | Notes |
|---|---|---|---|
| Pitch RMSE (static/slow, vs wedge) | ≤ 1° | ≤ 1.5° | |
| Roll RMSE (static/slow) | ≤ 1° | ≤ 1.5° | |
| Pitch/roll RMSE while walking | ≤ 2.5° | ≤ 2.5° | accel contaminated by motion |
| Yaw drift, static 5 min | ≤ 0.5°/min | — | ZRU + vision |
| Yaw drift, slow handheld motion | ≤ 2°/min | ≤ 3°/min | |
| 360° return-to-start yaw error | ≤ 4° | ≤ 6° | project.md's ≤ 2° needs keyframes (STRETCH S2) |
| Camera blocked 10 s | gyro fallback, LOW within 3 s, recovery < 2 s after uncover, no crash | | yaw error grows during blackout (measured, no target) |
| Custom vs ARCore, 2-min slow pan | median |Δyaw| ≤ 3°, pitch/roll ≤ 1.5° | | ARCore is a reference, not truth |
| Display rate | ≥ 30 Hz | ≥ 30 Hz | |
| Stability | 30 min, no crash, memory growth < 50 MB | | |
| Confidence honesty | ≥ 90% of |yaw error| ≤ 2σ_yaw in ground-truth tests | | σ_yaw is a conservative heuristic |

## 6. User stories
- US-1: As a user, I open the app, hold the phone still for 2 s, and see live yaw/pitch/roll with yaw starting at 0.
- US-2: As a user, I press "Reset yaw" to make my current direction 0.
- US-3: As a user, when someone walks in front of the camera, the yaw doesn't jump. The overlay shows their features in red.
- US-4: As a user, when I cover the lens, I see "Camera blocked: using gyro only" and the badge turns red. When I uncover it, it recovers.
- US-5: As the developer, I record a session, pull it to my laptop, replay it with different filter settings, and get plots and metrics.
- US-6: As the developer, I run Benchmark mode and see my estimator's error vs ARCore live.
- US-7: As an examiner, I can see proof the compass is never used (source audit + registered sensor list).
- US-8: As the developer, I calibrate my phone's camera and time offset with guided steps and scripts.

## 7. Definition of "project done"
- [ ] App runs on ≥ 1 phone (≥ 2 if available, S13).
- [ ] Compass provably unused: `docs/forbidden_api_audit.txt` + `registeredSensorTypes` in session meta.
- [ ] Phases 0–7 tasks DONE in Progress.md (STRETCH optional).
- [ ] Scenario matrix S1–S12 executed and logged; `docs/Results.md` written.
- [ ] Targets in §5 met, or shortfalls documented with analysis.
- [ ] Custom-vs-ARCore benchmark report produced (if the device supports ARCore).
- [ ] README, Architecture, Calibration guide (in Implementation.md) consistent with the code.
