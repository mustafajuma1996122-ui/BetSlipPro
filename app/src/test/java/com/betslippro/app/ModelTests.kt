package com.betslippro.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.util.Random
import kotlin.math.exp
import kotlin.math.ln

class ScoreModelTest {
    @Test fun matrixSumsToOne() {
        val total = ScoreModel.matrix(1.4, 1.1).sumOf { it.sum() }
        assertEquals(1.0, total, 1e-9)
    }

    // Reference values computed with tools/model.py (same algorithm).
    @Test fun zeroRhoIsPlainIndependentPoisson() {
        val p = PredictionEngine.fromLambdas(1.4, 1.1, 10, 0.0)
        assertEquals(0.43804, p.homeWin, 0.0005)
        assertEquals(0.26634, p.draw, 0.0005)
        assertEquals(0.29561, p.awayWin, 0.0005)
        assertEquals(0.45619, p.over25, 0.0005)
    }

    @Test fun dixonColesRaisesDrawsAndKeepsTotalsConsistent() {
        val p = PredictionEngine.fromLambdas(1.4, 1.1, 10, -0.10)
        assertEquals(0.29163, p.draw, 0.0005)
        assertEquals(1.0, p.homeWin + p.draw + p.awayWin, 1e-9)
        assertTrue(p.over15 >= p.over25 && p.over25 >= p.over35)
        assertTrue(p.draw > PredictionEngine.fromLambdas(1.4, 1.1, 10, 0.0).draw)
    }

    @Test fun simpleEngineStillWorks() {
        // Two league-average sides: the home team's home rates vs the away team's away rates.
        val homeForm = TeamForm(10, 1.50, 1.15)
        val awayForm = TeamForm(10, 1.15, 1.50)
        val p = PredictionEngine.predict(homeForm, awayForm)
        assertEquals(1.0, p.homeWin + p.draw + p.awayWin, 1e-9)
        assertTrue(p.homeWin > p.awayWin) // home advantage
    }
}

class StrengthModelTest {
    @Test fun epochDayMatchesKnownDates() {
        assertEquals(0L, StrengthModel.epochDay("1970-01-01"))
        assertEquals(11016L, StrengthModel.epochDay("2000-02-29"))
        assertEquals(20778L, StrengthModel.epochDay("2026-11-21T15:00Z"))
        assertEquals(null, StrengthModel.epochDay("bad"))
    }

    private fun poisson(rng: Random, lambda: Double): Int {
        val limit = exp(-lambda)
        var k = 0
        var p = rng.nextDouble()
        while (p > limit) { k++; p *= rng.nextDouble() }
        return k
    }

    @Test fun fitRecoversStrengthsAndBeatsLeagueAverageOutOfSample() {
        val rng = Random(11)
        val teams = (0 until 16).map { "T$it" }
        val attack = HashMap<String, Double>()
        for (t in teams) attack[t] = exp(rng.nextGaussian() * 0.35)
        val defense = HashMap<String, Double>()
        for (t in teams) defense[t] = exp(rng.nextGaussian() * 0.35)
        val start = LocalDate.of(2025, 1, 1)
        var counter = 0
        fun simulate(rounds: Int): List<EloMatch> {
            val out = ArrayList<EloMatch>()
            var idx = 0
            repeat(rounds) {
                for (h in teams) for (a in teams) {
                    if (h == a) continue
                    val lh = 1.25 * 1.25 * attack[h]!! * defense[a]!!
                    val la = 1.25 * attack[a]!! * defense[h]!!
                    val hg = poisson(rng, lh)
                    val ag = poisson(rng, la)
                    out += EloMatch("e${counter++}", start.plusDays((idx / 4).toLong()).toString(), h, a, hg, ag)
                    idx++
                }
            }
            return out
        }
        val train = simulate(3)
        val test = simulate(2)
        val nowDay = StrengthModel.epochDay(train.last().date)!! + 1
        val fit = StrengthModel.fit(train, nowDay)!!

        val ca = correlation(teams.map { fit.attack[it]!! }, teams.map { attack[it]!! })
        val cd = correlation(teams.map { fit.defense[it]!! }, teams.map { defense[it]!! })
        assertTrue("attack corr $ca", ca > 0.9)
        assertTrue("defense corr $cd", cd > 0.9)

        fun logLoss(lambdas: (EloMatch) -> Pair<Double, Double>): Double = test.sumOf { m ->
            val (lh, la) = lambdas(m)
            val p = PredictionEngine.fromLambdas(lh, la, 10, -0.10)
            val prob = when { m.homeGoals > m.awayGoals -> p.homeWin; m.homeGoals == m.awayGoals -> p.draw; else -> p.awayWin }
            -ln(prob.coerceAtLeast(1e-9))
        } / test.size
        val fitted = logLoss { StrengthModel.lambdas(fit, it.homeTeamId, it.awayTeamId)!! }
        val naive = logLoss { (fit.base * fit.gamma).coerceIn(0.15, 4.5) to fit.base.coerceIn(0.15, 4.5) }
        assertTrue("fit $fitted vs naive $naive", fitted < naive - 0.03)
    }

    @Test fun tooLittleDataReturnsNull() {
        assertEquals(null, StrengthModel.fit(listOf(EloMatch("1", "2026-01-01", "A", "B", 1, 0)), 20000L))
    }

    private fun correlation(x: List<Double>, y: List<Double>): Double {
        val mx = x.average(); val my = y.average()
        val cov = x.indices.sumOf { (x[it] - mx) * (y[it] - my) }
        val vx = x.sumOf { (it - mx) * (it - mx) }; val vy = y.sumOf { (it - my) * (it - my) }
        return cov / kotlin.math.sqrt(vx * vy)
    }
}

class EloTest {
    @Test fun homeWinMovesRatingsSymmetricallyByExpectedDelta() {
        val r = EloRatingEngine.calculate(listOf(EloMatch("1", "2026-01-01", "H", "A", 1, 0)))
        assertEquals(1500.0 + 11.57, r["H"]!!, 0.01)   // k=20, home edge 55 -> expected 0.4215
        assertEquals(1500.0 - 11.57, r["A"]!!, 0.01)
    }

    @Test fun duplicateEventIsCountedOnce() {
        val m = EloMatch("1", "2026-01-01", "H", "A", 1, 0)
        assertEquals(EloRatingEngine.calculate(listOf(m)), EloRatingEngine.calculate(listOf(m, m)))
    }
}
