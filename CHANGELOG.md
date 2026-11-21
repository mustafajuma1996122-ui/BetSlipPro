# Changelog

## 3.0.0
**Architecture**
- New free server-less engine (`engine/`, Python stdlib only) running on GitHub Actions every 4 hours: fetches football-data.co.uk results + upcoming fixtures + bookmaker odds (no API key), fits Dixon-Coles strengths, blends with the market, calibrates (isotonic, only if it helps on held-out data) and publishes `docs/predictions.json` via GitHub Pages.
- The Android app is now a thin viewer: it downloads one JSON file (cached offline). The feed URL is derived automatically from `GITHUB_REPOSITORY` at build time. The old on-device ESPN analysis remains as a fallback.
- One source of truth for the model (the duplicate Kotlin/Python implementation problem is gone for the feed path).

**Selection policy (breaking change)**
- Picks are no longer "highest probability of 9 markets". A pick is flagged *value* only if EV >= +4%, probability >= 30%, odds <= 5, data quality >= 65 and model-vs-market gap <= 10 points. Otherwise the match is shown as information only ("no value"). Notifications are sent for value picks only.
- Fractional Kelly (1/8, capped at 2%) replaces nothing silently: it is displayed as a *maximum suggested stake*, not advice.
- Accumulator builder limited to 3 legs with an explicit warning that margins compound.

**Track record**
- Append-only prediction ledger (`docs/ledger.json`): predictions are written before kickoff and never edited; results and closing odds are filled in afterwards.
- Public dashboard (`docs/index.html`): log-loss, Brier, RPS vs market baseline, calibration table, value-bet ROI and closing-line value (CLV).
- Weekly walk-forward tuning workflow (half-life, prior, rho, market weight, calibration).

**Engineering**
- 16 Python tests (no look-ahead, append-only ledger, kickoff guard, calibration, parsing) + Kotlin feed-parsing test; CI runs both.
- CI ignores data-only commits (`docs/**`).
## 2.1.0
**Model**
- Opponent-adjusted team strengths (Maher/Dixon-Coles style) fitted on the whole competition (~150 days of results) with time decay and Bayesian shrinkage — replaces "last 10 matches" averages.
- Dixon-Coles low-score correction (fixes under-estimated draws). Elo kept as an informational signal only (no double counting).
- Optional blend with bookmaker prices (margin removed) when a free The Odds API key is entered.
- `tools/backtest.py`: free walk-forward backtest on football-data.co.uk with log-loss/Brier, calibration table and blend-weight search (weekly GitHub Action).

**Automation**
- Daily background sync (WorkManager): fetch fixtures, analyse, grade finished predictions, notify only strong/high-quality picks. Can be turned off in Settings.

**Engineering**
- Room (SQLite) replaces JSON-in-SharedPreferences, with one-time automatic migration.
- API keys stored in encrypted preferences; `allowBackup=false`.
- Stable 64-bit IDs instead of `String.hashCode()`.
- Duplicate record-creation code removed; shared `AutoPipeline`.
- Unit tests (model, Elo, value finder, parsing, calibration, name matching) + CI runs tests, lint, debug and release builds; optional signing via secrets; Dependabot.
