package com.betslippro.app

import kotlin.math.min

/**
 * Transparent probability model. Inputs should be calculated from completed matches only.
 * This is a statistical estimate, never a guarantee or a source of certain outcomes.
 */
data class TeamForm(
    val matches: Int,
    val goalsForPerMatch: Double,
    val goalsAgainstPerMatch: Double,
    val xgForPerMatch: Double? = null,
    val xgAgainstPerMatch: Double? = null
)

data class MatchProbabilities(
    val expectedHomeGoals: Double,
    val expectedAwayGoals: Double,
    val homeWin: Double,
    val draw: Double,
    val awayWin: Double,
    val over15: Double,
    val over25: Double,
    val over35: Double,
    val bttsYes: Double,
    val mostLikelyScore: String,
    val confidence: String,
    val dataQuality: String
)

object PredictionEngine {
    fun predict(
        home: TeamForm,
        away: TeamForm,
        leagueHomeGoalsAvg: Double = 1.50,
        leagueAwayGoalsAvg: Double = 1.15,
        homeAdvantage: Double = 1.06,
        rho: Double = ScoreModel.DEFAULT_RHO
    ): MatchProbabilities {
        require(home.matches >= 0 && away.matches >= 0) { "عدد المباريات لا يمكن أن يكون سالباً" }
        require(leagueHomeGoalsAvg.isFinite() && leagueHomeGoalsAvg in 0.2..4.0)
        require(leagueAwayGoalsAvg.isFinite() && leagueAwayGoalsAvg in 0.2..4.0)
        require(homeAdvantage.isFinite() && homeAdvantage in 0.8..1.4)

        val homeAttack = blendedAttack(home)
        val awayAttack = blendedAttack(away)
        val homeDefense = blendedDefense(home)
        val awayDefense = blendedDefense(away)

        val lambdaHome = (leagueHomeGoalsAvg * (homeAttack / leagueHomeGoalsAvg) * (awayDefense / leagueHomeGoalsAvg) * homeAdvantage)
            .coerceIn(0.15, 4.5)
        val lambdaAway = (leagueAwayGoalsAvg * (awayAttack / leagueAwayGoalsAvg) * (homeDefense / leagueAwayGoalsAvg))
            .coerceIn(0.15, 4.5)
        return fromLambdas(lambdaHome, lambdaAway, min(home.matches, away.matches), rho)
    }

    /** Builds every market from expected goals. Used by both the simple and the strength-based engines. */
    fun fromLambdas(lambdaHome: Double, lambdaAway: Double, sample: Int, rho: Double = ScoreModel.DEFAULT_RHO): MatchProbabilities {
        val grid = ScoreModel.matrix(lambdaHome, lambdaAway, rho)
        var pHome = 0.0; var pDraw = 0.0; var pAway = 0.0
        var pBtts = 0.0; var pOver15 = 0.0; var pOver25 = 0.0; var pOver35 = 0.0
        var best = -1.0; var bestHome = 0; var bestAway = 0
        for (h in grid.indices) {
            for (a in grid[h].indices) {
                val p = grid[h][a]
                when {
                    h > a -> pHome += p
                    h == a -> pDraw += p
                    else -> pAway += p
                }
                if (h > 0 && a > 0) pBtts += p
                if (h + a >= 2) pOver15 += p
                if (h + a >= 3) pOver25 += p
                if (h + a >= 4) pOver35 += p
                if (p > best) { best = p; bestHome = h; bestAway = a }
            }
        }
        val quality = when {
            sample >= 10 -> "جيدة: آخر 10 مباريات أو أكثر"
            sample >= 5 -> "متوسطة: عينة من 5 إلى 9 مباريات"
            sample > 0 -> "محدودة: أقل من 5 مباريات"
            else -> "غير كافية: لا توجد بيانات تاريخية"
        }
        val confidence = when {
            sample >= 10 -> "متوسطة إحصائياً"
            sample >= 5 -> "منخفضة إلى متوسطة"
            else -> "منخفضة — لا تعتمد عليها منفردة"
        }
        return MatchProbabilities(
            lambdaHome, lambdaAway, pHome, pDraw, pAway,
            pOver15.coerceIn(0.0, 1.0), pOver25.coerceIn(0.0, 1.0),
            pOver35.coerceIn(0.0, 1.0), pBtts.coerceIn(0.0, 1.0),
            "$bestHome - $bestAway", confidence, quality
        )
    }

    private fun blendedAttack(form: TeamForm): Double {
        val goals = safeAverage(form.goalsForPerMatch)
        val xg = form.xgForPerMatch?.takeIf { it.isFinite() && it in 0.0..6.0 }
        return if (xg == null) goals else 0.65 * xg + 0.35 * goals
    }

    private fun blendedDefense(form: TeamForm): Double {
        val goals = safeAverage(form.goalsAgainstPerMatch)
        val xga = form.xgAgainstPerMatch?.takeIf { it.isFinite() && it in 0.0..6.0 }
        return if (xga == null) goals else 0.65 * xga + 0.35 * goals
    }

    private fun safeAverage(value: Double): Double = if (value.isFinite()) value.coerceIn(0.0, 6.0) else 1.2

    fun percent(value: Double): String = "${(value.coerceIn(0.0, 1.0) * 100.0).toInt()}%"
}
