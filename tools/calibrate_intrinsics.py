#!/usr/bin/env python3
"""Camera intrinsics from checkerboard PNGs captured in the app's CALIBRATE screen (Task 5.2).

usage: calibrate_intrinsics.py <dir_with_calib_pngs> [--cols 9 --rows 6 --square-mm 25] [--out calibration.json]
       calibrate_intrinsics.py --self-test
Writes/merges the "intrinsics" block of calibration.json (docs/DataModel.md §4).
"""
import argparse
import json
from pathlib import Path

import cv2
import numpy as np


def calibrate(images, cols, rows, square):
    objp = np.zeros((rows * cols, 3), np.float32)
    objp[:, :2] = np.mgrid[0:cols, 0:rows].T.reshape(-1, 2) * square
    objpts, imgpts, names, size = [], [], [], None
    crit = (cv2.TERM_CRITERIA_EPS + cv2.TERM_CRITERIA_MAX_ITER, 50, 1e-3)
    for name, img in images:
        size = img.shape[::-1]
        ok, c = cv2.findChessboardCorners(img, (cols, rows), cv2.CALIB_CB_ADAPTIVE_THRESH + cv2.CALIB_CB_NORMALIZE_IMAGE)
        if not ok:
            continue
        c = cv2.cornerSubPix(img, c, (5, 5), (-1, -1), crit)
        objpts.append(objp); imgpts.append(c); names.append(name)
    if len(objpts) < 6:
        raise SystemExit(f"only {len(objpts)} usable images (need ≥ 6; 20–30 recommended)")

    def run(op, ip):
        rms, k, d, rv, tv = cv2.calibrateCamera(op, ip, size, None, None)
        errs = []
        for o, i, r, t in zip(op, ip, rv, tv):
            p, _ = cv2.projectPoints(o, r, t, k, d)
            errs.append(float(np.sqrt(np.mean(np.sum((p - i) ** 2, axis=2)))))
        return rms, k, d, np.array(errs)

    rms, k, d, errs = run(objpts, imgpts)
    keep = errs <= 2 * np.median(errs)
    dropped = [n for n, kp in zip(names, keep) if not kp]
    if keep.sum() >= 6 and not keep.all():
        objpts = [o for o, kp in zip(objpts, keep) if kp]; imgpts = [i for i, kp in zip(imgpts, keep) if kp]
        rms, k, d, errs = run(objpts, imgpts)
    d = d.ravel().tolist() + [0.0] * 5
    return {
        "width": int(size[0]), "height": int(size[1]),
        "fx": float(k[0, 0]), "fy": float(k[1, 1]), "cx": float(k[0, 2]), "cy": float(k[1, 2]),
        "k1": d[0], "k2": d[1], "p1": d[2], "p2": d[3], "k3": d[4], "source": "CALIBRATED",
    }, float(rms), len(objpts), dropped


def render_board(k, rvec, tvec, size, cols, rows, sq):
    """Renders a synthetic checkerboard view (for --self-test)."""
    bw, bh = (cols + 1) * sq, (rows + 1) * sq
    scale = 4
    board = np.full((int(bh * scale), int(bw * scale)), 255, np.uint8)
    for r in range(rows + 1):
        for c in range(cols + 1):
            if (r + c) % 2 == 0:
                board[int(r * sq * scale):int((r + 1) * sq * scale), int(c * sq * scale):int((c + 1) * sq * scale)] = 0
    corners = np.array([[0, 0, 0], [bw, 0, 0], [bw, bh, 0], [0, bh, 0]], np.float32) - np.array([sq, sq, 0], np.float32)
    img_c, _ = cv2.projectPoints(corners, rvec, tvec, k, None)
    src = np.array([[0, 0], [bw * scale, 0], [bw * scale, bh * scale], [0, bh * scale]], np.float32)
    h = cv2.getPerspectiveTransform(src, img_c.reshape(4, 2).astype(np.float32))
    out = cv2.warpPerspective(board, h, size, borderValue=128)
    return cv2.GaussianBlur(out, (3, 3), 0.7)


def self_test():
    k = np.array([[520.0, 0, 318.0], [0, 520.0, 242.0], [0, 0, 1]])
    rng = np.random.default_rng(4)
    imgs = []
    for i in range(18):
        rvec = rng.normal(0, 0.35, 3)
        tvec = np.array([rng.uniform(-60, 0), rng.uniform(-50, 0), rng.uniform(250, 400)])
        imgs.append((f"syn_{i}", render_board(k, rvec, tvec, (640, 480), 9, 6, 25.0)))
    intr, rms, n, _ = calibrate(imgs, 9, 6, 25.0)
    ok = abs(intr["fx"] / 520 - 1) < 0.01 and abs(intr["cx"] - 318) < 5 and rms < 1.0
    print(f"self-test fx={intr['fx']:.1f} (520) cx={intr['cx']:.1f} (318) rms={rms:.3f} images={n} -> {'OK' if ok else 'FAIL'}")
    return ok


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("dir", nargs="?", type=Path)
    ap.add_argument("--cols", type=int, default=9); ap.add_argument("--rows", type=int, default=6)
    ap.add_argument("--square-mm", type=float, default=25.0)
    ap.add_argument("--out", type=Path, default=Path("calibration.json"))
    ap.add_argument("--self-test", action="store_true")
    a = ap.parse_args()
    if a.self_test:
        raise SystemExit(0 if self_test() else 1)
    if not a.dir:
        ap.error("dir required")
    files = sorted(a.dir.glob("calib_*.png"))
    images = [(f.name, cv2.imread(str(f), cv2.IMREAD_GRAYSCALE)) for f in files]
    intr, rms, n, dropped = calibrate(images, a.cols, a.rows, a.square_mm)
    cal = json.loads(a.out.read_text()) if a.out.exists() else {}
    cal["intrinsics"] = intr; cal["intrinsicsRmsPx"] = rms
    a.out.write_text(json.dumps(cal, indent=2))
    print(json.dumps({"rms_px": rms, "images_used": n, "dropped": dropped, "intrinsics": intr}, indent=2))
    print("quality:", "good" if rms < 0.5 else "acceptable" if rms < 1.0 else "poor — recapture")
    print(f"wrote {a.out}. Push: adb push {a.out} /sdcard/Android/data/com.camimu.orient/files/calibration.json")


if __name__ == "__main__":
    main()
