# Architecture — CamIMU-Orient (MEDIUM build)

This is the source of truth for **components, threads, frames and formulas**. If code and this
file disagree, fix one of them explicitly. Don't let them drift silently.

---

## 1. Big picture

A **loosely-coupled, rotation-only visual-inertial estimator**:

- The **gyro** drives the orientation at the IMU rate (~200 Hz).
- The **accelerometer** corrects **pitch/roll** (gravity direction) with a complementary filter.
- The **camera** measures the rotation between an *anchor frame* and the current frame. That
  measurement (a) feeds a small **Kalman filter on gyro bias**, and (b) nudges the attitude
  toward the vision rotation. Better bias means less yaw drift.
- No translation is estimated. Yaw is relative to start/reset.

ARCore runs as a **separate reference mode** (Phase 1) and, in **Benchmark mode** (Phase 6),
supplies both its own pose and the camera images our pipeline consumes.

```
                      ┌──────────────────────────── :app (Android) ───────────────────────────┐
 Gyro (uncal) ──┐     │                                                                       │
 Accel ─────────┴────►│ ImuSource ──► ImuSampleAssembler ──► EstimatorRunner (IMU thread)     │
                      │   (HandlerThread "imu")                   │  owns OrientationEstimator│
                      │                                           │  writes GyroHistory       │
                      │                                           ▼                           │
 Camera (CameraX) ───►│ FrameSource ──► VisionPipeline (thread "vision")                      │
   or ARCore CPU img  │   GrayFrame       FeatureTracker (OpenCV KLT)                         │
                      │                   GyroFlowPredictor gating ◄── GyroHistory (read)     │
                      │                   RotationRansac (Wahba/Horn)                         │
                      │                   AnchorWindow ──► VisionMeasurement ──post──► IMU thr│
                      │                                                                       │
                      │ EstimatorRunner ──► StateFlow<OrientationOutput> ──► Compose HUD      │
                      │                └──► SessionLogger (thread "log") ──► CSV files        │
                      │ ArCoreSessionController (GL thread) ──► ArCoreReading ──► HUD/logger  │
                      └───────────────────────────────────────────────────────────────────────┘
                                         │ uses
                      ┌──────────────────▼────────────── :core (pure JVM) ────────────────────┐
                      │ math: Vec3 Mat3 Quaternion SymmetricEigen AngleMath                   │
                      │ frames: Frames AngleConverter                                         │
                      │ fusion: GyroHistory StaticDetector AttitudeInitializer AttitudeFilter │
                      │         BiasKalman YawUncertainty ConfidenceModel OrientationEstimator│
                      │ vision: CameraModel GridBucketer GyroFlowPredictor WahbaSolver        │
                      │         RotationRansac VisionQuality AnchorWindow OcclusionDetector   │
                      │ config/model/io/sim                                                   │
                      └───────────────────────────────────────────────────────────────────────┘
                      :replay (JVM CLI) ── reads session CSVs ── drives OrientationEstimator
```

---

## 2. Modules

| Module | Type | Depends on | Contains |
|---|---|---|---|
| `:core` | Kotlin/JVM library | kotlinx-serialization-json | All math, fusion, vision geometry, config, CSV schema, simulators. No Android, no OpenCV. |
| `:app` | Android application | `:core`, CameraX, OpenCV, ARCore, Compose | Sensors, camera, OpenCV wrappers, ARCore, threads, logging, UI. |
| `:replay` | Kotlin/JVM application | `:core` | CLI: replay a logged session through `OrientationEstimator` with any config. |

Why: everything that can be wrong mathematically sits in `:core`, which tests in seconds
on the laptop with no phone.

---

## 3. Threading model

| Thread | Created by | Runs | Must never |
|---|---|---|---|
| `main` (UI) | Android | Compose, lifecycle, permission flow | do math or I/O |
| `imu` | `HandlerThread` in `ImuSource` | `SensorEventListener` callbacks, `ImuSampleAssembler`, **all** `OrientationEstimator` calls (`onImu`, `onVision`, `resetYaw`) | block, allocate per sample in hot paths where avoidable |
| `vision` | single-thread `Executor` given to CameraX `ImageAnalysis` (or fed by ARCore) | Y-plane copy, OpenCV tracking, gating, RANSAC, window logic | touch estimator state directly |
| `log` | `SessionLogger` | dequeue rows, write CSV, flush every 1 s | be awaited by other threads |
| GL thread | `GLSurfaceView` | ARCore `session.update()`, background render, pose read | do vision work (hand frames to `vision`) |

**Replay ordering:** every estimator-affecting action posted to the imu thread (vision measurement, occlusion
change, yaw reset) gets a sequence number `seq` and is logged with `t_applied_ns` = last processed IMU sample.
Replay applies actions after that sample in `seq` order, which reproduces the device exactly.

**Single-writer rule:** `OrientationEstimator` is touched only on the `imu` thread. The vision
thread posts `VisionMeasurement` objects with `imuHandler.post { estimator.onVision(m) }`.
UI reads the latest immutable `OrientationOutput` from a `StateFlow` (thread-safe, conflated).
`EstimatorRunner` publishes at most `uiPublishHz` (60 Hz).

**Shared reads:** `GyroHistory` (raw gyro ring buffer, 2 s) is written by `imu` and read by
`vision` for gyro-predicted flow. Its methods are `@Synchronized`, and each call holds the lock for microseconds.
The current bias estimate is published through an `@Volatile` field (`EstimatorRunner.latestBias`).

**Back-pressure:** CameraX `STRATEGY_KEEP_ONLY_LATEST`. If the vision thread is busy, frames
are dropped, not queued. Logger queue is bounded (`ArrayBlockingQueue(20000)`). When it is full, rows are
dropped and counted (`droppedRows` in `meta.json`). The IMU thread never waits.

**Why vision latency is OK:** the vision measurement mainly corrects the **gyro bias**, which
changes slowly, so applying it ~20–40 ms late is harmless. The attitude nudge is a small
right-multiplied correction (§6.5). Applying it slightly late gives a second-order error that we ignore.

---

## 4. Coordinate frames & conventions (exact)

### 4.1 Frames

| Symbol | Frame | Axes |
|---|---|---|
| `W` | World | Right-handed, **Z up** (opposite gravity). X = horizontal heading of the camera at init. Y = Z × X. |
| `D` | Device (Android sensor frame) | x → right edge, y → top edge, z → out of the screen (toward user), in the phone's **natural (portrait)** orientation. Gyro/accel are reported in `D`. |
| `C` | Raw camera image frame (OpenCV) | x → image column direction, y → image row direction (down), z → optical axis (out of the back lens). Image = raw sensor readout (landscape on most phones). |
| `O` | Output camera frame (upright portrait) | x → device right, y → device down, z → optical axis forward. Used for yaw/pitch/roll. |
| `A` | ARCore world | Y up (gravity aligned), X/Z horizontal, arbitrary heading at session start. |
| `G` | ARCore camera (OpenGL) from `displayOrientedPose` | x right, y up, −z = viewing direction, oriented to the display (portrait). |

The app is **locked to portrait** (MEDIUM scope). That is what makes `O` a fixed function of `D`.

### 4.2 Rotation and quaternion conventions

- `rAB` (3×3) maps a vector expressed in frame B into frame A: `v_A = rAB · v_B`.
- `qAB` is the same rotation as a **Hamilton** unit quaternion stored `[w, x, y, z]`:
  `v_A = qAB ⊗ [0, v_B] ⊗ qAB*`. Composition: `qAC = qAB ⊗ qBC`.
- Quaternion product: `(w1, v1) ⊗ (w2, v2) = (w1 w2 − v1·v2,  w1 v2 + w2 v1 + v1 × v2)`.
- Rotate vector: `v' = v + 2w (u × v) + 2 u × (u × v)` where `q = (w, u)`.
- `Exp(φ)` (rotation vector φ ∈ ℝ³, rad → unit quaternion):
  `θ = ‖φ‖; if θ < 1e-8: q = normalize(1, φ/2) else q = (cos(θ/2), sin(θ/2) φ/θ)`.
- `Log(q)` (unit quaternion → rotation vector): if `w < 0` negate q;
  `s = ‖u‖; if s < 1e-12: φ = 2u else φ = 2·atan2(s, w) · u/s`.
- Matrix from quaternion (q = w,x,y,z):
  ```
  R = [[1−2(y²+z²), 2(xy−wz),   2(xz+wy)],
       [2(xy+wz),   1−2(x²+z²), 2(yz−wx)],
       [2(xz−wy),   2(yz+wx),   1−2(x²+y²)]]
  ```
- Matrix → quaternion: Shepperd's method (pick the largest of `trace, R00, R11, R22` to avoid
  dividing by ~0). Always normalize the result and make `w ≥ 0`.
- Body-rate integration (ω in `D`): `qWD(k+1) = qWD(k) ⊗ Exp(ω · dt)` (right-multiply because
  ω is measured in the body frame). Re-normalize after every step.

### 4.3 Fixed frame rotations

**Device → output camera** (portrait-locked, back camera):
```
rDO = diag(1, −1, −1)          qDO = [0, 1, 0, 0]   (180° about x_D)
qWO = qWD ⊗ qDO
```

**Device → raw camera** `rCD` depends on `SENSOR_ORIENTATION` (back camera):
```
SENSOR_ORIENTATION = 90 :  rCD = [[ 0,−1, 0],     (x_C = −y_D, y_C = −x_D, z_C = −z_D)
                                  [−1, 0, 0],
                                  [ 0, 0,−1]]
SENSOR_ORIENTATION = 270:  rCD = [[ 0, 1, 0],     (x_C =  y_D, y_C =  x_D, z_C = −z_D)
                                  [ 1, 0, 0],
                                  [ 0, 0,−1]]
SENSOR_ORIENTATION = 0  :  rCD = diag(1, −1, −1)
SENSOR_ORIENTATION = 180:  rCD = diag(−1, 1, −1)
```
Derivation: `SENSOR_ORIENTATION` is the clockwise angle the raw image must be rotated to be
upright in portrait. Each matrix has det = +1. Task 3.8 **verifies the sign at runtime** by
comparing gyro and vision rotation vectors. A small mounting misalignment is ignored at MEDIUM.

**ARCore → ours:**
```
rWA = [[1, 0, 0],      (x_W = x_A, y_W = −z_A, z_W = y_A)     qWA = [cos45°, sin45°, 0, 0]
       [0, 0,−1],
       [0, 1, 0]]
rGO = diag(1, −1, −1)                                          qGO = [0, 1, 0, 0]
qWO_arcore = qWA ⊗ qAG ⊗ qGO        where qAG = displayOrientedPose rotation
```
ARCore's `Pose.getRotationQuaternion(dest, 0)` writes **[x, y, z, w]**. Reorder to `[w, x, y, z]`.

### 4.4 Yaw / pitch / roll (the output)

From `R = rWO` (columns are the O axes expressed in W):
```
r = R[:,0]  (camera right)    d = R[:,1]  (camera down)    f = R[:,2]  (camera forward)

yawRaw = atan2(f_y, f_x)
yaw    = wrapPi(yawRaw − yawRef)          // (−π, π], 0 at init / reset
pitch  = asin(clamp(f_z, −1, 1))          // + = camera looking up
roll   = atan2(r_z, −d_z)                 // 0 = horizon level, + = right side of phone raised
isVertical = |pitch| > verticalPitchDeg (default 80°)   // yaw & roll unreliable; HUD freezes them
wrapPi(a) = a − 2π·floor((a + π) / (2π)) mapped so that the result is in (−π, π]
```
**Sign of yaw:** right-handed about +Z (up). **Turning left (counter-clockwise seen from
above) increases yaw.** The HUD dial shows this convention.

**Reference pose (all angles 0):** phone upright in portrait, back camera looking at the horizon along +X_W:
```
rWO0 = [r d f] = [[ 0, 0, 1],
                  [−1, 0, 0],
                  [ 0,−1, 0]]
```
**Inverse (for tests and the simulator):**
```
rWO(yaw, pitch, roll) = Rz(yaw) · Ry(−pitch) · rWO0 · Rz(−roll)
```
`Rz`, `Ry` are standard right-handed rotations about the world Z/Y axes (Ry applied on the
left) and about the O frame's z axis (`Rz(−roll)` applied on the right). Round-trip tested in Task 1.3.

---

## 5. Gyro/IMU path (Phase 2)

### 5.1 Sample assembly
Gyro and accel events arrive separately. `ImuSampleAssembler` keeps the latest accel event and
emits one `ImuSample` per **gyro** event: `ImuSample(tNs = gyro.t, gyro = raw ω, accel = latest a,
accelAgeNs)`. If `accelAgeNs > 50 ms` the sample is flagged `accelValid = false`.

### 5.2 Initialization (`AttitudeInitializer`)
Hold still for `staticInitSec` (2 s). `StaticDetector` must report static for the whole window.
```
b0      = mean(ω_raw)                      // initial gyro bias (rad/s)
g_local = ‖mean(a)‖                        // local gravity incl. accel scale error (m/s²)
z_D     = mean(a)/‖mean(a)‖                // world up expressed in D
f_D     = (0, 0, −1)                       // camera forward in D
x_D     = normalize(f_D − (f_D·z_D) z_D)   // horizontal heading of the camera
          (if ‖·‖ < 0.1, i.e. camera nearly vertical, use x_D = normalize(e_y − (e_y·z_D) z_D))
y_D     = z_D × x_D
rDW     = [x_D y_D z_D] (columns)   →   rWD = rDWᵀ,  qWD = fromMatrix(rWD)
yawRef  = yawRaw(qWD ⊗ qDO)  (= 0 by construction unless vertical)
```
If not static within `initTimeoutSec` (5 s): init from the latest accel, `b0 = 0`,
bias variance = `initBiasSigma²`, and raise warning `INIT_NOT_STATIC`.

### 5.3 Propagation + tilt correction (`AttitudeFilter`, Mahony-style, proportional only)
For consecutive samples k−1, k:
```
dt     = (t_k − t_{k−1}) · 1e-9                      // seconds; drop sample if dt ≤ 0
ω      = ½(ω_raw,k−1 + ω_raw,k) − b                  // midpoint, bias-corrected (rad/s)
ĝ_D    = rWDᵀ · (0,0,1)                              // predicted "up" in D
â      = a_k / ‖a_k‖                                 // measured "up" (accelerometer reads +g up at rest)
w_a    = clamp(1 − |‖a_k‖ − g_local| / accelGate, 0, 1)
         · (‖ω‖ < maxRateForTilt ? 1 : 0) · (accelValid ? 1 : 0)
e_tilt = â × ĝ_D                                     // ⟂ to gravity → has no yaw component
ω_c    = ω + kTilt · w_a · e_tilt
qWD    ← normalize(qWD ⊗ Exp(ω_c · dt))
```
Why it works: the predicted-up vector moves as `dĝ/dt = ĝ × ω_c`, and the extra term pulls `ĝ`
toward `â` with time constant `τ = 1/kTilt` (default 2 s). Because `e_tilt ⟂ ĝ`, gravity
**cannot change yaw**, as physics says.

Gaps: if `dt > maxGapSec` (0.05 s), integrate anyway (zero-order hold on ω), emit warning
`SENSOR_GAP` for 1 s and add `σ_g² · dt` extra to the yaw variance (§5.5).

### 5.4 Gyro bias Kalman filter (`BiasKalman`, simplified: 3 independent scalar filters)
State `b ∈ ℝ³` (rad/s, device frame), diagonal variance `P = diag(P_x, P_y, P_z)` (rad²/s²).
```
predict(dt):          P_i ← P_i + σ_bw² · dt                       (bias random walk)
update(z, R_i):       d² = Σ_i (z_i − b_i)² / (P_i + R_i)
                      if d² > chi2Gate3 (11.34 = χ²₃ at 99%) → reject
                      K_i = P_i / (P_i + R_i);  b_i ← b_i + K_i (z_i − b_i);  P_i ← (1 − K_i) P_i
```
Two measurement sources:
- **ZRU (zero-rate update):** when `StaticDetector` reports static for `staticWindowSec` (0.5 s)
  and (if vision is valid) vision rotation rate < `zruMaxVisionRateRadS`:
  `z = mean(ω_raw over window)`, `R_i = σ_g² / T_window`.
- **Vision:** §6.5.

Initial: `b = b0`, `P_i = σ_g² / staticInitSec` (or `initBiasSigma²` if init wasn't static).

### 5.5 Yaw uncertainty (`YawUncertainty`, a conservative heuristic, not a full covariance)
```
P_bv   = Σ_i (ĝ_D,i)² · P_i                         // bias variance about the vertical
each IMU step:  σ²_yaw ← σ²_yaw + σ_g² · dt + P_bv · (2·t_c + dt) · dt
                t_c ← t_c + dt                       // time since last accepted vision update
on accepted vision update: t_c ← 0;  σ²_yaw ← σ²_yaw + σ²_vis,window
on resetYaw:  σ²_yaw ← 0
driftSigma = sqrt(P_bv)       (rad/s; shown as °/min = · 180/π · 60)
```
The `(2t_c + dt)·dt` term integrates to `P_bv · t_c²`. That is the growth of yaw error from a
constant bias error during a camera blackout.

---

## 6. Vision path (Phases 3–4)

### 6.1 Frames in, gray only
`GrayFrame(tNs, width, height, data: ByteArray)` (tightly packed Y plane, raw sensor orientation).
Analysis size 640×480 (`VisionConfig.analysisWidth/Height`). Frame time on the IMU clock:
`t = tCam + timeOffsetNs` (`timeOffsetNs` from calibration, default 0; see Task 5.4).

### 6.2 Camera model (`CameraModel`)
Intrinsics `fx, fy, cx, cy` and Brown–Conrady distortion `k1, k2, p1, p2, k3` (at analysis resolution).
```
pixel → normalized (distorted):   x_d = (u − cx)/fx,  y_d = (v − cy)/fy
undistort (fixed-point, 5 iterations, start x = x_d, y = y_d):
   r² = x² + y²;  radial = 1 + k1 r² + k2 r⁴ + k3 r⁶
   dx = 2 p1 x y + p2 (r² + 2x²);   dy = p1 (r² + 2y²) + 2 p2 x y
   x = (x_d − dx)/radial;  y = (y_d − dy)/radial
bearing b = normalize(x, y, 1)               // unit vector in C
project (no distortion, for gating): u = fx·b_x/b_z + cx, v = fy·b_y/b_z + cy
```

### 6.3 Tracking (`FeatureTracker` in :app, `GridBucketer` in :core)
1. Detect Shi–Tomasi corners (`Imgproc.goodFeaturesToTrack`, up to `detectMaxCorners`) with a
   mask that blocks circles of radius `minDistancePx` around existing tracks.
2. `GridBucketer`: split the image into `gridCols × gridRows` cells (8×6). Keep at most
   `maxPerCell` (4) new corners per cell, best-quality first, counting existing tracks.
3. Track prev → cur with pyramidal LK (`Video.calcOpticalFlowPyrLK`), initial guess = gyro-predicted
   position (`OPTFLOW_USE_INITIAL_FLOW`).
4. Forward–backward check: track cur → prev. Drop if round-trip error > `fbThresholdPx` (1.0).
5. Replenish when `nTracks < replenishFraction · targetTracks`.
Each `Track` stores `id`, `anchorPx` (position in the anchor frame, §6.6), `prevPx`, `curPx`, `age`.

### 6.4 Gyro-guided gating (`GyroFlowPredictor`), obstacle layer 1
Gyro rotation between the two frames (device frame, current bias):
`rotD1D2_gyro = GyroHistory.integrate(t1, t2, bias)` (D2 → D1).
```
rC1C2 = rCD · rotD1D2 · rCDᵀ           // camera frame 2 → camera frame 1
b2_pred = rC1C2ᵀ · b1                    // a static far point seen from frame 2
p2_pred = project(b2_pred)
gatePx  = gateBasePx + gateRateFactor · fx · ‖Log(rotD1D2)‖
reject track if ‖p2_meas − p2_pred‖ > gatePx      (moving objects, near objects with parallax, bad tracks)
```
The same `p2_pred` is the KLT initial guess (step 3), which helps on fast pans.

### 6.5 Rotation from bearings (`WahbaSolver`, `RotationRansac`)
**Problem (Wahba):** for static points, `b2 ≈ R21 · b1` (R21 maps frame-1 bearings to frame-2).
Find R21 minimizing `Σ w_i ‖b2_i − R21 b1_i‖²`.

**Horn's quaternion solution:**
```
S = Σ w_i b1_i b2_iᵀ        (3×3, S_ab = Σ w b1_a b2_b)
N = [[Sxx+Syy+Szz,  Syz−Szy,      Szx−Sxz,      Sxy−Syx    ],
     [Syz−Szy,      Sxx−Syy−Szz,  Sxy+Syx,      Szx+Sxz    ],
     [Szx−Sxz,      Sxy+Syx,     −Sxx+Syy−Szz,  Syz+Szy    ],
     [Sxy−Syx,      Szx+Sxz,      Syz+Szy,     −Sxx−Syy+Szz]]
q21 = eigenvector of N with the largest eigenvalue (Jacobi, SymmetricEigen), normalized, w ≥ 0
```
Equivalent SVD form (for cross-checks): `H = Σ b1 b2ᵀ = U Σ Vᵀ`, `R21 = V diag(1,1,det(VUᵀ)) Uᵀ`.

**RANSAC:** minimal sample = 2 correspondences (reject pairs whose `b1` are < 2° apart).
```
angular residual  ε_i = acos(clamp(b2_i · (R21 b1_i), −1, 1))
inlier if ε_i < θ_thr,  θ_thr = ransacThresholdPx / fx
iterations N = min(ransacMaxIter, ceil(log(1−p) / log(1 − w²))), w = best inlier ratio so far, p = 0.99
refine: Horn on all inliers (weights 1) → R21;  rmsErrRad = sqrt(mean ε² over inliers)
spread = (#grid cells containing ≥1 inlier) / (gridCols·gridRows)
```
Use a seeded `java.util.Random(seed)` so tests are deterministic.

**Quality score** (`VisionQuality`), obstacle layers 2, 3 and 6:
```
inlierRatio = nInliers / nTrackedBeforeGating           // majority rule: static background must dominate
quality = clamp(nInliers/goodInliers, 0, 1) · clamp((inlierRatio − minInlierRatio)/(1 − minInlierRatio), 0, 1)
          · clamp(spread/goodSpread, 0, 1)
valid   = nInliers ≥ minInliers && inlierRatio ≥ minInlierRatio && spread ≥ minSpread && quality > 0
```

### 6.6 Anchor window → measurement (`AnchorWindow`)
Frame-to-frame rotations are too short (33 ms) to see a small bias. So we measure the rotation
from an **anchor frame** to the current frame over up to `windowMaxSec` (1.0 s):
- The anchor is set when tracking (re)starts. All tracks store their anchor-frame pixel.
- Close the window when `elapsed ≥ windowMaxSec`, or when `survivingAnchorTracks / anchorTracks < windowMinSurvival` (0.5)
  and `elapsed ≥ windowMinSec` (0.2 s). On close: RANSAC on (anchorPx → curPx) of surviving tracks
  → `R21`. If valid, emit **one** `VisionMeasurement`. Then the current frame becomes the new anchor
  (all current tracks re-anchor).
- Convert to device frame: `rotD1D2_vis = rCDᵀ · R21ᵀ · rCD` (D2 → D1).

### 6.7 Vision update in the estimator (`OrientationEstimator.onVision`)
```
Δt          = (t2 − t1)·1e-9
(rotRaw, H) = GyroHistory.integrateWithBiasJacobian(t1, t2, bias = 0)   // raw gyro D2 → D1, and
              H = Σ_k R(D_k→D_2)ᵀ·dt_k  (3×3)                              // bias Jacobian
rotCorr     = GyroHistory.integrate(t1, t2, bias = b)        // bias-corrected
e           = Log(rotVisᵀ · rotRaw) ≈ H · b                    // first order in b, exact w.r.t. rotation in the window
z_b         = H⁻¹ · e                                          // gyro-bias observation (rad/s, D frame)
ω̄           = ‖Log(rotRaw)‖ / Δt                               // mean rotation rate
σ²_vis      = (sigmaRotRad² + (ω̄ · sigmaTimeOffsetSec)²) / quality
R_i         = σ²_vis · (H⁻¹H⁻ᵀ)_ii                             // diagonal kept (diagonal bias filter)
reject if ω̄ > maxRateForVisionRadS, or BiasKalman gate fails (d² > 11.34)
else: BiasKalman.update(z_b, R)
      e = Log(rotVisᵀ · rotCorr)                               // remaining attitude error over window (D2 frame)
      qWD ← normalize(qWD ⊗ Exp(−visionAttitudeGain · e))
      YawUncertainty.onVisionAccepted(σ²_vis)
```
Why `H`: with `ω_raw = ω_true + b`, each step is `Exp(ω dt)·Exp(b dt)`; moving every `Exp(b dt)` to the right
end rotates it by the remaining rotation of the window, so `rotRaw ≈ rotTrue · Exp(H·b)`. For small rotations
`H ≈ Δt·I` (the naive `e/Δt`); during handheld motion of ~1 rad per window the naive form is wrong by several
times the noise and was rejected by the gate. Synthetic check: 41/58 → 58/58 windows accepted after this fix.

### 6.8 Occlusion / texture (`OcclusionDetector`), obstacle layer 9
Inputs per frame: `nTracked`, image gray-level std (`Core.meanStdDev`). States:
`OK` → `LOW_TEXTURE` (nTracked < minTracked for > 0.5 s, image std ≥ minImageStd) /
`BLOCKED` (image std < minImageStd, or nTracked < 5) for > 0.5 s. While not OK: no vision
measurements, window reset, warning shown. After `blockedHoldSec` (3 s) → confidence LOW.

---

## 7. Confidence (`ConfidenceModel`)
```
visionAge = now − lastAcceptedVisionNs   (∞ if never)
driftDegPerMin = driftSigma · 180/π · 60
HIGH   if visionAge < 1.5 s and driftDegPerMin < 1.0 and no blocking warning
MEDIUM if visionAge < 5 s or driftDegPerMin < 3.0
LOW    otherwise, or BLOCKED for > blockedHoldSec, or isVertical, or INITIALIZING
```
`σ_yaw` (deg) is always shown next to the badge. Honesty principle: never show HIGH without
recent accepted vision.

---

## 8. ARCore reference path (Phases 1 and 6)
```
GLSurfaceView.onDrawFrame:
  session.setCameraTextureName(tex) (once);  frame = session.update()
  draw camera background (BackgroundRenderer, OES texture)
  cam = frame.camera;  state = cam.trackingState
  if state == TRACKING: qAG = cam.displayOrientedPose rotation → qWO = qWA ⊗ qAG ⊗ qGO
  publish ArCoreReading(tNs, qWO, state, failureReason)
Benchmark mode only: frame.acquireCameraImage() → copy Y plane → GrayFrame → vision executor (drop if busy)
```
ARCore yaw uses its own `yawRefArcore`, reset at the same moment as ours.
ARCore is a **reference, not ground truth**. It is a black box and we do not audit its
internal sensor use. The no-compass rule applies to *our* estimator.

---

## 9. Data flow per mode

| Mode | Camera owner | Estimators running | Logged files |
|---|---|---|---|
| ARCORE | ARCore | ARCore only | `arcore.csv`, `events.csv`, `meta.json` |
| CUSTOM | CameraX | ours (IMU + vision) | `imu.csv`, `est.csv`, `vision.csv`, `vision_frames.csv`, `events.csv`, `meta.json` |
| CUSTOM_IMU_ONLY | none (CameraX preview off) | ours, IMU only | `imu.csv`, `est.csv`, `events.csv`, `meta.json` |
| BENCHMARK | ARCore (CPU images shared) | both | all of the above |
| CALIBRATE | CameraX | none (capture PNGs / record pans) | `calib_*.png`, `imu.csv`, `vision_frames.csv` |

---

## 10. Performance budget (vision thread, 640×480, mid-range phone)
| Stage | Budget |
|---|---|
| Y-plane copy → `Mat` | ≤ 2 ms |
| detect (only when replenishing) + KLT fwd+bwd | ≤ 10 ms |
| gating + RANSAC (Kotlin) + quality | ≤ 3 ms |
| **total** | **≤ 20 ms median** (frame period 33 ms) |
IMU step (`onImu`) ≤ 0.05 ms. If vision median > 20 ms, reduce `maxPerCell` first, then analysis size.
