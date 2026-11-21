"""CLI:  python -m engine.run predict | tune   [--out docs] [--leagues E0,SP1,...]"""
import argparse, json, os, sys
from datetime import datetime, timezone

from . import data, ledger, metrics, predict
from .backtest import markdown, tune
from .model import days_from_iso

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
PARAMS = os.path.join(ROOT, "engine", "params.json")
DISCLAIMER = "توقعات إحصائية لأغراض معلوماتية فقط؛ لا تضمن نتائج ولا أرباحاً. القمار ينطوي على مخاطر مالية."


def load_params():
    p = json.load(open(PARAMS, encoding="utf-8"))
    p.setdefault("calibration", None)
    return p


def write_json(path, obj):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    json.dump(obj, open(path, "w", encoding="utf-8"), ensure_ascii=False, indent=1)


def cmd_predict(a):
    now = datetime.now(timezone.utc)
    today = now.date()
    today_iso, now_iso = today.isoformat(), now.strftime("%Y-%m-%dT%H:%M:%SZ")
    leagues = a.leagues.split(",")
    cache = os.path.join(ROOT, ".cache")
    history, problems = data.load_history(leagues, today, cache, back=2)
    fixtures, p2 = data.load_fixtures(leagues, cache)
    problems += p2
    if len(history) < 500:
        print("not enough history:", problems, file=sys.stderr)
        return 1
    params = load_params()
    day = days_from_iso(today_iso)
    preds = predict.predict_fixtures(history, fixtures, params, day)

    lpath = os.path.join(a.out, "ledger.json")
    entries = ledger.load(lpath)
    by_key = {(r["league"], r["date"], r["home"], r["away"]): r for r in history}
    graded = ledger.grade(entries, by_key, today_iso)
    added = ledger.append_new(entries, preds, today_iso, now_iso, predict.MODEL_VERSION)
    ledger.save(lpath, entries)

    recent = [e for e in entries if e["status"] == "graded" and e["date"] >= days_back(today_iso, 21)]
    feed = dict(generated_at=now_iso, model_version=predict.MODEL_VERSION, disclaimer=DISCLAIMER, source="football-data.co.uk",
                params={k: v for k, v in params.items() if k != "calibration"}, calibrated=bool(params.get("calibration")),
                problems=problems, fixtures=preds,
                results=[dict(id=e["id"], home_goals=e["result"][0], away_goals=e["result"][1]) for e in recent])
    write_json(os.path.join(a.out, "predictions.json"), feed)
    m = metrics.compute(entries)
    m["generated_at"] = now_iso
    write_json(os.path.join(a.out, "metrics.json"), m)
    print(f"fixtures={len(fixtures)} predicted={len(preds)} ledger_added={added} graded={graded} value_picks={sum(1 for f in preds if f['value_bets'])}")
    return 0


def days_back(iso, n):
    d = days_from_iso(iso) - n
    return datetime.fromtimestamp(d * 86400, timezone.utc).strftime("%Y-%m-%d")


def cmd_tune(a):
    today = datetime.now(timezone.utc).date()
    rows, problems = data.load_history(a.leagues.split(","), today, os.path.join(ROOT, ".cache"), back=3)
    if len(rows) < 1500:
        print("not enough data", problems, file=sys.stderr)
        return 1
    res = tune(rows)
    if not res:
        return 1
    params, report = res
    params["tuned_at"] = datetime.now(timezone.utc).strftime("%Y-%m-%d")
    json.dump(params, open(PARAMS, "w", encoding="utf-8"), ensure_ascii=False, indent=1)
    write_json(os.path.join(a.out, "backtest.json"), report)
    md = markdown(params, report, len(rows))
    open(os.path.join(a.out, "backtest.md"), "w", encoding="utf-8").write(md)
    print(md)
    return 0


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("command", choices=["predict", "tune"])
    ap.add_argument("--out", default=os.path.join(ROOT, "docs"))
    ap.add_argument("--leagues", default=",".join(data.DEFAULT_LEAGUES))
    a = ap.parse_args()
    sys.exit({"predict": cmd_predict, "tune": cmd_tune}[a.command](a))


if __name__ == "__main__":
    main()
