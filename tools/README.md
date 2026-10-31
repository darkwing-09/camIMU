# tools/ — analysis & calibration (Python)

```bash
python3 -m venv .venv && . .venv/bin/activate && pip install -r tools/requirements.txt
```
(The system Python on this machine already has numpy/pandas/matplotlib/scipy/OpenCV, so the venv is optional.)

| Script | Purpose | Self-check |
|---|---|---|
| `analyze_log.py <session>` | plots + `analysis/metrics.json` (drift, return-to-start via MARKs, vs-ARCore errors, vision stats, recovery, 2σ coverage) | run on a synthetic session |
| `axis_check.py <session>` | gyro vs vision rotation per axis → verifies `rCD` signs and intrinsics scale (Task 3.8) | synthetic: slopes ≈ 1.000 |
| `estimate_time_offset.py <session>` | camera–IMU time offset by rate cross-correlation (Task 5.4) | synthetic: recovers 12 ms (±1 ms) |
| `allan_variance.py <session> [--out calibration.json]` | gyro noise N and bias random walk K (Task 5.3) | `--self-test` |
| `calibrate_intrinsics.py <dir> [--out calibration.json]` | checkerboard intrinsics (Task 5.2) | `--self-test` (rendered boards) |
| `report.py sessions/* [--out docs/Results.md]` | results table over many sessions (Task 7.3) | — |

Synthetic session with known truth (no phone needed):
```bash
./gradlew :replay:run --args="--synthesize sessions/SYNTH"
python3 tools/analyze_log.py sessions/SYNTH
python3 tools/axis_check.py sessions/SYNTH
python3 tools/estimate_time_offset.py sessions/SYNTH
```

Pull real sessions from the phone:
```bash
adb pull /sdcard/Android/data/com.camimu.orient/files/sessions/ ./sessions/
```
Push calibration back:
```bash
adb push calibration.json /sdcard/Android/data/com.camimu.orient/files/calibration.json
```
