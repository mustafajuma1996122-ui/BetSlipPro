package com.betslippro.app

import kotlin.math.max
import kotlin.math.min

/**
 * Value-finding utilities. Prices are user-entered decimal odds; this module never
 * fetches bookmaker prices or claims that a positive edge guarantees a win.
 */
data class ValueAssessment(
    val modelProbability: Double,
    val marketImpliedProbability: Double,
    val fairOdds: Double,
    val edge: Double,
    val expectedReturnPerUnit: Double,
    val kellyFraction: Double,
    val valueScore: Int,
    val label: String,
    val warnings: List<String>
)

data class ValueCandidate(
    val fixtureId: Long,
    val fixtureLabel: String,
    val market: String,
    val selection: String,
    val modelProbability: Double,
    val decimalOdds: Double,
    val dataQualityScore: Int,
    val assessment: ValueAssessment
)

object ValueFinder {
    /**
     * Evaluate one selection. `expectedReturnPerUnit` is net expected value:
     * p * (odds - 1) - (1 - p), equivalently p * odds - 1.
     */
    fun assess(
        modelProbability: Double,
        decimalOdds: Double,
        dataQualityScore: Int = 100,
        minimumEdge: Double = 0.02
    ): ValueAssessment? {
        if (!modelProbability.isFinite() || modelProbability !in 0.001..0.999) return null
        if (!decimalOdds.isFinite() || decimalOdds <= 1.0 || decimalOdds > 1000.0) return null
        if (minimumEdge !in 0.0..0.25) return null

        val market = 1.0 / decimalOdds
        val edge = modelProbability - market
        val ev = modelProbability * decimalOdds - 1.0
        val b = decimalOdds - 1.0
        val rawKelly = ((b * modelProbability - (1.0 - modelProbability)) / b).coerceAtLeast(0.0)
        // Quarter-Kelly cap is deliberately conservative; never presented as a staking instruction.
        val quarterKelly = (rawKelly * 0.25).coerceIn(0.0, 0.05)
        val fairOdds = 1.0 / modelProbability
        val quality = dataQualityScore.coerceIn(0, 100)
        val warnings = mutableListOf<String>()
        if (quality < 65) warnings += "جودة البيانات أقل من المستوى المفضل؛ لا تعتمد إشارة القيمة"
        if (quality < 85) warnings += "البيانات ليست عالية الجودة"
        if (decimalOdds > 8.0) warnings += "سعر مرتفع وتقلب النتيجة كبير"
        if (modelProbability >= 0.90) warnings += "احتمال النموذج مرتفع جداً؛ راجع المعايرة والعينة"
        if (edge > 0.15) warnings += "فارق كبير بصورة غير معتادة؛ تحقق من صحة السعر والبيانات"

        val label = when {
            quality < 65 -> "غير مؤهل: جودة البيانات ضعيفة"
            edge >= minimumEdge && ev > 0.0 -> "قيمة محتملة"
            edge > 0.0 -> "أفضلية ضئيلة دون الحد المعتمد"
            else -> "لا توجد قيمة إيجابية"
        }
        val score = (
            50.0 + edge * 220.0 + (quality - 70) * 0.35 - max(0.0, decimalOdds - 5.0) * 1.2
        ).toInt().coerceIn(0, 100)
        return ValueAssessment(modelProbability, market, fairOdds, edge, ev, quarterKelly, score, label, warnings)
    }

    /** Remove the bookmaker overround from a complete set of mutually exclusive prices. */
    fun noVigProbabilities(decimalOdds: List<Double>): List<Double>? {
        if (decimalOdds.isEmpty() || decimalOdds.any { !it.isFinite() || it <= 1.0 }) return null
        val raw = decimalOdds.map { 1.0 / it }
        val total = raw.sum()
        if (!total.isFinite() || total <= 0.0) return null
        return raw.map { it / total }
    }

    /** Sort eligible candidates by value score, then edge, while excluding weak-data picks. */
    fun rank(candidates: List<ValueCandidate>, limit: Int = 10): List<ValueCandidate> {
        if (limit <= 0) return emptyList()
        return candidates
            .filter { it.dataQualityScore >= 65 && it.assessment.edge >= 0.02 && it.assessment.expectedReturnPerUnit > 0.0 }
            .distinctBy { it.fixtureId to (it.market + ":" + it.selection) }
            .sortedWith(compareByDescending<ValueCandidate> { it.assessment.valueScore }
                .thenByDescending { it.assessment.edge }
                .thenByDescending { it.dataQualityScore })
            .take(limit)
    }

    /** A product of probabilities is only a rough independent-leg estimate. */
    fun couponExpectedReturn(probabilities: List<Double>, decimalOdds: List<Double>): Double? {
        if (probabilities.isEmpty() || probabilities.size != decimalOdds.size) return null
        if (probabilities.any { !it.isFinite() || it !in 0.0..1.0 } || decimalOdds.any { !it.isFinite() || it <= 1.0 }) return null
        val jointProbability = probabilities.fold(1.0) { acc, p -> acc * p }
        val combinedOdds = decimalOdds.fold(1.0) { acc, odd -> acc * odd }
        return jointProbability * combinedOdds - 1.0
    }
}
