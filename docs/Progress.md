# Progress

Last updated: 2026-10-31 (docs created)
Current task: 0.1

Status values: `TODO` · `IN_PROGRESS` · `DONE` · `BLOCKED` · `NEEDS_DEVICE_CHECK`
Line format: `- [ ] <id> <title> — status: <S> | date: <YYYY-MM-DD> | tests: <summary> | note: <one line>`
Tick `[x]` only when status is DONE. Tasks marked [DEVICE] need user confirmation before DONE.

## Phase 0 — Setup
- [ ] 0.1 Create Android project (USER + Claude) — status: TODO | date: — | tests: — | note: —
- [ ] 0.2 Add :core and :replay modules, JVM 17 — status: TODO | date: — | tests: — | note: —
- [ ] 0.3 Forbidden-API guard + verify.sh — status: TODO | date: — | tests: — | note: —
- [ ] 0.4 Manifest, permissions, app shell with modes [DEVICE] — status: TODO | date: — | tests: — | note: —
- [ ] Checkpoint 0 — status: TODO | date: — | results: —

## Phase 1 — Math core + ARCore baseline
- [ ] 1.1 Vec3, Mat3, AngleMath — status: TODO | date: — | tests: — | note: —
- [ ] 1.2 Quaternion — status: TODO | date: — | tests: — | note: —
- [ ] 1.3 Frames + AngleConverter — status: TODO | date: — | tests: — | note: —
- [ ] 1.4 ARCore session + background [DEVICE] — status: TODO | date: — | tests: — | note: —
- [ ] 1.5 ARCore pose → angles, reset, numeric HUD [DEVICE] — status: TODO | date: — | tests: — | note: —
- [ ] 1.6 HUD widgets — status: TODO | date: — | tests: — | note: —
- [ ] Checkpoint 1 — status: TODO | date: — | results: pitch@30°=? roll@20°=? yaw@90°=?

## Phase 2 — IMU-only custom estimator
- [ ] 2.1 Data model + configs — status: TODO | date: — | tests: — | note: —
- [ ] 2.2 ImuSampleAssembler + GyroHistory — status: TODO | date: — | tests: — | note: —
- [ ] 2.3 StaticDetector + AttitudeInitializer — status: TODO | date: — | tests: — | note: —
- [ ] 2.4 AttitudeFilter — status: TODO | date: — | tests: — | note: —
- [ ] 2.5 BiasKalman, YawUncertainty, ConfidenceModel — status: TODO | date: — | tests: — | note: —
- [ ] 2.6 ImuSimulator + OrientationEstimator (IMU-only) — status: TODO | date: — | tests: — | note: —
- [ ] 2.7 ImuSource + TimeSync [DEVICE] — status: TODO | date: — | tests: — | note: gyro Hz=? clock=?
- [ ] 2.8 EstimatorRunner + Custom IMU-only HUD [DEVICE] — status: TODO | date: — | tests: — | note: —
- [ ] 2.9 SessionLogger — status: TODO | date: — | tests: — | note: —
- [ ] 2.10 :replay CLI + analyze_log.py — status: TODO | date: — | tests: — | note: —
- [ ] Checkpoint 2 — status: TODO | date: — | results: S1 drift=? °/min, wedge errors=?, gyro-only return=?

## Phase 3 — Vision front-end
- [ ] 3.1 CameraModel — status: TODO | date: — | tests: — | note: —
- [ ] 3.2 SymmetricEigen + WahbaSolver — status: TODO | date: — | tests: — | note: —
- [ ] 3.3 RotationRansac + VisionQuality — status: TODO | date: — | tests: — | note: —
- [ ] 3.4 GridBucketer + GyroFlowPredictor — status: TODO | date: — | tests: — | note: —
- [ ] 3.5 OpenCV + CameraX analysis + camera controls [DEVICE] — status: TODO | date: — | tests: — | note: size=? fps=? tsSource=? sensorOrientation=?
- [ ] 3.6 FeatureTracker + instrumented test [DEVICE] — status: TODO | date: — | tests: — | note: —
- [ ] 3.7 VisionPipeline + overlay + vision_frames.csv [DEVICE] — status: TODO | date: — | tests: — | note: procMs p50=?
- [ ] 3.8 Axis / rCD sanity check [DEVICE] — status: TODO | date: — | tests: — | note: slopes x=? y=? z=?
- [ ] 3.9 (OPTIONAL) Essential-matrix cross-check — status: TODO | date: — | tests: — | note: —
- [ ] Checkpoint 3 — status: TODO | date: — | results: —

## Phase 4 — Vision fusion + obstacle robustness
- [ ] 4.1 AnchorWindow → VisionMeasurement — status: TODO | date: — | tests: — | note: —
- [ ] 4.2 Estimator vision update — status: TODO | date: — | tests: — | note: —
- [ ] 4.3 Wire vision → estimator, vision.csv, replay [DEVICE] — status: TODO | date: — | tests: — | note: —
- [ ] 4.4 Occlusion / low texture / fast rotation / ZRU agreement — status: TODO | date: — | tests: — | note: —
- [ ] 4.5 Full confidence + warnings in HUD — status: TODO | date: — | tests: — | note: —
- [ ] Checkpoint 4 — status: TODO | date: — | results: S1=? pan drift=? 360°=? people Δ=? cover recovery=?

## Phase 5 — Calibration
- [ ] 5.1 CalibrationData file + loader — status: TODO | date: — | tests: — | note: —
- [ ] 5.2 Intrinsics capture + script [DEVICE] — status: TODO | date: — | tests: — | note: RMS=? px
- [ ] 5.3 Allan variance — status: TODO | date: — | tests: — | note: N=? K=?
- [ ] 5.4 Time offset — status: TODO | date: — | tests: — | note: t_d=? ms, peak corr=?
- [ ] Checkpoint 5 — status: TODO | date: — | results: —

## Phase 6 — Benchmark mode
- [ ] 6.1 ArCoreFrameSource [DEVICE] — status: TODO | date: — | tests: — | note: —
- [ ] 6.2 Comparison panel + arcore.csv — status: TODO | date: — | tests: — | note: —
- [ ] 6.3 Metrics + report scripts — status: TODO | date: — | tests: — | note: —
- [ ] Checkpoint 6 — status: TODO | date: — | results: median |Δyaw|=?

## Phase 7 — Validation + polish
- [ ] 7.1 Settings screen — status: TODO | date: — | tests: — | note: —
- [ ] 7.2 Performance + 30-min stability [DEVICE] — status: TODO | date: — | tests: — | note: mem growth=? fps=?
- [ ] 7.3 Scenario matrix S1–S12 + Results.md [DEVICE] — status: TODO | date: — | tests: — | note: —
- [ ] 7.4 Final docs + audit — status: TODO | date: — | tests: — | note: —
- [ ] Final checkpoint (PRD §7) — status: TODO | date: — | results: —

## STRETCH (only after Phase 7)
- [ ] S1 Stochastic cloning / full ESKF — status: TODO
- [ ] S2 Keyframe relocalization — status: TODO
- [ ] S3 Semantic masking — status: TODO
- [ ] S4 Rolling-shutter modelling — status: TODO

## Session log (newest first, one line per session)
- 2026-10-31 — Docs set created (CLAUDE.md, README.md, docs/*). No code yet.
