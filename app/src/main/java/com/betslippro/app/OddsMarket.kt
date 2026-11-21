package com.betslippro.app

import android.content.SharedPreferences
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

/**
 * Free-tier odds integration for The Odds API.
 * One region + one market is requested per sport key to keep credit use predictable.
 * API key is sent in a header (not in the URL) and is stored locally by the app.
 */
data class OddsOutcome(
    val name: String,
    val bestOdds: Double,
    val averageOdds: Double,
    val fairProbability: Double?,
    val bookmakerCount: Int,
    val latestUpdate: String,
    val previousBestOdds: Double? = null
) {
    val movement: Double? get() = previousBestOdds?.let { bestOdds - it }
}

data class OddsEvent(
    val id: String,
    val sportKey: String,
    val competition: String,
    val home: String,
    val away: String,
    val commenceTime: String,
    val bookmakerCount: Int,
    val outcomes: List<OddsOutcome>
)

data class OddsSport(val key: String, val title: String, val active: Boolean)

data class OddsFetchResult(
    val events: List<OddsEvent>,
    val requestsRemaining: String?,
    val requestsUsed: String?,
    val sportKeysFetched: Int
)

data class OddsSnapshot(
    val eventId: String,
    val outcome: String,
    val bestOdds: Double,
    val capturedAt: Long
)

object OddsMarketRepository {
    private const val PREF_KEY = "odds_snapshots_v19"

    fun loadSnapshots(prefs: SharedPreferences): List<OddsSnapshot> = try {
        val array = JSONArray(prefs.getString(PREF_KEY, "[]"))
        (0 until array.length()).mapNotNull { i ->
            val o = array.optJSONObject(i) ?: return@mapNotNull null
            OddsSnapshot(o.optString("eventId"), o.optString("outcome"), o.optDouble("bestOdds"), o.optLong("capturedAt"))
        }
    } catch (_: Exception) { emptyList() }

    fun saveSnapshots(prefs: SharedPreferences, snapshots: List<OddsSnapshot>) {
        val array = JSONArray()
        snapshots.takeLast(1200).forEach { s ->
            array.put(JSONObject().put("eventId", s.eventId).put("outcome", s.outcome)
                .put("bestOdds", s.bestOdds).put("capturedAt", s.capturedAt))
        }
        prefs.edit().putString(PREF_KEY, array.toString()).apply()
    }

    fun mergeSnapshots(old: List<OddsSnapshot>, events: List<OddsEvent>, now: Long = System.currentTimeMillis()): List<OddsSnapshot> {
        val additions = events.flatMap { event -> event.outcomes.map { OddsSnapshot(event.id, it.name, it.bestOdds, now) } }
        return (old + additions).takeLast(1200)
    }

    fun attachMovement(events: List<OddsEvent>, history: List<OddsSnapshot>): List<OddsEvent> = events.map { event ->
        val outcomes = event.outcomes.map { outcome ->
            val previous = history.asSequence()
                .filter { it.eventId == event.id && it.outcome.equals(outcome.name, ignoreCase = true) }
                .maxByOrNull { it.capturedAt }
            outcome.copy(previousBestOdds = previous?.bestOdds)
        }
        event.copy(outcomes = outcomes)
    }
}

object TheOddsApiClient {
    fun fetchSoccerSports(apiKey: String): Result<List<OddsSport>> = runCatching {
        require(apiKey.isNotBlank()) { "أدخل مفتاح The Odds API المجاني" }
        val connection = (URL("https://api.the-odds-api.com/v4/sports/?all=true").openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 12000
            readTimeout = 18000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("x-api-key", apiKey)
        }
        try {
            val status = connection.responseCode
            val body = if (status in 200..299) connection.inputStream.bufferedReader().use { it.readText() }
                else (connection.errorStream?.bufferedReader()?.use { it.readText() } ?: "")
            if (status !in 200..299) throw IllegalStateException("HTTP $status ${body.take(180)}")
            val array = JSONArray(body)
            (0 until array.length()).mapNotNull { i ->
                val o = array.optJSONObject(i) ?: return@mapNotNull null
                val key = o.optString("key")
                if (!key.startsWith("soccer_")) return@mapNotNull null
                OddsSport(key, o.optString("title", key), o.optBoolean("active", false))
            }.sortedBy { it.title.lowercase(Locale.ROOT) }
        } finally { connection.disconnect() }
    }

    fun fetchSoccerOdds(apiKey: String, sportKeysInput: String, region: String = "eu"): Result<OddsFetchResult> = runCatching {
        require(apiKey.isNotBlank()) { "أدخل مفتاح The Odds API المجاني" }
        val sportKeys = sportKeysInput.split(',', '\n', ';').map { it.trim() }
            .filter { it.matches(Regex("[A-Za-z0-9_]+")) }.distinct().take(20)
        require(sportKeys.isNotEmpty()) { "أدخل مفاتيح بطولات صحيحة مفصولة بفواصل" }
        require(region in setOf("eu", "uk", "us", "us2", "au")) { "منطقة الأسعار غير مدعومة" }

        val allEvents = linkedMapOf<String, OddsEvent>()
        var remaining: String? = null
        var used: String? = null
        var fetched = 0
        val errors = mutableListOf<String>()

        sportKeys.forEach { sportKey ->
            var connection: HttpURLConnection? = null
            try {
                val url = URL("https://api.the-odds-api.com/v4/sports/$sportKey/odds/?regions=$region&markets=h2h&oddsFormat=decimal&dateFormat=iso")
                connection = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 12000
                    readTimeout = 18000
                    setRequestProperty("Accept", "application/json")
                    setRequestProperty("x-api-key", apiKey)
                }
                val status = connection.responseCode
                remaining = connection.getHeaderField("x-requests-remaining") ?: remaining
                used = connection.getHeaderField("x-requests-used") ?: used
                if (status !in 200..299) {
                    val body = connection.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                    val detail = try { JSONObject(body).optString("message", body.take(180)) } catch (_: Exception) { body.take(180) }
                    errors += "$sportKey: HTTP $status $detail"
                } else {
                    val body = connection.inputStream.bufferedReader().use { it.readText() }
                    val array = JSONArray(body)
                    for (i in 0 until array.length()) {
                        val event = parseEvent(array.getJSONObject(i), sportKey) ?: continue
                        allEvents[event.id] = event
                    }
                    fetched++
                }
            } finally { connection?.disconnect() }
        }
        if (fetched == 0 && errors.isNotEmpty()) throw IllegalStateException(errors.take(3).joinToString("\n"))
        OddsFetchResult(allEvents.values.sortedBy { it.commenceTime }, remaining, used, fetched)
    }

    private fun parseEvent(o: JSONObject, sportKey: String): OddsEvent? {
        val id = o.optString("id")
        val home = o.optString("home_team")
        val away = o.optString("away_team")
        if (id.isBlank() || home.isBlank() || away.isBlank()) return null
        val bookmakers = o.optJSONArray("bookmakers") ?: JSONArray()
        val outcomePrices = linkedMapOf<String, MutableList<Double>>()
        val fairProbabilities = linkedMapOf<String, MutableList<Double>>()
        val updates = mutableListOf<String>()
        var validBookmakers = 0
        for (b in 0 until bookmakers.length()) {
            val bookmaker = bookmakers.optJSONObject(b) ?: continue
            val markets = bookmaker.optJSONArray("markets") ?: continue
            val h2h = (0 until markets.length()).mapNotNull { markets.optJSONObject(it) }
                .firstOrNull { it.optString("key") == "h2h" } ?: continue
            val outcomes = h2h.optJSONArray("outcomes") ?: continue
            val local = mutableListOf<Pair<String, Double>>()
            for (j in 0 until outcomes.length()) {
                val outcome = outcomes.optJSONObject(j) ?: continue
                val name = outcome.optString("name")
                val price = outcome.optDouble("price", Double.NaN)
                if (name.isNotBlank() && price.isFinite() && price > 1.0 && price <= 1000.0) local += name to price
            }
            if (local.size < 2) continue
            validBookmakers++
            val inverse = local.associate { it.first to (1.0 / it.second) }
            val total = inverse.values.sum()
            local.forEach { (name, price) ->
                outcomePrices.getOrPut(name) { mutableListOf() }.add(price)
                fairProbabilities.getOrPut(name) { mutableListOf() }.add((inverse[name] ?: 0.0) / total)
            }
            val update = h2h.optString("last_update")
            if (update.isNotBlank()) updates += update
        }
        if (validBookmakers == 0) return null
        val outcomes = outcomePrices.map { (name, prices) ->
            val sorted = prices.sorted()
            val avg = sorted.average()
            val best = sorted.maxOrNull() ?: return@map null
            val probs = fairProbabilities[name].orEmpty()
            OddsOutcome(
                name = name,
                bestOdds = best,
                averageOdds = avg,
                fairProbability = probs.takeIf { it.isNotEmpty() }?.average(),
                bookmakerCount = prices.size,
                latestUpdate = updates.maxOrNull().orEmpty()
            )
        }.filterNotNull()
        if (outcomes.isEmpty()) return null
        return OddsEvent(
            id = id,
            sportKey = sportKey,
            competition = o.optString("sport_title", sportKey),
            home = home,
            away = away,
            commenceTime = o.optString("commence_time"),
            bookmakerCount = validBookmakers,
            outcomes = outcomes
        )
    }
}

fun formatOddsMovement(value: Double?): String = when {
    value == null -> "لا توجد مقارنة سابقة"
    abs(value) < 0.005 -> "السعر مستقر"
    value > 0 -> "ارتفع السعر +${String.format(Locale.US, "%.2f", value)}"
    else -> "انخفض السعر ${String.format(Locale.US, "%.2f", value)}"
}

fun formatOddsTime(iso: String): String {
    return try {
        val parser = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        parser.timeZone = java.util.TimeZone.getTimeZone("UTC")
        val date = parser.parse(iso) ?: return iso
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(date) + " UTC"
    } catch (_: Exception) { iso.ifBlank { "غير متاح" } }
}

@androidx.compose.runtime.Composable
fun OddsMarketScreen(
    apiKey: String,
    onApiKeyChange: (String) -> Unit,
    sportKeys: String,
    onSportKeysChange: (String) -> Unit,
    region: String,
    onRegionChange: (String) -> Unit,
    events: List<OddsEvent>,
    modelPredictions: List<AutoPrediction>,
    loading: Boolean,
    message: String,
    requestsRemaining: String?,
    onFetch: () -> Unit,
    onLoadLeagues: () -> Unit,
    catalogLoading: Boolean,
    onAddToCoupon: (OddsEvent, OddsOutcome) -> Unit,
    onBackToSettings: () -> Unit
) {
    val bg = androidx.compose.ui.graphics.Color(0xFF0B1118)
    val panel = androidx.compose.ui.graphics.Color(0xFF141F2B)
    val green = androidx.compose.ui.graphics.Color(0xFF20C997)
    val white = androidx.compose.ui.graphics.Color(0xFFF4F7FA)
    val muted = androidx.compose.ui.graphics.Color(0xFF9AAAB9)

    androidx.compose.foundation.lazy.LazyColumn(
        modifier = androidx.compose.ui.Modifier.fillMaxSize().background(bg),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(14.dp),
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp)
    ) {
        item {
            Column(verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onBackToSettings) { Text("← الإعدادات", color = green) }
                Text("أسعار السوق المباشرة", color = white, fontSize = 21.sp, fontWeight = FontWeight.Bold)
                Text("تجميع أسعار عدة شركات ومتابعة تغير السعر قبل المباراة", color = muted, fontSize = 12.sp)
            }
        }
        item {
            Surface(color = panel, shape = RoundedCornerShape(16.dp)) {
                Column(Modifier.padding(14.dp), verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(9.dp)) {
                    Text("مصدر الأسعار المجاني", color = green, fontWeight = FontWeight.Bold)
                    Text("The Odds API — يتطلب مفتاحاً مجانياً. الخطة المجانية المنشورة توفر 500 رصيد شهرياً، والأسعار الكروية متاحة ضمن البطولات التي يغطيها المصدر. كل دوري/منطقة/سوق يستهلك طلباً وفق التسعير المعلن.", color = muted, fontSize = 11.sp)
                    androidx.compose.material3.OutlinedTextField(
                        value = apiKey, onValueChange = onApiKeyChange,
                        label = { Text("مفتاح The Odds API") }, singleLine = true,
                        modifier = androidx.compose.ui.Modifier.fillMaxWidth(),
                        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation()
                    )
                    androidx.compose.material3.OutlinedTextField(
                        value = sportKeys, onValueChange = onSportKeysChange,
                        label = { Text("مفاتيح الدوريات مفصولة بفواصل") },
                        modifier = androidx.compose.ui.Modifier.fillMaxWidth(), minLines = 2
                    )
                    TextButton(onClick = onLoadLeagues, enabled = !catalogLoading && apiKey.isNotBlank()) {
                        Text(if (catalogLoading) "جارٍ تحميل البطولات..." else "تحميل قائمة دوريات كرة القدم المتاحة", color = green)
                    }
                    androidx.compose.material3.OutlinedTextField(
                        value = region, onValueChange = { onRegionChange(it.trim().lowercase()) },
                        label = { Text("منطقة الشركات: eu / uk / us / au") }, singleLine = true,
                        modifier = androidx.compose.ui.Modifier.fillMaxWidth()
                    )
                    Button(onClick = onFetch, enabled = !loading && apiKey.isNotBlank(),
                        modifier = androidx.compose.ui.Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = green)) {
                        Text(if (loading) "جارٍ تحديث الأسعار..." else "جلب / تحديث الأسعار", color = bg)
                    }
                    if (requestsRemaining != null) Text("الرصيد المتبقي حسب آخر استجابة: $requestsRemaining", color = green, fontSize = 11.sp)
                    if (message.isNotBlank()) Text(message, color = if (message.contains("تعذر") || message.contains("خطأ")) androidx.compose.ui.graphics.Color(0xFFFF8A80) else muted, fontSize = 11.sp)
                    Text("يمكن تحميل مفاتيح جميع مسابقات كرة القدم التي يعرضها المزود؛ توفر الأسعار يتغير حسب الموسم والبطولة.", color = muted, fontSize = 10.sp)
                    Text("نصيحة لتوفير الرصيد: ابدأ بدوري أو دوريَين، ولا تحدّث الأسعار إلا عند الحاجة. الأسعار التاريخية الكاملة غير مشمولة بالخطة المجانية.", color = muted, fontSize = 10.sp)
                }
            }
        }
        if (events.isEmpty()) {
            item {
                Surface(color = panel, shape = RoundedCornerShape(14.dp)) {
                    Text("لا توجد أسعار معروضة حالياً. أدخل المفتاح ومفاتيح البطولات ثم حدّث الأسعار.", color = muted, modifier = androidx.compose.ui.Modifier.padding(16.dp), fontSize = 12.sp)
                }
            }
        }
        items(events, key = { it.id }) { event ->
            Surface(color = panel, shape = RoundedCornerShape(16.dp)) {
                Column(Modifier.padding(13.dp), verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
                    Text(event.competition, color = green, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    Text("${event.home}  -  ${event.away}", color = white, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Text("${formatOddsTime(event.commenceTime)}  •  ${event.bookmakerCount} شركات", color = muted, fontSize = 10.sp)
                    val modelPrediction = modelPredictions.firstOrNull {
                        it.market == "1X2" && it.fixture.home.equals(event.home, ignoreCase = true) && it.fixture.away.equals(event.away, ignoreCase = true)
                    }
                    val modelOutcomeName = when {
                        modelPrediction?.pick == "تعادل" -> "Draw"
                        modelPrediction?.pick == "فوز ${event.home}" -> event.home
                        modelPrediction?.pick == "فوز ${event.away}" -> event.away
                        else -> null
                    }
                    event.outcomes.forEach { outcome ->
                        val modelProbability = if (modelOutcomeName != null && outcome.name.equals(modelOutcomeName, ignoreCase = true)) modelPrediction?.probability else null
                        val valueAssessment = modelProbability?.let { ValueFinder.assess(it, outcome.bestOdds, modelPrediction?.audit?.score ?: 0) }
                        Surface(color = androidx.compose.ui.graphics.Color(0xFF1B2A38), shape = RoundedCornerShape(10.dp)) {
                            Row(
                                modifier = androidx.compose.ui.Modifier.fillMaxWidth().padding(9.dp),
                                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(outcome.name, color = white, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                                    Text("أفضل سعر ${String.format(Locale.US, "%.2f", outcome.bestOdds)}  •  متوسط ${String.format(Locale.US, "%.2f", outcome.averageOdds)}", color = muted, fontSize = 10.sp)
                                    Text("احتمال سوقي منزوع الهامش: ${outcome.fairProbability?.let { String.format(Locale.US, "%.1f%%", it * 100) } ?: "غير متاح"}  •  ${outcome.bookmakerCount} شركات", color = muted, fontSize = 10.sp)
                                    if (modelProbability != null) {
                                        Text("احتمال المحرك: ${String.format(Locale.US, "%.1f%%", modelProbability * 100)}", color = green, fontSize = 10.sp)
                                        Text("${valueAssessment?.label ?: "لا يمكن تقييم القيمة: جودة بيانات النموذج منخفضة"}  •  EV ${valueAssessment?.expectedReturnPerUnit?.let { String.format(Locale.US, "%+.1f%%", it * 100) } ?: "—"}", color = if ((valueAssessment?.expectedReturnPerUnit ?: -1.0) > 0.0) green else muted, fontSize = 10.sp)
                                    }
                                    val move = outcome.movement
                                    Text(formatOddsMovement(move), color = when { move == null -> muted; move > 0 -> androidx.compose.ui.graphics.Color(0xFF80CBC4); else -> androidx.compose.ui.graphics.Color(0xFFFFCC80) }, fontSize = 10.sp)
                                }
                                TextButton(onClick = { onAddToCoupon(event, outcome) }) { Text("إضافة", color = green, fontSize = 11.sp) }
                            }
                        }
                    }
                    Text("آخر تحديث للسوق: ${event.outcomes.firstOrNull()?.latestUpdate?.let { formatOddsTime(it) } ?: "غير متاح"}", color = muted, fontSize = 9.sp)
                }
            }
        }
    }
}
