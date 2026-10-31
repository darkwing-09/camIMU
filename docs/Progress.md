# Progress

Last updated: 2026-10-31 (implementation complete, awaiting device checks)
Current task: Device checklist (below). Needs the phone.

Status values: `TODO` · `IN_PROGRESS` · `DONE` · `BLOCKED` · `NEEDS_DEVICE_CHECK`
Line format: `- [ ] <id> <title> — status: <S> | date: <YYYY-MM-DD> | tests: <summary> | note: <one line>`
Tick `[x]` only when status is DONE. `NEEDS_DEVICE_CHECK` = code done and built; the user must confirm on the phone.

## Phase 0 — Setup
- [x] 0.1 Create Android project — status: DONE | date: 2026-10-31 | tests: assembleDebug OK | note: hand-written Gradle (ADR-018)
- [x] 0.2 :core and :replay modules, JVM 17 — status: DONE | date: 2026-10-31 | tests: core/replay build | note: —
- [x] 0.3 Forbidden-API guard + verify.sh — status: DONE | date: 2026-10-31 | tests: self-test OK, guard OK | note: —
- [ ] 0.4 Manifest, permissions, app shell — status: NEEDS_DEVICE_CHECK | date: 2026-10-31 | tests: lint clean | note: 6 modes on Home
- [ ] Checkpoint 0 — status: NEEDS_DEVICE_CHECK | results: —

## Phase 1 — Math core + ARCore baseline
- [x] 1.1 Vec3, Mat3, AngleMath — status: DONE | date: 2026-10-31 | tests: core:test | note: —
- [x] 1.2 Quaternion — status: DONE | date: 2026-10-31 | tests: core:test | note: incl. 180° fromMatrix cases
- [x] 1.3 Frames + AngleConverter — status: DONE | date: 2026-10-31 | tests: 9 groups pass | note: —
- [ ] 1.4 ARCore session + background — status: NEEDS_DEVICE_CHECK | date: 2026-10-31 | tests: builds | note: ArCoreController + BackgroundRenderer
- [ ] 1.5 ARCore pose → angles, reset, readout — status: NEEDS_DEVICE_CHECK | date: 2026-10-31 | tests: builds | note: displayOrientedPose
- [x] 1.6 HUD widgets — status: DONE | date: 2026-10-31 | tests: builds | note: horizon sign to be confirmed in Checkpoint 1
- [ ] Checkpoint 1 — status: NEEDS_DEVICE_CHECK | results: pitch@30°=? roll@20°=? yaw@90°=?

## Phase 2 — IMU-only custom estimator
- [x] 2.1 Data model + configs — status: DONE | date: 2026-10-31 | tests: ConfigJsonTest | note: —
- [x] 2.2 ImuSampleAssembler + GyroHistory — status: DONE | date: 2026-10-31 | tests: pass | note: + bias Jacobian (ADR-019)
- [x] 2.3 StaticDetector + AttitudeInitializer — status: DONE | date: 2026-10-31 | tests: pass | note: —
- [x] 2.4 AttitudeFilter — status: DONE | date: 2026-10-31 | tests: 5 cases pass | note: —
- [x] 2.5 BiasKalman, YawUncertainty, ConfidenceModel — status: DONE | date: 2026-10-31 | tests: pass | note: —
- [x] 2.6 ImuSimulator + OrientationEstimator — status: DONE | date: 2026-10-31 | tests: SY-1…SY-4 + reset pass | note: —
- [ ] 2.7 ImuSource + TimeSync — status: NEEDS_DEVICE_CHECK | date: 2026-10-31 | tests: builds | note: gyro Hz=? clock=?
- [ ] 2.8 EstimatorRunner + IMU-only HUD — status: NEEDS_DEVICE_CHECK | date: 2026-10-31 | tests: builds | note: —
- [ ] 2.9 SessionLogger — status: NEEDS_DEVICE_CHECK | date: 2026-10-31 | tests: CsvSchemaTest | note: verify adb pull path
- [x] 2.10 :replay CLI + analyze_log.py — status: DONE | date: 2026-10-31 | tests: ReplayDeterminismTest | note: + --synthesize
- [ ] Checkpoint 2 — status: NEEDS_DEVICE_CHECK | results: S1 drift=? °/min, wedge errors=?, gyro-only return=?

## Phase 3 — Vision front-end
- [x] 3.1 CameraModel — status: DONE | date: 2026-10-31 | tests: pass | note: iterative undistort to 1e-10
- [x] 3.2 SymmetricEigen + WahbaSolver — status: DONE | date: 2026-10-31 | tests: pass | note: —
- [x] 3.3 RotationRansac + VisionQuality — status: DONE | date: 2026-10-31 | tests: outliers / crowd / majority | note: —
- [x] 3.4 GridBucketer + GyroFlowPredictor — status: DONE | date: 2026-10-31 | tests: pass | note: —
- [ ] 3.5 OpenCV + CameraX analysis — status: NEEDS_DEVICE_CHECK | date: 2026-10-31 | tests: builds | note: size=? fps=? tsSource=?
- [ ] 3.6 FeatureTracker + instrumented test — status: NEEDS_DEVICE_CHECK | date: 2026-10-31 | tests: connectedDebugAndroidTest pending | note: —
- [ ] 3.7 VisionPipeline + overlay — status: NEEDS_DEVICE_CHECK | date: 2026-10-31 | tests: builds | note: procMs p50=?
- [ ] 3.8 Axis / rCD check — status: NEEDS_DEVICE_CHECK | date: 2026-10-31 | tests: axis_check.py OK on synthetic | note: slopes x=? y=? z=?
- [ ] 3.9 (OPTIONAL) Essential-matrix cross-check — status: TODO | note: skipped by design (optional)
- [ ] Checkpoint 3 — status: NEEDS_DEVICE_CHECK | results: —

## Phase 4 — Vision fusion + obstacle robustness
- [x] 4.1 AnchorWindow → VisionMeasurement — status: DONE | date: 2026-10-31 | tests: pass | note: —
- [x] 4.2 Estimator vision update — status: DONE | date: 2026-10-31 | tests: SY-5…SY-7 + quality/too-fast pass | note: Jacobian measurement
- [ ] 4.3 Wire vision → estimator, vision.csv, replay — status: NEEDS_DEVICE_CHECK | date: 2026-10-31 | tests: replay determinism | note: seq ordering (ADR-020)
- [x] 4.4 Occlusion / low texture / fast rotation / ZRU agreement — status: DONE | date: 2026-10-31 | tests: OcclusionDetectorTest | note: device check in CP4
- [x] 4.5 Confidence + warnings in HUD — status: DONE | date: 2026-10-31 | tests: truth table | note: —
- [ ] Checkpoint 4 — status: NEEDS_DEVICE_CHECK | results: S1=? pan drift=? 360°=? people Δ=? cover recovery=?

## Phase 5 — Calibration
- [x] 5.1 CalibrationData file + loader — status: DONE | date: 2026-10-31 | tests: ConfigJsonTest | note: —
- [ ] 5.2 Intrinsics capture + script — status: NEEDS_DEVICE_CHECK | date: 2026-10-31 | tests: --self-test OK (fx 519.7/520) | note: RMS=? px
- [ ] 5.3 Allan variance — status: NEEDS_DEVICE_CHECK | date: 2026-10-31 | tests: --self-test OK | note: N=? K=?
- [ ] 5.4 Time offset — status: NEEDS_DEVICE_CHECK | date: 2026-10-31 | tests: synthetic 11.5 ms vs 12 ms | note: t_d=? ms
- [ ] Checkpoint 5 — status: NEEDS_DEVICE_CHECK | results: —

## Phase 6 — Benchmark mode
- [ ] 6.1 ARCore CPU frames → custom pipeline — status: NEEDS_DEVICE_CHECK | date: 2026-10-31 | tests: builds | note: clock offset logged in meta notes
- [x] 6.2 Comparison panel + arcore.csv — status: DONE | date: 2026-10-31 | tests: builds | note: 30-s Δyaw trace
- [x] 6.3 Metrics + report scripts — status: DONE | date: 2026-10-31 | tests: synthetic metrics OK | note: —
- [ ] Checkpoint 6 — status: NEEDS_DEVICE_CHECK | results: median |Δyaw|=?

## Phase 7 — Validation + polish
- [x] 7.1 Settings screen — status: DONE | date: 2026-10-31 | tests: builds | note: applies next session
- [ ] 7.2 Performance + 30-min stability — status: NEEDS_DEVICE_CHECK | note: mem growth=? fps=?
- [ ] 7.3 Scenario matrix S1–S12 + Results.md — status: NEEDS_DEVICE_CHECK | note: tools/report.py ready
- [x] 7.4 Final docs + audit — status: DONE | date: 2026-10-31 | tests: verify.sh | note: docs/forbidden_api_audit.txt
- [ ] Final checkpoint (PRD §7) — status: NEEDS_DEVICE_CHECK | results: —

## STRETCH (only after Phase 7)
- [ ] S1 Stochastic cloning / full ESKF — status: TODO
- [ ] S2 Keyframe relocalization — status: TODO
- [ ] S3 Semantic masking — status: TODO
- [ ] S4 Rolling-shutter modelling — status: TODO

## Device checklist (first session with the phone, about 1 hour)
1. `adb devices` → device listed. `./gradlew :app:installDebug`. Open the app → 6 mode cards, portrait locked.
2. `./gradlew :app:connectedDebugAndroidTest` → FeatureTrackerTest passes (KLT recovers a 5/−3 px shift).
3. CUSTOM IMU-ONLY on a table: "HOLD STILL" ≈ 2 s, then gyro ≈ 200 Hz, clock ELAPSED_REALTIME. Record 5 min static (S1) → expect drift ≤ 0.5°/min.
4. CUSTOM at a textured room: vision ≈ 30 fps, proc ≤ 20 ms, tracks ≥ 80, windows mostly accepted, badge HIGH within ~20 s.
5. Axis check (Task 3.8): REC, rotate ~5× about each device axis, STOP, pull, `python3 tools/axis_check.py sessions/<s>` → slopes 0.9–1.1, positive.
   If a slope is negative: set `"sensorOrientationDegOverride"` in calibration.json (try 270) and recheck.
6. Calibrate (CALIBRATE screen, 20–30 checkerboard shots) → `tools/calibrate_intrinsics.py` → push calibration.json.
7. Time offset: REC 30 s of varied fast shaking → `tools/estimate_time_offset.py` → put `timeOffsetNs_total` into calibration.json.
8. ARCore + BENCHMARK (if supported): Checkpoint 1 numbers, then a 2-min slow pan → `analyze_log.py` vs_arcore.
9. Checkpoint 4 table (Implementation.md), then the scenario matrix → `tools/report.py sessions/* --out docs/Results.md`.

## Session log (newest first, one line per session)
- 2026-10-31 — Full implementation: core (estimator, vision geometry, replay IO), app (CameraX/OpenCV/ARCore/Compose HUD), replay CLI + synthesizer, Python tools. verify.sh green; synthetic end-to-end validated. No phone connected yet.
- 2026-10-31 — Docs set created (CLAUDE.md, README.md, docs/*). No code yet.
