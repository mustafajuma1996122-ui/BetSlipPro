"""Turns upcoming fixtures into calibrated probabilities and (only when justified) value picks."""
from collections import defaultdict

from . import calibrate
from .backtest import WINDOW_DAYS
from .data import LEAGUES, fixture_id
from .model import blend, fit_strengths, markets, no_vig, predict_lambdas, score_matrix

MODEL_VERSION = "V3.0-DC-Market-Isotonic"

# Value-pick rules (deliberately strict; most matches should end up as "no bet").
MIN_EV = 0.04          # expected return per unit stake must be at least +4%
MIN_PROB = 0.30        # avoid long shots
MAX_ODDS = 5.0
MAX_DISAGREE = 0.10    # model vs market probability gap above this is treated as suspicious, not value
MIN_QUALITY = 65
KELLY_FRACTION = 0.125
KELLY_CAP = 0.02


def _quality(n_home, n_away, fit_n, has_odds):
    score, warnings = 100, []
    m = min(n_home, n_away)
    if m < 8:
        score -= 35; warnings.append("سجل أحد الفريقين أقل من 8 مباريات")
    elif m < 15:
        score -= 10; warnings.append("سجل أحد الفريقين أقل من 15 مباراة")
    if fit_n < 100:
        score -= 20; warnings.append(f"عدد مباريات الدوري المستخدمة محدود ({fit_n})")
    if not has_odds:
        score -= 15; warnings.append("لا توجد أسعار سوق لهذه المباراة")
    return max(score, 0), warnings


def _bet(label_market, label_pick, p, odds, fair):
    ev = p * odds - 1.0
    b = odds - 1.0
    kelly = max((b * p - (1 - p)) / b, 0.0) * KELLY_FRACTION if b > 0 else 0.0
    return dict(market=label_market, pick=label_pick, probability=p, odds=odds, market_fair=fair,
                ev=ev, kelly=min(kelly, KELLY_CAP))


def predict_fixtures(history, fixtures, params, today_day):
    """history: finished matches (dict rows). fixtures: upcoming rows. Returns list of fixture dicts."""
    by_league = defaultdict(list)
    for r in history:
        if r["day"] < today_day:
            by_league[r["league"]].append(r)
    fits, counts = {}, {}
    out = []
    for fx in fixtures:
        lg = fx["league"]
        hist = [r for r in by_league.get(lg, []) if today_day - r["day"] <= WINDOW_DAYS]
        if lg not in fits:
            fits[lg] = fit_strengths([(r["day"], r["home"], r["away"], r["hg"], r["ag"]) for r in hist], today_day,
                                     params["half_life"], params["prior"]) if len(hist) >= 60 else None
            c = defaultdict(int)
            for r in hist:
                c[r["home"]] += 1; c[r["away"]] += 1
            counts[lg] = c
        fit = fits[lg]
        lam = predict_lambdas(fit, fx["home"], fx["away"]) if fit else None
        if lam is None:
            continue
        mk = markets(score_matrix(*lam, params["rho"]))
        model = [mk["home"], mk["draw"], mk["away"]]
        market = no_vig(fx["odds"]) if fx["odds"] else None
        final = blend(model, market, params["market_weight"]) if market else model
        final = list(calibrate.apply(params.get("calibration"), final))

        p_over25 = mk["o25"]
        ou_fair = no_vig(fx["ou"]) if fx["ou"] else None  # [over, under]
        if ou_fair:
            p_over25 = (1 - params["market_weight"]) * p_over25 + params["market_weight"] * ou_fair[0]

        m = score_matrix(*lam, params["rho"])
        best = max(((h, a) for h in range(7) for a in range(7)), key=lambda t: m[t[0]][t[1]])
        q_score, q_warn = _quality(counts[lg][fx["home"]], counts[lg][fx["away"]], fit["n"], bool(fx["odds"]))

        home_n, away_n = fx["home"], fx["away"]
        cands = [  # (market, pick text exactly as the Android app expects it, probability)
            ("1X2", f"فوز {home_n}", final[0]), ("1X2", "تعادل", final[1]), ("1X2", f"فوز {away_n}", final[2]),
            ("الأهداف", "أكثر من 1.5", mk["o15"]), ("الأهداف", "أقل من 1.5", 1 - mk["o15"]),
            ("الأهداف", "أكثر من 2.5", p_over25), ("الأهداف", "أقل من 2.5", 1 - p_over25),
            ("يسجل الفريقان", "نعم", mk["btts"]), ("يسجل الفريقان", "لا", 1 - mk["btts"]),
        ]
        priced = []
        if fx["odds"] and market:
            for i, (mkt, pk, p) in enumerate(cands[:3]):
                priced.append(_bet(mkt, pk, p, fx["odds"][i], market[i]))
        if fx["ou"] and ou_fair:
            priced.append(_bet("الأهداف", "أكثر من 2.5", p_over25, fx["ou"][0], ou_fair[0]))
            priced.append(_bet("الأهداف", "أقل من 2.5", 1 - p_over25, fx["ou"][1], ou_fair[1]))
        value = [b for b in priced if q_score >= MIN_QUALITY and b["ev"] >= MIN_EV and b["probability"] >= MIN_PROB
                 and b["odds"] <= MAX_ODDS and 0 <= b["probability"] - b["market_fair"] <= MAX_DISAGREE]
        value.sort(key=lambda b: -b["ev"])

        top = max(cands, key=lambda c: c[2])
        if value:
            v = value[0]
            pick = dict(market=v["market"], pick=v["pick"], probability=v["probability"], odds=v["odds"],
                        ev=v["ev"], kelly=v["kelly"], value=True)
        else:
            pick = dict(market=top[0], pick=top[1], probability=top[2], odds=None, ev=None, kelly=0.0, value=False)

        out.append(dict(
            id=fixture_id(lg, fx["date"], fx["home"], fx["away"]), league_code=lg, league=LEAGUES.get(lg, lg),
            date=fx["date"], time=fx["time"], home=fx["home"], away=fx["away"],
            expected_goals=[round(lam[0], 3), round(lam[1], 3)], expected_score=f"{best[0]}-{best[1]}",
            probs=dict(home=final[0], draw=final[1], away=final[2], over15=mk["o15"], over25=p_over25, under25=1 - p_over25,
                       btts=mk["btts"]),
            model_probs=dict(home=model[0], draw=model[1], away=model[2]),
            market_probs=dict(home=market[0], draw=market[1], away=market[2]) if market else None,
            odds=dict(home=fx["odds"][0], draw=fx["odds"][1], away=fx["odds"][2]) if fx["odds"] else None,
            odds_ou25=dict(over=fx["ou"][0], under=fx["ou"][1]) if fx["ou"] else None,
            value_bets=value, pick=pick, quality=dict(score=q_score, warnings=q_warn)))
    out.sort(key=lambda f: (f["date"], f["time"], f["home"]))
    return out
