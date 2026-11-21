"""Public track record computed only from the frozen ledger."""
from collections import defaultdict

from .backtest import outcome
from .model import brier, log_loss, no_vig, rps


def _o(e):
    hg, ag = e["result"]
    return 0 if hg > ag else (1 if hg == ag else 2)


def compute(entries):
    graded = [e for e in entries if e["status"] == "graded" and e.get("result")]
    out = dict(total_predictions=len(entries), graded=len(graded), pending=len(entries) - len(graded))
    if not graded:
        return out

    def agg(get):
        rows = [(get(e), _o(e)) for e in graded if get(e)]
        if not rows:
            return None
        return dict(n=len(rows), log_loss=sum(log_loss(p, o) for p, o in rows) / len(rows),
                    brier=sum(brier(p, o) for p, o in rows) / len(rows), rps=sum(rps(p, o) for p, o in rows) / len(rows))

    t = lambda d: [d["home"], d["draw"], d["away"]] if d else None
    out["final"] = agg(lambda e: t(e["probs"]))
    out["model_only"] = agg(lambda e: t(e["model_probs"]))
    out["market_only"] = agg(lambda e: t(e["market_probs"]))
    out["uniform"] = agg(lambda e: [1 / 3] * 3)

    buckets = defaultdict(lambda: [0, 0.0, 0])
    for e in graded:
        for k, key in enumerate(("home", "draw", "away")):
            p = e["probs"][key]
            b = min(int(p * 10), 9)
            buckets[b][0] += 1; buckets[b][1] += p; buckets[b][2] += int(_o(e) == k)
    out["calibration"] = [dict(bucket=f"{b*10}-{b*10+10}%", n=v[0], predicted=v[1] / v[0], observed=v[2] / v[0]) for b, v in sorted(buckets.items())]

    bets, clv = [], []
    for e in graded:
        for b in e["value_bets"]:
            bets.append(b)
            c = (e.get("closing") or {})
            fair = None
            if b["market"] == "1X2" and c.get("odds"):
                f = no_vig(c["odds"]); idx = 1 if b["pick"] == "تعادل" else (0 if b["pick"] == f"فوز {e['home']}" else 2)
                fair = f[idx]
            elif b["market"] == "الأهداف" and c.get("ou"):
                f = no_vig(c["ou"]); fair = f[0] if "أكثر" in b["pick"] and "2.5" in b["pick"] else (f[1] if "2.5" in b["pick"] else None)
            if fair:
                clv.append(b["odds"] * fair - 1.0)  # >0 means we beat the closing price
    if bets:
        stake = len(bets)
        ret = sum(b["odds"] for b in bets if b.get("won"))
        out["value_bets"] = dict(n=len(bets), hit_rate=sum(1 for b in bets if b.get("won")) / len(bets),
                                 flat_roi=(ret - stake) / stake, avg_clv=(sum(clv) / len(clv)) if clv else None, clv_n=len(clv),
                                 note="عينة صغيرة لا تُثبت شيئاً قبل مئات الرهانات؛ اعتمد على CLV أكثر من العائد.")
    return out
