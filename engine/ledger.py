"""Append-only prediction ledger. A prediction is written once, before kickoff, and its probabilities/odds are never edited.
Only result, closing odds and grading fields are filled in afterwards."""
import json, os

FROZEN = ("id", "league_code", "league", "date", "time", "home", "away", "probs", "model_probs", "market_probs",
          "odds", "odds_ou25", "value_bets", "pick", "quality", "created_at", "model_version")


def load(path):
    if os.path.exists(path):
        try:
            return json.load(open(path, encoding="utf-8"))
        except Exception:
            pass
    return []


def save(path, entries):
    os.makedirs(os.path.dirname(path) or ".", exist_ok=True)
    json.dump(entries, open(path, "w", encoding="utf-8"), ensure_ascii=False, separators=(",", ":"))


def append_new(entries, fixtures, today_iso, now_iso, model_version):
    """Add fixtures not yet in the ledger and not yet started (today's matches only if kickoff is still ahead)."""
    now_hm = now_iso[11:16]
    have = {e["id"] for e in entries}
    added = 0
    for f in fixtures:
        if f["id"] in have or f["date"] < today_iso:
            continue
        if f["date"] == today_iso and (not f.get("time") or f["time"] <= now_hm):
            continue  # kickoff time unknown or already passed: never record after the fact
        e = {k: f[k] for k in f if k in FROZEN}
        e.update(created_at=now_iso, model_version=model_version, status="pending", result=None, closing=None)
        entries.append(e)
        added += 1
    return added


def _won(bet, hg, ag, home):
    pk = bet["pick"]
    if bet["market"] == "1X2":
        return hg == ag if pk == "تعادل" else (hg > ag if pk == f"فوز {home}" else ag > hg)
    if bet["market"] == "الأهداف":
        line = float("".join(c for c in pk if c.isdigit() or c == "."))
        return hg + ag > line if "أكثر" in pk else hg + ag < line
    return False


def grade(entries, results_by_key, today_iso):
    """results_by_key[(league, date, home, away)] = row with hg/ag and closing odds. Fills only result fields."""
    n = 0
    for e in entries:
        if e["status"] != "pending" or e["date"] > today_iso:
            continue
        r = results_by_key.get((e["league_code"], e["date"], e["home"], e["away"]))
        if not r or r["hg"] is None:
            continue
        e["result"] = [r["hg"], r["ag"]]
        e["closing"] = dict(odds=r.get("close"), ou=r.get("close_ou"))
        e["status"] = "graded"
        for b in e["value_bets"]:
            b["won"] = bool(_won(b, r["hg"], r["ag"], e["home"]))
        n += 1
    return n
