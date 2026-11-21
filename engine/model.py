"""Reference implementation of the BET SLIP PRO model (mirrors app/.../ScoreModel.kt and StrengthModel.kt).

Pure standard library. Used by backtest.py to validate and tune the model on free historical data
(football-data.co.uk) before changing the constants shipped in the Android app.
"""
import math
from datetime import date

MAX_GOALS = 10


def days_from_iso(s):
    """'YYYY-MM-DD...' -> days since epoch (same algorithm as StrengthModel.epochDay in Kotlin)."""
    y, m, d = int(s[0:4]), int(s[5:7]), int(s[8:10])
    return date(y, m, d).toordinal() - date(1970, 1, 1).toordinal()


def poisson_pmf(lam, n=MAX_GOALS):
    p = [math.exp(-lam)]
    for i in range(1, n + 1):
        p.append(p[-1] * lam / i)
    s = sum(p)
    return [x / s for x in p]


def tau(h, a, lh, la, rho):
    if h == 0 and a == 0:
        return 1.0 - lh * la * rho
    if h == 0 and a == 1:
        return 1.0 + lh * rho
    if h == 1 and a == 0:
        return 1.0 + la * rho
    if h == 1 and a == 1:
        return 1.0 - rho
    return 1.0


def score_matrix(lh, la, rho=-0.10):
    ph, pa = poisson_pmf(lh), poisson_pmf(la)
    m = [[ph[h] * pa[a] * max(tau(h, a, lh, la, rho), 0.0) for a in range(MAX_GOALS + 1)] for h in range(MAX_GOALS + 1)]
    total = sum(sum(r) for r in m)
    return [[x / total for x in r] for r in m]


def markets(m):
    home = draw = away = btts = o15 = o25 = o35 = 0.0
    for h, row in enumerate(m):
        for a, p in enumerate(row):
            if h > a: home += p
            elif h == a: draw += p
            else: away += p
            if h > 0 and a > 0: btts += p
            if h + a >= 2: o15 += p
            if h + a >= 3: o25 += p
            if h + a >= 4: o35 += p
    return dict(home=home, draw=draw, away=away, btts=btts, o15=o15, o25=o25, o35=o35)


def fit_strengths(matches, now_day, half_life_days=150.0, prior_matches=4.0, iterations=40):
    """matches: iterable of (day, home_id, away_id, home_goals, away_goals).

    Iterative multiplicative Poisson fit (Maher / Dixon-Coles style):
      lambda_home = base * gamma * A[home] * D[away]
      lambda_away = base * A[away] * D[home]
    with exponential time decay and Bayesian shrinkage of A and D towards 1.
    """
    rows = []
    for day, h, a, hg, ag in matches:
        age = max(now_day - day, 0)
        w = 0.5 ** (age / half_life_days)
        rows.append((h, a, hg, ag, w))
    teams = {t for r in rows for t in (r[0], r[1])}
    A = {t: 1.0 for t in teams}
    D = {t: 1.0 for t in teams}
    sw = sum(r[4] for r in rows)
    if not rows or sw <= 0:
        return None
    base = max(sum(r[4] * (r[2] + r[3]) for r in rows) / (2 * sw), 0.2)
    gamma = 1.2
    for _ in range(iterations):
        eh = sum(w * A[h] * D[a] for h, a, hg, ag, w in rows)
        gamma = sum(w * hg for h, a, hg, ag, w in rows) / max(base * eh, 1e-9)
        gamma = min(max(gamma, 0.8), 1.6)
        den = sum(w * (gamma * A[h] * D[a] + A[a] * D[h]) for h, a, hg, ag, w in rows)
        base = sum(w * (hg + ag) for h, a, hg, ag, w in rows) / max(den, 1e-9)
        k = prior_matches * base
        numA = {t: k for t in teams}; denA = {t: k for t in teams}
        for h, a, hg, ag, w in rows:
            numA[h] += w * hg; denA[h] += w * base * gamma * D[a]
            numA[a] += w * ag; denA[a] += w * base * D[h]
        for t in teams:
            A[t] = numA[t] / denA[t]
        numD = {t: k for t in teams}; denD = {t: k for t in teams}
        for h, a, hg, ag, w in rows:
            numD[h] += w * ag; denD[h] += w * base * A[a]
            numD[a] += w * hg; denD[a] += w * base * gamma * A[h]
        for t in teams:
            D[t] = numD[t] / denD[t]
        mA = sum(A.values()) / len(A); mD = sum(D.values()) / len(D)
        for t in teams:
            A[t] /= mA; D[t] /= mD
        base *= mA * mD
    return dict(A=A, D=D, base=base, gamma=gamma, n=len(rows))


def predict_lambdas(fit, home, away):
    if fit is None or home not in fit["A"] or away not in fit["A"]:
        return None
    lh = fit["base"] * fit["gamma"] * fit["A"][home] * fit["D"][away]
    la = fit["base"] * fit["A"][away] * fit["D"][home]
    return min(max(lh, 0.15), 4.5), min(max(la, 0.15), 4.5)


def no_vig(odds):
    raw = [1.0 / o for o in odds]
    s = sum(raw)
    return [r / s for r in raw]


def blend(model, market, w):
    return [(1 - w) * m + w * k for m, k in zip(model, market)]


def log_loss(probs, outcome_idx):
    return -math.log(max(probs[outcome_idx], 1e-9))


def brier(probs, outcome_idx):
    return sum((p - (1.0 if i == outcome_idx else 0.0)) ** 2 for i, p in enumerate(probs))


def rps(probs, outcome_idx):
    """Ranked probability score for ordered outcomes (home, draw, away). Lower is better."""
    cum_p = cum_o = 0.0
    total = 0.0
    for i in range(len(probs) - 1):
        cum_p += probs[i]
        cum_o += 1.0 if i == outcome_idx else 0.0
        total += (cum_p - cum_o) ** 2
    return total / (len(probs) - 1)
