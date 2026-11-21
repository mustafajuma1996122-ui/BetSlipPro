package com.betslippro.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IdsTest {
    @Test fun stableAndDistinct() {
        assertEquals(Ids.stable("a", "b"), Ids.stable("a", "b"))
        assertTrue(Ids.stable("a", "b") != Ids.stable("b", "a"))
        assertTrue(Ids.stable("x") >= 0)
    }
}

class TeamNameMatcherTest {
    @Test fun matchesCommonVariants() {
        assertTrue(TeamNameMatcher.same("Man United", "Manchester United FC"))
        assertTrue(TeamNameMatcher.same("Brighton & Hove Albion", "Brighton"))
        assertTrue(TeamNameMatcher.same("Atlético Madrid", "Atletico Madrid"))
    }

    @Test fun doesNotConfuseDifferentClubs() {
        assertFalse(TeamNameMatcher.same("Manchester City", "Manchester United"))
        assertFalse(TeamNameMatcher.same("Sporting CP", "Sporting Gijon"))
        assertFalse(TeamNameMatcher.same("Real Madrid", "Atletico Madrid"))
    }
}

class MarketBlendTest {
    @Test fun blendNormalisesAndRespectsWeight() {
        val model = Triple(0.5, 0.3, 0.2); val market = Triple(0.4, 0.3, 0.3)
        val b = MarketBlend.blend(model, market, 0.5)
        assertEquals(1.0, b.first + b.second + b.third, 1e-9)
        assertEquals(0.45, b.first, 1e-9)
        assertEquals(model.first, MarketBlend.blend(model, market, 0.0).first, 1e-9)
        assertEquals(market.first, MarketBlend.blend(model, market, 1.0).first, 1e-9)
    }

    private fun outcome(name: String, p: Double) = OddsOutcome(name, 2.0, 2.0, p, 5, "")

    @Test fun marketTripleNeedsCleanThreeWay() {
        val ev = OddsEvent("1", "soccer_epl", "EPL", "Man United", "Liverpool", "", 5,
            listOf(outcome("Manchester United", 0.3), outcome("Draw", 0.3), outcome("Liverpool", 0.4)))
        val t = MarketBlend.marketTriple(ev)
        assertNotNull(t)
        assertEquals(0.3, t!!.first, 1e-9)
        assertNull(MarketBlend.marketTriple(ev.copy(outcomes = ev.outcomes.take(2))))
        assertNotNull(MarketBlend.findEvent(listOf(ev), "Manchester United FC", "Liverpool FC"))
    }
}

class ValueFinderTest {
    @Test fun assessGivesExpectedEdgeAndEv() {
        val a = ValueFinder.assess(0.55, 2.0)!!
        assertEquals(0.05, a.edge, 1e-9)
        assertEquals(0.10, a.expectedReturnPerUnit, 1e-9)
        assertEquals(1.0 / 0.55, a.fairOdds, 1e-9)
        assertEquals(0.025, a.kellyFraction, 1e-9) // quarter Kelly of 0.10
        assertEquals("قيمة محتملة", a.label)
    }

    @Test fun invalidInputsAreRejected() {
        assertNull(ValueFinder.assess(1.5, 2.0))
        assertNull(ValueFinder.assess(0.5, 1.0))
    }

    @Test fun noVigSumsToOne() {
        val p = ValueFinder.noVigProbabilities(listOf(2.0, 3.5, 4.0))!!
        assertEquals(1.0, p.sum(), 1e-9)
        assertEquals(0.48276, p[0], 1e-4)
        assertNull(ValueFinder.noVigProbabilities(listOf(0.9, 2.0)))
    }

    @Test fun weakDataIsNotEligible() {
        val a = ValueFinder.assess(0.60, 2.5, dataQualityScore = 40)!!
        assertTrue(a.label.startsWith("غير مؤهل"))
    }
}

class OpenMatchdayParsingTest {
    @Test fun csvLineHandlesQuotesAndCommas() {
        assertEquals(listOf("a", "b,c", "d\"e", ""), OpenMatchdayProvider.parseCsvLine("a,\"b,c\",\"d\"\"e\","))
    }

    @Test fun seasonCodeSplitsEuropeanAndCalendarYearLeagues() {
        assertEquals("2526", OpenMatchdayProvider.seasonCode("2025-09-10", "ENG_premier-league"))
        assertEquals("2526", OpenMatchdayProvider.seasonCode("2026-02-10", "ENG_premier-league"))
        assertEquals("2026", OpenMatchdayProvider.seasonCode("2026-02-10", "BRA_serie-a"))
    }

    @Test fun parsesFixturesForTargetDateOnly() {
        val csv = "date,time,home,away\n2026-11-21,15:00,A,B\n2026-11-22,15:00,C,D\n"
        val rows = OpenMatchdayProvider.parseFixtures(csv, "L", "ENG_x", "2026-11-21")
        assertEquals(1, rows.size)
        assertEquals("A", rows[0].home)
    }

    @Test fun parsesLeagueIndex() {
        val md = "| Folder | League |\n|---|---|\n| `ENG_premier-league` | Premier League |\n"
        val l = OpenMatchdayProvider.parseLeagueIndex(md)
        assertEquals(listOf("ENG_premier-league"), l.map { it.folder })
    }
}

class CalibrationAndCouponTest {
    private fun fx(id: Long = 1) = Fixture(id, "Home", "Away", "L", "2026-11-21 15:00", "1", "2", "eng.1")
    private fun pick(id: Long, p: Double, score: Int = 80) = AutoPrediction(
        fx(id), "1X2", "فوز Home", p, "1 - 0", "q",
        audit = PredictionAudit(score, TeamStrengthProfile(1.0, 1.0, 1.0, 1.0), TeamStrengthProfile(1.0, 1.0, 1.0, 1.0), emptyList(), null)
    )

    @Test fun smallSampleFallsBackToRawProbability() {
        assertEquals(0.7, PredictionIntelligence.calibrateProbability(0.7, "L", "1X2", "2026-11-21 10:00", emptyList()), 1e-9)
    }

    @Test fun calibrationShrinksTowardsObservedHitRate() {
        val hist = (1..40).map { i ->
            PredictionRecord(i.toLong(), fx(i.toLong()), "1X2", "فوز Home", 0.70, "1 - 0", created = "2026-01-%02d 10:00".format(i % 28 + 1),
                actualHome = if (i % 2 == 0) 2 else 0, actualAway = 1, rawProbability = 0.70)
        }
        val c = PredictionIntelligence.calibrateProbability(0.70, "L", "1X2", "2026-11-21 10:00", hist)
        assertTrue("expected shrink below 0.70, got $c", c < 0.70 && c > 0.5)
    }

    @Test fun smartCouponFiltersWeakAndDuplicates() {
        val coupon = PredictionIntelligence.buildSmartCoupon(listOf(pick(1, 0.9), pick(1, 0.8), pick(2, 0.55), pick(3, 0.7, score = 50), pick(4, 0.65)))
        assertEquals(listOf(1L, 4L), coupon.map { it.fixture.id })
    }

    @Test fun jointProbabilityIsProduct() {
        assertEquals(0.42, PredictionIntelligence.approximateCouponProbability(listOf(pick(1, 0.7), pick(2, 0.6))), 1e-9)
    }
}
