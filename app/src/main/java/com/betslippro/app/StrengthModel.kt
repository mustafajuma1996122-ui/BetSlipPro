package com.betslippro.app

import kotlin.math.pow

data class StrengthFit(
    val attack: Map<String, Double>,
    val defense: Map<String, Double>,
    val base: Double,
    val gamma: Double,
    val matches: Int
)

/**
 * Opponent-adjusted team strengths (Maher / Dixon-Coles style multiplicative Poisson):
 *   lambdaHome = base * gamma * attack[home] * defense[away]
 *   lambdaAway = base * attack[away] * defense[home]
 * Fitted iteratively with exponential time decay and Bayesian shrinkage of every rating towards 1,
 * so a team with few matches is pulled towards the league average instead of being over-trusted.
 * Mirrors tools/model.py (fit_strengths).
 */
object StrengthModel {
    private class Row(val h: Int, val a: Int, val hg: Double, val ag: Double, val w: Double)

    /** ISO date ("YYYY-MM-DD..." ) -> days since 1970-01-01, without java.time (minSdk 24). */
    fun epochDay(iso: String): Long? {
        if (iso.length < 10) return null
        val y = iso.substring(0, 4).toIntOrNull() ?: return null
        val m = iso.substring(5, 7).toIntOrNull() ?: return null
        val d = iso.substring(8, 10).toIntOrNull() ?: return null
        if (m !in 1..12 || d !in 1..31) return null
        val yy = if (m <= 2) y - 1 else y
        val era = (if (yy >= 0) yy else yy - 399) / 400
        val yoe = yy - era * 400
        val mp = (m + 9) % 12
        val doy = (153 * mp + 2) / 5 + d - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        return era.toLong() * 146097L + doe - 719468L
    }

    fun fit(
        matches: List<EloMatch>,
        nowDay: Long,
        halfLifeDays: Double = 150.0,
        priorMatches: Double = 4.0,
        iterations: Int = 40
    ): StrengthFit? {
        val index = HashMap<String, Int>()
        val rows = ArrayList<Row>()
        for (m in matches.distinctBy { it.eventId }) {
            if (m.homeGoals < 0 || m.awayGoals < 0 || m.homeTeamId.isBlank() || m.awayTeamId.isBlank()) continue
            val day = epochDay(m.date) ?: continue
            val age = (nowDay - day).coerceAtLeast(0L).toDouble()
            val w = 0.5.pow(age / halfLifeDays)
            val hi = index.getOrPut(m.homeTeamId) { index.size }
            val ai = index.getOrPut(m.awayTeamId) { index.size }
            rows += Row(hi, ai, m.homeGoals.toDouble(), m.awayGoals.toDouble(), w)
        }
        val sw = rows.sumOf { it.w }
        if (rows.size < 10 || sw <= 0.0) return null
        val n = index.size
        val att = DoubleArray(n) { 1.0 }
        val def = DoubleArray(n) { 1.0 }
        var base = (rows.sumOf { it.w * (it.hg + it.ag) } / (2 * sw)).coerceAtLeast(0.2)
        var gamma = 1.2

        repeat(iterations) {
            val eh = rows.sumOf { it.w * att[it.h] * def[it.a] }
            gamma = (rows.sumOf { it.w * it.hg } / (base * eh).coerceAtLeast(1e-9)).coerceIn(0.8, 1.6)
            val den = rows.sumOf { it.w * (gamma * att[it.h] * def[it.a] + att[it.a] * def[it.h]) }
            base = rows.sumOf { it.w * (it.hg + it.ag) } / den.coerceAtLeast(1e-9)
            val k = priorMatches * base

            val numA = DoubleArray(n) { k }; val denA = DoubleArray(n) { k }
            for (r in rows) {
                numA[r.h] += r.w * r.hg; denA[r.h] += r.w * base * gamma * def[r.a]
                numA[r.a] += r.w * r.ag; denA[r.a] += r.w * base * def[r.h]
            }
            for (t in 0 until n) att[t] = numA[t] / denA[t]

            val numD = DoubleArray(n) { k }; val denD = DoubleArray(n) { k }
            for (r in rows) {
                numD[r.h] += r.w * r.ag; denD[r.h] += r.w * base * att[r.a]
                numD[r.a] += r.w * r.hg; denD[r.a] += r.w * base * gamma * att[r.h]
            }
            for (t in 0 until n) def[t] = numD[t] / denD[t]

            val mA = att.average(); val mD = def.average()
            for (t in 0 until n) { att[t] /= mA; def[t] /= mD }
            base *= mA * mD
        }
        return StrengthFit(
            index.mapValues { att[it.value] }, index.mapValues { def[it.value] }, base, gamma, rows.size
        )
    }

    /** Expected goals (home, away) or null when either team is unknown to the fit. */
    fun lambdas(fit: StrengthFit, home: String, away: String): Pair<Double, Double>? {
        val ah = fit.attack[home] ?: return null
        val aa = fit.attack[away] ?: return null
        val dh = fit.defense[home] ?: return null
        val da = fit.defense[away] ?: return null
        return (fit.base * fit.gamma * ah * da).coerceIn(0.15, 4.5) to (fit.base * aa * dh).coerceIn(0.15, 4.5)
    }
}
