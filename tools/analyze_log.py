#!/usr/bin/env python3
"""Plots and metrics for one CamIMU-Orient session.

usage: analyze_log.py <session_dir> [--est est.csv|replay/est_replay.csv] [--t0 S --t1 S] [--no-plots]
Writes <session>/analysis/metrics.json and PNG plots. Metrics: docs/TestPlan.md §6.
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path

import numpy as np

from camimu_common import load, meta, wrap_deg


def drift_deg_per_min(t_s, yaw_deg):
    if len(t_s) < 10:
        return float("nan")
    y = np.unwrap(np.radians(yaw_deg))
    return float(np.degrees(np.polyfit(t_s / 60.0, y, 1)[0]))


def analyze(session: Path, est_name: str = "est.csv", t0=None, t1=None, plots=True) -> dict:
    est = load(session, est_name)
    if est is None:
        raise SystemExit(f"no {est_name} in {session}")
    est = est[est["initialized"] == 1].copy()
    tstart = est["t_ns"].iloc[0]
    est["t"] = (est["t_ns"] - tstart) * 1e-9
    m: dict = {"session": session.name, "est_file": est_name, "duration_s": float(est["t"].iloc[-1])}
    md = meta(session)
    m["device"] = md.get("deviceModel", "")
    m["registeredSensorTypes"] = md.get("registeredSensorTypes", [])
    m["measuredGyroHz"] = md.get("measuredGyroHz")
    m["droppedRows"] = md.get("droppedRows")

    sel = est
    if t0 is not None:
        sel = sel[sel["t"] >= t0]
    if t1 is not None:
        sel = sel[sel["t"] <= t1]
    m["yaw_drift_deg_per_min"] = drift_deg_per_min(sel["t"].to_numpy(), sel["yaw_deg"].to_numpy())
    m["pitch_std_deg"] = float(sel["pitch_deg"].std())
    m["roll_std_deg"] = float(sel["roll_deg"].std())
    m["final_sigma_yaw_deg"] = float(est["sigma_yaw_deg"].iloc[-1])
    m["confidence_share"] = {k: float(v) for k, v in est["confidence"].value_counts(normalize=True).items()}

    ev = load(session, "events.csv")
    if ev is not None:
        marks = ev[ev["event"] == "MARK"]
        if len(marks) >= 2:
            def yaw_at(tn):
                w = est[(est["t_ns"] >= tn) & (est["t_ns"] <= tn + 1_000_000_000)]
                return float(np.degrees(np.angle(np.mean(np.exp(1j * np.radians(w["yaw_deg"]))))))
            m["return_to_start_deg"] = float(abs(wrap_deg(yaw_at(marks["t_ns"].iloc[-1]) - yaw_at(marks["t_ns"].iloc[0]))))
        occ = ev[ev["event"] == "OCCLUSION_STATE"]
        vis = load(session, "vision.csv")
        if len(occ) and vis is not None:
            rec = []
            acc = vis[vis["accepted"] == 1]["t_applied_ns"].to_numpy()
            for t_ok in occ[occ["detail"] == "OK"]["t_ns"]:
                nxt = acc[acc >= t_ok]
                if len(nxt):
                    rec.append((nxt[0] - t_ok) * 1e-9)
            if rec:
                m["recovery_after_occlusion_s"] = rec

    vis = load(session, "vision.csv")
    if vis is not None:
        m["vision_windows"] = int(len(vis))
        m["vision_accept_rate"] = float(vis["accepted"].mean())
        m["vision_reject_reasons"] = {str(k): int(v) for k, v in vis["reject_reason"].fillna("").value_counts().items() if k}
    fr = load(session, "vision_frames.csv")
    if fr is not None:
        m["vision_proc_ms_p50"] = float(fr["proc_ms"].quantile(0.5))
        m["vision_proc_ms_p95"] = float(fr["proc_ms"].quantile(0.95))
        dt = np.diff(fr["t_cam_ns"].to_numpy()) * 1e-9
        m["vision_fps"] = float(1.0 / np.median(dt)) if len(dt) else None
        m["tracks_median"] = float(fr["n_tracked"].median())

    ar = load(session, "arcore.csv")
    dyaw = None
    if ar is not None:
        ar = ar[ar["tracking_state"] == "TRACKING"]
        if len(ar) > 10:
            tt = est["t_ns"].to_numpy()
            ok = (tt >= ar["t_ns"].iloc[0]) & (tt <= ar["t_ns"].iloc[-1])
            e2 = est[ok]
            ay = np.degrees(np.unwrap(np.radians(ar["yaw_deg"].to_numpy())))
            a_yaw = np.interp(e2["t_ns"], ar["t_ns"], ay)
            a_p = np.interp(e2["t_ns"], ar["t_ns"], ar["pitch_deg"])
            a_r = np.degrees(np.interp(e2["t_ns"], ar["t_ns"], np.unwrap(np.radians(ar["roll_deg"]))))
            dyaw = wrap_deg(e2["yaw_deg"].to_numpy() - a_yaw)
            first = (e2["t_ns"] - e2["t_ns"].iloc[0]) < 2_000_000_000
            dyaw = wrap_deg(dyaw - np.degrees(np.angle(np.mean(np.exp(1j * np.radians(dyaw[first.to_numpy()]))))))
            dp = e2["pitch_deg"].to_numpy() - a_p
            dr = wrap_deg(e2["roll_deg"].to_numpy() - a_r)
            m["vs_arcore"] = {
                "median_abs_dyaw_deg": float(np.median(np.abs(dyaw))),
                "max_abs_dyaw_deg": float(np.max(np.abs(dyaw))),
                "pitch_rmse_deg": float(np.sqrt(np.mean(dp ** 2))),
                "roll_rmse_deg": float(np.sqrt(np.mean(dr ** 2))),
                "two_sigma_coverage": float(np.mean(np.abs(dyaw) <= 2 * e2["sigma_yaw_deg"].to_numpy() + 1e-9)),
                "note": "ARCore is a reference, not ground truth; initial yaw offset removed",
            }

    out = session / "analysis"
    out.mkdir(exist_ok=True)
    (out / "metrics.json").write_text(json.dumps(m, indent=2, default=float))
    if plots:
        make_plots(est, out, dyaw)
    return m


def make_plots(est, out: Path, dyaw):
    import matplotlib
    matplotlib.use("Agg")
    import matplotlib.pyplot as plt

    fig, ax = plt.subplots(4, 1, figsize=(11, 10), sharex=True)
    for col, c in (("yaw_deg", "tab:green"), ("pitch_deg", "tab:orange"), ("roll_deg", "tab:blue")):
        ax[0].plot(est["t"], est[col], label=col, color=c, lw=0.8)
    ax[0].set_ylabel("deg"); ax[0].legend(loc="upper right"); ax[0].set_title("Orientation (yaw relative)")
    for col in ("bgx", "bgy", "bgz"):
        ax[1].plot(est["t"], np.degrees(est[col]), label=col, lw=0.8)
    ax[1].set_ylabel("bias °/s"); ax[1].legend(loc="upper right")
    ax[2].plot(est["t"], est["sigma_yaw_deg"], color="k", lw=0.8, label="σ_yaw")
    ax[2].plot(est["t"], est["drift_sigma_deg_min"], color="tab:red", lw=0.8, label="drift σ °/min")
    ax[2].legend(loc="upper right")
    conf = est["confidence"].map({"LOW": 0, "MEDIUM": 1, "HIGH": 2})
    ax[3].step(est["t"], conf, where="post", lw=0.8)
    ax[3].set_yticks([0, 1, 2], ["LOW", "MED", "HIGH"]); ax[3].set_xlabel("t [s]")
    fig.tight_layout(); fig.savefig(out / "orientation.png", dpi=110); plt.close(fig)
    if dyaw is not None:
        fig, a = plt.subplots(figsize=(11, 3))
        a.plot(dyaw, lw=0.8); a.set_ylabel("Δyaw vs ARCore [deg]"); a.set_xlabel("sample")
        fig.tight_layout(); fig.savefig(out / "dyaw_vs_arcore.png", dpi=110); plt.close(fig)


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("session", type=Path)
    ap.add_argument("--est", default="est.csv", help="est.csv or replay/est_replay.csv (relative to session)")
    ap.add_argument("--t0", type=float); ap.add_argument("--t1", type=float)
    ap.add_argument("--no-plots", action="store_true")
    a = ap.parse_args()
    m = analyze(a.session, a.est, a.t0, a.t1, not a.no_plots)
    print(json.dumps(m, indent=2, default=float))


if __name__ == "__main__":
    main()
