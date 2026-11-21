package com.betslippro.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteFeedTest {
    private val json = """
    {"generated_at":"2026-11-21T10:00:00Z","fixtures":[
      {"id":101,"home":"Arsenal","away":"Chelsea","league":"EPL","date":"2026-11-22","expected_score":"2-1",
       "quality":{"score":90,"warnings":[]},
       "pick":{"market":"1X2","pick":"فوز Arsenal","probability":0.55,"odds":2.3,"ev":0.265,"kelly":0.02,"value":true}},
      {"id":102,"home":"A","away":"B","league":"EPL","date":"2026-11-22","expected_score":"1-1",
       "quality":{"score":60,"warnings":["سجل أحد الفريقين أقل من 15 مباراة"]},
       "pick":{"market":"الأهداف","pick":"أكثر من 1.5","probability":0.74,"odds":null,"ev":null,"kelly":0.0,"value":false}},
      {"id":103,"home":"C","away":"D","league":"EPL","date":"2026-11-22","expected_score":"0-0",
       "quality":{"score":80,"warnings":[]},
       "pick":{"market":"1X2","pick":"تعادل","probability":0.30,"odds":null,"ev":null,"kelly":0.0,"value":false}}
    ],
    "results":[{"id":55,"home_goals":2,"away_goals":0}]}
    """.trimIndent()

    @Test fun parsesPicksAndRanksValueFirst() {
        val feed = RemoteFeed.parse(json)
        assertEquals("2026-11-21T10:00:00Z", feed.generatedAt)
        // 103 (prob 0.30, not value) is dropped; value pick is listed before the higher-probability non-value pick
        assertEquals(listOf(101L, 102L), feed.picks.map { it.fixture.id })
        assertTrue(feed.picks[0].valuePick)
        assertFalse(feed.picks[1].valuePick)
        assertEquals(90, feed.picks[0].qualityScore)
        assertEquals("فوز Arsenal", feed.picks[0].pick)
    }

    @Test fun parsesGradedResults() {
        assertEquals(2 to 0, RemoteFeed.parse(json).results[55L])
    }
}
