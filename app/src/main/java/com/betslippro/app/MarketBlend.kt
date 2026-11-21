package com.betslippro.app

import java.text.Normalizer
import java.util.Locale

/**
 * Combines the model with bookmaker prices (margin removed). Bookmaker prices carry information the
 * model cannot see (injuries, line-ups, news), so blending is usually better than either alone.
 * The weight is a conservative default; tools/backtest.py reports the best weight on historical data.
 */
object MarketBlend {
    const val DEFAULT_MARKET_WEIGHT = 0.5

    /** Blend two probability triples (home, draw, away) and renormalise. */
    fun blend(model: Triple<Double, Double, Double>, market: Triple<Double, Double, Double>, marketWeight: Double = DEFAULT_MARKET_WEIGHT): Triple<Double, Double, Double> {
        val w = marketWeight.coerceIn(0.0, 1.0)
        val h = (1 - w) * model.first + w * market.first
        val d = (1 - w) * model.second + w * market.second
        val a = (1 - w) * model.third + w * market.third
        val s = (h + d + a).coerceAtLeast(1e-12)
        return Triple(h / s, d / s, a / s)
    }

    /** Fair (no-vig) 1X2 probabilities for a fixture, from an odds event, or null if not a clean 3-way market. */
    fun marketTriple(event: OddsEvent): Triple<Double, Double, Double>? {
        val home = event.outcomes.firstOrNull { TeamNameMatcher.same(it.name, event.home) }?.fairProbability
        val away = event.outcomes.firstOrNull { TeamNameMatcher.same(it.name, event.away) }?.fairProbability
        val draw = event.outcomes.firstOrNull { it.name.equals("Draw", ignoreCase = true) }?.fairProbability
        if (home == null || away == null || draw == null) return null
        val s = home + draw + away
        if (!s.isFinite() || s <= 0.0) return null
        return Triple(home / s, draw / s, away / s)
    }

    fun findEvent(events: List<OddsEvent>, homeName: String, awayName: String): OddsEvent? =
        events.firstOrNull { TeamNameMatcher.same(it.home, homeName) && TeamNameMatcher.same(it.away, awayName) }
}

/** Tolerant team-name matching across data providers ("Man United" vs "Manchester United FC"). */
object TeamNameMatcher {
    private val noise = setOf("fc", "cf", "afc", "sc", "ac", "as", "cd", "ud", "sv", "fk", "the", "club", "de", "calcio", "1", "04", "05")
    private val aliases = mapOf("utd" to "united", "man" to "manchester", "inter" to "internazionale", "spurs" to "tottenham", "wolves" to "wolverhampton", "brighton" to "brighton")

    fun normalize(name: String): String = Normalizer.normalize(name, Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "")
        .lowercase(Locale.ROOT)
        .replace(Regex("[^a-z0-9 ]"), " ")
        .split(' ').filter { it.isNotBlank() && it !in noise }
        .joinToString(" ") { aliases[it] ?: it }

    fun same(a: String, b: String): Boolean {
        val x = normalize(a); val y = normalize(b)
        if (x.isEmpty() || y.isEmpty()) return false
        if (x == y) return true
        val tx = x.split(' ').toSet(); val ty = y.split(' ').toSet()
        val inter = tx.intersect(ty).size
        // Same club if one name's tokens are fully contained in the other's (and share a meaningful token).
        return inter > 0 && (tx.all { it in ty } || ty.all { it in tx })
    }
}
