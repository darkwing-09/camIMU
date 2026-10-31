# CLAUDE.md — CamIMU-Orient

Read this file at the start of every session. Then read `docs/Progress.md` to see where we are.

## What this project is
Android app (Kotlin) that estimates phone **yaw / pitch / roll** in real time from the
**gyroscope + accelerometer + back camera only**. No compass. Yaw is **relative** to the start
heading (or the last "Reset yaw"). It must stay usable with moving people or a blocked camera
(vision is gated out, and the app falls back to gyro-only and shows lower confidence).
Full spec: `project.md`. Build plan: `docs/Implementation.md` (the main file).

## Hard rules (never break these)
1. NEVER use the magnetometer or any compass-derived API. Forbidden anywhere in source:
   `TYPE_MAGNETIC_FIELD`, `TYPE_MAGNETIC_FIELD_UNCALIBRATED`, `TYPE_ROTATION_VECTOR`,
   `TYPE_GEOMAGNETIC_ROTATION_VECTOR`, `TYPE_ORIENTATION`, `SensorManager.getRotationMatrix(`,
   `SensorManager.getOrientation(`, `getInclination(`, `GeomagneticField`.
   `scripts/check_forbidden_apis.sh` enforces this. It must pass before any commit.
2. Yaw is relative. Never label it "north" or "heading from north" in UI, logs or docs.
3. Always use the **real** timestamp delta `dt = (t_k - t_{k-1}) * 1e-9` (seconds). Never a fixed dt.
4. The orientation state is a **unit quaternion** (Hamilton, `[w, x, y, z]`, `q_WD` = device→world).
   Euler angles are for display/logging only. Never integrate Euler angles.
5. Units inside code: radians, rad/s, m/s², nanoseconds (`Long`). Degrees only at UI/CSV edges,
   and those names end in `Deg` / `_deg`.
6. `:core` is pure Kotlin/JVM: no `android.*`, no OpenCV imports. All math lives there and is unit-tested.
7. Do not invent library APIs. If unsure an API exists, check the dependency's sources/docs, or
   write `UNKNOWN - verify` in `docs/Issues.md` and ask. Do not upgrade library versions unless the task says to.
8. One task at a time. Do only the task you were given, then stop.

## Commands
```bash
./scripts/verify.sh                       # everything below, in order (use before every commit)
./scripts/check_forbidden_apis.sh         # forbidden sensor/compass API guard
./gradlew :core:test                      # pure JVM unit tests (no phone needed, fast)
./gradlew :app:testDebugUnitTest          # app JVM unit tests
./gradlew :app:assembleDebug              # build APK
./gradlew :app:lintDebug                  # Android lint
./gradlew :app:installDebug               # install on the connected phone (needs adb device)
./gradlew :app:connectedDebugAndroidTest  # instrumented tests (needs phone) [DEVICE]
./gradlew :replay:run --args="--session <dir> --config <json> --out <dir>"   # offline replay
python3 tools/analyze_log.py <session_dir>   # metrics + plots (venv: tools/requirements.txt)
adb logcat -s CamIMU                       # app logs (all tags start with CamIMU)
```

## Folder map
```
CLAUDE.md  README.md  project.md
docs/       PRD, Architecture, Rules, DataModel, TestPlan, Decisions, Progress, Issues, Prompts, Implementation
core/       pure Kotlin/JVM: math, frames, fusion, vision geometry, io, sim  (package com.camimu.orient.core.*)
app/        Android app: sensors, camera, vision (OpenCV), ar (ARCore), pipeline, logging, calibration, ui
replay/     JVM CLI that replays logged sessions through :core
tools/      Python analysis & calibration scripts
scripts/    check_forbidden_apis.sh, verify.sh
```
Kotlin source roots: `core/src/main/kotlin/com/camimu/orient/core/...`,
`app/src/main/java/com/camimu/orient/...`, `replay/src/main/kotlin/com/camimu/orient/replay/...`.

## Coding style (details in docs/Rules.md)
- Kotlin official style. Small classes, one responsibility, no file over ~250 lines.
- Prefer pure functions and immutable `data class`es. Mutable state lives only in the estimator
  (IMU thread) and in UI state holders.
- Every public function in `:core` has KDoc stating units and frames, e.g. `/** @param omega rad/s, device frame */`.
- Name frames explicitly: `qWD`, `rCD`, `rotD1D2`. A rotation `rAB` maps B-frame vectors into A.
- No magic numbers: tunables go in `EstimatorConfig` / `VisionConfig` (see docs/DataModel.md).
- Log with tag prefix `CamIMU` (e.g. `CamIMU.Imu`). Never log per IMU sample to logcat.
- Never block the IMU thread or the camera analyzer; drop work instead.

## Task workflow
1. Read the task in `docs/Implementation.md` (and only the docs it references).
2. Set its status to `IN_PROGRESS` in `docs/Progress.md`.
3. Implement exactly the listed files. Write or extend the unit tests first for `:core` code.
4. Run the task's Verification commands, then `./scripts/verify.sh`.
5. Update `docs/Progress.md` (see below). Add new risks/unknowns to `docs/Issues.md`.
6. Suggest the commit message from the task. Commit only if the user asked you to.
7. Stop and report: what changed, test results (paste the summary), anything the user must do on the phone.

Tasks tagged **[DEVICE]** need the physical phone. Build, install and then STOP. Give the user
numbered manual steps and the numbers they should see. Do not mark the task DONE until the user confirms.

## Updating docs/Progress.md
- Edit only the line(s) for your task plus the "Last updated" header and "Current task" line.
- Status values: `TODO`, `IN_PROGRESS`, `DONE`, `BLOCKED`, `NEEDS_DEVICE_CHECK`.
- On DONE fill: date (YYYY-MM-DD), test summary (e.g. `core:test 42/42`), and a one-line note.
- On BLOCKED write the reason and add an entry in `docs/Issues.md`.

## Definition of done (every task)
- [ ] Only the files the task lists were created or changed (or the deviation is explained).
- [ ] `./scripts/verify.sh` passes (forbidden-API guard, core tests, app unit tests, assembleDebug, lint with no new errors).
- [ ] New `:core` logic has unit tests, including at least one edge case (zero, wrap-around, singular, empty input).
- [ ] Units and frames are documented in KDoc. No new magic numbers.
- [ ] The task's acceptance criteria are met. [DEVICE] criteria are confirmed by the user.
- [ ] `docs/Progress.md` is updated. New unknowns are added to `docs/Issues.md`.

## If something goes wrong
- Build broken by a version/API mismatch: do not guess. Report the exact error, check the
  dependency's docs or sources, and record it in `docs/Issues.md`.
- Test failing: fix the code, not the test, unless the test contradicts `docs/Architecture.md`
  (then say so explicitly).
- Math doubt: `docs/Architecture.md` §4 (frames and formulas) is the source of truth.
