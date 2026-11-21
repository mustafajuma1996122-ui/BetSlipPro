package com.betslippro.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale

/**
 * Reads the predictions published by the free GitHub Actions engine (docs/predictions.json on GitHub Pages).
 * All modelling (Dixon-Coles + market blend + calibration + value rules) happens server-side in one place;
 * the app only downloads one small file. The last good copy is cached so the app works offline.
 */
data class FeedData(val picks: List<AutoPrediction>, val results: Map<Long, Pair<Int, Int>>, val generatedAt: String)

object RemoteFeed {
    private const val CACHE_NAME = "predictions_feed.json"
    private const val MAX_STALE_MS = 48L * 60 * 60 * 1000
    @Volatile private var appContext: Context? = null

    fun init(context: Context) { appContext = context.applicationContext }

    /** A URL saved in preferences ("feed_url") wins over the build-time default (BuildConfig.FEED_URL). */
    fun url(context: Context): String {
        val custom = context.getSharedPreferences("bet_slip_pro", Context.MODE_PRIVATE).getString("feed_url", "")?.trim().orEmpty()
        return custom.ifBlank { BuildConfig.FEED_URL }
    }

    /** Download (or fall back to a recent cached copy). Fails if no feed URL is configured. */
    fun fetch(): Result<FeedData> = runCatching {
        val ctx = appContext ?: error("RemoteFeed not initialised")
        val url = url(ctx)
        require(url.isNotBlank()) { "لم يُضبط رابط الخلاصة (feed_url). ابنِ التطبيق عبر GitHub Actions أو مرّر -PfeedUrl=..." }
        val cache = File(ctx.cacheDir, CACHE_NAME)
        val body = try {
            readHttp(url).also { parse(it); cache.writeText(it, Charsets.UTF_8) } // parse first: never cache garbage
        } catch (e: Exception) {
            if (cache.exists() && System.currentTimeMillis() - cache.lastModified() < MAX_STALE_MS) cache.readText(Charsets.UTF_8) else throw e
        }
        parse(body)
    }

    /** Final scores the engine has already graded (cached copy, no network). */
    fun cachedResults(): Map<Long, Pair<Int, Int>> {
        val ctx = appContext ?: return emptyMap()
        val cache = File(ctx.cacheDir, CACHE_NAME)
        return runCatching { if (cache.exists()) parse(cache.readText(Charsets.UTF_8)).results else emptyMap() }.getOrDefault(emptyMap())
    }

    internal fun parse(json: String): FeedData {
        val root = JSONObject(json)
        val arr = root.optJSONArray("fixtures") ?: JSONArray()
        val picks = ArrayList<AutoPrediction>()
        for (i in 0 until arr.length()) {
            val f = arr.getJSONObject(i)
            val p = f.getJSONObject("pick")
            val fixture = Fixture(f.getLong("id"), f.getString("home"), f.getString("away"), f.optString("league"), f.optString("date"))
            val prob = p.getDouble("probability")
            val value = p.optBoolean("value", false)
            val q = f.optJSONObject("quality")
            val score = q?.optInt("score", 0) ?: 0
            val warnings = q?.optJSONArray("warnings")?.let { w -> (0 until w.length()).map { w.getString(it) } }.orEmpty()
            val odds = if (p.isNull("odds")) null else p.getDouble("odds")
            val ev = if (p.isNull("ev")) null else p.getDouble("ev")
            val details = if (value && odds != null && ev != null)
                "قيمة محتملة • سعر ${String.format(Locale.US, "%.2f", odds)} • عائد متوقع ${String.format(Locale.US, "%+.1f", ev * 100)}% • حد أقصى مقترح للحصة ${String.format(Locale.US, "%.1f", p.optDouble("kelly", 0.0) * 100)}% (كسر كيلي)"
            else "لا قيمة: لا يوجد سعر يتفوق على السوق بهامش كافٍ؛ هذا التوقع للمعلومة فقط وليس إشارة رهان"
            val quality = "جودة البيانات $score/100" + if (warnings.isNotEmpty()) " • " + warnings.joinToString("؛ ") else ""
            picks += AutoPrediction(
                fixture, p.getString("market"), p.getString("pick"), prob, f.optString("expected_score", "—"), quality,
                rawProbability = prob, modelDetails = details, valuePick = value, qualityScore = score
            )
        }
        val results = HashMap<Long, Pair<Int, Int>>()
        root.optJSONArray("results")?.let { r ->
            for (i in 0 until r.length()) {
                val o = r.getJSONObject(i)
                results[o.getLong("id")] = o.getInt("home_goals") to o.getInt("away_goals")
            }
        }
        val ranked = picks.filter { it.valuePick || it.probability >= 0.52 }
            .sortedWith(compareByDescending<AutoPrediction> { it.valuePick }.thenByDescending { it.probability })
            .take(25)
        return FeedData(ranked, results, root.optString("generated_at"))
    }
}
