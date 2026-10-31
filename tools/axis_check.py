#!/usr/bin/env python3
"""Gyro-vs-vision axis/sign check for rCD (Implementation.md Task 3.8).

usage: axis_check.py <session_dir>
Compares each frame-pair vision rotation (vision_frames.csv, device frame) with the gyro rotation
over the same interval. Expected: per-axis correlation > 0.9 and slope 0.9–1.1 (positive!).
"""
import argparse
import json
from pathlib import Path

import numpy as np

from camimu_common import est_bias, gyro_attitude, interp_quat, load, qconj, qlog, qmul


def check(session: Path) -> dict:
    imu = load(session, "imu.csv"); fr = load(session, "vision_frames.csv")
    if imu is None or fr is None:
        raise SystemExit("needs imu.csv and vision_frames.csv")
    fr = fr.dropna(subset=["rvx"])
    bt, b = est_bias(load(session, "est.csv"))
    t, q = gyro_attitude(imu, bt, b)
    t2 = fr["t_ns"].to_numpy(np.int64)
    t1 = t2 - (fr["t_cam_ns"].to_numpy(np.int64) - fr["t_cam_prev_ns"].to_numpy(np.int64))
    ok = (t1 >= t[0]) & (t2 <= t[-1])
    q1 = interp_quat(t, q, t1[ok]); q2 = interp_quat(t, q, t2[ok])
    rg = qlog(qmul(qconj(q1), q2))
    rv = fr[["rvx", "rvy", "rvz"]].to_numpy(float)[ok]
    res = {}
    for i, ax in enumerate("xyz"):
        g, v = rg[:, i], rv[:, i]
        corr = float(np.corrcoef(g, v)[0, 1]) if np.std(g) > 0 and np.std(v) > 0 else float("nan")
        slope = float(np.dot(g, v) / np.dot(g, g)) if np.dot(g, g) > 0 else float("nan")
        res[ax] = {"corr": corr, "slope": slope}
    cross = np.corrcoef(np.hstack([rg, rv]).T)[:3, 3:]
    res["cross_corr_gyro_rows_vision_cols"] = cross.round(3).tolist()
    res["median_abs_diff_deg_per_frame"] = float(np.degrees(np.median(np.linalg.norm(rg - rv, axis=1))))
    res["verdict"] = "OK" if all(res[a]["corr"] > 0.9 and 0.9 <= res[a]["slope"] <= 1.1 for a in "xyz") else "CHECK rCD / intrinsics"
    return res


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("session", type=Path)
    print(json.dumps(check(ap.parse_args().session), indent=2))


if __name__ == "__main__":
    main()
