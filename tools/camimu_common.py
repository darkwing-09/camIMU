"""Shared helpers for CamIMU-Orient session analysis (CSV schemas: docs/DataModel.md §5)."""
from __future__ import annotations

import json
from pathlib import Path

import numpy as np
import pandas as pd


def load(session: Path, name: str) -> pd.DataFrame | None:
    """Reads <session>/<name> (e.g. 'imu.csv'); None if missing or empty."""
    p = Path(session) / name
    if not p.exists() or p.stat().st_size == 0:
        return None
    df = pd.read_csv(p)
    return df if len(df) else None


def meta(session: Path) -> dict:
    p = Path(session) / "meta.json"
    return json.loads(p.read_text()) if p.exists() else {}


def wrap_deg(a):
    """Wrap degrees to (-180, 180]."""
    a = np.asarray(a, dtype=float)
    r = (a + 180.0) % 360.0
    r = np.where(r <= 0, r + 360.0, r)
    return r - 180.0


def qmul(a: np.ndarray, b: np.ndarray) -> np.ndarray:
    """Hamilton product of [w,x,y,z] quaternions (broadcasts over rows)."""
    w1, x1, y1, z1 = a[..., 0], a[..., 1], a[..., 2], a[..., 3]
    w2, x2, y2, z2 = b[..., 0], b[..., 1], b[..., 2], b[..., 3]
    return np.stack([
        w1 * w2 - x1 * x2 - y1 * y2 - z1 * z2,
        w1 * x2 + x1 * w2 + y1 * z2 - z1 * y2,
        w1 * y2 - x1 * z2 + y1 * w2 + z1 * x2,
        w1 * z2 + x1 * y2 - y1 * x2 + z1 * w2,
    ], axis=-1)


def qexp(phi: np.ndarray) -> np.ndarray:
    """Rotation vectors (N,3) → unit quaternions (N,4) [w,x,y,z]."""
    th = np.linalg.norm(phi, axis=-1, keepdims=True)
    small = th < 1e-8
    s = np.where(small, 0.5, np.sin(th / 2) / np.where(small, 1.0, th))
    q = np.concatenate([np.cos(th / 2), phi * s], axis=-1)
    return q / np.linalg.norm(q, axis=-1, keepdims=True)


def qlog(q: np.ndarray) -> np.ndarray:
    """Unit quaternions (N,4) → rotation vectors (N,3)."""
    q = np.where(q[..., :1] < 0, -q, q)
    v = q[..., 1:]
    s = np.linalg.norm(v, axis=-1, keepdims=True)
    k = np.where(s < 1e-12, 2.0, 2 * np.arctan2(s, q[..., :1]) / np.where(s < 1e-12, 1.0, s))
    return v * k


def qconj(q: np.ndarray) -> np.ndarray:
    return q * np.array([1.0, -1.0, -1.0, -1.0])


def gyro_attitude(imu: pd.DataFrame, bias_t_ns=None, bias=None) -> tuple[np.ndarray, np.ndarray]:
    """Integrates (ω_raw − bias) with the same midpoint rule as the app. Returns (t_ns, q[N,4])."""
    t = imu["t_ns"].to_numpy(np.int64)
    w = imu[["gx", "gy", "gz"]].to_numpy(float)
    if bias is not None:
        b = np.stack([np.interp(t, bias_t_ns, bias[:, i]) for i in range(3)], axis=1)
        w = w - b
    dt = np.diff(t) * 1e-9
    steps = qexp(((w[:-1] + w[1:]) * 0.5) * dt[:, None])
    q = np.empty((len(t), 4))
    q[0] = [1, 0, 0, 0]
    cur = np.array([1.0, 0, 0, 0])
    for i, s in enumerate(steps):
        cur = qmul(cur, s)
        cur /= np.linalg.norm(cur)
        q[i + 1] = cur
    return t, q


def interp_quat(t_ns: np.ndarray, q: np.ndarray, tq: np.ndarray) -> np.ndarray:
    """Linear-then-normalize quaternion interpolation (fine for 5 ms spacing)."""
    idx = np.clip(np.searchsorted(t_ns, tq) - 1, 0, len(t_ns) - 2)
    a = (tq - t_ns[idx]) / (t_ns[idx + 1] - t_ns[idx])
    q0, q1 = q[idx], q[idx + 1]
    q1 = np.where((np.sum(q0 * q1, axis=1) < 0)[:, None], -q1, q1)
    r = q0 * (1 - a)[:, None] + q1 * a[:, None]
    return r / np.linalg.norm(r, axis=1, keepdims=True)


def est_bias(est: pd.DataFrame | None):
    if est is None:
        return None, None
    e = est[est["initialized"] == 1]
    if not len(e):
        return None, None
    return e["t_ns"].to_numpy(np.int64), e[["bgx", "bgy", "bgz"]].to_numpy(float)
