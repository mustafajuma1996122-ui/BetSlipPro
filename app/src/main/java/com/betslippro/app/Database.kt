package com.betslippro.app

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction

@Entity(tableName = "fixtures")
data class FixtureEntity(
    @PrimaryKey val id: Long,
    val home: String, val away: String, val league: String, val date: String,
    val homeTeamId: String, val awayTeamId: String, val sourceLeagueCode: String
)

@Entity(tableName = "predictions", indices = [Index("created"), Index("actualHome")])
data class PredictionEntity(
    @PrimaryKey val id: Long,
    val fixtureId: Long, val fixtureHome: String, val fixtureAway: String, val fixtureLeague: String, val fixtureDate: String,
    val fixtureHomeTeamId: String, val fixtureAwayTeamId: String, val fixtureSource: String,
    val market: String, val pick: String, val probability: Double, val expectedScore: String,
    val modelVersion: String, val created: String,
    val actualHome: Int?, val actualAway: Int?, val rawProbability: Double?
)

@Entity(tableName = "slips")
data class SlipEntity(
    @PrimaryKey val id: Long,
    val title: String, val stake: Double, val created: String, val selectionsJson: String
)

@Dao
abstract class AppDao {
    @Query("SELECT * FROM fixtures ORDER BY date") abstract suspend fun fixtures(): List<FixtureEntity>
    @Query("DELETE FROM fixtures") abstract suspend fun clearFixtures()
    @Insert(onConflict = OnConflictStrategy.REPLACE) abstract suspend fun insertFixtures(rows: List<FixtureEntity>)
    @Transaction open suspend fun replaceFixtures(rows: List<FixtureEntity>) { clearFixtures(); insertFixtures(rows) }

    @Query("SELECT * FROM predictions ORDER BY created") abstract suspend fun predictions(): List<PredictionEntity>
    @Query("DELETE FROM predictions") abstract suspend fun clearPredictions()
    @Insert(onConflict = OnConflictStrategy.REPLACE) abstract suspend fun insertPredictions(rows: List<PredictionEntity>)
    @Transaction open suspend fun replacePredictions(rows: List<PredictionEntity>) { clearPredictions(); insertPredictions(rows) }

    @Query("SELECT * FROM slips ORDER BY id DESC") abstract suspend fun slips(): List<SlipEntity>
    @Query("DELETE FROM slips") abstract suspend fun clearSlips()
    @Insert(onConflict = OnConflictStrategy.REPLACE) abstract suspend fun insertSlips(rows: List<SlipEntity>)
    @Transaction open suspend fun replaceSlips(rows: List<SlipEntity>) { clearSlips(); insertSlips(rows) }
}

@Database(entities = [FixtureEntity::class, PredictionEntity::class, SlipEntity::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun dao(): AppDao

    companion object {
        @Volatile private var instance: AppDatabase? = null
        fun get(context: Context): AppDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "bet_slip_pro.db")
                .build().also { instance = it }
        }
    }
}
