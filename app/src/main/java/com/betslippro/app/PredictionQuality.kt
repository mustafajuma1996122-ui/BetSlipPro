package com.betslippro.app

import kotlin.math.abs
import kotlin.math.roundToInt

/** Transparent quality controls. These checks flag weak/inconsistent inputs; they do not prove a pick is correct. */
data class TeamStrengthProfile(
    val attackGoalsPerMatch: Double,
    val defenseGoalsConcededPerMatch: Double,
    val attackIndex: Double,
    val defensiveIndex: Double
)

data class PredictionAudit(
    val score: Int,
    val home: TeamStrengthProfile,
    val away: TeamStrengthProfile,
    val warnings: List<String>,
    val confidenceWarning: String?
)

data class OddsComparison(
    val impliedProbability: Double,
    val edge: Double,
    val verdict: String
)

data class CouponAudit(
    val legs: Int,
    val approximateJointProbability: Double,
    val weakestLegProbability: Double,
    val qualityLabel: String,
    val warnings: List<String>
)

object PredictionQuality {
    fun audit(home: TeamForm, away: TeamForm, selectedProbability: Double): PredictionAudit {
        val warnings = mutableListOf<String>()
        var score = 100
        val forms = listOf("صاحب الأرض" to home, "الضيف" to away)
        forms.forEach { (label, form) ->
            if (form.matches < 5) { score -= 25; warnings += "عينة $label أقل من 5 مباريات" }
            else if (form.matches < 10) { score -= 12; warnings += "عينة $label أقل من 10 مباريات" }
            if (!form.goalsForPerMatch.isFinite() || form.goalsForPerMatch !in 0.0..6.0) {
                score -= 30; warnings += "معدل تسجيل $label غير منطقي أو غير صالح"
            }
            if (!form.goalsAgainstPerMatch.isFinite() || form.goalsAgainstPerMatch !in 0.0..6.0) {
                score -= 30; warnings += "معدل استقبال $label غير منطقي أو غير صالح"
            }
            val xg = form.xgForPerMatch
            if (xg != null && (!xg.isFinite() || xg !in 0.0..6.0)) {
                score -= 15; warnings += "قيمة xG لـ $label خارج النطاق المتوقع"
            } else if (xg != null && form.goalsForPerMatch.isFinite() && abs(xg - form.goalsForPerMatch) >= 1.5) {
                score -= 10; warnings += "تباين كبير بين xG والأهداف الفعلية لـ $label"
            }
            val xga = form.xgAgainstPerMatch
            if (xga != null && (!xga.isFinite() || xga !in 0.0..6.0)) {
                score -= 15; warnings += "قيمة xGA لـ $label خارج النطاق المتوقع"
            } else if (xga != null && form.goalsAgainstPerMatch.isFinite() && abs(xga - form.goalsAgainstPerMatch) >= 1.5) {
                score -= 10; warnings += "تباين كبير بين xGA والأهداف المستقبلة لـ $label"
            }
        }
        if (selectedProbability !in 0.0..1.0 || !selectedProbability.isFinite()) {
            score = 0; warnings += "احتمال التوقع غير صالح"
        }
        val minSample = minOf(home.matches, away.matches)
        val confidenceWarning = when {
            selectedProbability >= 0.90 && minSample < 20 -> "احتمال شديد الارتفاع دون عينة كافية؛ لا تعتبره يقيناً"
            selectedProbability >= 0.80 && minSample < 10 -> "احتمال مرتفع مع عينة تاريخية محدودة؛ قد تكون الثقة مبالغاً بها"
            else -> null
        }
        if (confidenceWarning != null) { score -= 15; warnings += confidenceWarning }
        if (warnings.isEmpty()) warnings += "لم تُكتشف مشكلة واضحة في حدود البيانات المتاحة"
        return PredictionAudit(
            score.coerceIn(0, 100),
            profile(home), profile(away),
            warnings.distinct(), confidenceWarning
        )
    }

    private fun profile(form: TeamForm): TeamStrengthProfile {
        val attack = form.goalsForPerMatch.takeIf { it.isFinite() }?.coerceIn(0.0, 6.0) ?: 0.0
        val conceded = form.goalsAgainstPerMatch.takeIf { it.isFinite() }?.coerceIn(0.0, 6.0) ?: 0.0
        // Indices are relative to neutral reference values, not league-adjusted ratings.
        return TeamStrengthProfile(attack, conceded, (attack / 1.5).coerceIn(0.0, 4.0), (1.5 / (conceded + 0.25)).coerceIn(0.0, 4.0))
    }

    fun compareDecimalOdds(modelProbability: Double, decimalOdds: Double): OddsComparison? {
        if (!modelProbability.isFinite() || modelProbability !in 0.0..1.0 || !decimalOdds.isFinite() || decimalOdds <= 1.0) return null
        val implied = (1.0 / decimalOdds).coerceIn(0.0, 1.0)
        val edge = modelProbability - implied
        val verdict = when {
            edge >= 0.05 -> "أفضلية نموذجية أولية؛ تحقق من هامش السوق وجودة البيانات"
            edge >= 0.02 -> "أفضلية محدودة؛ لا تكفي وحدها لاتخاذ قرار"
            edge <= -0.03 -> "السوق يسعّر الاحتمال أعلى من النموذج"
            else -> "لا توجد أفضلية واضحة بين النموذج والسعر المدخل"
        }
        return OddsComparison(implied, edge, verdict)
    }

    fun couponAudit(predictions: List<AutoPrediction>): CouponAudit {
        val distinct = predictions.distinctBy { it.fixture.id }
        val probabilities = distinct.map { it.probability.coerceIn(0.0, 1.0) }
        val joint = probabilities.fold(1.0) { acc, p -> acc * p }
        val warnings = mutableListOf<String>()
        if (distinct.size < 2) warnings += "القسيمة تحتاج اختيارين على الأقل"
        if (distinct.size > 4) warnings += "عدد الاختيارات مرتفع ويزيد تراكم المخاطر"
        if (distinct.map { it.fixture.league }.distinct().size < distinct.size && distinct.size > 1)
            warnings += "توجد اختيارات من الدوري نفسه؛ الاستقلال الإحصائي غير مضمون"
        if (distinct.any { it.probability < 0.60 }) warnings += "يوجد اختيار باحتمال أقل من 60%"
        if (distinct.any { it.quality.contains("محدودة") || it.quality.contains("غير كافية") })
            warnings += "بعض المباريات تعتمد على بيانات تاريخية محدودة"
        if (distinct.size >= 3) warnings += "الاحتمال التجميعي حاصل ضرب تقريبي ويفترض استقلال الاختيارات"
        val label = when {
            distinct.isEmpty() -> "لا توجد قسيمة"
            warnings.any { it.contains("محدودة") || it.contains("استقلال") || it.contains("مرتفع") } -> "مخاطر مرتفعة / تحتاج مراجعة"
            joint < 0.25 -> "احتمال تجميعي منخفض"
            else -> "تحتاج مراجعة قبل الاعتماد"
        }
        return CouponAudit(distinct.size, joint, probabilities.minOrNull() ?: 0.0, label, warnings.distinct())
    }

    fun scoreLabel(score: Int): String = when {
        score >= 85 -> "جودة بيانات جيدة (${score}%)"
        score >= 65 -> "جودة بيانات متوسطة (${score}%)"
        else -> "جودة بيانات منخفضة (${score}%)"
    }
}
