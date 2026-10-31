# Prompts for Claude Code

Copy-paste these into Claude Code, started from the project root (`claude` in this folder, so it loads `CLAUDE.md`).
Rule of thumb: **one task per prompt**, and start a fresh conversation (`/clear`) at each new phase.

---

## 0. Universal task prompt (use for any task)
```
Read CLAUDE.md, then docs/Progress.md, then Task <X.Y> in docs/Implementation.md
(and only the Architecture.md / DataModel.md sections it references).
Implement ONLY Task <X.Y>. Write/extend the :core unit tests first where the task has :core code.
Run the task's Verify commands and ./scripts/verify.sh. Paste the test summary.
Update docs/Progress.md for this task (status, date, tests, note). Add any unknowns to docs/Issues.md.
If the task is [DEVICE]: build + install, then STOP and give me numbered manual steps and the numbers I should see.
Do not commit. Suggest the commit message from the task. Then stop.
```

## 1. Phase prompts

### Phase 0 — Setup
```
Read CLAUDE.md and docs/Implementation.md "Before you start" and Phase 0.
I have created the Android Studio project in this folder (Task 0.1 step 1–2 done).
Do Task 0.1 steps 3–4 (verify the build from the terminal, fix .gitignore). Do NOT run git init.
Then stop and report the AGP/Kotlin/Compose/compileSdk versions from the template so I can record them in docs/Decisions.md ADR-015.
```
```
Read CLAUDE.md and Task 0.2 in docs/Implementation.md. Before adding dependencies, show me the exact
libs.versions.toml lines you will add and which versions you checked (mark any you couldn't check as VERIFY).
Then implement Task 0.2, run its Verify commands, update docs/Progress.md, and stop.
```
```
Read CLAUDE.md and Task 0.3. Implement exactly the two scripts shown, make them executable, run
--self-test and verify.sh, then temporarily add Sensor.TYPE_ROTATION_VECTOR to MainActivity.kt,
show me that verify.sh fails, revert it, show it passes again. Update Progress.md and stop.
```
Task 0.4: use the universal prompt with `0.4`.

### Phase 1 — Math core + ARCore baseline
```
Read CLAUDE.md, docs/Architecture.md §4, and Task 1.1 in docs/Implementation.md.
Implement only Task 1.1 (tests first). Run ./gradlew :core:test and ./scripts/verify.sh.
Update docs/Progress.md and stop.
```
Then the universal prompt for `1.2`, `1.3` (tell it: "Task 1.3 tests must include all 9 groups listed").
```
Read CLAUDE.md and Task 1.4. Before writing code, list the ARCore APIs you'll use and check each exists in
the ARCore version in libs.versions.toml (look in the Gradle cache sources/javadoc if needed). Mark anything
unconfirmed as VERIFY in docs/Issues.md instead of guessing. Implement Task 1.4, build, install, and stop
with manual steps for me.
```
Universal prompt for `1.5`, `1.6`. After 1.6: "Give me the Checkpoint 1 procedure as a numbered checklist with a blank for each number."

### Phase 2 — IMU-only estimator
Universal prompt for 2.1 → 2.6 (pure `:core`, no phone needed). For 2.6 add:
```
After implementing, print a short table of the five synthetic scenarios: metric, threshold, measured value.
```
For 2.7 and 2.8 (device): universal prompt. For 2.10:
```
Implement Task 2.10. Then show me how to run the replay and analysis on the session I pulled to
sessions/<name> (I'll paste the folder name). Create the Python venv instructions in tools/README.md.
```

### Phase 3 — Vision front-end
Universal prompt for 3.1–3.4 (`:core`). For 3.5:
```
Read CLAUDE.md and Task 3.5. Verify CameraX/Camera2Interop/OpenCV APIs against the versions in
libs.versions.toml before use (record VERIFY items). Implement, install, and stop with steps; the debug text
must show: analysis size, fps, sensor orientation, timestamp source, OIS availability, intrinsics source, frame age.
```
Universal prompt for 3.6, 3.7. For 3.8:
```
Implement tools/axis_check.py for Task 3.8, then give me the exact recording procedure. After I paste the
script output, interpret it: are the rCD signs correct? If not, propose the minimal fix to Frames.kt
plus a new failing unit test that reproduces it, then fix it.
```

### Phase 4 — Fusion
Universal prompt for 4.1. For 4.2:
```
Read CLAUDE.md, Architecture.md §6.7 and Task 4.2. Implement the five synthetic tests FIRST and show
they fail (or don't compile), then implement onVision until they pass. Print the table of scenario
metrics. Update Progress.md and stop.
```
Universal prompt for 4.3 → 4.5. Then:
```
Give me the Checkpoint 4 procedure as a checklist (session names, what to do physically, which command to
run afterwards, the pass threshold). Don't write code.
```

### Phase 5 — Calibration
```
Implement Task 5.<n>. For Python scripts: add argparse --help, print a clear summary, write JSON
matching the CalibrationData schema in docs/DataModel.md §4. Add a tiny self-check mode that runs on
synthetic data (e.g. --self-test) and run it.
```

### Phase 6 — Benchmark
Universal prompt for 6.1–6.3. For 6.1 add: "Log the measured ARCore timestamp offset and CPU image size to meta.json."

### Phase 7 — Validation & polish
Universal prompt for 7.1, 7.2. For 7.3:
```
I've recorded sessions S1..S12 into sessions/. Run tools/report.py on them, then write docs/Results.md:
per scenario the measured metrics vs the PRD §5 targets, PASS/FAIL, and for each FAIL a short analysis with
the evidence (plot file names) and the likely fix. Be honest; don't round in our favour.
```

## 2. Utility prompts

**Resume after a break**
```
Read CLAUDE.md and docs/Progress.md. Tell me: the current task, what's done, what's IN_PROGRESS or BLOCKED,
and the next step. Don't change any files.
```

**Build is broken**
```
./scripts/verify.sh fails. Read CLAUDE.md. Run it, find the first real error, explain the cause in 2–3
sentences, and fix it with the smallest change. Don't upgrade library versions unless the error requires it
(if so, ask me first). Re-run verify.sh and stop.
```

**Tuning with a recorded session**
```
Read docs/Implementation.md "Replay / offline testing" and "Tuning guide". Session: sessions/<name>.
Symptom: <e.g. yaw drifts 4°/min during slow pans>. Propose at most 3 single-parameter changes, run the
replay for each, run analyze_log.py, and show a table: config, drift °/min, pitch/roll RMSE, accepted
windows %. Don't change code or default configs; put candidate configs in tuning/.
```

**Self-review before commit**
```
Review the uncommitted changes against CLAUDE.md "Definition of done" and docs/Rules.md. Check:
forbidden APIs, units in names, frames in names, :core purity, thread rules, tests for edge cases,
no magic numbers, Progress.md updated. List problems; fix only the clear ones; stop.
```

**Docs consistency check**
```
Check that class names, file paths, config field names and CSV columns are consistent between
docs/DataModel.md, docs/Architecture.md, docs/Implementation.md and the code. Report mismatches as a
table; fix the docs only where the code is clearly right (otherwise ask me).
```

**Explain for learning**
```
Explain how <component, e.g. AttitudeFilter> works in this codebase as if I'm a Python ML student new to
Android: the idea, the math in 5 lines, and the 3 lines of code that matter most. No changes.
```
