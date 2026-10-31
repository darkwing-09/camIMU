#!/usr/bin/env python3
"""Camera–IMU time offset t_d by cross-correlating rotation-rate magnitudes (Task 5.4).

usage: estimate_time_offset.py <session_dir> [--range-ms 60]
Convention: a camera sample at camera time t_c happened at IMU time t_c + t_d.
Put the result into calibration.json as "timeOffsetNs" (for BENCHMARK recordings: "arcoreTimeOffsetNs",
added on top of whatever is already configured).
"""
import argparse
import json
from pathlib import Path

import numpy as np

from camimu_common import est_bias, load, meta


def estimate(session: Path, range_ms: float = 60.0) -> dict:
    imu = load(session, "imu.csv"); fr = load(session, "vision_frames.csv")
    if imu is None or fr is None:
        raise SystemExit("needs imu.csv and vision_frames.csv")
    fr = fr.dropna(subset=["rvx"])
    tg = imu["t_ns"].to_numpy(np.int64)
    w = imu[["gx", "gy", "gz"]].to_numpy(float)
    bt, b = est_bias(load(session, "est.csv"))
    if b is not None:
        w = w - np.stack([np.interp(tg, bt, b[:, i]) for i in range(3)], axis=1)
    g_rate = np.linalg.norm(w, axis=1)
    tc0 = fr["t_cam_prev_ns"].to_numpy(np.int64); tc1 = fr["t_cam_ns"].to_numpy(np.int64)
    tv = (tc0 + tc1) // 2
    v_rate = np.linalg.norm(fr[["rvx", "rvy", "rvz"]].to_numpy(float), axis=1) / ((tc1 - tc0) * 1e-9)
    # The frame timestamps actually used on the device (t_ns) already contain any configured offset.
    configured = int(np.median(fr["t_ns"].to_numpy(np.int64) - tc1))
    base = tg[0]
    g_t = (tg - base) * 1e-9
    v_t = (tv + configured - base) * 1e-9
    lo = max(g_t[0], v_t[0]) + range_ms / 1000 + 0.1
    hi = min(g_t[-1], v_t[-1]) - range_ms / 1000 - 0.1
    if hi - lo < 5:
        raise SystemExit("recording too short (need ≥ 5 s of overlap with motion)")
    grid = np.arange(lo, hi, 0.001)
    vs = np.interp(grid, v_t, v_rate)
    vs = (vs - vs.mean()) / (vs.std() + 1e-12)
    shifts = np.arange(-range_ms, range_ms + 1, 1.0)
    corr = []
    for s in shifts:
        gs = np.interp(grid + s / 1000.0, g_t, g_rate)
        gs = (gs - gs.mean()) / (gs.std() + 1e-12)
        corr.append(float(np.mean(vs * gs)))
    corr = np.array(corr)
    k = int(np.argmax(corr))
    best = shifts[k]
    if 0 < k < len(corr) - 1:
        a, b0, c = corr[k - 1], corr[k], corr[k + 1]
        den = a - 2 * b0 + c
        if den != 0:
            best += 0.5 * (a - c) / den
    total_ns = int(round(configured + best * 1e6))
    return {
        "additional_offset_ms": float(best),
        "configured_offset_ms": configured / 1e6,
        "timeOffsetNs_total": total_ns,
        "peak_correlation": float(corr[k]),
        "truth_ns_if_synthetic": meta(session).get("notes", {}).get("truthTimeOffsetNs"),
        "quality": "good" if corr[k] > 0.8 else "weak: record faster, varied rotations at a textured scene",
    }


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("session", type=Path)
    ap.add_argument("--range-ms", type=float, default=60.0)
    a = ap.parse_args()
    print(json.dumps(estimate(a.session, a.range_ms), indent=2))


if __name__ == "__main__":
    main()
