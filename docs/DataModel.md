# Data Model — CamIMU-Orient

Names here are binding: code, CSV headers and docs must use exactly these names.
Units: rad, rad/s, m/s², ns (`Long`) internally. Degrees only in fields/columns ending `Deg` / `_deg`.
All `:core` classes live in `com.camimu.orient.core.<subpackage>`.

---

## 1. Math & model types (`core/math`, `core/model`)

| Type | Fields | Notes |
|---|---|---|
| `Vec3` | `x, y, z: Double` | immutable; `ZERO, EX, EY, EZ` |
| `Mat3` | row-major `DoubleArray(9)` (private) | immutable; `IDENTITY`, `of`, `fromCols`, `diag`, `rotX/Y/Z` |
| `Quaternion` | `w, x, y, z: Double` | Hamilton, `qAB` maps B→A; `IDENTITY`, `exp`, `log`, `fromMatrix`, `toMatrix` |
| `Px` | `u, v: Double` | pixel coords in the **raw** analysis image (u = column, v = row) |
| `AttitudeAngles` | `yawRad, pitchRad, rollRad: Double`, `isVertical: Boolean` | getters `yawDeg/pitchDeg/rollDeg` |
| `ImuSample` | `tNs: Long`, `gyroRaw: Vec3` (rad/s, D), `accel: Vec3` (m/s², D), `accelValid: Boolean`, `accelAgeNs: Long`, `osBias: Vec3?` (rad/s, from `TYPE_GYROSCOPE_UNCALIBRATED` values[3..5], log only) | one per gyro event |
| `GrayFrame` | `tCamNs: Long` (raw camera ts), `tNs: Long` (IMU clock incl. offsets), `width, height: Int`, `data: ByteArray` (w·h, tightly packed, **reused**) | raw sensor orientation |
| `CameraIntrinsics` | `width, height: Int`, `fx, fy, cx, cy: Double` (px), `k1, k2, p1, p2, k3: Double`, `source: IntrinsicsSource` | `@Serializable` |
| `IntrinsicsSource` | `CALIBRATED, FOV_ESTIMATE, ARCORE` | priority CALIBRATED > ARCORE (benchmark) > FOV_ESTIMATE |
| `Track` | `id: Long`, `anchor: Px?` (null if born after the anchor frame), `prev, cur: Px`, `age: Int` (frames) | |
| `VisionMeasurement` | `t1Ns, t2Ns: Long` (IMU clock), `rotD1D2: Quaternion` (D at t2 → D at t1), `quality: Double` (0..1), `nInliers: Int`, `inlierRatio: Double`, `spread: Double`, `rmsErrRad: Double`, `tCam2Ns: Long` | one per closed anchor window |
| `VisionRejectReason` | `NOT_INITIALIZED, NO_GYRO_COVERAGE, TOO_FAST, GATE, LOW_QUALITY, BLOCKED, NOT_FINITE` | |
| `VisionUpdateOutcome` | `accepted: Boolean`, `reason: VisionRejectReason?`, `mahalanobis2: Double`, `zBias: Vec3?` | |
| `OcclusionState` | `OK, LOW_TEXTURE, BLOCKED` | |
| `Confidence` | `HIGH, MEDIUM, LOW` | |
| `OrientationWarning` | `INITIALIZING, INIT_NOT_STATIC, CAMERA_BLOCKED, LOW_TEXTURE, MOVING_TOO_FAST, VERTICAL, ACCEL_DISTURBED, SENSOR_GAP, TRACKING_LOST` | |
| `OrientationOutput` | `tNs: Long`, `initialized: Boolean`, `qWD: Quaternion`, `angles: AttitudeAngles`, `gyroBias: Vec3` (rad/s), `sigmaYawRad: Double`, `driftSigmaRadS: Double`, `confidence: Confidence`, `warnings: Set<OrientationWarning>`, `lastVisionAcceptedNs: Long?` | immutable snapshot → StateFlow, est.csv |
| `ArTracking` | `TRACKING, PAUSED, STOPPED` | |
| `ArCoreReading` | `tNs: Long` (elapsedRealtime at read), `tArCoreNs: Long` (`frame.timestamp`), `qWO: Quaternion`, `tracking: ArTracking`, `failureReason: String`, `angles: AttitudeAngles` | |
| `RansacResult` | `q21: Quaternion?`, `inlierMask: BooleanArray`, `nInliers: Int`, `rmsErrRad: Double`, `iterations: Int` | |
| `QualityResult` | `quality: Double`, `valid: Boolean`, `inlierRatio: Double`, `spread: Double`, `reason: VisionRejectReason?` | |
| `InitResult` | `qWD`, `bias: Vec3`, `biasVar: Double`, `gLocal: Double`, `yawRefRad: Double`, `wasStatic: Boolean` | |

---

## 2. `EstimatorConfig` (`core/config`, `@Serializable`) — defaults and what each tunes

| Field | Default | Unit | Tunes / meaning |
|---|---|---|---|
| `imuSamplingPeriodUs` | 5000 | µs | requested sensor period (≈200 Hz) |
| `maxAccelAgeNs` | 50_000_000 | ns | accel older than this → `accelValid=false` |
| `staticInitSec` | 2.0 | s | stillness needed for init |
| `initTimeoutSec` | 5.0 | s | init anyway after this (warning INIT_NOT_STATIC) |
| `staticWindowSec` | 0.5 | s | ZRU window |
| `staticGyroStdThr` | 0.01 | rad/s | per-axis gyro std below → static |
| `staticAccelStdThr` | 0.08 | m/s² | per-axis accel std below → static |
| `zruMaxRateRadS` | 0.05 | rad/s | ‖mean ω − b‖ must be below this for ZRU (blocks slow-turn false statics) |
| `zruMaxVisionRateRadS` | 0.0035 | rad/s | (≈0.2°/s) if vision OK, it must agree the phone is still |
| `initBiasSigma` | 0.01 | rad/s | bias std if init wasn't static |
| `gyroNoiseDensity` (σ_g) | 1.5e-4 | rad/s/√Hz | white gyro noise. **Measure** (Task 5.3) |
| `biasRandomWalk` (σ_bw) | 2.0e-5 | rad/s/√s | bias wander. **Measure** (Task 5.3) |
| `kTilt` | 0.5 | 1/s | tilt correction gain, τ = 1/kTilt (2 s). ↑ faster settling, ↑ walking sensitivity |
| `accelGateMs2` | 1.0 | m/s² | `|‖a‖−g|` at which accel weight reaches 0 |
| `maxRateForTiltRadS` | 2.0 | rad/s | no tilt correction above this rate (centripetal accel) |
| `maxGapSec` | 0.05 | s | larger dt = SENSOR_GAP |
| `chi2Gate3` | 11.34 | — | χ²(3 dof, 99%) gate for bias updates |
| `sigmaRotRad` | 3.0e-4 | rad | vision window rotation noise at quality 1 |
| `sigmaTimeOffsetSec` | 0.002 | s | residual t_d uncertainty, scaled by rate |
| `visionAttitudeGain` | 0.5 | — (0..1] | fraction of window attitude error removed per accepted measurement |
| `maxRateForVisionRadS` | 3.0 | rad/s | (≈170°/s) reject vision windows faster than this |
| `verticalPitchDeg` | 80.0 | deg | |pitch| above → VERTICAL, yaw/roll frozen in HUD |
| `highVisionAgeSec` | 1.5 | s | HIGH requires accepted vision newer than this |
| `mediumVisionAgeSec` | 5.0 | s | |
| `highDriftDegPerMin` | 1.0 | °/min | HIGH requires drift σ below this |
| `mediumDriftDegPerMin` | 3.0 | °/min | |
| `blockedHoldSec` | 3.0 | s | BLOCKED longer than this → LOW |
| `uiPublishHz` | 60.0 | Hz | StateFlow publish rate |
| `estLogDecimation` | 1 | samples | write every Nth est row |
| `loggerFlushMs` | 1000 | ms | CSV flush period |

## 3. `VisionConfig` (`core/config`, `@Serializable`)

| Field | Default | Unit | Tunes / meaning |
|---|---|---|---|
| `analysisWidth` / `analysisHeight` | 640 / 480 | px | analysis stream size (4:3) |
| `gridCols` / `gridRows` | 8 / 6 | — | bucketing grid (obstacle layer 3) |
| `maxPerCell` | 4 | — | max tracks per cell (≤ 192 total) |
| `targetTracks` | 150 | — | desired live tracks |
| `replenishFraction` | 0.6 | — | detect new corners when tracks < 0.6·target |
| `detectMaxCorners` | 600 | — | candidates from `goodFeaturesToTrack` |
| `qualityLevel` | 0.01 | — | Shi–Tomasi relative quality |
| `minDistancePx` | 10.0 | px | min spacing between features |
| `blockSize` | 3 | px | corner detector block |
| `kltWinSize` | 21 | px | LK window |
| `kltMaxLevel` | 3 | — | pyramid levels |
| `fbThresholdPx` | 1.0 | px | forward–backward round-trip limit |
| `gateBasePx` | 4.0 | px | gyro gate base (obstacle layer 1) |
| `gateRateFactor` | 0.15 | — | gate grows with predicted motion: `+ factor·fx·angle` |
| `ransacThresholdPx` | 1.5 | px | inlier threshold (converted to rad by /fx) |
| `ransacMaxIter` | 100 | — | |
| `ransacConfidence` | 0.99 | — | for adaptive iteration count |
| `minInliers` | 15 | — | validity |
| `goodInliers` | 60 | — | quality saturates here |
| `minInlierRatio` | 0.4 | — | majority rule (inliers / tracks before gating) |
| `minSpread` | 0.15 | — | fraction of grid cells with inliers |
| `goodSpread` | 0.4 | — | quality saturates here |
| `windowMaxSec` | 1.0 | s | anchor window length |
| `windowMinSec` | 0.2 | s | shortest window that may emit |
| `windowMinSurvival` | 0.5 | — | close window when surviving anchor tracks fall below |
| `minTracked` | 20 | — | fewer → LOW_TEXTURE |
| `minImageStd` | 8.0 | gray levels | below → BLOCKED |
| `occlusionDebounceSec` | 0.5 | s | state must persist before switching |
| `debugOverlay` | true | — | publish FrameDebug |

## 4. `CalibrationData` (`calibration.json`, `@Serializable`)

```json
{
  "deviceModel": "UNKNOWN - verify",
  "intrinsics": { "width": 640, "height": 480, "fx": 0.0, "fy": 0.0, "cx": 320.0, "cy": 240.0,
                  "k1": 0.0, "k2": 0.0, "p1": 0.0, "p2": 0.0, "k3": 0.0, "source": "CALIBRATED" },
  "intrinsicsRmsPx": 0.0,
  "timeOffsetNs": 0,
  "arcoreTimeOffsetNs": 0,
  "gyroNoiseDensity": null,
  "biasRandomWalk": null,
  "sensorOrientationDegOverride": null
}
```
`intrinsics` may be `null` (→ FOV estimate). `null` noise values keep `EstimatorConfig` defaults. Location on phone:
`/sdcard/Android/data/com.camimu.orient/files/calibration.json`.

---

## 5. Session files (one directory per session)

Directory: `<externalFilesDir>/sessions/<yyyyMMdd_HHmmss>_<MODE>/`. All CSVs: comma-separated, `.` decimal
(`Locale.ROOT`), header row first, `\n` line endings. Empty optional value = empty field.

### `imu.csv` (every IMU sample)
`t_ns,gx,gy,gz,ax,ay,az,accel_valid,accel_age_ns,os_bgx,os_bgy,os_bgz`
(rad/s, m/s², `accel_valid` 0/1; `os_bg*` empty if `TYPE_GYROSCOPE` was used)

### `est.csv` (estimator output, every `estLogDecimation`-th sample)
`t_ns,initialized,qw,qx,qy,qz,yaw_deg,pitch_deg,roll_deg,is_vertical,bgx,bgy,bgz,sigma_yaw_deg,drift_sigma_deg_min,confidence,warnings`
(`q*` = qWD; `bg*` rad/s; `warnings` = names joined by `|`)

### `vision.csv` (one row per closed anchor window, accepted or not)
`t1_ns,t2_ns,t_cam2_ns,t_applied_ns,seq,rvx,rvy,rvz,quality,n_inliers,inlier_ratio,spread,rms_err_rad,accepted,reject_reason,mahal2,zbx,zby,zbz`
(`rv*` = `rotD1D2.log()` rad; `t_applied_ns` = `t_ns` of the last IMU sample processed before `onVision`; `seq` = global order of estimator actions shared with events.csv; both used by replay)

### `vision_frames.csv` (one row per processed frame)
`t_cam_prev_ns,t_cam_ns,t_ns,n_tracked,n_fb_ok,n_gated_out,n_inliers,rvx,rvy,rvz,valid,image_std,occlusion_state,proc_ms,ess_diff_deg`
(`rv*` = frame-pair rotation in device frame, D(t) → D(t_prev), rad; `ess_diff_deg` empty unless Task 3.9 enabled)

### `arcore.csv`
`t_ns,t_arcore_ns,qw,qx,qy,qz,yaw_deg,pitch_deg,roll_deg,tracking_state,failure_reason`
(`q*` = qWO in our world frame)

### `events.csv`
`t_ns,seq,event,detail` (`seq` = −1 for informational events) with events: `SESSION_START, SESSION_END, MODE, INIT_DONE, RESET_YAW, MARK, SENSOR_GAP, OCCLUSION_STATE, ADAPT, CONFIG_CHANGED`.

### `meta.json` (`SessionMeta`)
| Field | Example / meaning |
|---|---|
| `appVersion`, `coreVersion` | `"0.1.0"` |
| `deviceManufacturer`, `deviceModel`, `androidSdk` | from `Build` |
| `startWallClockIso` | `"2026-11-02T14:03:11Z"` |
| `mode` | `CUSTOM` |
| `estimatorConfig`, `visionConfig`, `calibration` | full JSON objects actually used |
| `registeredSensorTypes` | e.g. `[16, 1]` (16 = GYROSCOPE_UNCALIBRATED, 1 = ACCELEROMETER) |
| `sensorNames` | name + vendor per registered sensor |
| `sensorClock` | `ELAPSED_REALTIME` / `MONOTONIC` / `UNKNOWN` |
| `measuredGyroHz`, `measuredAccelHz` | from `RateMeter` at session end |
| `cameraTimestampSource` | `REALTIME` / `UNKNOWN` / `ARCORE` |
| `sensorOrientationDeg`, `rCD` | `90`, row-major 9 numbers |
| `analysisWidth`, `analysisHeight` | actual stream size |
| `droppedRows` | logger queue overflow count (should be 0) |

---

## 6. Key public APIs (summary; signatures in Implementation.md)

```kotlin
class OrientationEstimator(cfg: EstimatorConfig, gyroHistory: GyroHistory = GyroHistory()) {
    val isInitialized: Boolean; val bias: Vec3
    fun onImu(s: ImuSample): OrientationOutput
    fun onVision(m: VisionMeasurement): VisionUpdateOutcome
    fun setVisionState(state: OcclusionState, nowNs: Long)
    fun resetYaw()
}
class GyroHistory(capacity: Int = 512) {
    fun add(tNs: Long, omegaRaw: Vec3)
    fun integrate(t1Ns: Long, t2Ns: Long, bias: Vec3): Quaternion?
    fun integrateWithBiasJacobian(t1Ns: Long, t2Ns: Long, bias: Vec3): WithJacobian?   // rotD1D2 + H (3×3)
}
object AngleConverter { fun yawRaw(qWO: Quaternion): Double; fun toAngles(qWO: Quaternion, yawRefRad: Double, verticalPitchRad: Double): AttitudeAngles; fun fromAngles(yawRad: Double, pitchRad: Double, rollRad: Double): Quaternion }
object WahbaSolver { fun solve(b1: List<Vec3>, b2: List<Vec3>, weights: DoubleArray? = null): Quaternion? }
class RotationRansac(cfg: VisionConfig, seed: Long = 42L) { fun estimate(b1: List<Vec3>, b2: List<Vec3>, thresholdRad: Double): RansacResult }
```
