"""Walk-forward tuning on free historical data (football-data.co.uk).

Every prediction is made only from matches played BEFORE it. Output: best half-life / prior / rho,
best market-blend weight and an isotonic calibration curve that is kept only if it improves held-out log-loss.
"""
import math
from collections import defaultdict

from . import calibrate
from .model import (blend, brier, fit_strengths, log_loss, markets, no_vig, predict_lambdas, rps, score_matrix)

WINDOW_DAYS = 400


def outcome(m):
    return 0 if m["hg"] > m["ag"] else (1 if m["hg"] == m["ag"] else 2)


def collect(rows, half_life, prior, min_history=120, refit_days=14, iterations=30):
    """Fit once per (half_life, prior); returns [{m, lam}] for matches predictable from past data only."""
    by_league = defaultdict(list)
    for r in rows:
        by_league[r["league"]].append(r)
    out = []
    for league, lrows in by_league.items():
        lrows.sort(key=lambda r: r["day"])
        cache_day, fit = None, None
        for i, m in enumerate(lrows):
            if i < min_history:
                continue
            if cache_day is None or m["day"] - cache_day >= refit_days:
                hist = [r for r in lrows[:i] if m["day"] - r["day"] <= WINDOW_DAYS]
                if len(hist) < min_history:
                    continue
                fit = fit_strengths([(r["day"], r["home"], r["away"], r["hg"], r["ag"]) for r in hist], m["day"], half_life, prior, iterations)
                cache_day = m["day"]
            lam = predict_lambdas(fit, m["home"], m["away"]) if fit else None
            if lam:
                out.append(dict(m=m, lam=lam))
    out.sort(key=lambda r: r["m"]["day"])
    return out


def with_rho(results, rho):
    for r in results:
        mk = markets(score_matrix(*r["lam"], rho))
        r["model"] = [mk["home"], mk["draw"], mk["away"]]
        r["market"] = no_vig(r["m"]["odds"]) if r["m"]["odds"] else None
    return results


def mean_ll(rs, key):
    rs = [r for r in rs if r.get(key) is not None]
    return sum(log_loss(r[key], outcome(r["m"])) for r in rs) / len(rs) if rs else None


def tune(rows, grid_hl=(120, 200), grid_prior=(3, 6), grid_rho=(0.0, -0.05, -0.10, -0.15), log=print):
    best = None
    for hl in grid_hl:
        for pr in grid_prior:
            res = collect(rows, hl, pr)
            for rho in grid_rho:
                ll = mean_ll(with_rho(res, rho), "model")
                if ll is not None and (best is None or ll < best[0]):
                    best = (ll, hl, pr, rho)
            log(f"  half_life={hl} prior={pr} done (n={len(res)})")
    if best is None:
        return None
    _, hl, pr, rho = best
    res = with_rho(collect(rows, hl, pr), rho)

    withmk = [r for r in res if r["market"]]
    best_w, best_wll = 0.5, None
    for w in [i / 10 for i in range(0, 11)]:
        ll = sum(log_loss(blend(r["model"], r["market"], w), outcome(r["m"])) for r in withmk) / len(withmk) if withmk else None
        if ll is not None and (best_wll is None or ll < best_wll):
            best_wll, best_w = ll, w
    for r in res:
        r["final"] = blend(r["model"], r["market"], best_w) if r["market"] else r["model"]

    # Calibration: learn on the older 70%, accept only if it helps on the newest 30%.
    cut = int(len(res) * 0.7)
    learn, hold = res[:cut], res[cut:]
    cal_try = calibrate.fit([(tuple(r["final"]), outcome(r["m"])) for r in learn])
    use_cal, gain = None, 0.0
    if cal_try and hold:
        base_ll = sum(log_loss(r["final"], outcome(r["m"])) for r in hold) / len(hold)
        cal_ll = sum(log_loss(calibrate.apply(cal_try, r["final"]), outcome(r["m"])) for r in hold) / len(hold)
        gain = base_ll - cal_ll
        if gain > 0.0005:
            use_cal = calibrate.fit([(tuple(r["final"]), outcome(r["m"])) for r in res])

    def agg(key, rs):
        rs = [r for r in rs if r.get(key) is not None]
        if not rs:
            return None
        return dict(log_loss=sum(log_loss(r[key], outcome(r["m"])) for r in rs) / len(rs),
                    brier=sum(brier(r[key], outcome(r["m"])) for r in rs) / len(rs),
                    rps=sum(rps(r[key], outcome(r["m"])) for r in rs) / len(rs), n=len(rs))

    for r in res:
        r["uniform"] = [1 / 3] * 3
    report = {k: agg(k, res if k != "market" else withmk) for k in ("uniform", "model", "market", "final")}
    report["final_on_odds_rows"] = agg("final", withmk)
    report["calibration_gain_holdout"] = gain
    # calibration table of the final 1X2 probabilities (all three classes pooled)
    buckets = defaultdict(lambda: [0, 0.0, 0])
    for r in res:
        for k in range(3):
            b = min(int(r["final"][k] * 10), 9)
            buckets[b][0] += 1; buckets[b][1] += r["final"][k]; buckets[b][2] += int(outcome(r["m"]) == k)
    report["calibration_table"] = [dict(bucket=f"{b*10}-{b*10+10}%", n=v[0], predicted=v[1] / v[0], observed=v[2] / v[0]) for b, v in sorted(buckets.items())]
    params = dict(half_life=float(hl), prior=float(pr), rho=rho, market_weight=best_w, calibration=use_cal)
    return params, report


def markdown(params, report, n_rows):
    L = ["# تقرير الاختبار التاريخي (walk-forward)", "",
         f"المباريات المقيّمة: {report['model']['n'] if report.get('model') else 0} (من أصل {n_rows} مباراة تاريخية)",
         f"المعاملات: half_life={params['half_life']}، prior={params['prior']}، rho={params['rho']}، وزن السوق={params['market_weight']}",
         f"المعايرة: {'مفعّلة' if params['calibration'] else 'غير مفعّلة (لم تُحسّن الاختبار المنفصل)'} — الفرق في log-loss: {report['calibration_gain_holdout']:.4f}", "",
         "| النموذج | log-loss | Brier | RPS | n |", "|---|---|---|---|---|"]
    names = {"uniform": "عشوائي 1/3", "model": "النموذج فقط", "market": "السوق (بدون هامش)", "final": "النهائي (نموذج+سوق+معايرة)"}
    for k, label in names.items():
        a = report.get(k)
        L.append(f"| {label} | {a['log_loss']:.4f} | {a['brier']:.4f} | {a['rps']:.4f} | {a['n']} |" if a else f"| {label} | - | - | - | 0 |")
    L += ["", "## جدول المعايرة", "", "| الشريحة | n | المتوقع | الفعلي |", "|---|---|---|---|"]
    for b in report["calibration_table"]:
        L.append(f"| {b['bucket']} | {b['n']} | {b['predicted']:.3f} | {b['observed']:.3f} |")
    L += ["", "> فحص جودة على بيانات سابقة؛ لا يضمن نتائج أو أرباحاً مستقبلية."]
    return "\n".join(L)
