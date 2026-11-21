package com.betslippro.app

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray

/**
 * Single access point for persisted app data (Room/SQLite). All disk work happens off the main thread;
 * writes are serialised so rapid UI actions cannot interleave and corrupt each other.
 */
class Store private constructor(private val db: AppDatabase) {
    private val dao = db.dao()
    private val writeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()

    suspend fun loadFixtures(): List<Fixture> = withContext(Dispatchers.IO) {
        dao.fixtures().map { Fixture(it.id, it.home, it.away, it.league, it.date, it.homeTeamId, it.awayTeamId, it.sourceLeagueCode) }
    }

    suspend fun loadPredictionRecords(): List<PredictionRecord> = withContext(Dispatchers.IO) {
        dao.predictions().map {
            PredictionRecord(
                it.id, Fixture(it.fixtureId, it.fixtureHome, it.fixtureAway, it.fixtureLeague, it.fixtureDate, it.fixtureHomeTeamId, it.fixtureAwayTeamId, it.fixtureSource),
                it.market, it.pick, it.probability, it.expectedScore, it.modelVersion, it.created, it.actualHome, it.actualAway, it.rawProbability
            )
        }
    }

    suspend fun loadSlips(): List<SavedSlip> = withContext(Dispatchers.IO) {
        dao.slips().mapNotNull { row ->
            runCatching {
                val a = JSONArray(row.selectionsJson)
                SavedSlip(row.id, row.title, row.stake, (0 until a.length()).map { jsonSelection(a.getJSONObject(it)) }, row.created)
            }.getOrNull()
        }
    }

    fun saveFixtures(list: List<Fixture>) = write {
        dao.replaceFixtures(list.map { FixtureEntity(it.id, it.home, it.away, it.league, it.date, it.homeTeamId, it.awayTeamId, it.sourceLeagueCode) })
    }

    fun savePredictionRecords(list: List<PredictionRecord>) = write { dao.replacePredictions(list.map(::toEntity)) }

    suspend fun savePredictionRecordsNow(list: List<PredictionRecord>) = mutex.withLock { dao.replacePredictions(list.map(::toEntity)) }
    suspend fun saveFixturesNow(list: List<Fixture>) = mutex.withLock {
        dao.replaceFixtures(list.map { FixtureEntity(it.id, it.home, it.away, it.league, it.date, it.homeTeamId, it.awayTeamId, it.sourceLeagueCode) })
    }

    fun saveSlips(list: List<SavedSlip>) = write {
        dao.replaceSlips(list.map { s ->
            val arr = JSONArray(); s.selections.forEach { arr.put(selectionJson(it)) }
            SlipEntity(s.id, s.title, s.stake, s.created, arr.toString())
        })
    }

    private fun write(block: suspend () -> Unit) { writeScope.launch { mutex.withLock { runCatching { block() } } } }

    private fun toEntity(r: PredictionRecord) = PredictionEntity(
        r.id, r.fixture.id, r.fixture.home, r.fixture.away, r.fixture.league, r.fixture.date,
        r.fixture.homeTeamId, r.fixture.awayTeamId, r.fixture.sourceLeagueCode,
        r.market, r.pick, r.probability, r.expectedScore, r.modelVersion, r.created, r.actualHome, r.actualAway, r.rawProbability
    )

    /** One-time import of the pre-2.1 SharedPreferences JSON into Room. Old keys are removed only after success. */
    suspend fun migrateFromPrefsIfNeeded(prefs: SharedPreferences) = withContext(Dispatchers.IO) {
        if (prefs.getBoolean("room_migrated_v1", false)) return@withContext
        val fixtures = loadFixtures(prefs)
        val slips = loadSlips(prefs)
        val records = loadPredictionRecords(prefs)
        mutex.withLock {
            dao.replaceFixtures(fixtures.map { FixtureEntity(it.id, it.home, it.away, it.league, it.date, it.homeTeamId, it.awayTeamId, it.sourceLeagueCode) })
            dao.replacePredictions(records.map(::toEntity))
            dao.replaceSlips(slips.map { s ->
                val arr = JSONArray(); s.selections.forEach { arr.put(selectionJson(it)) }
                SlipEntity(s.id, s.title, s.stake, s.created, arr.toString())
            })
        }
        prefs.edit().putBoolean("room_migrated_v1", true).remove("fixtures").remove("slips").remove("prediction_records").apply()
    }

    companion object {
        @Volatile private var instance: Store? = null
        fun get(context: Context): Store = instance ?: synchronized(this) {
            instance ?: Store(AppDatabase.get(context)).also { instance = it }
        }
    }
}
