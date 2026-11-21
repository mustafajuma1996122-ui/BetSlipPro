"""Free data access: football-data.co.uk (results, upcoming fixtures and bookmaker odds, no key needed)."""
import csv, hashlib, io, os, time, urllib.request
from datetime import date, datetime, timezone

from .model import days_from_iso

BASE = "https://www.football-data.co.uk"
UA = {"User-Agent": "BetSlipPro-engine/3.0 (+github actions)"}

# code -> Arabic display name (the app is Arabic-first)
LEAGUES = {
    "E0": "الدوري الإنجليزي الممتاز", "E1": "تشامبيونشيب إنجلترا",
    "SP1": "الدوري الإسباني", "SP2": "الدرجة الثانية الإسبانية",
    "D1": "الدوري الألماني", "D2": "الدرجة الثانية الألمانية",
    "I1": "الدوري الإيطالي", "I2": "الدرجة الثانية الإيطالية",
    "F1": "الدوري الفرنسي", "F2": "الدرجة الثانية الفرنسية",
    "N1": "الدوري الهولندي", "P1": "الدوري البرتغالي", "B1": "الدوري البلجيكي",
    "T1": "الدوري التركي", "SC0": "الدوري الاسكتلندي", "G1": "الدوري اليوناني",
}
DEFAULT_LEAGUES = ["E0", "E1", "SP1", "D1", "I1", "F1", "N1", "P1", "B1", "T1", "SC0", "G1"]

ODDS_1X2 = [("AvgH", "AvgD", "AvgA"), ("B365H", "B365D", "B365A"), ("PSH", "PSD", "PSA"), ("BWH", "BWD", "BWA")]
ODDS_OU = [("Avg>2.5", "Avg<2.5"), ("B365>2.5", "B365<2.5"), ("P>2.5", "P<2.5")]
CLOSE_1X2 = [("AvgCH", "AvgCD", "AvgCA"), ("PSCH", "PSCD", "PSCA"), ("B365CH", "B365CD", "B365CA")]
CLOSE_OU = [("AvgC>2.5", "AvgC<2.5"), ("PC>2.5", "PC<2.5"), ("B365C>2.5", "B365C<2.5")]


def season_code(today: date) -> str:
    start = today.year if today.month >= 7 else today.year - 1
    return "%02d%02d" % (start % 100, (start + 1) % 100)


def season_codes(today: date, back: int = 2):
    out = []
    y = today.year if today.month >= 7 else today.year - 1
    for k in range(back + 1):
        s = y - k
        out.append("%02d%02d" % (s % 100, (s + 1) % 100))
    return out


def fixture_id(league: str, day: str, home: str, away: str) -> int:
    """Stable positive 60-bit id; identical on every run so the ledger and the app can match records."""
    return int(hashlib.sha1(f"{league}|{day}|{home}|{away}".encode("utf-8")).hexdigest()[:15], 16)


def http_get(url: str, cache_path: str = None, ttl_hours: float = 3.0, retries: int = 3) -> str:
    if cache_path and os.path.exists(cache_path) and (time.time() - os.path.getmtime(cache_path)) < ttl_hours * 3600:
        return open(cache_path, "rb").read().decode("latin-1")
    last = None
    for i in range(retries):
        try:
            raw = urllib.request.urlopen(urllib.request.Request(url, headers=UA), timeout=40).read()
            if cache_path:
                os.makedirs(os.path.dirname(cache_path), exist_ok=True)
                open(cache_path, "wb").write(raw)
            return raw.decode("latin-1")
        except Exception as e:  # network error / 404 for a missing season
            last = e
            time.sleep(2 * (i + 1))
    if cache_path and os.path.exists(cache_path):  # stale cache beats no data
        return open(cache_path, "rb").read().decode("latin-1")
    raise RuntimeError(f"download failed: {url}: {last}")


def _parse_date(s):
    s = (s or "").strip()
    for f in ("%d/%m/%Y", "%d/%m/%y"):
        try:
            return datetime.strptime(s, f).strftime("%Y-%m-%d")
        except ValueError:
            pass
    return None


def _num(x):
    try:
        v = float(x)
        return v if v > 1.0 else None
    except (TypeError, ValueError):
        return None


def _first(r, groups):
    for cols in groups:
        vals = [_num(r.get(c)) for c in cols]
        if all(v is not None for v in vals):
            return tuple(vals)
    return None


def parse_rows(text: str, default_league: str = None):
    """Parse a football-data.co.uk CSV (results file or fixtures.csv) into normalised dict rows."""
    text = text.lstrip("﻿")
    rows = []
    for r in csv.DictReader(io.StringIO(text)):
        r = {(k or "").strip().lstrip("﻿"): (v or "").strip() for k, v in r.items()}
        league = r.get("Div") or default_league
        d = _parse_date(r.get("Date"))
        home, away = r.get("HomeTeam"), r.get("AwayTeam")
        if not league or not d or not home or not away:
            continue
        try:
            hg, ag = int(r["FTHG"]), int(r["FTAG"])
        except (KeyError, ValueError):
            hg = ag = None
        rows.append(dict(league=league, date=d, day=days_from_iso(d), time=r.get("Time", ""), home=home, away=away,
                         hg=hg, ag=ag, odds=_first(r, ODDS_1X2), ou=_first(r, ODDS_OU),
                         close=_first(r, CLOSE_1X2), close_ou=_first(r, CLOSE_OU)))
    return rows


def load_history(leagues, today: date, cache_dir: str, back: int = 2):
    rows, problems = [], []
    for lg in leagues:
        for ss in season_codes(today, back):
            try:
                rows += [x for x in parse_rows(http_get(f"{BASE}/mmz4281/{ss}/{lg}.csv", os.path.join(cache_dir, f"{lg}_{ss}.csv"), 3.0), lg)
                         if x["hg"] is not None]
            except Exception as e:
                problems.append(f"{lg} {ss}: {e}")
    return rows, problems


def load_fixtures(leagues, cache_dir: str):
    """Upcoming matches (usually the next match-week) with bookmaker odds. Returns (rows, problems)."""
    try:
        rows = parse_rows(http_get(f"{BASE}/fixtures.csv", os.path.join(cache_dir, "fixtures.csv"), 1.0))
    except Exception as e:
        return [], [f"fixtures.csv: {e}"]
    return [r for r in rows if r["league"] in leagues and r["hg"] is None], []
