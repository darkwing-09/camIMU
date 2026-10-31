#!/usr/bin/env python3
"""Aggregates analysis/metrics.json of many sessions into a Markdown results table.

usage: report.py <session_dir>... [--out docs/Results.md]   (runs analyze_log on sessions lacking metrics)
"""
import argparse
import json
from pathlib import Path

from analyze_log import analyze

TARGETS = "Targets (PRD §5): static drift ≤ 0.5°/min · slow-motion drift ≤ 2°/min · 360° return ≤ 4° (≤ 6° with people) · " \
          "pitch/roll vs reference ≤ 1.5° · vision p50 ≤ 20 ms"


def fmt(v, nd=2):
    return "—" if v is None else f"{v:.{nd}f}" if isinstance(v, (int, float)) else str(v)


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("sessions", nargs="+", type=Path)
    ap.add_argument("--out", type=Path, default=Path("docs/Results.md"))
    a = ap.parse_args()
    rows = []
    for s in a.sessions:
        if not (s / "est.csv").exists():
            continue
        mp = s / "analysis" / "metrics.json"
        m = json.loads(mp.read_text()) if mp.exists() else analyze(s, plots=True)
        va = m.get("vs_arcore", {})
        rows.append([
            s.name, m.get("device", ""), fmt(m.get("duration_s"), 0), fmt(m.get("yaw_drift_deg_per_min")),
            fmt(m.get("return_to_start_deg")), fmt(va.get("median_abs_dyaw_deg")), fmt(va.get("pitch_rmse_deg")),
            fmt(va.get("roll_rmse_deg")), fmt(m.get("vision_accept_rate")), fmt(m.get("vision_proc_ms_p50"), 1),
            fmt(m.get("droppedRows"), 0),
        ])
    hdr = ["session", "device", "dur s", "drift °/min", "return °", "|Δyaw| vs AR °", "pitch RMSE °", "roll RMSE °",
           "vision acc.", "proc p50 ms", "dropped"]
    lines = ["# Results", "", TARGETS, "", "| " + " | ".join(hdr) + " |", "|" + "---|" * len(hdr)]
    lines += ["| " + " | ".join(r) + " |" for r in rows]
    lines += ["", "Analysis per failing scenario (cause · evidence plot · fix):", "", "- (fill in)"]
    a.out.write_text("\n".join(lines) + "\n")
    print(f"wrote {a.out} ({len(rows)} sessions)")


if __name__ == "__main__":
    main()
