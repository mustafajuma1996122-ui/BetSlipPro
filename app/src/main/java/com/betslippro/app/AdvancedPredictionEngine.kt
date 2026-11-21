package com.betslippro.app

import kotlin.math.pow

/** A completed fixture from the perspective of a team; newest fixtures should be supplied first. */
data class TeamMatchResult(
    val eventId: String,
    val date: String,
    val teamId: String,
    val opponentId: String,
    val goalsFor: Int,
    val goalsAgainst: Int,
    val wasHome: Boolean
)

data class AdvancedTeamHistory(
    val teamId: String,
    val matches: List<TeamMatchResult>
)

data class EloMatch(
    val eventId: String,
    val date: String,
    val homeTeamId: String,
    val awayTeamId: String,
    val homeGoals: Int,
    val awayGoals: Int
)

data class AdvancedPredictionResult(
    val probabilities: MatchProbabilities,
    val homeElo: Double,
    val awayElo: Double,
    val eloDifference: Double,
    val homeVenueSample: Int,
    val awayVenueSample: Int,
    val headToHeadSample: Int,
    val modelLabel: String,
    val warnings: List<String>
)

/** Elo ratings calculated only from completed fixtures in the supplied dataset. */
object EloRatingEngine {
    fun calculate(matches: List<EloMatch>, initialRating: Double = 1500.0, kFactor: Double = 20.0): Map<String, Double> {
        val ratings = mutableMapOf<String, Double>()
        val unique = matches.distinctBy { it.eventId }
            .filter { it.homeGoals >= 0 && it.awayGoals >= 0 && it.homeTeamId.isNotBlank() && it.awayTeamId.isNotBlank() }
            .sortedBy { it.date }
        unique.forEach { match ->
            val home = ratings.getOrPut(match.homeTeamId) { initialRating }
            val away = ratings.getOrPut(match.awayTeamId) { initialRating }
            val expectedHome = 1.0 / (1.0 + 10.0.pow(((away + 55.0) - home) / 400.0))
            val actualHome = when {
                match.homeGoals > match.awayGoals -> 1.0
                match.homeGoals == match.awayGoals -> 0.5
                else -> 0.0
            }
            val margin = kotlin.math.abs(match.homeGoals - match.awayGoals)
            val marginMultiplier = if (margin <= 1) 1.0 else (1.0 + 0.15 * (margin - 1)).coerceAtMost(1.6)
            val delta = kFactor * marginMultiplier * (actualHome - expectedHome)
            ratings[match.homeTeamId] = home + delta
            ratings[match.awayTeamId] = away - delta
        }
        return ratings
    }
}

object AdvancedPredictionEngine {
    /**
     * Preferred engine: opponent-adjusted strengths fitted on the whole competition (see StrengthModel)
     * + Dixon-Coles score distribution. Elo is kept only as an informational/secondary signal, because
     * feeding it into expected goals as well would double count team strength.
     * Returns null when either team is unknown to the fit, so callers can fall back to [predict].
     */
    fun predictWithStrengths(
        home: AdvancedTeamHistory,
        away: AdvancedTeamHistory,
        fit: StrengthFit,
        eloRatings: Map<String, Double>,
        rho: Double = ScoreModel.DEFAULT_RHO
    ): AdvancedPredictionResult? {
        val lambdas = StrengthModel.lambdas(fit, home.teamId, away.teamId) ?: return null
        val sample = kotlin.math.min(home.matches.size, away.matches.size)
        val probabilities = PredictionEngine.fromLambdas(lambdas.first, lambdas.second, sample, rho)
        val homeElo = eloRatings[home.teamId] ?: 1500.0
        val awayElo = eloRatings[away.teamId] ?: 1500.0
        val warnings = mutableListOf<String>()
        if (sample < 10) warnings += "السجل أقل من 10 مباريات لأحد الفريقين"
        if (fit.matches < 60) warnings += "عدد مباريات الدوري المستخدمة في التقدير محدود (${fit.matches})"
        if (home.teamId !in eloRatings || away.teamId !in eloRatings) warnings += "تقييم Elo غير مكتمل"
        val label = if (warnings.isEmpty()) "Dixon-Coles + قوة الفرق (معدلة بالخصم) + Elo" else "Dixon-Coles + قوة الفرق (بيانات جزئية)"
        return AdvancedPredictionResult(
            probabilities, homeElo, awayElo, homeElo - awayElo,
            home.matches.count { it.wasHome }, away.matches.count { !it.wasHome },
            home.matches.count { it.opponentId == away.teamId }, label, warnings
        )
    }

    /**
     * Blends recent form (last 5 and last 10), home/away venue splits, a shrunk H2H signal,
     * Elo strength and the existing independent-Poisson score model.
     */
    fun predict(
        home: AdvancedTeamHistory,
        away: AdvancedTeamHistory,
        eloRatings: Map<String, Double>,
        leagueHomeAverage: Double = 1.50,
        leagueAwayAverage: Double = 1.15
    ): AdvancedPredictionResult {
        require(home.teamId.isNotBlank() && away.teamId.isNotBlank())
        require(leagueHomeAverage in 0.2..4.0 && leagueAwayAverage in 0.2..4.0)

        val hAll = home.matches.sortedByDescending { it.date }
        val aAll = away.matches.sortedByDescending { it.date }
        val hVenue = hAll.filter { it.wasHome }
        val aVenue = aAll.filter { !it.wasHome }
        val hAttack = attackRate(hAll, hVenue)
        val hConcede = concedeRate(hAll, hVenue)
        val aAttack = attackRate(aAll, aVenue)
        val aConcede = concedeRate(aAll, aVenue)

        // Venue-specific rates are blended with the overall recent rate to avoid tiny-sample overfit.
        var adjustedHomeAttack = hAttack
        var adjustedAwayAttack = aAttack

        val h2h = hAll.filter { it.opponentId == away.teamId && it.wasHome }.take(6)
        if (h2h.size >= 2) {
            val h2hHomeScored = weightedMean(h2h.map { it.goalsFor })
            val h2hAwayScored = weightedMean(h2h.map { it.goalsAgainst })
            val shrink = (h2h.size / 10.0).coerceIn(0.0, 0.35)
            adjustedHomeAttack = adjustedHomeAttack * (1.0 - shrink) + h2hHomeScored.coerceIn(0.2, 4.0) * shrink
            adjustedAwayAttack = adjustedAwayAttack * (1.0 - shrink) + h2hAwayScored.coerceIn(0.2, 4.0) * shrink
        }

        val homeElo = eloRatings[home.teamId] ?: 1500.0
        val awayElo = eloRatings[away.teamId] ?: 1500.0
        val eloDiff = homeElo - awayElo
        // Elo influences goal expectation modestly; it cannot dominate the match-form signal.
        val eloFactor = (10.0.pow((eloDiff / 400.0) * 0.35)).coerceIn(0.85, 1.18)
        val adjustedHomeForm = TeamForm(hAll.size, (adjustedHomeAttack * eloFactor).coerceIn(0.15, 4.5), hConcede)
        val adjustedAwayForm = TeamForm(aAll.size, (adjustedAwayAttack / eloFactor).coerceIn(0.15, 4.5), aConcede)
        val probabilities = PredictionEngine.predict(
            adjustedHomeForm, adjustedAwayForm, leagueHomeAverage, leagueAwayAverage, 1.0
        )

        val warnings = mutableListOf<String>()
        if (hAll.size < 10 || aAll.size < 10) warnings += "السجل أقل من 10 مباريات لأحد الفريقين"
        if (hVenue.size < 3) warnings += "عينة الفريق المضيف على أرضه محدودة"
        if (aVenue.size < 3) warnings += "عينة الفريق الضيف خارج أرضه محدودة"
        if (h2h.size < 2) warnings += "المواجهات المباشرة غير كافية؛ تم تقليل أثرها/استبعادها"
        if (home.teamId !in eloRatings || away.teamId !in eloRatings) warnings += "تقييم Elo غير مكتمل؛ استُخدم التقييم الابتدائي للفريق غير المتوفر"
        val label = if (warnings.isEmpty()) "Poisson + Elo + Venue + H2H" else "Poisson + Elo + Venue (بيانات جزئية)"
        return AdvancedPredictionResult(probabilities, homeElo, awayElo, eloDiff, hVenue.size, aVenue.size, h2h.size, label, warnings)
    }

    private fun attackRate(all: List<TeamMatchResult>, venue: List<TeamMatchResult>): Double {
        val recent = recentBlend(all.map { it.goalsFor })
        val venueRate = recentBlend(venue.map { it.goalsFor })
        val weight = (venue.size / (venue.size + 4.0)).coerceIn(0.0, 0.65)
        return (recent * (1.0 - weight) + venueRate * weight).coerceIn(0.15, 4.5)
    }

    private fun concedeRate(all: List<TeamMatchResult>, venue: List<TeamMatchResult>): Double {
        val recent = recentBlend(all.map { it.goalsAgainst })
        val venueRate = recentBlend(venue.map { it.goalsAgainst })
        val weight = (venue.size / (venue.size + 4.0)).coerceIn(0.0, 0.65)
        return (recent * (1.0 - weight) + venueRate * weight).coerceIn(0.15, 4.5)
    }

    private fun recentBlend(values: List<Int>): Double {
        if (values.isEmpty()) return 1.2
        val newest = values.take(10)
        val lastFive = weightedMean(newest.take(5))
        val lastTen = weightedMean(newest)
        return (0.60 * lastFive + 0.40 * lastTen).coerceIn(0.0, 6.0)
    }

    /** Newest first; exponential weights 1.0, 0.85, 0.72... */
    private fun weightedMean(values: List<Int>): Double {
        if (values.isEmpty()) return 1.2
        var numerator = 0.0
        var denominator = 0.0
        values.forEachIndexed { index, value ->
            val weight = 0.85.pow(index.toDouble())
            numerator += value.coerceAtLeast(0) * weight
            denominator += weight
        }
        return if (denominator == 0.0) 1.2 else numerator / denominator
    }
}
