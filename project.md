# CamIMU-Orient — Real-Time Yaw / Pitch / Roll from Camera + Internal IMU (No Compass)

> **One-line summary:** A mobile app that estimates the phone's 3D orientation (yaw, pitch, roll) in real time, using **only** the built-in IMU (gyroscope + accelerometer) and the camera, with **no magnetometer/compass**, and stays accurate when obstacles (people, vehicles, blocked view) are in the scene.

---

## Table of Contents

1. [Project Overview](#1-project-overview)
2. [Problem Statement & Requirements](#2-problem-statement--requirements)
3. [Interpretation of the Brief & Assumptions](#3-interpretation-of-the-brief--assumptions)
4. [Fundamental Physics: What Is and Isn't Observable](#4-fundamental-physics-what-is-and-isnt-observable)
5. [Coordinate Frames & Angle Conventions](#5-coordinate-frames--angle-conventions)
6. [System Architecture](#6-system-architecture)
7. [Sensors & Data Acquisition](#7-sensors--data-acquisition)
8. [Algorithms in Detail](#8-algorithms-in-detail)
9. [Obstacle & Dynamic-Object Handling](#9-obstacle--dynamic-object-handling)
10. [Confidence, Failure Modes & Fallbacks](#10-confidence-failure-modes--fallbacks)
11. [Implementation Paths (A: ARCore, B: Custom)](#11-implementation-paths)
12. [Tech Stack](#12-tech-stack)
13. [Project Structure](#13-project-structure)
14. [Module Specifications](#14-module-specifications)
15. [User Interface (HUD)](#15-user-interface-hud)
16. [Calibration](#16-calibration)
17. [Data Logging & Tooling](#17-data-logging--tooling)
18. [Testing & Validation Plan](#18-testing--validation-plan)
19. [Performance Budget](#19-performance-budget)
20. [Accuracy Targets & Acceptance Criteria](#20-accuracy-targets--acceptance-criteria)
21. [Risks & Mitigations](#21-risks--mitigations)
22. [Roadmap & Milestones](#22-roadmap--milestones)
23. [Future Extensions](#23-future-extensions)
24. [Glossary](#24-glossary)
25. [References](#25-references)

---

## 1. Project Overview

**CamIMU-Orient** is a smartphone application that continuously computes the device's **attitude**: **yaw**, **pitch**, and **roll**, in real time.

The defining constraints:

| Constraint | Detail |
|---|---|
| Sensors allowed | Internal **IMU** (3-axis gyroscope, 3-axis accelerometer) and the **camera** |
| Sensor forbidden | **Magnetometer / compass** (no magnetic heading) |
| Environment | Real-world scenes with **obstacles**: moving people, vehicles, objects partially or fully blocking the view |
| Platform | Mobile app (Android primary; iOS as a secondary option) |
| Quality bar | Well functional, stable, ideal/accurate output, with a visible confidence indicator |

### Why this is non-trivial

- A gyroscope alone gives smooth, fast orientation but **drifts**.
- An accelerometer fixes drift in **pitch and roll** (gravity direction) but says **nothing about yaw**.
- A compass would normally fix yaw. It's banned here, so **the camera must supply the yaw reference**.
- The camera sees the real world, so moving obstacles, blocked views, blur and low light can corrupt visual estimates. The system must detect and reject those.

---

## 2. Problem Statement & Requirements

### 2.1 Problem statement

> Compute yaw, pitch and roll of a handheld/mobile device in real time, accurately, using only the internal IMU and the camera, without a compass, while remaining robust to obstacles in the camera's field of view.

### 2.2 Functional requirements

| ID | Requirement |
|---|---|
| FR-1 | Output yaw, pitch, roll continuously at **≥ 30 Hz** (display) and ideally the IMU rate internally (100–200 Hz). |
| FR-2 | Use **only** gyroscope, accelerometer and camera. The magnetometer, `TYPE_ROTATION_VECTOR`, and any compass API must **not** be used. |
| FR-3 | Show a live HUD with numeric angles, an artificial horizon, a heading dial, and a confidence indicator. |
| FR-4 | Handle moving obstacles in view without large yaw errors (outlier rejection). |
| FR-5 | When the camera is blocked/featureless, **gracefully degrade** to gyro-only and show reduced confidence. |
| FR-6 | Provide a **"Reset yaw to 0"** control (set the current heading as the reference). |
| FR-7 | Log all raw and estimated data to CSV for offline analysis. |
| FR-8 | Provide a **benchmark mode** comparing the custom estimator against a reference (ARCore pose and/or ground truth). |
| FR-9 | Run continuously for ≥ 30 minutes without crash, thermal shutdown, or unbounded drift growth. |

### 2.3 Non-functional requirements

| ID | Requirement |
|---|---|
| NFR-1 | End-to-end latency (motion → display) ≤ 50 ms. |
| NFR-2 | CPU usage reasonable on a mid-range phone (< ~40% sustained on the vision thread). |
| NFR-3 | Battery-conscious (no unnecessary full-res processing, no wakelock leaks). |
| NFR-4 | Deterministic, testable estimator core (pure functions where possible, replayable from logs). |
| NFR-5 | Clear in-app messaging when accuracy is degraded. |

---

## 3. Interpretation of the Brief & Assumptions

The original brief reads: *"use – Internal mm u, Only cam; restriction – No compass."*

**Assumptions made explicit:**

1. **"Internal mm u" = the phone's internal IMU** (Inertial Measurement Unit: gyroscope + accelerometer). If "mm u" meant something else (e.g., an external module), the architecture still holds but the sensor layer changes.
2. **"Only cam"** = the camera is the only additional sensor. No GPS, Wi-Fi, barometer, or LiDAR in the core estimator.
3. **"No compass"** = no magnetometer and no OS-level fused heading that secretly uses it.
4. **Yaw is relative to the start heading.** Without a compass there is no absolute north (see §4). If true north is required, a one-time anchor is needed (user alignment, or GNSS course-over-ground while moving). This is a physical limit, not an implementation shortcoming.
5. Primary target is **Android** (broad IMU/camera APIs, ARCore); iOS (CoreMotion + ARKit) is a later port.
6. The device is **handheld** with mostly rotational motion plus modest translation (walking, panning). Fast translation (vehicle) is a stretch scenario.

---

## 4. Fundamental Physics: What Is and Isn't Observable

| Angle | Observable from… | Absolute? | Typical achievable accuracy |
|---|---|---|---|
| **Pitch** | Gravity direction (accelerometer) + gyro | **Yes** (relative to horizon) | ~0.5–1° |
| **Roll** | Gravity direction (accelerometer) + gyro | **Yes** (relative to horizon) | ~0.5–1° |
| **Yaw** | Gyro integration, corrected by **camera** | **No**, relative to the initial heading | Drift-limited, ~1–2°/min target |

**Why yaw is special:** gravity is a vector pointing down. Rotating the phone about the vertical axis (yaw) does not change what the accelerometer reads, so yaw is *unobservable* from gravity. Only a horizontal reference (magnetic north, or visual landmarks) pins it. With no compass, the **camera's view of the static world is the yaw reference**.

**Gyro error model:**

- Measured rate: `ω_meas = ω_true + b_g + n_g`, where `b_g` = slowly varying bias, `n_g` = white noise.
- Integrating `b_g` gives linear angle drift. Uncorrected phone MEMS gyros typically drift on the order of degrees per minute; estimating the bias online (with static periods and camera updates) reduces this substantially.
- Integrating noise gives a random walk (Angle Random Walk).

**Consequence for design:** the estimator must (a) estimate and subtract gyro bias online, (b) use gravity for pitch/roll, (c) use camera-derived rotation for yaw/bias observability, (d) accept that yaw is relative.

---

## 5. Coordinate Frames & Angle Conventions

Defining conventions precisely avoids the #1 source of bugs in orientation apps.

### 5.1 Frames

| Frame | Definition |
|---|---|
| **World (W)** | Right-handed. **Z up** (opposite gravity). X = initial horizontal heading of the camera's forward axis at start (or after "Reset yaw"). Y = Z × X. |
| **Device (D)** | Android device frame: **x right, y up (toward top edge), z out of the screen** (toward the user). |
| **Camera (C)** | OpenCV convention: **x right, y down, z forward** (out of the lens). The back camera looks along **−z_D**. |
| **IMU (I)** | Same as Device on Android (sensor axes are reported in the device frame). |

The fixed rotation `R_CD` (device → camera) is mostly a known axis flip for the back camera (plus a small mounting misalignment, see §16 calibration).

### 5.2 Orientation representation

- **Internal state: unit quaternion** `q_WD` (or `q_WC`). Quaternions avoid gimbal lock and integrate cleanly.
- **Display only:** convert to yaw/pitch/roll.

### 5.3 Yaw / pitch / roll definition (camera-axis based)

To keep outputs intuitive and unambiguous, angles are defined from the **camera's optical axis** in the world frame.

Let `R = R_WC` (camera → world). Define:

```
f = R · [0, 0, 1]ᵀ     // camera forward (optical axis) in world
r = R · [1, 0, 0]ᵀ     // camera right in world
d = R · [0, 1, 0]ᵀ     // camera down in world
```

Then:

```
yaw   = atan2(f_y, f_x) − yaw_ref        // heading of optical axis, 0 at start/reset, wrapped to (−180°, 180°]
pitch = asin(f_z)                        // elevation of optical axis above the horizon; + = looking up
roll  = atan2(r_z, −d_z)                 // rotation about the optical axis; 0 when the horizon is level; + = right side raised
```

**Singularity:** when the camera looks straight up/down (`|pitch| → 90°`), yaw and roll are not independently defined. The app must freeze/flag yaw there, and the quaternion remains valid regardless.

> A classic aerospace ZYX Euler decomposition of `q_WD` is also provided as an alternative "device-frame" output (useful for comparing with other tools). Both modes must be selectable in settings.

---

## 6. System Architecture

### 6.1 High-level block diagram

```
          ┌────────────────────────── MOBILE APP ───────────────────────────┐
          │                                                                 │
 Gyro ───►│  IMU Thread (200 Hz)          Camera Thread (30 fps)            │
 Accel ──►│  ├─ timestamp + buffer        ├─ capture Y plane (grayscale)    │
          │  ├─ bias-corrected gyro       ├─ undistort / downscale          │
          │  └─ gravity estimate          ├─ feature detect + KLT track     │
 Camera ─►│                               ├─ gyro-guided gating + RANSAC    │
          │                               └─ visual rotation ΔR_vis + conf. │
          │            │                              │                     │
          │            ▼                              ▼                     │
          │        ┌───────────── FUSION ENGINE (ESKF) ─────────────┐       │
          │        │ predict: gyro      update: gravity (pitch/roll)│       │
          │        │ update: camera ΔR  (yaw + gyro bias)           │       │
          │        │ outputs: q_WD, bias, covariance, confidence    │       │
          │        └──────────────────────┬─────────────────────────┘       │
          │                               ▼                                 │
          │            Angle Converter → Yaw / Pitch / Roll                 │
          │                               ▼                                 │
          │        HUD (Compose)  ·  CSV Logger  ·  Benchmark vs ARCore     │
          └─────────────────────────────────────────────────────────────────┘
```

### 6.2 Design principle

**Loosely-coupled visual–inertial rotation estimator.** The gyro provides high-rate prediction; the camera supplies *rotation-only* measurements. Position/translation is deliberately **not** estimated, because only the rotation is needed. That removes scale ambiguity and makes the system far lighter than full VIO/SLAM.

### 6.3 Data flow summary

1. IMU samples arrive at 100–200 Hz → timestamped → ring buffer.
2. Camera frames arrive at 30 fps → timestamped on the **same clock**.
3. Vision pipeline computes the relative rotation between consecutive (or key) frames.
4. Fusion engine propagates with gyro, then applies gravity and vision updates.
5. Angle converter emits yaw/pitch/roll + confidence → HUD and logger.

---

## 7. Sensors & Data Acquisition

### 7.1 Android sensor selection

| Purpose | Sensor | Notes |
|---|---|---|
| Angular rate | `TYPE_GYROSCOPE_UNCALIBRATED` (preferred) or `TYPE_GYROSCOPE` | Uncalibrated gives raw rates plus the OS's bias estimate, so you control bias estimation. |
| Gravity / tilt | `TYPE_ACCELEROMETER` | Use low-pass filtering to separate gravity; gate by `‖a‖ ≈ 9.81 m/s²`. |
| Sanity reference (optional) | `TYPE_GAME_ROTATION_VECTOR` | Gyro+accel only, **no magnetometer**: allowed as a comparison baseline. |
| **Forbidden** | `TYPE_MAGNETIC_FIELD`, `TYPE_ROTATION_VECTOR`, `TYPE_GEOMAGNETIC_ROTATION_VECTOR`, `getOrientation` w/ magnetic data | Must never be registered. Add a CI/lint check that greps for them. |

Sampling: `registerListener(..., samplingPeriodUs = 5_000)` (≈200 Hz; actual rate is device-dependent). Log the real measured rate. The `HIGH_SAMPLING_RATE_SENSORS` permission is required on Android 12+ for rates > 200 Hz.

### 7.2 Camera

| Setting | Choice | Reason |
|---|---|---|
| API | **CameraX `ImageAnalysis`** (or Camera2 for finer control) | CameraX is simpler. Camera2 gives control over stabilization and exposure. |
| Format | `YUV_420_888`, use the **Y (luma) plane only** | Grayscale suffices; avoids color conversion. |
| Resolution | 640×480 or 960×540 analysis stream | Balance of feature quality and speed. |
| Frame rate | 30 fps | |
| Stabilization | **Disable OIS and EIS** (`LENS_OPTICAL_STABILIZATION_MODE_OFF`, `CONTROL_VIDEO_STABILIZATION_MODE_OFF`) | Stabilization changes effective intrinsics/rotation and breaks the camera–IMU model. |
| Focus / exposure | Lock focus (infinity-ish), prefer fixed/short exposure | Focus breathing alters FOV. Long exposure → motion blur. |
| Timestamps | Use `SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME` devices where possible | Frame timestamps then share the `elapsedRealtimeNanos` clock with sensor events. |

### 7.3 Time synchronization

Camera–IMU time offset (`t_d`) is a classic accuracy killer (even 10 ms error at fast rotation causes visible error).

- Use the same monotonic clock for both streams.
- Estimate a constant offset `t_d` during calibration (cross-correlate gyro rate magnitude vs. visual angular rate magnitude) and apply it at runtime.
- At each camera frame, **interpolate** the gyro to the exact exposure-midpoint timestamp.
- Account for **rolling shutter** readout time (~10–30 ms) for fast pans (treat as a calibrated per-row time or reduce the vision weight during fast rotation).

---

## 8. Algorithms in Detail

### 8.1 Gyro propagation (prediction step)

Nominal state: quaternion `q` (device→world) and gyro bias `b_g`.

```
ω = ω_meas − b_g
q_{k+1} = q_k ⊗ exp(½ · ω · Δt)          // exp = quaternion from rotation vector
```

Use a 2nd-order (midpoint) integrator, or integrate over the sample interval with the actual `Δt` (never a fixed `Δt`).

### 8.2 Gravity update (pitch & roll)

When the device is **not accelerating much**, i.e. `| ‖a‖ − 9.81 | < ε` (e.g. ε ≈ 0.5–1 m/s²) and the gyro rate is moderate:

```
ĝ_D  = R_WDᵀ · [0, 0, 1]ᵀ                 // predicted "up" in device frame
z_a  = a_meas / ‖a_meas‖                    // measured up
residual = z_a × ĝ_D   (or angle-axis error)
```

Feed `residual` to the error-state filter (only tilt axes are observable). Inflate the measurement noise `R_a` as `|‖a‖ − 9.81|` grows, so shaking or walking doesn't corrupt tilt.

*Baseline alternative (for the first prototype):* a **Madgwick** or **complementary filter** (gyro + accel only) is a good stepping stone before the full ESKF.

### 8.3 Error-State Kalman Filter (ESKF)

**State:** nominal `q`, `b_g`. **Error state:** `δx = [δθ (3), δb_g (3)]ᵀ`.

**Error dynamics (continuous):**

```
δθ' = −[ω − b_g]× δθ − δb_g − n_g
δb_g' = n_bg                                    // bias random walk
```

Discretize → `F_k`, `Q_k`. Propagate covariance `P ← F P Fᵀ + Q` at every IMU sample.

**Update (generic):**

```
K  = P Hᵀ (H P Hᵀ + R)⁻¹
δx = K · residual
q  ← q ⊗ exp(½ δθ);  b_g ← b_g + δb_g
P  ← (I − K H) P  (Joseph form for numerical stability)
```

Two measurement types: **gravity** (§8.2) and **visual rotation** (§8.4–8.5).

### 8.4 Vision front-end: tracking features

Per frame (grayscale, downscaled):

1. **Detect** corners: Shi–Tomasi (`goodFeaturesToTrack`) or FAST, using **grid bucketing** (e.g., 8×6 cells, ≤ 10 features/cell) so features spread across the whole image, not clustered on one obstacle.
2. **Track** with **pyramidal Lucas–Kanade (KLT)** from the previous frame, seeded with the **gyro-predicted flow** (initial guess) for robustness to fast pans.
3. **Forward-backward check:** track forward and back, and discard points whose round-trip error > ~1 px.
4. **Replenish** features when the count drops (< ~60% of the target).
5. *(Alternative: ORB descriptors + brute-force/FLANN matching, heavier but more robust to large motion.)*

### 8.5 Vision rotation measurement

Convert tracked pixels to **unit bearing vectors** using the intrinsics `K` and distortion coefficients:

```
p_norm = K⁻¹ [u, v, 1]ᵀ → undistort → b = p_norm / ‖p_norm‖
```

Two estimators, selected by parallax:

**(a) Rotation-only (Wahba/Kabsch), the primary path for handheld panning.**
For static far-field points, `b₂ ≈ R · b₁`. Solve for `R` via SVD:

```
H = Σ b₁ b₂ᵀ ; U,S,Vᵀ = svd(H) ; R = V · diag(1,1,det(VUᵀ)) · Uᵀ
```

Wrap in RANSAC (3-point or 2-point minimal samples), scoring by angular error of the bearing vectors.

**(b) Essential-matrix path (when translation is significant).**
`E = findEssentialMat(pts1, pts2, K, RANSAC, prob=0.999, thr≈1px)` then `recoverPose(E, …) → R, t̂`. Use only `R`. Falls back to (a) when parallax is low (E degenerates under pure rotation).

**Output:** `ΔR_vis` (rotation from frame k−1 to k), inlier count `N_in`, inlier ratio, RMS reprojection/angular error, and the spatial spread of inliers, all forming a **vision quality score** `s_vis ∈ [0,1]`.

### 8.6 Vision update into the filter

Residual from comparing gyro-predicted vs. vision rotation over the frame interval:

```
ΔR_gyro = (integrated from IMU between the two frame timestamps, using current b_g)
e = Log( ΔR_gyroᵀ · ΔR_vis )              // 3-vector, small-angle residual in the body frame
```

- The residual is dominated by **gyro bias × Δt** (+ noise), so the filter learns `b_g` → this is what bounds yaw drift.
- Measurement noise `R_v` scales inversely with `s_vis` and rises with rotation speed (blur / rolling shutter).
- Use a **Mahalanobis gate**: reject the update if `eᵀ S⁻¹ e > χ²(3, 0.99)`.

Implementation note: because the residual refers to the *relative* rotation across an interval, use **stochastic cloning** (augment the state with the orientation at the previous frame) or approximate as a bias/rate measurement (`e/Δt` observes `δb_g`). Start with the simple approximation; upgrade to cloning if needed.

### 8.7 Keyframe yaw anchoring (drift-bounded heading)

Frame-to-frame rotation chaining still accumulates small errors. To bound yaw drift:

1. Maintain a small set of **rotation keyframes**: features + the filter's orientation at capture.
2. When the current view overlaps a keyframe (match ≥ N inliers), compute the rotation relative to the keyframe → this yields an **absolute-ish yaw measurement relative to the world frame** (with respect to when the keyframe was stored), with no accumulation across the chain.
3. Re-localizing against the **initial keyframe** gives a loop-closure-like yaw correction when the user returns to the start view.
4. Cull keyframes by age, view diversity, and quality (cap ~20–30).

### 8.8 Static detection & bias initialization

- **Zero-rate update (ZRU):** when `‖ω_meas‖` stays below a threshold for ≥ 0.5 s *and* the vision shows ~no motion, estimate `b_g` as the mean gyro reading (a strong, cheap bias observation).
- **Startup:** ask the user to hold still for ~2 s to initialize bias and the gravity-aligned attitude (pitch/roll), then set `yaw_ref` = current yaw.

### 8.9 Angle output & smoothing

- Convert `q` to yaw/pitch/roll via §5.3.
- Do **not** low-pass the filter's output (it adds lag). If display jitter appears, apply a very light critically-damped smoother on the display value only.
- Handle wrap-around (±180°) correctly when smoothing or plotting.

---

## 9. Obstacle & Dynamic-Object Handling

"Obstacles" can mean (1) moving objects (people, cars), (2) near static objects, or (3) the camera view being partly/fully blocked. Defenses are layered:

| Layer | Technique | What it handles |
|---|---|---|
| 1 | **Gyro-guided gating**: discard tracks whose flow disagrees with the gyro-predicted flow beyond a threshold | Moving objects, bad tracks |
| 2 | **RANSAC consensus** on a single global rotation | Any minority of independently moving points |
| 3 | **Spatial bucketing** | Prevents one large obstacle from dominating the features |
| 4 | **Far-field preference**: weight points by estimated parallax; rotation is best measured by distant points; near points are contaminated by translation | Close objects / walking parallax |
| 5 | **Optional semantic mask** (lightweight TFLite segmentation, e.g. DeepLab/MediaPipe) that excludes `person` / `vehicle` / `sky-cloud` regions | Large moving objects, crowds, clouds |
| 6 | **Track-age & consistency scoring** | Short-lived, flickering features |
| 7 | **Mahalanobis gate in the filter** | Any residual outlier that slips through |
| 8 | **Confidence-weighted fusion** (`R_v` inflated as quality drops) | Gradual degradation |
| 9 | **Occlusion detection** (feature count collapse, very low image gradient/entropy) → camera updates paused | Hand over the lens, dark scenes |

**Majority rule:** the approach assumes the **static background dominates** the inlier set. If moving objects occupy > ~50% of the image, the vision update must be rejected (or the semantic mask used) and the system falls back to gyro-only with rising uncertainty.

---

## 10. Confidence, Failure Modes & Fallbacks

### 10.1 Confidence signals

- Filter covariance (yaw standard deviation `σ_yaw`).
- Vision quality `s_vis` (inliers, spread, residual).
- Time since the last accepted vision update.
- Accelerometer validity (is the device accelerating?).

Combine into a three-level indicator: 🟢 **High** (σ_yaw < ~1°) · 🟡 **Medium** (1–3°) · 🔴 **Low** (> 3° or camera blocked > N s).

### 10.2 Failure mode table

| Condition | Effect | System response |
|---|---|---|
| Camera covered / dark | No features | Gyro-only; yaw uncertainty grows; HUD → 🔴 after threshold |
| Textureless scene (blank wall, fog) | Few/poor features | Same as above; show "Low texture" message |
| Fast rotation / motion blur | Track loss, rolling-shutter distortion | Increase `R_v`, rely on gyro; re-acquire afterwards |
| Crowd/vehicle dominating view | Wrong global rotation | Gating + semantic mask; reject update |
| Phone shaken/accelerating | Gravity unreliable | Inflate `R_a`, skip tilt correction; gyro bridges |
| Looking straight up/down | Yaw/roll singularity | Freeze yaw display, keep quaternion; label as "vertical" |
| Sensor-rate hiccups | Integration error | Use real `Δt`, detect gaps, widen covariance |
| Thermal throttling | Frame drops | Adaptive resolution/feature count |

### 10.3 Honesty principle

The app must **never present a confident yaw it doesn't have**. Reported uncertainty is part of the output (`σ_yaw` displayed or logged).

---

## 11. Implementation Paths

### Path A — ARCore-based (fast, accurate baseline)

ARCore already fuses camera and IMU (visual-inertial odometry), handles dynamic scenes reasonably, and exposes the camera pose each frame. It uses **no compass** for tracking.

- Read `frame.camera.pose` (or `displayOrientedPose`) → rotation quaternion → convert to yaw/pitch/roll using §5.3.
- ARCore's world frame is gravity-aligned (Y up); **remap axes** to the Z-up convention defined here.
- Yaw is relative to the session start → matches the no-compass requirement.
- Check `camera.trackingState` and `trackingFailureReason` and map them to the confidence indicator.
- **Caveat:** requires an ARCore-supported device; it's a black box (limited tunability); it also estimates translation (heavier than needed).

### Path B — Custom estimator (OpenCV + ESKF)

The pipeline described in §6–§10, implemented in Kotlin (fusion, UI) and C++/NDK with OpenCV (vision).

- Full control, lighter weight (rotation-only), works on non-ARCore devices, and is "your algorithm".
- Requires careful calibration, time-sync, and tuning.

### Recommended strategy

1. **Phase 1: Path A** to get a working, accurate app quickly (this is also your reference).
2. **Phase 2: Path B** developed in parallel inside the same app.
3. **Phase 3: Benchmark mode** compares B against A (and against physical ground truth) and logs the error.
4. Ship whichever meets the accuracy targets, with B as the "own algorithm" deliverable and A as the fallback/reference.

---

## 12. Tech Stack

| Layer | Technology |
|---|---|
| Language (app) | **Kotlin** |
| Language (vision core) | **C++17** via **NDK/JNI** (OpenCV) |
| UI | **Jetpack Compose** (HUD, charts) |
| Camera | **CameraX** (`ImageAnalysis`) / Camera2 for stabilization control |
| Sensors | Android `SensorManager` |
| AR reference | **ARCore SDK** (Path A / benchmark) |
| Vision | **OpenCV 4.x Android SDK** (KLT, RANSAC, essential matrix, SVD) |
| Linear algebra | Eigen (C++) / small custom quaternion lib in Kotlin |
| Concurrency | Kotlin Coroutines + dedicated `HandlerThread`s |
| Logging | CSV writer (buffered) + optional ROS-bag-like replay format |
| Offline analysis | **Python** (NumPy, Pandas, Matplotlib): error plots, Allan variance, drift stats |
| Optional ML | TFLite segmentation model for dynamic-object masks |
| Build | Gradle (Kotlin DSL), CMake for the NDK |
| Min SDK | 26 (ARCore requires 24+) · Target: latest stable |
| iOS (later) | Swift, CoreMotion (`CMAttitudeReferenceFrame.xArbitraryZVertical`, **not** the `...Magnetic...` frames), ARKit, Accelerate |

---

## 13. Project Structure

```
CamIMU-Orient/
├── README.md
├── project.md                     ← this document
├── app/
│   ├── src/main/
│   │   ├── AndroidManifest.xml
│   │   ├── java/com/camimu/orient/
│   │   │   ├── MainActivity.kt
│   │   │   ├── sensors/
│   │   │   │   ├── ImuSource.kt           // gyro/accel capture, timestamps, ring buffer
│   │   │   │   ├── CameraSource.kt        // CameraX/Camera2 capture, intrinsics, timestamps
│   │   │   │   └── TimeSync.kt            // clock alignment, camera–IMU offset
│   │   │   ├── fusion/
│   │   │   │   ├── Quaternion.kt
│   │   │   │   ├── Eskf.kt                // predict / gravity update / vision update
│   │   │   │   ├── BiasEstimator.kt       // static detection, ZRU
│   │   │   │   ├── ConfidenceModel.kt
│   │   │   │   └── AngleConverter.kt      // q → yaw/pitch/roll (§5.3)
│   │   │   ├── vision/
│   │   │   │   ├── VisionBridge.kt        // JNI wrapper
│   │   │   │   └── SemanticMask.kt        // optional TFLite
│   │   │   ├── ar/
│   │   │   │   └── ArCoreReference.kt     // Path A / benchmark reference
│   │   │   ├── logging/
│   │   │   │   └── CsvLogger.kt
│   │   │   ├── calibration/
│   │   │   │   ├── IntrinsicsCalibrator.kt
│   │   │   │   └── ExtrinsicTimeCalibrator.kt
│   │   │   └── ui/
│   │   │       ├── Hud.kt                 // Compose HUD
│   │   │       ├── ArtificialHorizon.kt
│   │   │       ├── HeadingDial.kt
│   │   │       └── SettingsScreen.kt
│   │   └── cpp/
│   │       ├── CMakeLists.txt
│   │       ├── feature_tracker.cpp        // detect + KLT + bucketing
│   │       ├── rotation_estimator.cpp     // Wahba/RANSAC, essential matrix
│   │       └── native_bridge.cpp
├── tools/
│   ├── analyze_log.py                     // error vs time, drift/min, RMSE
│   ├── allan_variance.py
│   └── replay_sim.py                      // run the estimator offline on logs
├── docs/
│   ├── test_protocols.md
│   └── calibration_guide.md
└── tests/
    ├── unit/   (quaternion math, angle conversion, ESKF steps)
    └── replay/ (golden logs with expected outputs)
```

---

## 14. Module Specifications

### 14.1 `ImuSource`
- Registers gyro (uncalibrated) + accelerometer at max useful rate.
- Emits `ImuSample(t_ns, gyro[3], accel[3])` into a thread-safe ring buffer (≥ 2 s).
- Tracks the actual sample rate and gap events.

### 14.2 `CameraSource`
- Delivers `Frame(t_ns, yPlane, width, height)`; reads the intrinsics (`LENS_INTRINSIC_CALIBRATION`) or the calibrated values.
- Drops frames if the vision thread is busy (never queue unbounded).

### 14.3 `TimeSync`
- Verifies the shared clock, stores the estimated offset `t_d`, and interpolates IMU samples to any query time.

### 14.4 Vision core (C++)
- Input: previous/current gray frames, gyro-predicted rotation.
- Output: `VisionResult { R_rel (3×3), nInliers, inlierRatio, rmsErr, spread, quality, valid }`.
- Must run ≤ ~15 ms/frame on a mid-range device.

### 14.5 `Eskf`
- API sketch:

```kotlin
class Eskf {
    fun predict(omegaMeas: Vec3, dt: Double)
    fun updateGravity(accelMeas: Vec3, accelQuality: Double)
    fun updateVision(dRvis: Mat3, dt: Double, quality: Double): Boolean // false if gated out
    fun orientation(): Quaternion
    fun gyroBias(): Vec3
    fun yawSigmaDeg(): Double
    fun resetYaw()
}
```

### 14.6 `AngleConverter`
- Pure function `(Quaternion, yawRef) → (yaw, pitch, roll)` with unit tests at all cardinal orientations, near-singularity cases, and wrap-around.

### 14.7 `CsvLogger`
- Buffered, off-main-thread, flushed every ~1 s.

**Log columns (suggested):**
`t_ns, gx, gy, gz, ax, ay, az, bgx, bgy, bgz, qw, qx, qy, qz, yaw, pitch, roll, sigma_yaw, vis_inliers, vis_quality, vis_accepted, arcore_yaw, arcore_pitch, arcore_roll, arcore_tracking, ground_truth_yaw (if any)`

---

## 15. User Interface (HUD)

| Element | Description |
|---|---|
| **Live camera preview** | Background, low-latency (preview is separate from the analysis stream) |
| **Numeric readout** | Yaw / Pitch / Roll in degrees (1 decimal), large and legible |
| **Artificial horizon** | Pitch/roll indicator, like an aircraft attitude indicator |
| **Heading dial** | Relative yaw ring ("0" = start heading); no north marker, with a note that it is relative |
| **Confidence badge** | 🟢/🟡/🔴 + `σ_yaw` |
| **Feature overlay (debug)** | Draw inliers (green) / outliers (red) / masked regions on the preview |
| **Buttons** | Reset yaw · Start/Stop logging · Toggle estimator (Custom / ARCore / Both) · Calibrate |
| **Comparison panel** | Custom vs ARCore deltas and a live error graph |
| **Warnings** | "Camera blocked", "Low texture", "Moving too fast", "Looking straight up/down" |

UX notes: portrait and landscape must both work (rotate the frames/axes correctly). Keep the screen on during use.

---

## 16. Calibration

| Item | Method | Frequency |
|---|---|---|
| **Camera intrinsics** (fx, fy, cx, cy, distortion) | Checkerboard/Charuco with OpenCV `calibrateCamera`, or use `LENS_INTRINSIC_CALIBRATION` as the initial guess | Once per device model |
| **Gyro bias** | Static hold (~2 s) at startup + continuous online estimation | Every start + continuous |
| **Gyro scale / axis misalignment** (optional) | Controlled turntable rotations | Once, if needed |
| **Camera–IMU rotation extrinsic** `R_CD` | Start from the known axis flip; refine by minimizing the gyro-vs-vision rotation residual over a recorded rotation sequence | Once per device model |
| **Camera–IMU time offset** `t_d` | Cross-correlation of the gyro-rate magnitude and the visual angular-rate magnitude | Once per device model (verify periodically) |
| **Accelerometer bias/scale** (optional) | 6-position static test | Rarely |

A guided **in-app calibration wizard** should walk the user through the steps and store results per device.

---

## 17. Data Logging & Tooling

- **CSV logs** per session with a header block (device model, OS version, sensor rates, intrinsics, calibration values, app version, parameters).
- **Replay simulator** (`replay_sim.py` / on-device): feeds logged IMU+frames to the estimator for **deterministic debugging and parameter tuning** without re-collecting data.
- **Analysis scripts:** yaw error vs time, drift (°/min) via linear fit, RMSE, max error, error vs number of obstacles, and latency histograms.
- **Allan variance** of the stationary gyro log to derive noise (`σ_g`) and bias-instability parameters for `Q`.

---

## 18. Testing & Validation Plan

### 18.1 Unit tests
- Quaternion algebra (normalize, multiply, exp/log).
- Angle conversion: cardinal poses, singularities, wrap-around, sign conventions.
- ESKF: propagation with a constant rate; gravity update convergence; a vision update with synthetic data.
- Time interpolation.

### 18.2 Synthetic tests
- Simulate a trajectory with known angles → generate synthetic gyro (with bias/noise) and synthetic feature tracks (with outliers) → confirm the estimator recovers the truth.

### 18.3 Ground-truth methods (real device)

| Method | Provides | Notes |
|---|---|---|
| **Motorized/manual turntable with angle marks** | Yaw truth | Mount the phone on a tripod head over a protractor; known rotations of 90°/180°/360° |
| **Floor with taped angle lines** | Yaw & repeatability | Return-to-start tests |
| **Spirit level / inclinometer, tilted jig** | Pitch/roll truth | Fixed-angle wedges (e.g., 10°, 20°, 30°) |
| **ARCore pose** | Dense relative reference | Good for overall comparison, not absolute truth |
| **Motion capture (if accessible)** | Full 6-DoF truth | Best, optional |

### 18.4 Scenario matrix

| # | Scenario | Purpose |
|---|---|---|
| S1 | Static, 5 min | Bias, noise, drift floor |
| S2 | Slow pan, 360° and return | Loop-closure yaw error |
| S3 | Fast pan (≥ 120°/s) | Blur/rolling-shutter robustness |
| S4 | Walking with the phone in hand | Translation + gravity corruption |
| S5 | Moving people in the background (1, 3, 10) | Dynamic-object rejection |
| S6 | Large obstacle covering 30% / 50% / 70% of the view | Majority-rule limit |
| S7 | Near obstacles (walls, furniture at < 1 m) | Parallax handling |
| S8 | Camera fully covered for 10 s, then uncovered | Fallback + recovery |
| S9 | Low light / indoor dim | Feature starvation |
| S10 | Textureless wall / fog | Weak features |
| S11 | Looking up/down, sweeping through the vertical | Singularity behavior |
| S12 | 30-minute continuous run | Stability, thermal, memory |
| S13 | Multiple devices | Portability |

### 18.5 Metrics

- **Pitch/roll RMSE** (°), **max error** (°).
- **Yaw drift** (°/min) via a linear fit during stationary/slow-motion periods.
- **Return-to-start yaw error** (°) after a 360° loop.
- **Error vs obstacle level** (per S5–S6).
- **Recovery time** after occlusion (s).
- **Latency** (ms) and **processing time per frame** (ms).
- **Confidence calibration:** does the reported `σ_yaw` actually bound the real error (e.g., ~95% of errors within 2σ)?

---

## 19. Performance Budget

Target: mid-range Android phone, 30 fps analysis.

| Stage | Budget |
|---|---|
| Frame grab + Y-plane copy/downscale | ≤ 3 ms |
| Feature detect (bucketed) + KLT | ≤ 6 ms |
| RANSAC rotation + quality scoring | ≤ 3 ms |
| (Optional) semantic mask (TFLite, runs at lower rate, e.g. 5–10 Hz) | ≤ 15 ms, async |
| ESKF predict (per IMU sample) | ≤ 0.05 ms |
| ESKF updates | ≤ 0.5 ms |
| HUD render | ≤ 8 ms |
| **Frame total (vision thread)** | **≤ ~15 ms** (leaves headroom within the 33 ms frame period) |

Adaptive degradation: if the frame time exceeds budget, reduce features/resolution first, then skip frames. The IMU thread must never block.

---

## 20. Accuracy Targets & Acceptance Criteria

> These are **engineering targets**, not guarantees. Actual results depend on the device's sensor quality, scene texture, and calibration. Report measured results honestly per device.

| Metric | Target (normal scenes) | Target (with moving obstacles) |
|---|---|---|
| Pitch RMSE | ≤ 1° | ≤ 1.5° |
| Roll RMSE | ≤ 1° | ≤ 1.5° |
| Yaw drift (slow/static) | ≤ 1°/min | ≤ 2°/min |
| 360° return-to-start yaw error | ≤ 2° | ≤ 4° |
| Display rate | ≥ 30 Hz | ≥ 30 Hz |
| Latency | ≤ 50 ms | ≤ 50 ms |
| Camera-blocked behavior | Gyro fallback, confidence → 🔴, no crash, recovers < 2 s after unblocking | |
| Stability | 30 min run, no crash, bounded memory | |

**Project is "done" when:**
- [ ] The app runs on ≥ 2 different devices.
- [ ] The magnetometer/compass is provably unused (code + runtime audit).
- [ ] The scenario matrix S1–S12 is executed and logged.
- [ ] The targets above are met, or shortfalls are documented with analysis.
- [ ] The Custom-vs-ARCore benchmark report is produced.
- [ ] Docs complete (this file, calibration guide, test protocol).

---

## 21. Risks & Mitigations

| Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|
| Yaw expected to be absolute north | High (misunderstanding) | High | State clearly (§3–4); offer an optional alignment/GNSS-course anchor |
| Camera–IMU time offset errors | High | High | Calibrate `t_d`; interpolate to the exposure midpoint |
| OIS/EIS corrupts the visual model | Medium | High | Disable stabilization via Camera2; test per device |
| Rolling shutter on fast pans | Medium | Medium | Down-weight vision at high rates; model readout time |
| Dynamic objects dominate view | Medium | High | Multi-layer rejection; semantic mask; gyro fallback |
| Feature-poor environments | Medium | Medium | Confidence reporting; gyro bridging; user hints |
| Device fragmentation (sensor quality, intrinsics) | High | Medium | Per-device calibration profile; test on several devices |
| Thermal throttling | Medium | Medium | Adaptive load; lower resolution/features |
| ESKF tuning difficulty | Medium | Medium | Allan-variance-derived `Q`; replay-based tuning |
| ARCore unavailable on a device | Medium | Low | Custom path B as the independent implementation |
| Gimbal/singularity artifacts | Low | Medium | Quaternion core; flag the vertical pose |

---

## 22. Roadmap & Milestones

| Phase | Deliverable | Est. effort |
|---|---|---|
| **0. Setup** | Android project, permissions, CameraX preview, sensor capture, CSV logger | 2–3 days |
| **1. Baseline (Path A)** | ARCore pose → yaw/pitch/roll, HUD, reset-yaw, confidence from tracking state | 3–5 days |
| **2. IMU-only attitude** | Complementary/Madgwick filter, pitch/roll accuracy verified vs. level jig | 3–4 days |
| **3. Vision front-end** | Feature tracker + KLT + RANSAC rotation (NDK/OpenCV), debug overlay | 1–2 weeks |
| **4. ESKF fusion** | Gyro predict, gravity update, vision update, bias estimation, gating | 1–2 weeks |
| **5. Obstacle robustness** | Bucketing, gyro-guided gating, far-field weighting, occlusion detection, optional semantic mask | 1 week |
| **6. Keyframe yaw anchoring** | Keyframe store + relocalization for drift-bounded yaw | 1 week |
| **7. Calibration tooling** | Intrinsics, time offset, extrinsic rotation wizards | 1 week |
| **8. Benchmark & validation** | Scenario matrix S1–S13, plots, report | 1–2 weeks |
| **9. Polish & release** | Settings, error states, performance tuning, docs | 1 week |

(Estimates assume one developer working part-time; Path A alone yields a working app within the first ~1.5 weeks.)

---

## 23. Future Extensions

- **iOS port** (CoreMotion arbitrary-Z-vertical frame + ARKit reference).
- **Absolute heading anchor**: optional one-time alignment (point at a known landmark/sun azimuth) or GNSS course-over-ground while walking/driving. This remains a *user-chosen* add-on; the core stays compass-free.
- **Full VIO** (add translation) for navigation, since the architecture is a subset.
- **Learned visual rotation / learned inertial** models for extra robustness (e.g., end-to-end networks as a prior).
- **Sun/sky-based yaw** cues (outdoor).
- **Multi-camera** (wide + main) fusion for a larger FOV and better far-field coverage.
- **Edge deployment** on other platforms (drones, rovers, wearables), with the same estimator core.

---

## 24. Glossary

| Term | Meaning |
|---|---|
| **IMU** | Inertial Measurement Unit: gyroscope + accelerometer (+ magnetometer in 9-axis units, unused here) |
| **Yaw** | Rotation about the vertical axis (heading); relative here |
| **Pitch** | Elevation/tilt up-down relative to the horizon |
| **Roll** | Rotation about the optical/forward axis; horizon tilt |
| **Quaternion** | 4-parameter rotation representation with no gimbal lock |
| **ESKF** | Error-State Kalman Filter: estimates small errors around a nominal state |
| **Bias (gyro)** | Slowly varying offset in the gyro reading; the main source of drift |
| **KLT** | Kanade–Lucas–Tomasi feature tracker |
| **RANSAC** | Random Sample Consensus: robust model fitting that ignores outliers |
| **Essential matrix** | 3×3 matrix encoding relative rotation+translation direction between calibrated views |
| **Wahba's problem** | Finding the optimal rotation aligning two sets of vectors (solved via SVD) |
| **VIO** | Visual-Inertial Odometry |
| **OIS / EIS** | Optical / Electronic Image Stabilization |
| **Rolling shutter** | Sensor reads rows at different times, so fast motion skews the image |
| **Mahalanobis gate** | Statistical test to reject measurements inconsistent with the filter's prediction |
| **ZRU/ZUPT** | Zero-rate / Zero-velocity update: exploit known stationary periods |
| **Allan variance** | Method to characterize gyro noise and bias instability |
| **Loop closure** | Recognizing a previously seen view to cancel accumulated drift |

---

## 25. References

- Madgwick, S. *An efficient orientation filter for inertial and inertial/magnetic sensor arrays* (2010).
- Solà, J. *Quaternion kinematics for the error-state Kalman filter* (2017).
- Mourikis & Roumeliotis. *A Multi-State Constraint Kalman Filter for Vision-aided Inertial Navigation* (MSCKF, 2007), relevant to stochastic cloning.
- Hartley & Zisserman. *Multiple View Geometry in Computer Vision*.
- Nistér, D. *An efficient solution to the five-point relative pose problem* (2004).
- Lucas & Kanade (1981); Shi & Tomasi, *Good Features to Track* (1994).
- Furgale et al., *Unified temporal and spatial calibration for multi-sensor systems* (Kalibr).
- Android docs: Sensors Overview, Camera2 / CameraX, `SENSOR_INFO_TIMESTAMP_SOURCE`, ARCore `Camera.getPose`.
- Apple docs: CoreMotion `CMAttitudeReferenceFrame`, ARKit camera transform.
- OpenCV docs: `findEssentialMat`, `recoverPose`, `calcOpticalFlowPyrLK`, `calibrateCamera`.

---

*Document status: v1.0 specification. Next step: scaffold the Android project (Phase 0–1: sensors, CameraX, ARCore baseline, HUD, logging).*
