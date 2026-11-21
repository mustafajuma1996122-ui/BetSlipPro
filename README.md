# BET SLIP PRO AI — Android

**Version:** 3.0.0  
**Platform:** Android 7.0+ (minSdk 24)  
**Language/UI:** Kotlin + Jetpack Compose  
**Copyright:** © 2026 Mustafa J. Burhan. All rights reserved.

BET SLIP PRO AI is an Arabic-first football match analysis and prediction-slip builder. It is an informational/statistical tool; it does not guarantee match outcomes or betting profits.

![BET SLIP PRO icon](assets/icons/bet-slip-pro-icon-512.png)

## Included

- Dark Arabic Android interface.
- Manual fixtures and single/accumulator slip workflow.
- Local slip archive and export/share functions.
- Statistical model: opponent-adjusted team strengths + Dixon-Coles score model (+ Elo as info), optional blend with bookmaker prices.
- Automatic daily background sync, result grading and notifications (WorkManager; can be disabled in Settings).
- Local Room database; API keys in encrypted storage.
- Quality/value-analysis components where required inputs are available.
- Keyless public fixture/data integration as implemented in the source.
- Launcher icon resources for legacy and adaptive Android launchers.
- GitHub Actions: unit tests + lint + debug/release APK on every push; weekly free-data backtest.

## How it works (v3)

```
GitHub Actions (every 4h)           GitHub Pages                 Android app
python -m engine.run predict  --->  docs/predictions.json  --->  downloads 1 JSON file
  football-data.co.uk (free)        docs/metrics.json            shows picks, value flags,
  Dixon-Coles + market + calib.     docs/ledger.json (append)    grades results, notifies
                                    docs/index.html (dashboard)
```
See `دليل_التشغيل.md` (Arabic) for the full step-by-step guide: publish the repo, enable Pages, run the workflows, download the APK.

Local commands:
```
python3 -m unittest discover -s tests        # tests
python3 -m engine.run predict                # one prediction cycle (needs internet)
python3 -m engine.run tune                   # weekly walk-forward tuning (slow)
```
Build an APK locally with a custom feed: `gradle assembleDebug -PfeedUrl=https://USER.github.io/REPO/predictions.json`.

## Build an APK on GitHub (recommended)

1. Create a new **private or public** repository on GitHub.
2. Extract this project ZIP on your computer.
3. Upload the **contents** of the `BetSlipPro` folder to the repository root (the root must contain `settings.gradle.kts`, `build.gradle.kts`, and the `app/` folder).
4. Commit and push to the `main` branch (or `master`).
5. Open the repository's **Actions** tab and select **Android APK**.
6. Wait for the workflow to finish successfully.
7. Open the completed workflow run, scroll to **Artifacts**, and download `BET-SLIP-PRO-debug-apk`.
8. Extract the artifact ZIP to get `app-debug.apk`, then install it on an Android device. You may need to allow installation from that source.

The workflow installs JDK 17 and Gradle 8.9, then runs `clean assembleDebug`. No API keys or signing secrets are required for this debug build.

## Build locally with Android Studio

- Install Android Studio with Android SDK Platform 35 and JDK 17.
- Open the project folder containing `settings.gradle.kts`.
- Allow Gradle sync to complete, then choose **Build → Build Bundle(s) / APK(s) → Build APK(s)**.
- The debug APK is generated at `app/build/outputs/apk/debug/app-debug.apk`.

This source package does not include a Gradle wrapper. GitHub Actions installs Gradle 8.9 explicitly; Android Studio may prompt to configure a compatible Gradle installation when importing the project.

## App icon package

- `assets/icons/bet-slip-pro-icon-1024.png` — master icon.
- `assets/icons/bet-slip-pro-icon-512.png` — repository/store artwork source.
- `app/src/main/res/mipmap-*` — density-specific legacy launcher icons.
- `app/src/main/res/mipmap-anydpi-v26/` — Android adaptive launcher icon definitions.
- `app/src/main/res/drawable/ic_launcher_foreground.xml` — adaptive foreground vector.

## Free-only stack (no paid services)

Everything runs on free data and tooling: football-data.co.uk CSVs (results, fixtures, odds), GitHub Actions and GitHub Pages. The legacy on-device fallback can still use ESPN public scoreboards. No server and no API key are required.

## Optional signed release

Add these GitHub secrets: `BETSLIP_KEYSTORE_B64` (base64 of your .jks), `BETSLIP_KEYSTORE_PASSWORD`, `BETSLIP_KEY_ALIAS`, `BETSLIP_KEY_PASSWORD`. Without them the release APK is unsigned.

## Automatic data and limitations

- The app's keyless fixture feed is based on the public Open-Matchday dataset and other public endpoints integrated in the source. Dataset coverage and update frequency vary by competition.
- Public endpoints may change, rate-limit requests, or omit historical team data. The app cannot produce a responsible analysis when required history or team identifiers are missing.
- The free automatic mode does **not** provide guaranteed global live odds, injuries, confirmed lineups, or complete xG coverage. No fake odds or fabricated data should be treated as real.
- A successful CI build verifies that the source compiles into a debug APK; it does not validate prediction accuracy or guarantee uninterrupted third-party data access.
- Check each upstream dataset/API's current terms and attribution requirements before redistribution or commercial use.

## Copyright and third-party notices

The original application source, branding, and original icon artwork are © 2026 **Mustafa J. Burhan**. All rights reserved, subject to third-party components and data sources, which remain governed by their own licenses and terms. See [`LICENSE`](LICENSE).

This repository's proprietary notice does not grant ownership of third-party data, team marks, league marks, or libraries. Review upstream terms before publishing the app publicly or commercially.

## Version

- `versionCode`: 13
- `versionName`: 3.0.0
