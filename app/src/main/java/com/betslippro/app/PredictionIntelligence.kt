package com.betslippro.app


/**
 * Conservative empirical calibration using only already-evaluated predictions
 * created before the prediction being calibrated. It deliberately falls back
 * to the raw model probability when the historical sample is too small.
 */
object PredictionIntelligence {
    private const val MIN_CALIBRATION_SAMPLE = 20
    private const val PRIOR_STRENGTH = 20.0

    fun calibrateProbability(
        rawProbability: Double,
        league: String,
        market: String,
        predictionCreated: String,
        history: List<PredictionRecord>
    ): Double {
        val raw = rawProbability.coerceIn(0.001, 0.999)
        val priorRows = history.asSequence()
            .filter { it.evaluated && it.market == market }
            .filter { it.fixture.league.equals(league, ignoreCase = true) }
            .filter { it.created.isNotBlank() && it.created < predictionCreated }
            .filter {
                val historicalRaw = it.rawProbability ?: it.probability
                historicalRaw.isFinite() && kotlin.math.abs(historicalRaw - raw) <= 0.12
            }
            .toList()
        if (priorRows.size < MIN_CALIBRATION_SAMPLE) return raw

        val hits = priorRows.count(::wasPredictionCorrect).toDouble()
        // Bayesian shrinkage prevents small/local samples from swinging the model wildly.
        return ((hits + raw * PRIOR_STRENGTH) / (priorRows.size + PRIOR_STRENGTH))
            .coerceIn(0.02, 0.98)
    }

    fun buildSmartCoupon(predictions: List<AutoPrediction>, minimumProbability: Double = 0.60, maxLegs: Int = 3): List<AutoPrediction> {
        require(minimumProbability in 0.0..1.0)
        require(maxLegs in 2..8)
        return predictions.asSequence()
            .filter { it.probability.isFinite() && it.probability >= minimumProbability }
            .filter { it.audit == null || it.audit.score >= 65 }
            .filter { it.fixture.date.isNotBlank() }
            .distinctBy { it.fixture.id }
            .sortedByDescending { it.probability }
            .take(maxLegs)
            .toList()
    }

    fun leaguePerformance(records: List<PredictionRecord>): List<LeaguePerformance> =
        records.filter { it.evaluated }
            .groupBy { it.fixture.league.ifBlank { "بطولة غير محددة" } }
            .map { (league, rows) ->
                val hits = rows.count(::wasPredictionCorrect)
                val brier = rows.map { row ->
                    val p = row.probability.coerceIn(0.0001, 0.9999)
                    val y = if (wasPredictionCorrect(row)) 1.0 else 0.0
                    (p - y) * (p - y)
                }.average()
                LeaguePerformance(league, rows.size, hits, hits.toDouble() / rows.size, brier)
            }
            .sortedWith(compareByDescending<LeaguePerformance> { it.sampleSize }.thenByDescending { it.hitRate })

    fun approximateCouponProbability(legs: List<AutoPrediction>): Double =
        legs.fold(1.0) { acc, leg -> acc * leg.probability.coerceIn(0.0, 1.0) }

    private fun wasPredictionCorrect(record: PredictionRecord): Boolean {
        val home = record.actualHome ?: return false
        val away = record.actualAway ?: return false
        return when (record.market) {
            "1X2" -> when {
                record.pick == "تعادل" -> home == away
                record.pick == "فوز ${record.fixture.home}" -> home > away
                record.pick == "فوز ${record.fixture.away}" -> away > home
                else -> false
            }
            "الأهداف" -> {
                val line = Regex("(\\d+(?:\\.\\d+)?)").find(record.pick)?.value?.toDoubleOrNull() ?: return false
                if (record.pick.contains("أكثر")) home + away > line else home + away < line
            }
            "يسجل الفريقان" -> if (record.pick == "نعم") home > 0 && away > 0 else home == 0 || away == 0
            else -> false
        }
    }
}

data class LeaguePerformance(
    val league: String,
    val sampleSize: Int,
    val hits: Int,
    val hitRate: Double,
    val brierScore: Double
)
