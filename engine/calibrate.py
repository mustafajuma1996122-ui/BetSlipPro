"""Isotonic calibration (pool-adjacent-violators). Learned walk-forward, applied only if it improves held-out log-loss."""


def pav(points):
    """points: iterable of (p, y). Returns monotone step function as sorted list of (x_mean, y_mean)."""
    pts = sorted(points)
    blocks = []  # [sum_x, sum_y, n]
    for p, y in pts:
        blocks.append([p, y, 1])
        while len(blocks) >= 2 and blocks[-2][1] / blocks[-2][2] > blocks[-1][1] / blocks[-1][2]:
            b = blocks.pop()
            blocks[-1][0] += b[0]; blocks[-1][1] += b[1]; blocks[-1][2] += b[2]
    return [(b[0] / b[2], b[1] / b[2]) for b in blocks]


def thin(curve, max_points=25):
    if len(curve) <= max_points:
        return curve
    step = (len(curve) - 1) / (max_points - 1)
    return [curve[round(i * step)] for i in range(max_points)]


def fit(triples_outcomes, min_samples=300):
    """triples_outcomes: list of ((ph, pd, pa), outcome_idx). Pools the three classes into one curve."""
    if len(triples_outcomes) < min_samples:
        return None
    pts = [(p[k], 1.0 if o == k else 0.0) for p, o in triples_outcomes for k in range(3)]
    curve = thin(pav(pts))
    return {"x": [round(x, 5) for x, _ in curve], "y": [round(y, 5) for _, y in curve]}


def apply_one(cal, p):
    xs, ys = cal["x"], cal["y"]
    if p <= xs[0]:
        return ys[0]
    if p >= xs[-1]:
        return ys[-1]
    lo, hi = 0, len(xs) - 1
    while hi - lo > 1:
        mid = (lo + hi) // 2
        if xs[mid] <= p:
            lo = mid
        else:
            hi = mid
    t = (p - xs[lo]) / max(xs[hi] - xs[lo], 1e-12)
    return ys[lo] + t * (ys[hi] - ys[lo])


def apply(cal, triple):
    """Calibrate a (home, draw, away) triple and renormalise. Identity when cal is None."""
    if not cal:
        return tuple(triple)
    out = [max(apply_one(cal, p), 0.005) for p in triple]
    s = sum(out)
    return tuple(x / s for x in out)
