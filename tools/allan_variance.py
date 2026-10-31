#!/usr/bin/env python3
"""Allan deviation of a static gyro recording → gyroNoiseDensity (N) and biasRandomWalk (K) (Task 5.3).

usage: allan_variance.py <session_dir> [--out calibration.json]   (≥ 20 min static recommended)
       allan_variance.py --self-test
"""
import argparse
import json
from pathlib import Path

import numpy as np


def adev(rate: np.ndarray, fs: float, n_taus: int = 60):
    """Overlapping Allan deviation of a rate signal. Returns (taus, sigmas)."""
    theta = np.cumsum(rate) / fs
    n = len(theta)
    ms = np.unique(np.logspace(0, np.log10(max(2, n // 10)), n_taus).astype(int))
    taus, sig = [], []
    for m in ms:
        if n - 2 * m < 10:
            break
        d = theta[2 * m:] - 2 * theta[m:-m] + theta[:-2 * m]
        tau = m / fs
        taus.append(tau)
        sig.append(np.sqrt(np.sum(d * d) / (2 * tau * tau * (n - 2 * m))))
    return np.array(taus), np.array(sig)


def fit_params(taus, sig):
    """N from the −1/2 slope region (σ·√τ), K from the +1/2 region (σ·√(3/τ)), B = min σ / 0.664."""
    slope = np.gradient(np.log(sig), np.log(taus))
    white = (slope < -0.35) & (slope > -0.65)
    n_val = float(np.median(sig[white] * np.sqrt(taus[white]))) if white.any() else float(sig[0] * np.sqrt(taus[0]))
    rw = slope > 0.35
    k_val = float(np.median(sig[rw] * np.sqrt(3.0 / taus[rw]))) if rw.any() else None
    b_val = float(sig.min() / 0.664)
    if k_val is None:  # rough fallback (stated in output)
        k_val = float(b_val / np.sqrt(taus[np.argmin(sig)]))
    return n_val, k_val, b_val, bool(rw.any())


def run(session: Path):
    import pandas as pd
    imu = pd.read_csv(session / "imu.csv")
    t = imu["t_ns"].to_numpy(np.int64)
    fs = 1.0 / np.median(np.diff(t) * 1e-9)
    res = {"fs_hz": fs, "duration_min": (t[-1] - t[0]) * 1e-9 / 60}
    ns, ks, curves = [], [], {}
    for ax in ("gx", "gy", "gz"):
        taus, sig = adev(imu[ax].to_numpy(float), fs)
        n_val, k_val, b_val, has_rw = fit_params(taus, sig)
        res[ax] = {"N_rad_s_sqrtHz": n_val, "K_rad_s_sqrt_s": k_val, "bias_instability_rad_s": b_val, "K_from_rw_region": has_rw}
        ns.append(n_val); ks.append(k_val); curves[ax] = (taus, sig)
    res["gyroNoiseDensity"] = float(np.max(ns))
    res["biasRandomWalk"] = float(np.max(ks))
    import matplotlib
    matplotlib.use("Agg")
    import matplotlib.pyplot as plt
    fig, a = plt.subplots(figsize=(7, 5))
    for ax, (tt, ss) in curves.items():
        a.loglog(tt, ss, label=ax)
    a.set_xlabel("τ [s]"); a.set_ylabel("Allan deviation [rad/s]"); a.grid(True, which="both", alpha=0.3); a.legend()
    out = session / "analysis"; out.mkdir(exist_ok=True)
    fig.savefig(out / "allan.png", dpi=110)
    return res


def self_test():
    rng = np.random.default_rng(1)
    fs, n_true, k_true = 200.0, 2e-4, 5e-5
    n = int(fs * 3600)
    white = rng.normal(0, n_true * np.sqrt(fs), n)
    walk = np.cumsum(rng.normal(0, k_true / np.sqrt(fs), n))
    taus, sig = adev(white + walk, fs)
    n_val, k_val, _, has_rw = fit_params(taus, sig)
    ok = abs(n_val / n_true - 1) < 0.1 and has_rw and abs(k_val / k_true - 1) < 0.35
    print(f"self-test N={n_val:.3e} (true {n_true}) K={k_val:.3e} (true {k_true}) -> {'OK' if ok else 'FAIL'}")
    return ok


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("session", nargs="?", type=Path)
    ap.add_argument("--out", type=Path, help="merge values into this calibration.json")
    ap.add_argument("--self-test", action="store_true")
    a = ap.parse_args()
    if a.self_test:
        raise SystemExit(0 if self_test() else 1)
    if not a.session:
        ap.error("session required")
    res = run(a.session)
    print(json.dumps(res, indent=2))
    if a.out:
        cal = json.loads(a.out.read_text()) if a.out.exists() else {}
        cal["gyroNoiseDensity"] = res["gyroNoiseDensity"]; cal["biasRandomWalk"] = res["biasRandomWalk"]
        a.out.write_text(json.dumps(cal, indent=2)); print(f"updated {a.out}")


if __name__ == "__main__":
    main()
