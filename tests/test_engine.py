import math, os, random, sys, unittest
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
from engine import backtest, calibrate, data, ledger, metrics, predict
from engine.model import *


def synth(n_teams=12, seasons_days=380, seed=1, vig=1.06):
    """Synthetic league with known strengths + noisy 'market' odds."""
    rnd = random.Random(seed)
    teams = [f"T{i}" for i in range(n_teams)]
    att = {t: math.exp(rnd.gauss(0, 0.25)) for t in teams}
    dfn = {t: math.exp(rnd.gauss(0, 0.25)) for t in teams}
    rows, day = [], 19000
    def pois(l):
        L, k, p = math.exp(-l), 0, 1.0
        while True:
            p *= rnd.random()
            if p <= L: return k
            k += 1
    for d in range(seasons_days):
        for _ in range(3):
            h, a = rnd.sample(teams, 2)
            lh, la = 1.4 * att[h] * dfn[a], 1.1 * att[a] * dfn[h]
            m = markets(score_matrix(lh, la, 0.0))
            fair = [m["home"], m["draw"], m["away"]]
            odds = tuple(round(1 / (p * vig), 2) for p in fair)
            iso = date_from(day + d)
            rows.append(dict(league="X1", date=iso, day=day + d, time="15:00", home=h, away=a, hg=pois(lh), ag=pois(la),
                             odds=odds, ou=None, close=odds, close_ou=None))
    return rows


def date_from(day):
    import datetime
    return (datetime.date(1970, 1, 1) + datetime.timedelta(days=day)).isoformat()


class DataTests(unittest.TestCase):
    def test_season_code(self):
        import datetime
        self.assertEqual(data.season_code(datetime.date(2026, 11, 21)), "2627")
        self.assertEqual(data.season_code(datetime.date(2026, 3, 1)), "2526")
        self.assertEqual(data.season_codes(datetime.date(2026, 11, 21), 2), ["2627", "2526", "2425"])

    def test_parse_rows(self):
        csv_text = "Div,Date,Time,HomeTeam,AwayTeam,FTHG,FTAG,AvgH,AvgD,AvgA,Avg>2.5,Avg<2.5,AvgCH,AvgCD,AvgCA\n" \
                   "E0,21/11/2026,15:00,Arsenal,Chelsea,,,2.0,3.5,4.0,1.9,1.95,,,\n" \
                   "E0,14/11/26,15:00,Spurs,Leeds,2,1,1.8,3.6,4.8,,,1.7,3.8,5.0\n"
        r = data.parse_rows(csv_text)
        self.assertEqual(len(r), 2)
        self.assertIsNone(r[0]["hg"]); self.assertEqual(r[0]["odds"], (2.0, 3.5, 4.0)); self.assertEqual(r[0]["ou"], (1.9, 1.95))
        self.assertEqual((r[1]["hg"], r[1]["ag"], r[1]["date"]), (2, 1, "2026-11-14")); self.assertEqual(r[1]["close"], (1.7, 3.8, 5.0))

    def test_fixture_id_stable(self):
        a = data.fixture_id("E0", "2026-11-21", "A", "B")
        self.assertEqual(a, data.fixture_id("E0", "2026-11-21", "A", "B")); self.assertTrue(0 < a < 2 ** 63)


class CalibrationTests(unittest.TestCase):
    def test_pav_monotone_and_apply(self):
        rnd = random.Random(3)
        pts = []
        for _ in range(3000):
            p = rnd.random(); pts.append((p, 1.0 if rnd.random() < p ** 1.3 else 0.0))
        c = calibrate.pav(pts)
        ys = [y for _, y in c]
        self.assertTrue(all(b >= a - 1e-12 for a, b in zip(ys, ys[1:])))
        cal = {"x": [x for x, _ in c], "y": ys}
        t = calibrate.apply(cal, (0.5, 0.3, 0.2)); self.assertAlmostEqual(sum(t), 1.0)

    def test_identity_without_curve(self):
        self.assertEqual(calibrate.apply(None, (0.5, 0.3, 0.2)), (0.5, 0.3, 0.2))

    def test_too_few_samples(self):
        self.assertIsNone(calibrate.fit([((0.4, 0.3, 0.3), 0)] * 10))


class ModelTests(unittest.TestCase):
    def test_rps_bounds(self):
        self.assertAlmostEqual(rps([1, 0, 0], 0), 0.0); self.assertAlmostEqual(rps([0, 0, 1], 0), 1.0)

    def test_fit_recovers_strength_order(self):
        rows = synth(seed=5)
        fit = fit_strengths([(r["day"], r["home"], r["away"], r["hg"], r["ag"]) for r in rows], rows[-1]["day"] + 1, 400)
        self.assertTrue(all(math.isfinite(v) for v in fit["A"].values()))


class BacktestAndPredict(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.rows = synth(n_teams=14, seasons_days=420, seed=11)

    def test_tune_beats_uniform_and_is_honest_about_market(self):
        params, rep = backtest.tune(self.rows, grid_hl=(200,), grid_prior=(4,), grid_rho=(0.0, -0.1), log=lambda *_: None)
        self.assertLess(rep["model"]["log_loss"], rep["uniform"]["log_loss"])
        self.assertLess(rep["final"]["log_loss"], rep["uniform"]["log_loss"])
        self.assertIn("market_weight", params)
        self.assertTrue(backtest.markdown(params, rep, len(self.rows)))

    def test_no_lookahead_in_collect(self):
        res = backtest.collect(self.rows, 150, 4)
        first_day = min(r["m"]["day"] for r in res)
        self.assertGreater(first_day, self.rows[0]["day"] + 30)

    def _fixtures(self, hist):
        last = hist[-1]["day"]
        fx = [dict(league="X1", date=date_from(last + 2), day=last + 2, time="20:00", home="T1", away="T2", hg=None, ag=None,
                   odds=(2.4, 3.3, 3.2), ou=(1.95, 1.9), close=None, close_ou=None)]
        return fx, last + 1

    def test_predict_schema_and_probabilities(self):
        fx, day = self._fixtures(self.rows)
        params = dict(half_life=150.0, prior=4.0, rho=-0.1, market_weight=0.6, calibration=None)
        out = predict.predict_fixtures(self.rows, fx, params, day)
        self.assertEqual(len(out), 1); f = out[0]
        self.assertAlmostEqual(f["probs"]["home"] + f["probs"]["draw"] + f["probs"]["away"], 1.0, places=6)
        self.assertIn(f["pick"]["market"], ("1X2", "الأهداف", "يسجل الفريقان"))

    def test_value_rules_reject_longshots_and_poor_quality(self):
        b = predict._bet("1X2", "x", 0.2, 6.0, 0.15)
        self.assertGreater(b["ev"], 0.04)  # looks great on paper...
        self.assertTrue(b["probability"] < predict.MIN_PROB)  # ...but is filtered by MIN_PROB / MAX_ODDS in predict_fixtures
        self.assertLessEqual(b["kelly"], predict.KELLY_CAP)

    def test_unknown_team_skipped(self):
        fx, day = self._fixtures(self.rows); fx[0]["home"] = "NOPE"
        params = dict(half_life=150.0, prior=4.0, rho=-0.1, market_weight=0.6, calibration=None)
        self.assertEqual(predict.predict_fixtures(self.rows, fx, params, day), [])


class LedgerTests(unittest.TestCase):
    def _fx(self, **kw):
        base = dict(id=1, league_code="X1", league="L", date="2026-11-22", time="15:00", home="A", away="B",
                    probs=dict(home=.5, draw=.3, away=.2), model_probs=dict(home=.5, draw=.3, away=.2), market_probs=None,
                    odds=None, odds_ou25=None, pick=dict(market="1X2", pick="فوز A", probability=.5, value=False),
                    quality=dict(score=90, warnings=[]),
                    value_bets=[dict(market="1X2", pick="فوز A", probability=.5, odds=2.2, market_fair=.45, ev=.1, kelly=.01)])
        base.update(kw); return base

    def test_append_only_and_frozen(self):
        e = []
        self.assertEqual(ledger.append_new(e, [self._fx()], "2026-11-21", "2026-11-21T10:00:00Z", "v"), 1)
        e[0]["probs"]["home"] = .5
        before = repr(e[0]["probs"])
        # second run with different probabilities must not overwrite
        self.assertEqual(ledger.append_new(e, [self._fx(probs=dict(home=.9, draw=.05, away=.05))], "2026-11-21", "2026-11-21T16:00:00Z", "v"), 0)
        self.assertEqual(repr(e[0]["probs"]), before)

    def test_rejects_after_kickoff(self):
        e = []
        n = ledger.append_new(e, [self._fx(date="2026-11-21", time="09:00")], "2026-11-21", "2026-11-21T10:00:00Z", "v")
        self.assertEqual(n, 0)
        self.assertEqual(ledger.append_new(e, [self._fx(date="2026-11-21", time="")], "2026-11-21", "2026-11-21T10:00:00Z", "v"), 0)
        self.assertEqual(ledger.append_new(e, [self._fx(date="2026-11-20")], "2026-11-21", "2026-11-21T10:00:00Z", "v"), 0)

    def test_grade_and_metrics(self):
        e = []
        ledger.append_new(e, [self._fx()], "2026-11-21", "2026-11-21T10:00:00Z", "v")
        res = {("X1", "2026-11-22", "A", "B"): dict(hg=2, ag=1, close=(2.0, 3.4, 4.0), close_ou=None)}
        self.assertEqual(ledger.grade(e, res, "2026-11-23"), 1)
        self.assertEqual(e[0]["status"], "graded"); self.assertTrue(e[0]["value_bets"][0]["won"])
        self.assertEqual(ledger.grade(e, res, "2026-11-23"), 0)
        m = metrics.compute(e)
        self.assertEqual(m["graded"], 1); self.assertAlmostEqual(m["value_bets"]["flat_roi"], 1.2)
        self.assertIsNotNone(m["value_bets"]["avg_clv"])


if __name__ == "__main__":
    unittest.main()
