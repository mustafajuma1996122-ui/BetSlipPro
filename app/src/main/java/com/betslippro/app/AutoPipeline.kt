package com.betslippro.app

import android.content.Context
import java.util.Locale

data class PipelineSummary(
    val fixtures: Int,
    val picks: Int,
    val newRecords: Int,
    val resultsSynced: Int,
    val notifyPicks: List<AutoPrediction>
)

/** The whole automatic cycle (fetch -> analyse -> record -> grade finished matches). Shared by UI and the daily worker. */
object AutoPipeline {
    const val MODEL_VERSION = "V3.0-DC-Market-Isotonic"
    const val NOTIFY_MIN_PROBABILITY = 0.65
    const val NOTIFY_MIN_QUALITY = 65

    fun recordsFor(picks: List<AutoPrediction>, existingIds: Set<Long>, modelVersion: String = MODEL_VERSION): List<PredictionRecord> =
        picks.map { p ->
            PredictionRecord(
                Ids.stable(p.fixture.id.toString(), p.market, p.pick), p.fixture, p.market, p.pick,
                p.probability, p.expectedScore, modelVersion = modelVersion, rawProbability = p.rawProbability
            )
        }.filterNot { it.id in existingIds }

    fun mergeFixtures(existing: List<Fixture>, incoming: List<Fixture>): List<Fixture> =
        (existing.filter { it.date.take(10) >= today() } + incoming).groupBy {
            it.home.trim().lowercase(Locale.ROOT) + "|" + it.away.trim().lowercase(Locale.ROOT) + "|" + it.date.take(10)
        }.values.map { rows -> rows.firstOrNull { it.homeTeamId.isNotBlank() && it.awayTeamId.isNotBlank() } ?: rows.first() }

    suspend fun run(context: Context): PipelineSummary {
        val app = context.applicationContext
        RemoteFeed.init(app)
        val store = Store.get(app)
        val plain = app.getSharedPreferences("bet_slip_pro", Context.MODE_PRIVATE)
        val secure = SecureStore.get(app)
        store.migrateFromPrefsIfNeeded(plain)
        SecureStore.migrate(plain, secure)

        val incoming = fetchCombinedFixtures(secure.getString("api_token", "") ?: "", today(), "ALL", app.cacheDir).getOrThrow()
        val merged = mergeFixtures(store.loadFixtures(), incoming)
        store.saveFixturesNow(merged)

        // Optional free-tier odds (only if the user entered a key): used to blend model + market.
        val oddsKey = secure.getString("odds_api_key", "") ?: ""
        val oddsEvents = if (oddsKey.isBlank()) emptyList() else kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            TheOddsApiClient.fetchSoccerOdds(
                oddsKey.trim(),
                plain.getString("odds_sport_keys", DEFAULT_ODDS_SPORTS) ?: DEFAULT_ODDS_SPORTS,
                plain.getString("odds_region", "eu") ?: "eu"
            ).getOrNull()?.events.orEmpty()
        }

        var records = store.loadPredictionRecords()
        val picks = analyzeTopPredictions(merged, records, oddsEvents).getOrDefault(emptyList())
        val fresh = recordsFor(picks, records.map { it.id }.toSet())
        records = (records + fresh).takeLast(1500)

        val synced = syncCompletedPredictionResults(records.filterNot { it.evaluated })
        if (synced.isNotEmpty()) {
            records = records.map { r ->
                val s = synced[r.id]
                if (!r.evaluated && s != null) r.copy(actualHome = s.first, actualAway = s.second) else r
            }
        }
        store.savePredictionRecordsNow(records)

        val freshIds = fresh.map { it.id }.toSet()
        val notify = picks.filter {
            it.valuePick && (it.qualityScore ?: it.audit?.score ?: 0) >= NOTIFY_MIN_QUALITY &&
                Ids.stable(it.fixture.id.toString(), it.market, it.pick) in freshIds
        }.take(3)
        return PipelineSummary(merged.size, picks.size, fresh.size, synced.size, notify)
    }

    const val DEFAULT_ODDS_SPORTS = "soccer_epl,soccer_spain_la_liga,soccer_germany_bundesliga,soccer_italy_serie_a,soccer_france_ligue_one,soccer_uefa_champs_league"
}
