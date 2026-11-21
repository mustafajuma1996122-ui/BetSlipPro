package com.betslippro.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Assessment
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Ticket
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

private val Bg = Color(0xFF0B1118)
private val Panel = Color(0xFF141F2B)
private val Panel2 = Color(0xFF1B2A38)
private val Green = Color(0xFF20C997)
private val Muted = Color(0xFF9AAAB9)
private val White = Color(0xFFF4F7FA)

 data class Fixture(val id: Long, val home: String, val away: String, val league: String, val date: String, val homeTeamId: String = "", val awayTeamId: String = "", val sourceLeagueCode: String = "")
 data class Selection(val id: Long, val fixture: Fixture, val market: String, val pick: String, val odds: Double)
 data class SavedSlip(val id: Long, val title: String, val stake: Double, val selections: List<Selection>, val created: String)
data class AutoPrediction(
    val fixture: Fixture,
    val market: String,
    val pick: String,
    val probability: Double,
    val expectedScore: String,
    val quality: String,
    val rawProbability: Double = probability,
    val homeForm: TeamForm? = null,
    val awayForm: TeamForm? = null,
    val audit: PredictionAudit? = null,
    val modelDetails: String = "",
    val valuePick: Boolean = false,
    val qualityScore: Int? = null
)
data class PredictionRecord(
    val id: Long,
    val fixture: Fixture,
    val market: String,
    val pick: String,
    val probability: Double,
    val expectedScore: String,
    val modelVersion: String = "Poisson-1.0",
    val created: String = nowText(),
    val actualHome: Int? = null,
    val actualAway: Int? = null,
    val rawProbability: Double? = null
) {
    val evaluated: Boolean get() = actualHome != null && actualAway != null
}

class MainActivity : ComponentActivity() {
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        RemoteFeed.init(this)
        val prefs = getSharedPreferences("bet_slip_pro", MODE_PRIVATE)
        DailySyncScheduler.apply(this, prefs.getBoolean(DailySyncScheduler.PREF_ENABLED, true))
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent { BetSlipApp() }
    }
}

@Composable
fun BetSlipApp() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("bet_slip_pro", 0) }
    val secure = remember { SecureStore.get(context) }
    val store = remember { Store.get(context) }
    val scope = rememberCoroutineScope()
    var tab by remember { mutableIntStateOf(0) }
    var fixtures by remember { mutableStateOf(listOf<Fixture>()) }
    var autoPicks by remember { mutableStateOf(listOf<AutoPrediction>()) }
    var autoLoading by remember { mutableStateOf(false) }
    var autoMessage by remember { mutableStateOf("") }
    var selections by remember { mutableStateOf(listOf<Selection>()) }
    var slips by remember { mutableStateOf(listOf<SavedSlip>()) }
    var predictionRecords by remember { mutableStateOf(listOf<PredictionRecord>()) }
    var stake by remember { mutableStateOf("10000") }
    var showAdd by remember { mutableStateOf(false) }
    var showSlipName by remember { mutableStateOf(false) }
    var apiToken by remember { mutableStateOf("") }
    var apiDate by remember { mutableStateOf(today()) }
    var selectedLeague by remember { mutableStateOf("ALL") }
    var loading by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var manualMode by remember { mutableStateOf(true) }
    var oddsApiKey by remember { mutableStateOf("") }
    var oddsSportKeys by remember { mutableStateOf(prefs.getString("odds_sport_keys", "soccer_epl,soccer_spain_la_liga,soccer_germany_bundesliga,soccer_italy_serie_a,soccer_france_ligue_one,soccer_uefa_champs_league") ?: "soccer_epl,soccer_spain_la_liga,soccer_germany_bundesliga,soccer_italy_serie_a,soccer_france_ligue_one,soccer_uefa_champs_league") }
    var oddsRegion by remember { mutableStateOf(prefs.getString("odds_region", "eu") ?: "eu") }
    var oddsEvents by remember { mutableStateOf(listOf<OddsEvent>()) }
    var oddsSnapshots by remember { mutableStateOf(OddsMarketRepository.loadSnapshots(prefs)) }
    var oddsLoading by remember { mutableStateOf(false) }
    var oddsMessage by remember { mutableStateOf("") }
    var oddsRemaining by remember { mutableStateOf<String?>(null) }
    var oddsCatalogLoading by remember { mutableStateOf(false) }

    // Automatic no-key sync on app open; cached data avoids repeated downloads within 6 hours.
    LaunchedEffect(Unit) {
        // Load persisted data off the main thread (Room), migrate old prefs/keys once, then sync.
        withContext(Dispatchers.IO) { store.migrateFromPrefsIfNeeded(prefs); SecureStore.migrate(prefs, secure) }
        fixtures = store.loadFixtures()
        slips = store.loadSlips()
        predictionRecords = store.loadPredictionRecords()
        apiToken = secure.getString("api_token", "") ?: ""
        oddsApiKey = secure.getString("odds_api_key", "") ?: ""
        val syncKey = "auto_sync_${today()}_ALL"
        val lastSync = prefs.getLong(syncKey, 0L)
        if (System.currentTimeMillis() - lastSync >= 6 * 60 * 60 * 1000L) {
            loading = true
            message = "مزامنة تلقائية للمباريات من المصادر المجانية..."
            val syncResult = fetchCombinedFixtures(apiToken, apiDate, selectedLeague, context.cacheDir)
            loading = false
            syncResult.onSuccess { incoming ->
                val merged = (fixtures.filter { it.date.take(10) >= today() } + incoming).groupBy {
                    it.home.trim().lowercase(Locale.ROOT) + "|" + it.away.trim().lowercase(Locale.ROOT) + "|" + it.date.take(10)
                }.values.map { rows -> rows.firstOrNull { it.homeTeamId.isNotBlank() && it.awayTeamId.isNotBlank() } ?: rows.first() }
                fixtures = merged
                store.saveFixtures(fixtures)
                prefs.edit().putLong(syncKey, System.currentTimeMillis()).apply()
                message = "اكتملت المزامنة التلقائية: ${incoming.size} مباراة. جارٍ تحليل المباريات المؤهلة..."
                autoLoading = true
                val analyzed = analyzeTopPredictions(merged, predictionRecords, oddsEvents)
                autoLoading = false
                analyzed.onSuccess { picks ->
                    autoPicks = picks
                    val newRecords = AutoPipeline.recordsFor(picks, predictionRecords.map { it.id }.toSet())
                    predictionRecords = (predictionRecords + newRecords).takeLast(1500)
                    store.savePredictionRecords(predictionRecords)
                    autoMessage = "اكتمل التحليل التلقائي: ${picks.size} توقع مؤهل."
                }.onFailure { autoMessage = "تم جلب المباريات، لكن تعذر التحليل: ${it.message ?: "سجلات غير كافية"}" }
            }.onFailure { message = "تعذرت المزامنة التلقائية حالياً؛ يمكنك إعادة المحاولة من شاشة المباريات. ${it.message ?: ""}" }
        }
    }

    MaterialTheme(colorScheme = darkColorScheme(primary = Green, background = Bg, surface = Panel, onSurface = White)) {
        Scaffold(containerColor = Bg, bottomBar = {
            NavigationBar(containerColor = Panel, contentColor = White) {
                NavigationBarItem(selected = tab == 0, onClick = { tab = 0 }, icon = { Icon(Icons.Default.Home, null) }, label = { Text("الرئيسية") })
                NavigationBarItem(selected = tab == 1, onClick = { tab = 1 }, icon = { Icon(Icons.Default.CalendarMonth, null) }, label = { Text("المباريات") })
                NavigationBarItem(selected = tab == 2, onClick = { tab = 2 }, icon = { Icon(Icons.Default.Ticket, null) }, label = { Text("القسيمة") })
                NavigationBarItem(selected = tab == 3, onClick = { tab = 3 }, icon = { Icon(Icons.Default.Archive, null) }, label = { Text("الأرشيف") })
                NavigationBarItem(selected = tab == 4, onClick = { tab = 4 }, icon = { Icon(Icons.Default.Calculate, null) }, label = { Text("التحليل") })
                NavigationBarItem(selected = tab == 5, onClick = { tab = 5 }, icon = { Icon(Icons.Default.Assessment, null) }, label = { Text("الدقة") })
                NavigationBarItem(selected = tab == 6 || tab == 7, onClick = { tab = 7 }, icon = { Icon(Icons.Default.Settings, null) }, label = { Text("المزيد") })
            }
        }) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).background(Bg)) {
                when (tab) {
                    0 -> Dashboard(fixtures.size, selections.size, slips.size, onMatches = { tab = 1 }, onSlip = { tab = 2 })
                    1 -> MatchesScreen(fixtures, selections, onAdd = { showAdd = true }, onSelect = { f ->
                        val default = Selection(System.nanoTime(), f, "1X2", "فوز صاحب الأرض", 1.0)
                        selections = selections + default
                        tab = 2
                    }, onRemove = { f -> fixtures = fixtures.filterNot { it.id == f.id }; store.saveFixtures(fixtures) }, onFetch = {
                        loading = true; message = "جارٍ جلب المباريات من المصادر المجانية..."
                        scope.launch {
                            val result = fetchCombinedFixtures(apiToken, apiDate, selectedLeague, context.cacheDir)
                            loading = false
                            result.onSuccess { incoming ->
                                val merged = (fixtures.filter { it.date.take(10) >= today() } + incoming).groupBy { it.home.trim().lowercase(Locale.ROOT) + "|" + it.away.trim().lowercase(Locale.ROOT) + "|" + it.date.take(10) }
                                    .values.map { rows -> rows.firstOrNull { it.homeTeamId.isNotBlank() && it.awayTeamId.isNotBlank() } ?: rows.first() }
                                fixtures = merged
                                store.saveFixtures(fixtures)
                                message = if (incoming.isEmpty()) "لم يعثر المصدر المجاني على مباريات لهذا التاريخ؛ جرّب تاريخاً آخر أو أضف مباريات يدوياً." else "تم جلب ${incoming.size} مباراة؛ بدأ التحليل التلقائي للمباريات المؤهلة."
                                autoLoading = true
                                scope.launch {
                                    val analyzed = analyzeTopPredictions(merged, predictionRecords, oddsEvents)
                                    autoLoading = false
                                    analyzed.onSuccess { picks ->
                                        autoPicks = picks
                                        val additions = AutoPipeline.recordsFor(picks, predictionRecords.map { it.id }.toSet())
                                        predictionRecords = (predictionRecords + additions).takeLast(1500)
                                        store.savePredictionRecords(predictionRecords)
                                        autoMessage = "اكتمل التحليل: ${picks.size} توقع مؤهل."
                                    }.onFailure { autoMessage = "لم يكتمل التحليل: ${it.message ?: "بيانات تاريخية غير كافية"}" }
                                }
                            }.onFailure { message = "تعذر الجلب: ${it.message ?: "تحقق من الاتصال أو جرّب لاحقاً"}" }
                        }
                    }, loading, message, apiDate, { apiDate = it }, selectedLeague, { selectedLeague = it }, autoPicks, autoLoading, autoMessage, onAnalyze = {
                        if (fixtures.isEmpty()) autoMessage = "اجلب المباريات أولاً"
                        else {
                            autoLoading = true; autoMessage = "جارٍ تحليل سجلات الفرق من البيانات المجانية..."
                            scope.launch {
                                val analysis = analyzeTopPredictions(fixtures, predictionRecords, oddsEvents)
                                autoLoading = false
                                analysis.onSuccess { picks ->
                                    autoPicks = picks
                                    val additions = AutoPipeline.recordsFor(picks, predictionRecords.map { it.id }.toSet())
                                    predictionRecords = (predictionRecords + additions).takeLast(1000)
                                    store.savePredictionRecords(predictionRecords)
                                    autoMessage = if (picks.isEmpty()) "لم تتوفر سجلات تاريخية كافية لهذه المباريات. جرّب الدوريات الكبرى أو تاريخاً آخر." else "اكتمل تحليل ${picks.size} توقعات، وأضيف ${additions.size} إلى سجل قياس الدقة."
                                }
                                    .onFailure { autoMessage = "تعذر التحليل: ${it.message ?: "بيانات الفرق غير متاحة"}" }
                            }
                        }
                    }, onSelectPrediction = { prediction ->
                        selections = selections + Selection(System.nanoTime(), prediction.fixture, prediction.market, prediction.pick, 1.0)
                        tab = 2
                    }, onBuildSmartCoupon = { picks ->
                        selections = selections + picks.map { prediction -> Selection(System.nanoTime() + prediction.fixture.id, prediction.fixture, prediction.market, prediction.pick, 1.0) }
                        tab = 2
                    })
                    2 -> SlipScreen(selections, stake, { stake = it }, onRemove = { s -> selections = selections.filterNot { it.id == s.id } }, onMarket = { s, market, pick, odds -> selections = selections.map { if (it.id == s.id) it.copy(market = market, pick = pick, odds = odds) else it } }, onSave = { showSlipName = true }, onShare = { shareText(context, slipText("BET SLIP PRO", stake.toDoubleOrNull() ?: 0.0, selections)) }, onPdf = { exportPdf(context, "BET SLIP PRO", stake.toDoubleOrNull() ?: 0.0, selections) }, onPng = { exportPng(context, "BET SLIP PRO", stake.toDoubleOrNull() ?: 0.0, selections) })
                    3 -> ArchiveScreen(slips, onShare = { s -> shareText(context, slipText(s.title, s.stake, s.selections)) }, onDelete = { s -> slips = slips.filterNot { it.id == s.id }; store.saveSlips(slips) })
                    4 -> AutomaticAnalysisScreen(autoPicks, autoLoading, autoMessage, onAnalyze = {
                        if (fixtures.isEmpty()) autoMessage = "لا توجد مباريات للتحليل حالياً. ستتم المزامنة تلقائياً عند توفر البيانات."
                        else {
                            autoLoading = true
                            scope.launch {
                                val analyzed = analyzeTopPredictions(fixtures, predictionRecords, oddsEvents)
                                autoLoading = false
                                analyzed.onSuccess { picks ->
                                    autoPicks = picks
                                    autoMessage = "اكتمل التحليل: ${picks.size} توقع مؤهل."
                                }.onFailure { autoMessage = "تعذر التحليل: ${it.message ?: "بيانات غير كافية"}" }
                            }
                        }
                    })
                    5 -> EvaluationScreen(
                        records = predictionRecords,
                        onSaveResult = { recordId, homeScore, awayScore ->
                            predictionRecords = predictionRecords.map { r -> if (r.id == recordId && !r.evaluated) r.copy(actualHome = homeScore, actualAway = awayScore) else r }
                            store.savePredictionRecords(predictionRecords)
                        },
                        onDelete = { recordId -> predictionRecords = predictionRecords.filterNot { it.id == recordId }; store.savePredictionRecords(predictionRecords) },
                        onClear = { predictionRecords = emptyList(); store.savePredictionRecords(predictionRecords) },
                        onSync = { onComplete ->
                            scope.launch {
                                val synced = syncCompletedPredictionResults(predictionRecords.filterNot { it.evaluated })
                                if (synced.isNotEmpty()) {
                                    predictionRecords = predictionRecords.map { r ->
                                        val score = synced[r.id]
                                        if (!r.evaluated && score != null) r.copy(actualHome = score.first, actualAway = score.second) else r
                                    }
                                    store.savePredictionRecords(predictionRecords)
                                }
                                onComplete(synced.size)
                            }
                        }
                    )
                    6 -> OddsMarketScreen(
                        apiKey = oddsApiKey,
                        onApiKeyChange = { oddsApiKey = it; secure.edit().putString("odds_api_key", it).apply() },
                        sportKeys = oddsSportKeys,
                        onSportKeysChange = { oddsSportKeys = it; prefs.edit().putString("odds_sport_keys", it).apply() },
                        region = oddsRegion,
                        onRegionChange = { oddsRegion = it; prefs.edit().putString("odds_region", it).apply() },
                        events = oddsEvents,
                        modelPredictions = autoPicks,
                        loading = oddsLoading,
                        message = oddsMessage,
                        requestsRemaining = oddsRemaining,
                        onLoadLeagues = {
                            oddsCatalogLoading = true
                            scope.launch {
                                val catalog = withContext(Dispatchers.IO) { TheOddsApiClient.fetchSoccerSports(oddsApiKey.trim()) }
                                oddsCatalogLoading = false
                                catalog.onSuccess { sports ->
                                    oddsSportKeys = sports.joinToString(",") { it.key }
                                    prefs.edit().putString("odds_sport_keys", oddsSportKeys).apply()
                                    oddsMessage = "تم تحميل ${sports.size} مسابقة كرة قدم من قائمة المزود. بعض البطولات قد لا تتوفر لها أسعار حالياً."
                                }.onFailure { oddsMessage = "تعذر تحميل قائمة البطولات: ${it.message ?: "تحقق من المفتاح"}" }
                            }
                        },
                        catalogLoading = oddsCatalogLoading,
                        onFetch = {
                            oddsLoading = true
                            oddsMessage = "جارٍ جلب الأسعار من شركات متعددة..."
                            scope.launch {
                                val result = withContext(Dispatchers.IO) { TheOddsApiClient.fetchSoccerOdds(oddsApiKey.trim(), oddsSportKeys, oddsRegion) }
                                oddsLoading = false
                                result.onSuccess { fetched ->
                                    oddsEvents = OddsMarketRepository.attachMovement(fetched.events, oddsSnapshots)
                                    oddsSnapshots = OddsMarketRepository.mergeSnapshots(oddsSnapshots, fetched.events)
                                    OddsMarketRepository.saveSnapshots(prefs, oddsSnapshots)
                                    oddsRemaining = fetched.requestsRemaining
                                    oddsMessage = "تم تحديث ${fetched.events.size} مباراة من ${fetched.sportKeysFetched} بطولات. حركة السعر تقارن بآخر تحديث محفوظ على هذا الجهاز."
                                }.onFailure { oddsMessage = "تعذر جلب الأسعار: ${it.message ?: "تحقق من المفتاح أو حدود الاستخدام"}" }
                            }
                        },
                        onAddToCoupon = { event, outcome ->
                            val fixture = Fixture(Ids.stable(event.id), event.home, event.away, event.competition, event.commenceTime)
                            selections = selections + Selection(System.nanoTime(), fixture, "1X2", outcome.name, outcome.bestOdds)
                            tab = 2
                        },
                        onBackToSettings = { tab = 7 }
                    )
                    else -> SettingsScreen(apiToken, { apiToken = it; secure.edit().putString("api_token", it).apply() }, apiDate, { apiDate = it }, manualMode, { manualMode = it }, onOdds = { tab = 6 })
                }
            }
        }
        if (showAdd) AddFixtureDialog(onDismiss = { showAdd = false }, onAdd = { home, away, league, date ->
            val f = Fixture(System.nanoTime(), home, away, league, date)
            fixtures = fixtures + f; store.saveFixtures(fixtures); showAdd = false
        })
        if (showSlipName) NameDialog(onDismiss = { showSlipName = false }, onSave = { name ->
            val s = SavedSlip(System.nanoTime(), name, stake.toDoubleOrNull() ?: 0.0, selections, nowText())
            slips = listOf(s) + slips; store.saveSlips(slips); showSlipName = false; message = "تم حفظ القسيمة"
        })
    }
}


@Composable
fun PredictionScreen(savedApiToken: String, onTokenChange: (String) -> Unit) {
    var homeName by remember { mutableStateOf("الفريق المضيف") }
    var awayName by remember { mutableStateOf("الفريق الضيف") }
    var homeScores by remember { mutableStateOf("") }
    var awayScores by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<MatchProbabilities?>(null) }
    var error by remember { mutableStateOf("") }
    var competitionCode by remember { mutableStateOf("PL") }
    var season by remember { mutableStateOf("2026") }
    var apiToken by remember { mutableStateOf(savedApiToken) }
    var fetching by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LazyColumn(contentPadding = PaddingValues(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Header("محرك التحليل", "تحليل إحصائي لآخر 10 مباريات مكتملة لكل فريق") }
        item {
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Surface(color = Panel, shape = RoundedCornerShape(16.dp)) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("بيانات المباراة", color = Green, fontWeight = FontWeight.Bold)
                        OutlinedTextField(value = homeName, onValueChange = { homeName = it }, label = { Text("اسم الفريق المضيف") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                        OutlinedTextField(value = awayName, onValueChange = { awayName = it }, label = { Text("اسم الفريق الضيف") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    }
                }
                Surface(color = Panel, shape = RoundedCornerShape(16.dp)) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("جلب السجل تلقائياً (مجاني)", color = Green, fontWeight = FontWeight.Bold)
                        Text("يتطلب رمز Football-data.org المجاني. أدخل رمزك من الإعدادات أو هنا، ورمز البطولة مثل PL أو PD أو BL1 أو SA أو FL1، ثم اضغط جلب آخر 10 مباريات.", color = Muted, fontSize = 12.sp)
                        OutlinedTextField(value = apiToken, onValueChange = { apiToken = it; onTokenChange(it) }, label = { Text("API Token") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(value = competitionCode, onValueChange = { competitionCode = it.uppercase().take(8) }, label = { Text("رمز البطولة") }, modifier = Modifier.weight(1f), singleLine = true)
                            OutlinedTextField(value = season, onValueChange = { season = it.filter(Char::isDigit).take(4) }, label = { Text("الموسم") }, modifier = Modifier.weight(1f), singleLine = true)
                        }
                        Button(onClick = {
                            if (apiToken.isBlank() || competitionCode.isBlank() || season.length != 4) {
                                error = "أدخل رمز API ورمز البطولة والموسم بشكل صحيح"
                            } else {
                                fetching = true; error = "جارٍ جلب سجل الفريقين..."
                                scope.launch {
                                    try {
                                        val home = fetchFootballDataRecentScores(apiToken.trim(), competitionCode.trim(), homeName.trim(), season)
                                        val away = fetchFootballDataRecentScores(apiToken.trim(), competitionCode.trim(), awayName.trim(), season)
                                        homeScores = home.joinToString(", ") { "${it.first}-${it.second}" }
                                        awayScores = away.joinToString(", ") { "${it.first}-${it.second}" }
                                        error = if (home.isEmpty() || away.isEmpty()) "لم يتم العثور على سجل كامل للفريقين في هذه البطولة. تحقق من أسماء الفرق ورمز البطولة والموسم أو أدخل النتائج يدوياً." else "تم جلب ${home.size} مباراة لـ $homeName و${away.size} مباراة لـ $awayName. راجع النتائج قبل التحليل."
                                    } catch (e: Exception) {
                                        error = "فشل جلب السجل: ${e.message ?: "تحقق من الرمز والإنترنت وحدود API"}"
                                    } finally { fetching = false }
                                }
                            }
                        }, enabled = !fetching, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Green)) {
                            Text(if (fetching) "جارٍ جلب البيانات..." else "جلب آخر 10 مباريات للفريقين", color = Bg, fontWeight = FontWeight.Bold)
                        }
                    }
                }
                Surface(color = Panel, shape = RoundedCornerShape(16.dp)) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("آخر مباريات $homeName", color = Green, fontWeight = FontWeight.Bold)
                        Text("اكتب النتائج من منظور الفريق: أهدافه - أهداف الخصم، مفصولة بفاصلة، والأحدث أولاً. مثال: 2-1, 0-0, 1-2", color = Muted, fontSize = 12.sp)
                        OutlinedTextField(value = homeScores, onValueChange = { homeScores = it }, label = { Text("نتائج الفريق المضيف") }, modifier = Modifier.fillMaxWidth(), minLines = 3)
                    }
                }
                Surface(color = Panel, shape = RoundedCornerShape(16.dp)) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("آخر مباريات $awayName", color = Green, fontWeight = FontWeight.Bold)
                        Text("أدخل النتائج من منظور الفريق الضيف وبنفس الصيغة", color = Muted, fontSize = 12.sp)
                        OutlinedTextField(value = awayScores, onValueChange = { awayScores = it }, label = { Text("نتائج الفريق الضيف") }, modifier = Modifier.fillMaxWidth(), minLines = 3)
                    }
                }
                Button(onClick = {
                    val h = parseRecentScores(homeScores)
                    val a = parseRecentScores(awayScores)
                    if (homeName.isBlank() || awayName.isBlank()) {
                        error = "أدخل اسم الفريقين"
                        result = null
                    } else if (h.isEmpty() || a.isEmpty()) {
                        error = "تعذر قراءة النتائج. استخدم الصيغة 2-1, 1-0 وتأكد من إدخال نتيجة واحدة على الأقل لكل فريق."
                        result = null
                    } else {
                        val hf = TeamForm(h.size, weightedRecentAverage(h.map { it.first }), weightedRecentAverage(h.map { it.second }))
                        val af = TeamForm(a.size, weightedRecentAverage(a.map { it.first }), weightedRecentAverage(a.map { it.second }))
                        result = PredictionEngine.predict(hf, af)
                        error = ""
                    }
                }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Green)) {
                    Icon(Icons.Default.Calculate, null, tint = Bg); Spacer(Modifier.width(8.dp)); Text("تحليل المباراة", color = Bg, fontWeight = FontWeight.Bold)
                }
                if (error.isNotBlank()) Text(error, color = Color(0xFFFF8A80), fontSize = 12.sp)
                result?.let { r ->
                    Surface(color = Panel2, shape = RoundedCornerShape(18.dp)) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("نتائج التحليل الإحصائي", color = Green, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                            Text("${homeName} ضد ${awayName}", color = White, fontWeight = FontWeight.SemiBold)
                            Text("احتمالات 1X2", color = Muted, fontSize = 12.sp)
                            ProbabilityRow("فوز $homeName", r.homeWin)
                            ProbabilityRow("التعادل", r.draw)
                            ProbabilityRow("فوز $awayName", r.awayWin)
                            Divider(color = Muted.copy(alpha = 0.25f))
                            ProbabilityRow("أكثر من 1.5 هدف", r.over15)
                            ProbabilityRow("أكثر من 2.5 هدف", r.over25)
                            ProbabilityRow("أكثر من 3.5 هدف", r.over35)
                            ProbabilityRow("يسجل الفريقان", r.bttsYes)
                            Divider(color = Muted.copy(alpha = 0.25f))
                            Text("الأهداف المتوقعة: ${String.format(Locale.US, "%.2f", r.expectedHomeGoals)} - ${String.format(Locale.US, "%.2f", r.expectedAwayGoals)}", color = White)
                            Text("النتيجة المفردة الأكثر احتمالاً: ${r.mostLikelyScore}", color = White)
                            Text("جودة البيانات: ${r.dataQuality}", color = Muted, fontSize = 12.sp)
                            Text("مستوى الثقة: ${r.confidence}", color = Muted, fontSize = 12.sp)
                            Text("هذه تقديرات نموذجية وليست ضماناً للنتيجة. أدخل نتائج مكتملة وحديثة، ويفضل أن تكون من نفس البطولة والمستوى.", color = Color(0xFFFFD180), fontSize = 11.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProbabilityRow(label: String, probability: Double) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = White, modifier = Modifier.weight(1f), fontSize = 13.sp)
        Text(PredictionEngine.percent(probability), color = Green, fontWeight = FontWeight.Bold)
    }
    LinearProgressIndicator(progress = { probability.toFloat().coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().height(5.dp), color = Green, trackColor = Bg)
}

private suspend fun fetchFootballDataRecentScores(
    token: String,
    competitionCode: String,
    teamName: String,
    season: String
): List<Pair<Int, Int>> = withContext(Dispatchers.IO) {
    require(token.isNotBlank()) { "رمز API مطلوب" }
    require(competitionCode.matches(Regex("[A-Z0-9]{2,8}"))) { "رمز البطولة غير صالح" }
    val teamsBody = readHttp(
        "https://api.football-data.org/v4/competitions/$competitionCode/teams?season=$season",
        mapOf("X-Auth-Token" to token)
    )
    val teams = JSONObject(teamsBody).optJSONArray("teams") ?: JSONArray()
    val target = teamName.trim().lowercase(Locale.ROOT)
    var teamId: Long? = null
    var canonicalName = teamName
    for (i in 0 until teams.length()) {
        val team = teams.optJSONObject(i) ?: continue
        val candidate = team.optString("name", "").trim()
        val shortName = team.optString("shortName", "").trim()
        if (candidate.lowercase(Locale.ROOT) == target || shortName.lowercase(Locale.ROOT) == target ||
            candidate.lowercase(Locale.ROOT).contains(target) || target.contains(candidate.lowercase(Locale.ROOT))) {
            teamId = team.optLong("id").takeIf { it > 0 }
            canonicalName = candidate.ifBlank { teamName }
            break
        }
    }
    val id = teamId ?: throw IllegalStateException("لم يتم العثور على الفريق '$teamName' ضمن $competitionCode")
    val matchesBody = readHttp(
        "https://api.football-data.org/v4/teams/$id/matches?status=FINISHED&limit=100",
        mapOf("X-Auth-Token" to token)
    )
    val matches = JSONObject(matchesBody).optJSONArray("matches") ?: JSONArray()
    val completed = mutableListOf<Pair<String, Pair<Int, Int>>>()
    for (i in 0 until matches.length()) {
        val match = matches.optJSONObject(i) ?: continue
        if (match.optString("status") != "FINISHED") continue
        val homeTeam = match.optJSONObject("homeTeam")
        val awayTeam = match.optJSONObject("awayTeam")
        val homeId = homeTeam?.optLong("id", -1L) ?: -1L
        val awayId = awayTeam?.optLong("id", -1L) ?: -1L
        val score = match.optJSONObject("score")?.optJSONObject("fullTime") ?: continue
        val hg = score.optInt("home", -1)
        val ag = score.optInt("away", -1)
        if (hg < 0 || ag < 0) continue
        val pair = when (id) {
            homeId -> hg to ag
            awayId -> ag to hg
            else -> continue
        }
        completed += match.optString("utcDate", "") to pair
    }
    completed.sortedByDescending { it.first }.take(10).map { it.second }
}

private fun weightedRecentAverage(values: List<Int>): Double {
    if (values.isEmpty()) return 0.0
    // Input is newest first. Recent matches receive a larger linear weight.
    var weightedTotal = 0.0
    var totalWeight = 0.0
    values.take(10).forEachIndexed { index, value ->
        val weight = (values.size - index).toDouble()
        weightedTotal += value * weight
        totalWeight += weight
    }
    return if (totalWeight > 0.0) weightedTotal / totalWeight else 0.0
}

private fun parseRecentScores(raw: String): List<Pair<Int, Int>> {
    return raw.split(',', '\n', ';').mapNotNull { item ->
        val parts = item.trim().split(Regex("\\s*[-:]\\s*"))
        if (parts.size != 2) return@mapNotNull null
        val scored = parts[0].trim().toIntOrNull() ?: return@mapNotNull null
        val conceded = parts[1].trim().toIntOrNull() ?: return@mapNotNull null
        if (scored !in 0..15 || conceded !in 0..15) null else scored to conceded
    }.take(10)
}

@Composable fun Header(title: String, subtitle: String = "منصة إنشاء قسائم التوقعات") {
    Column(Modifier.fillMaxWidth().background(Bg).padding(horizontal = 20.dp, vertical = 18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { Text("BET SLIP PRO", color = Green, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp); Text(title, color = White, fontSize = 25.sp, fontWeight = FontWeight.Bold) }
            Surface(color = Panel2, shape = RoundedCornerShape(14.dp)) { Text("PRO", color = Green, modifier = Modifier.padding(horizontal = 13.dp, vertical = 8.dp), fontWeight = FontWeight.Bold) }
        }
        Text(subtitle, color = Muted, fontSize = 12.sp)
    }
}

@Composable fun Dashboard(matchCount: Int, selectionCount: Int, slipCount: Int, onMatches: () -> Unit, onSlip: () -> Unit) {
    LazyColumn(contentPadding = PaddingValues(bottom = 20.dp)) {
        item { Header("لوحة التحكم", "تابع مبارياتك وأنشئ قسائمك بسهولة") }
        item {
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StatCard("المباريات", matchCount.toString(), Modifier.weight(1f)); StatCard("اختيارات القسيمة", selectionCount.toString(), Modifier.weight(1f)); StatCard("القسائم المحفوظة", slipCount.toString(), Modifier.weight(1f))
                }
                Surface(color = Panel, shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(18.dp)) {
                        Text("أنشئ قسيمتك التالية", color = White, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                        Text("أضف مباراة، اختر السوق والتوقع، ودع التطبيق يحسب المعامل والعائد المحتمل.", color = Muted, fontSize = 13.sp)
                        Spacer(Modifier.height(16.dp))
                        Button(onClick = onMatches, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Green), shape = RoundedCornerShape(12.dp)) { Icon(Icons.Default.Add, null); Spacer(Modifier.width(8.dp)); Text("إضافة أو استيراد مباريات", color = Bg, fontWeight = FontWeight.Bold) }
                        OutlinedButton(onClick = onSlip, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) { Icon(Icons.Default.Ticket, null); Spacer(Modifier.width(8.dp)); Text("فتح القسيمة الحالية") }
                    }
                }
                Text("مميزات التطبيق", color = White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                FeatureRow("قسائم أحادية وتراكمية", "إضافة اختيار واحد أو عدة اختيارات")
                FeatureRow("حساب تلقائي", "ضرب المعاملات وحساب العائد")
                FeatureRow("حفظ ومشاركة", "أرشفة القسائم ومشاركتها كنص أو PDF")
                FeatureRow("مزامنة تلقائية", "جلب مباريات 75 مسابقة دون مفاتيح API")
            }
        }
    }
}

@Composable fun StatCard(label: String, value: String, modifier: Modifier = Modifier) { Surface(modifier, color = Panel, shape = RoundedCornerShape(16.dp)) { Column(Modifier.padding(12.dp)) { Text(value, color = Green, fontSize = 23.sp, fontWeight = FontWeight.Bold); Text(label, color = Muted, fontSize = 10.sp) } } }
@Composable fun FeatureRow(title: String, desc: String) { Surface(color = Panel, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) { Column(Modifier.padding(14.dp)) { Text(title, color = White, fontWeight = FontWeight.SemiBold); Text(desc, color = Muted, fontSize = 12.sp) } } }

@Composable fun MatchesScreen(fixtures: List<Fixture>, selections: List<Selection>, onAdd: () -> Unit, onSelect: (Fixture) -> Unit, onRemove: (Fixture) -> Unit, onFetch: () -> Unit, loading: Boolean, message: String, date: String, onDate: (String) -> Unit, selectedLeague: String, onLeague: (String) -> Unit, autoPicks: List<AutoPrediction>, autoLoading: Boolean, autoMessage: String, onAnalyze: () -> Unit, onSelectPrediction: (AutoPrediction) -> Unit, onBuildSmartCoupon: (List<AutoPrediction>) -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Header("المباريات", "مزامنة تلقائية من مصادر مجانية دون مفاتيح API")
        Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onAdd, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = Green)) { Icon(Icons.Default.Add, null); Text("إضافة يدوية", color = Bg) }
            OutlinedButton(onClick = onFetch, modifier = Modifier.weight(1f), enabled = !loading) { Text(if (loading) "جارٍ التحديث..." else "إعادة المزامنة") }
        }
        OutlinedTextField(value = date, onValueChange = onDate, label = { Text("التاريخ (YYYY-MM-DD)") }, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), singleLine = true)
        var leagueMenu by remember { mutableStateOf(false) }
        Column(Modifier.padding(horizontal = 16.dp)) {
            Box {
                OutlinedButton(onClick = { leagueMenu = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(if (selectedLeague == "ALL") "كل الدوريات العالمية المدعومة" else (ESPN_LEAGUES.firstOrNull { it.code == selectedLeague }?.name ?: selectedLeague), color = White)
                }
                DropdownMenu(expanded = leagueMenu, onDismissRequest = { leagueMenu = false }) {
                    DropdownMenuItem(text = { Text("كل الدوريات المتاحة (حتى 75 مسابقة)") }, onClick = { onLeague("ALL"); leagueMenu = false })
                    ESPN_LEAGUES.forEach { league -> DropdownMenuItem(text = { Text(league.name) }, onClick = { onLeague(league.code); leagueMenu = false }) }
                }
            }
            Text("Open-Matchday يوفر جدولاً يومياً لـ75 مسابقة دون مفتاح؛ ويُدمج ESPN عند توفر بيانات أغنى ومعرفات الفرق.", color = Muted, fontSize = 10.sp, modifier = Modifier.padding(top = 4.dp))
        }
        if (message.isNotBlank()) Text(message, color = Green, modifier = Modifier.padding(horizontal = 18.dp), fontSize = 12.sp)
        if (fixtures.isNotEmpty()) {
            OutlinedButton(onClick = onAnalyze, enabled = !autoLoading, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                Text(if (autoLoading) "جارٍ تحليل المباريات..." else "تحليل المباريات واستخراج أفضل 10 توقعات", color = Green)
            }
        }
        if (autoMessage.isNotBlank()) Text(autoMessage, color = if (autoPicks.isEmpty()) Muted else Green, modifier = Modifier.padding(horizontal = 18.dp), fontSize = 11.sp)
        if (autoPicks.isNotEmpty()) {
            val smartLegs = remember(autoPicks) { PredictionIntelligence.buildSmartCoupon(autoPicks) }
            Surface(color = Panel, shape = RoundedCornerShape(14.dp), modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("منشئ القسيمة الذكية", color = Green, fontWeight = FontWeight.Bold)
                    Text("اختيار حتى 3 مباريات مختلفة، بحد أدنى 60% لاحتمال الاختيار. تحذير: القسائم التراكمية تضاعف هامش الوكيل وتخفض العائد المتوقع؛ الأفضل الاختيار المفرد.", color = Muted, fontSize = 10.sp)
                    val couponAudit = remember(smartLegs) { PredictionQuality.couponAudit(smartLegs) }
                    Text("الاختيارات المؤهلة: ${smartLegs.size} • الاحتمال التجميعي التقريبي: ${if (smartLegs.isEmpty()) "—" else PredictionEngine.percent(couponAudit.approximateJointProbability)}", color = White, fontSize = 11.sp)
                    Text("تقييم القسيمة: ${couponAudit.qualityLabel} • أضعف اختيار: ${if (smartLegs.isEmpty()) "—" else PredictionEngine.percent(couponAudit.weakestLegProbability)}", color = if (couponAudit.warnings.isEmpty()) Green else Color(0xFFFFC46B), fontSize = 10.sp)
                    couponAudit.warnings.forEach { Text("• $it", color = Muted, fontSize = 9.sp) }
                    Button(onClick = { onBuildSmartCoupon(smartLegs) }, enabled = smartLegs.size >= 2, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Green)) { Text("إنشاء قسيمة من ${smartLegs.size} اختيارات", color = Bg) }
                    Text("الاحتمال التجميعي تقريبي ويفترض استقلال المباريات؛ لا يراعي الترابط أو هامش السوق ولا يمثل ضماناً.", color = Muted, fontSize = 9.sp)
                }
            }
            Text("أفضل التوقعات بعد المعايرة المتاحة", color = Green, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 16.dp))
            autoPicks.forEachIndexed { index, prediction ->
                Surface(color = Panel2, shape = RoundedCornerShape(14.dp), modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp).fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("${index + 1}. ${prediction.fixture.home} × ${prediction.fixture.away}", color = White, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f), fontSize = 13.sp)
                            Text(PredictionEngine.percent(prediction.probability), color = Green, fontWeight = FontWeight.Bold)
                        }
                        Text("${prediction.market}: ${prediction.pick}  •  نتيجة النموذج: ${prediction.expectedScore}", color = Muted, fontSize = 11.sp)
                        Text(prediction.quality, color = Muted, fontSize = 10.sp)
                        if (prediction.modelDetails.isNotBlank()) Text(prediction.modelDetails, color = Green, fontSize = 9.sp)
                        prediction.audit?.let { audit ->
                            Text(PredictionQuality.scoreLabel(audit.score), color = if (audit.score >= 85) Green else Color(0xFFFFC46B), fontSize = 10.sp)
                            Text("${prediction.fixture.home} • هجوم ${String.format(Locale.US, "%.2f", audit.home.attackGoalsPerMatch)} هدف/مباراة (مؤشر ${String.format(Locale.US, "%.2f", audit.home.attackIndex)}) • دفاع يستقبل ${String.format(Locale.US, "%.2f", audit.home.defenseGoalsConcededPerMatch)} (مؤشر ${String.format(Locale.US, "%.2f", audit.home.defensiveIndex)})", color = Muted, fontSize = 9.sp)
                            Text("${prediction.fixture.away} • هجوم ${String.format(Locale.US, "%.2f", audit.away.attackGoalsPerMatch)} هدف/مباراة (مؤشر ${String.format(Locale.US, "%.2f", audit.away.attackIndex)}) • دفاع يستقبل ${String.format(Locale.US, "%.2f", audit.away.defenseGoalsConcededPerMatch)} (مؤشر ${String.format(Locale.US, "%.2f", audit.away.defensiveIndex)})", color = Muted, fontSize = 9.sp)
                            Text("المؤشر 1.00 = مستوى مرجعي عام؛ ليس ترتيباً رسمياً للدوري. البيانات من مصدر ESPN واحد.", color = Muted, fontSize = 8.sp)
                            audit.warnings.forEach { warning -> Text("تنبيه: $warning", color = Color(0xFFFFC46B), fontSize = 9.sp) }
                        }
                        var marketOddsText by remember(prediction.fixture.id, prediction.market, prediction.pick) { mutableStateOf("") }
                        OutlinedTextField(
                            value = marketOddsText,
                            onValueChange = { marketOddsText = it.filter { ch -> ch.isDigit() || ch == '.' } },
                            label = { Text("سعر السوق العشري (اختياري)") },
                            placeholder = { Text("مثال: 1.85") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        ValueFinder.assess(
                            modelProbability = prediction.probability,
                            decimalOdds = marketOddsText.toDoubleOrNull() ?: Double.NaN,
                            dataQualityScore = (prediction.audit?.score ?: prediction.qualityScore ?: 0)
                        )?.let { value ->
                            Text("السعر العادل: ${String.format(Locale.US, "%.2f", value.fairOdds)} • احتمال السوق: ${PredictionEngine.percent(value.marketImpliedProbability)}", color = Muted, fontSize = 10.sp)
                            Text("الفارق: ${if (value.edge >= 0) "+" else ""}${String.format(Locale.US, "%.1f", value.edge * 100)} نقطة • العائد المتوقع: ${String.format(Locale.US, "%.1f", value.expectedReturnPerUnit * 100)}%", color = if (value.edge >= 0.02 && ((prediction.audit?.score ?: prediction.qualityScore ?: 0)) >= 65) Green else Color(0xFFFFC46B), fontSize = 10.sp)
                            Text("${value.label} • مؤشر القيمة ${value.valueScore}/100", color = if (value.label == "قيمة محتملة") Green else Muted, fontSize = 10.sp)
                            value.warnings.forEach { warning -> Text("تنبيه: $warning", color = Color(0xFFFFC46B), fontSize = 9.sp) }
                            Text("القيمة تقدير إحصائي يعتمد على السعر الذي أدخلته وجودة النموذج؛ ليست ضماناً للربح.", color = Muted, fontSize = 8.sp)
                        }
                        TextButton(onClick = { onSelectPrediction(prediction) }) { Text("إضافة هذا التوقع للقسيمة", color = Green) }
                    }
                }
            }
        }
        if (fixtures.isEmpty()) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("لا توجد مباريات بعد. أضف مباراة يدوياً أو اجلبها.", color = Muted) }
        else LazyColumn(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(fixtures, key = { it.id }) { f -> Surface(color = Panel, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) { Text(f.league.ifBlank { "مباراة" }, color = Green, fontSize = 11.sp, modifier = Modifier.weight(1f)); Text(f.date, color = Muted, fontSize = 10.sp) }
                    Spacer(Modifier.height(8.dp)); Text("${f.home}  ×  ${f.away}", color = White, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { onSelect(f) }, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = Green), contentPadding = PaddingValues(8.dp)) { Text("إضافة للقسيمة", color = Bg) }
                        OutlinedButton(onClick = { onRemove(f) }, contentPadding = PaddingValues(8.dp)) { Text("حذف") }
                    }
                }
            } }
        }
    }
}

@Composable fun SlipScreen(selections: List<Selection>, stake: String, onStake: (String) -> Unit, onRemove: (Selection) -> Unit, onMarket: (Selection, String, String, Double) -> Unit, onSave: () -> Unit, onShare: () -> Unit, onPdf: () -> Unit, onPng: () -> Unit) {
    val totalOdds = selections.fold(1.0) { acc, s -> acc * s.odds }
    val amount = stake.toDoubleOrNull() ?: 0.0
    val payout = totalOdds * amount
    LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
        item { Header("القسيمة", if (selections.size <= 1) "قسيمة أحادية" else "قسيمة تراكمية • ${selections.size} اختيارات") }
        item {
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Surface(color = Panel, shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("نوع القسيمة", color = Muted); Text(if (selections.size > 1) "تراكمية" else "أحادية", color = Green, fontWeight = FontWeight.Bold) }
                        Spacer(Modifier.height(10.dp))
                        Text("المعامل الإجمالي", color = Muted, fontSize = 12.sp)
                        Text(if (selections.isEmpty()) "0.00" else String.format(Locale.US, "%.2f", totalOdds), color = Green, fontSize = 32.sp, fontWeight = FontWeight.Bold)
                        OutlinedTextField(value = stake, onValueChange = onStake, label = { Text("مبلغ الرهان") }, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("العائد المحتمل", color = Muted); Text(String.format(Locale.US, "%.0f", payout), color = White, fontWeight = FontWeight.Bold, fontSize = 19.sp) }
                        Text("الربح الصافي المحتمل: ${String.format(Locale.US, "%.0f", (payout - amount).coerceAtLeast(0.0))}", color = Green, fontSize = 12.sp)
                    }
                }
                if (selections.isEmpty()) Text("القسيمة فارغة. اذهب إلى المباريات وأضف اختياراً.", color = Muted)
            }
        }
        items(selections, key = { it.id }) { s ->
            var market by remember(s.id) { mutableStateOf(s.market) }
            var pick by remember(s.id) { mutableStateOf(s.pick) }
            var odd by remember(s.id) { mutableStateOf(s.odds.toString()) }
            Surface(color = Panel, shape = RoundedCornerShape(16.dp), modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth()) {
                Column(Modifier.padding(14.dp)) {
                    Text(s.fixture.league, color = Green, fontSize = 11.sp)
                    Text("${s.fixture.home} × ${s.fixture.away}", color = White, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(value = market, onValueChange = { market = it; onMarket(s, it, pick, odd.toDoubleOrNull() ?: 1.0) }, label = { Text("السوق: 1X2 / Goals / BTTS / Score") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    OutlinedTextField(value = pick, onValueChange = { pick = it; onMarket(s, market, it, odd.toDoubleOrNull() ?: 1.0) }, label = { Text("التوقع") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    OutlinedTextField(value = odd, onValueChange = { odd = it; onMarket(s, market, pick, it.toDoubleOrNull() ?: 1.0) }, label = { Text("المعامل العشري") }, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true)
                    TextButton(onClick = { onRemove(s) }) { Text("إزالة الاختيار", color = Color(0xFFFF7777)) }
                }
            }
        }
        item {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onSave, enabled = selections.isNotEmpty(), modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Green)) { Text("حفظ القسيمة", color = Bg, fontWeight = FontWeight.Bold) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onShare, enabled = selections.isNotEmpty(), modifier = Modifier.weight(1f)) { Icon(Icons.Default.Share, null); Text("مشاركة") }
                    OutlinedButton(onClick = onPdf, enabled = selections.isNotEmpty(), modifier = Modifier.weight(1f)) { Text("تصدير PDF") }
                }
                OutlinedButton(onClick = onPng, enabled = selections.isNotEmpty(), modifier = Modifier.fillMaxWidth()) { Text("تصدير صورة PNG") }
                Text("الحسابات تقديرية وتعتمد على المعاملات التي تدخلها؛ لا تمثل ضماناً للنتيجة.", color = Muted, fontSize = 10.sp)
            }
        }
    }
}

@Composable fun ArchiveScreen(slips: List<SavedSlip>, onShare: (SavedSlip) -> Unit, onDelete: (SavedSlip) -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Header("الأرشيف", "القسائم المحفوظة على هذا الجهاز")
        if (slips.isEmpty()) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("لا توجد قسائم محفوظة", color = Muted) }
        else LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(slips, key = { it.id }) { s ->
                val odds = s.selections.fold(1.0) { a, b -> a * b.odds }
                Surface(color = Panel, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp)) {
                        Text(s.title, color = White, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                        Text("${s.created} • ${s.selections.size} اختيارات", color = Muted, fontSize = 11.sp)
                        Text("المعامل ${String.format(Locale.US, "%.2f", odds)}  |  الرهان ${s.stake}", color = Green)
                        Row { TextButton(onClick = { onShare(s) }) { Text("مشاركة") }; TextButton(onClick = { onDelete(s) }) { Text("حذف", color = Color(0xFFFF7777)) } }
                    }
                }
            }
        }
    }
}


private data class EvaluationMetric(val league: String, val market: String, val count: Int, val hits: Int, val brier: Double, val logLoss: Double)

private fun predictionHit(record: PredictionRecord): Boolean {
    val h = record.actualHome ?: return false
    val a = record.actualAway ?: return false
    return when (record.market) {
        "1X2" -> when {
            record.pick == "تعادل" -> h == a
            record.pick == "فوز ${record.fixture.home}" -> h > a
            record.pick == "فوز ${record.fixture.away}" -> a > h
            else -> false
        }
        "الأهداف" -> {
            val line = Regex("(\\d+(?:\\.\\d+)?)").find(record.pick)?.value?.toDoubleOrNull() ?: return false
            val over = record.pick.contains("أكثر")
            if (over) h + a > line else h + a < line
        }
        "يسجل الفريقان" -> if (record.pick == "نعم") h > 0 && a > 0 else h == 0 || a == 0
        else -> false
    }
}

private fun evaluationMetrics(records: List<PredictionRecord>): List<EvaluationMetric> = records.filter { it.evaluated }
    .groupBy { (it.fixture.league.ifBlank { "بطولة غير محددة" }) to it.market }
    .map { (key, rows) ->
        val hits = rows.count { predictionHit(it) }
        val brier = rows.map { r -> val p = r.probability.coerceIn(0.0001, 0.9999); val y = if (predictionHit(r)) 1.0 else 0.0; (p - y) * (p - y) }.average()
        val logLoss = rows.map { r -> val p = r.probability.coerceIn(0.0001, 0.9999); val y = if (predictionHit(r)) 1.0 else 0.0; -(y * kotlin.math.ln(p) + (1.0 - y) * kotlin.math.ln(1.0 - p)) }.average()
        EvaluationMetric(key.first, key.second, rows.size, hits, brier, logLoss)
    }.sortedWith(compareByDescending<EvaluationMetric> { it.count }.thenBy { it.league }.thenBy { it.market })

@Composable
fun AutomaticAnalysisScreen(
    picks: List<AutoPrediction>,
    loading: Boolean,
    message: String,
    onAnalyze: () -> Unit
) {
    LazyColumn(contentPadding = PaddingValues(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Header("التحليل التلقائي", "توقعات مبنية على السجلات المتاحة دون إدخال مفاتيح API") }
        item {
            Surface(color = Panel, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("محرك Poisson + Elo + تحليل الأرض والخارج", color = Green, fontWeight = FontWeight.Bold)
                    Text("يحلل التطبيق تلقائياً المباريات التي تتوفر لها معرفات فرق وسجلات تاريخية كافية. المباريات التي تفتقر إلى البيانات تُستبعد من التوقع بدلاً من عرض ثقة غير مدعومة.", color = Muted, fontSize = 12.sp)
                    Button(onClick = onAnalyze, enabled = !loading, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Green)) {
                        Text(if (loading) "جارٍ التحليل..." else "إعادة تحليل المباريات المتاحة", color = Bg)
                    }
                    if (message.isNotBlank()) Text(message, color = Muted, fontSize = 11.sp)
                }
            }
        }
        if (picks.isEmpty()) {
            item { Text("لا توجد توقعات مؤهلة حالياً. قد تكون المباريات لم تبدأ بياناتها التاريخية بالوصول بعد.", color = Muted, modifier = Modifier.padding(horizontal = 16.dp), fontSize = 12.sp) }
        } else {
            items(picks, key = { "${it.fixture.id}-${it.market}-${it.pick}" }) { pick ->
                Surface(color = Panel, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                    Column(Modifier.padding(13.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text(pick.fixture.league, color = Green, fontSize = 10.sp)
                        Text("${pick.fixture.home} × ${pick.fixture.away}", color = White, fontWeight = FontWeight.Bold)
                        Text("${pick.market}: ${pick.pick}", color = White, fontSize = 13.sp)
                        Text("احتمال النموذج: ${String.format(Locale.US, "%.1f%%", pick.probability * 100)}  •  النتيجة المتوقعة: ${pick.expectedScore}", color = Muted, fontSize = 11.sp)
                        Text("جودة البيانات: ${pick.quality}", color = Muted, fontSize = 10.sp)
                    }
                }
            }
        }
    }
}

@Composable fun EvaluationScreen(records: List<PredictionRecord>, onSaveResult: (Long, Int, Int) -> Unit, onDelete: (Long) -> Unit, onClear: () -> Unit, onSync: ((Int) -> Unit) -> Unit) {
    val evaluated = records.filter { it.evaluated }
    var syncMessage by remember { mutableStateOf("") }
    var syncing by remember { mutableStateOf(false) }
    val pending = records.filterNot { it.evaluated }.sortedByDescending { it.created }
    val metrics = remember(records) { evaluationMetrics(records) }
    val leagueStats = remember(records) { PredictionIntelligence.leaguePerformance(records) }
    val hitRate = if (evaluated.isEmpty()) 0.0 else evaluated.count { predictionHit(it) }.toDouble() / evaluated.size
    Column(Modifier.fillMaxSize()) {
        Header("قياس الدقة", "اختبر توقعات المحرك على النتائج الفعلية")
        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatCard("التوقعات المقيمة", evaluated.size.toString(), Modifier.weight(1f))
                    StatCard("نسبة الإصابة", if (evaluated.isEmpty()) "—" else "${(hitRate * 100).roundToInt()}%", Modifier.weight(1f))
                    StatCard("بانتظار النتيجة", pending.size.toString(), Modifier.weight(1f))
                }
                Spacer(Modifier.height(8.dp))
                Text("سجّل النتيجة النهائية لكل مباراة بعد انتهائها. لا تُحسب التوقعات المعلّقة ضمن الدقة.", color = Muted, fontSize = 11.sp)
                Button(onClick = { syncing = true; syncMessage = "جارٍ البحث عن النتائج النهائية المتاحة..."; onSync { count -> syncing = false; syncMessage = if (count > 0) "تم تحديث $count نتيجة تلقائياً." else "لم يعثر المصدر على نتائج نهائية لهذه التوقعات؛ أدخل النتائج يدوياً عند توفرها." } }, enabled = pending.isNotEmpty() && !syncing, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Green)) {
                    Text(if (syncing) "جارٍ التحديث..." else "تحديث النتائج تلقائياً من ESPN", color = Bg)
                }
                if (syncMessage.isNotBlank()) Text(syncMessage, color = Muted, fontSize = 10.sp)
            }
            item { Text("ذكاء الدوريات والمعايرة", color = Green, fontWeight = FontWeight.Bold, fontSize = 16.sp) }
            if (leagueStats.isEmpty()) item { FeatureRow("لا توجد بيانات كافية بعد", "ستظهر مؤشرات كل دوري بعد تسجيل نتائج توقعات مكتملة.") }
            items(leagueStats) { stat ->
                Surface(color = Panel, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(stat.league, color = White, fontWeight = FontWeight.Bold)
                        Text("عينة: ${stat.sampleSize} • الإصابة: ${stat.hits}/${stat.sampleSize} (${(stat.hitRate * 100).roundToInt()}%)", color = Green, fontSize = 11.sp)
                        Text("Brier Score: ${String.format(Locale.US, "%.3f", stat.brierScore)} • الأقل أفضل", color = Muted, fontSize = 10.sp)
                        Text(if (stat.sampleSize < 30) "العينة صغيرة؛ لا تستخدم هذا المؤشر لتعديل النموذج بعد." else "العينة تسمح بمراقبة أولية للأداء، وليست إثباتاً للتفوق.", color = Muted, fontSize = 9.sp)
                    }
                }
            }
            item { Text("الأداء حسب الدوري والسوق", color = Green, fontWeight = FontWeight.Bold, fontSize = 16.sp) }
            if (metrics.isEmpty()) item { FeatureRow("لا توجد نتائج مقيمة بعد", "ستظهر هنا نسبة الإصابة وBrier Score وLog Loss بعد إدخال النتائج النهائية.") }
            items(metrics) { m ->
                Surface(color = Panel, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(13.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(m.league, color = White, fontWeight = FontWeight.Bold)
                        Text("السوق: ${m.market} • العينة: ${m.count}", color = Muted, fontSize = 11.sp)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("الإصابة: ${m.hits}/${m.count} (${(m.hits * 100.0 / m.count).roundToInt()}%)", color = Green, fontWeight = FontWeight.SemiBold)
                            Text("Brier: ${String.format(Locale.US, "%.3f", m.brier)}", color = White)
                        }
                        Text("Log Loss: ${String.format(Locale.US, "%.3f", m.logLoss)} • الأقل أفضل", color = Muted, fontSize = 10.sp)
                    }
                }
            }
            item { Text("توقعات بانتظار النتيجة (${pending.size})", color = Green, fontWeight = FontWeight.Bold, fontSize = 16.sp) }
            if (pending.isEmpty()) item { FeatureRow("لا توجد توقعات معلّقة", "حلّل المباريات من تبويب المباريات لتسجيل التوقعات تلقائياً هنا.") }
            items(pending, key = { it.id }) { r ->
                var homeScore by remember(r.id) { mutableStateOf("") }
                var awayScore by remember(r.id) { mutableStateOf("") }
                Surface(color = Panel, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(13.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        Text("${r.fixture.home} × ${r.fixture.away}", color = White, fontWeight = FontWeight.Bold)
                        Text("${r.fixture.league} • ${r.fixture.date}", color = Muted, fontSize = 10.sp)
                        Text("${r.market}: ${r.pick} • احتمال النموذج ${PredictionEngine.percent(r.probability)}", color = Green, fontSize = 12.sp)
                        Text("النتيجة المتوقعة: ${r.expectedScore} • ${r.modelVersion}", color = Muted, fontSize = 10.sp)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(homeScore, { homeScore = it.filter(Char::isDigit).take(2) }, label = { Text("صاحب الأرض") }, modifier = Modifier.weight(1f), singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                            Text("-", color = White)
                            OutlinedTextField(awayScore, { awayScore = it.filter(Char::isDigit).take(2) }, label = { Text("الضيف") }, modifier = Modifier.weight(1f), singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Button(onClick = { onSaveResult(r.id, homeScore.toInt(), awayScore.toInt()) }, enabled = homeScore.isNotBlank() && awayScore.isNotBlank(), colors = ButtonDefaults.buttonColors(containerColor = Green)) { Text("حفظ النتيجة", color = Bg) }
                            TextButton(onClick = { onDelete(r.id) }) { Text("حذف", color = Color(0xFFFF8888)) }
                        }
                    }
                }
            }
            if (records.isNotEmpty()) item { TextButton(onClick = onClear) { Text("مسح سجل القياس بالكامل", color = Color(0xFFFF8888)) } }
            item { Text("ملاحظة: Brier Score وLog Loss يقيسان جودة الاحتمالات، ونسبة الإصابة تقيس صحة الاختيار المحدد. هذه المقاييس تحتاج عينة كبيرة ومتوازنة ولا تثبت أداء النموذج من عدد قليل من المباريات.", color = Muted, fontSize = 10.sp) }
        }
    }
}

@Composable fun SettingsScreen(token: String, onToken: (String) -> Unit, date: String, onDate: (String) -> Unit, manual: Boolean, onManual: (Boolean) -> Unit, onOdds: () -> Unit) {
    val ctx = LocalContext.current
    val settingsPrefs = remember { ctx.getSharedPreferences("bet_slip_pro", 0) }
    var dailySync by remember { mutableStateOf(settingsPrefs.getBoolean(DailySyncScheduler.PREF_ENABLED, true)) }
    Column(Modifier.fillMaxSize()) {
        Header("الإعدادات", "إعدادات مصادر المباريات والتطبيق")
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Surface(color = Panel, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("التحديث اليومي التلقائي", color = White, fontWeight = FontWeight.Bold)
                        Text("يجلب المباريات ويحللها ويقيّم التوقعات المنتهية في الخلفية مرة يومياً، ويرسل إشعاراً بالتوقعات القوية فقط.", color = Muted, fontSize = 11.sp)
                    }
                    Switch(checked = dailySync, onCheckedChange = {
                        dailySync = it
                        settingsPrefs.edit().putBoolean(DailySyncScheduler.PREF_ENABLED, it).apply()
                        DailySyncScheduler.apply(ctx, it)
                    })
                }
            }
            Surface(color = Panel, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("المزامنة التلقائية — دون مفاتيح API", color = White, fontWeight = FontWeight.Bold)
                    Text("الإصدار 2.0 يجلب جدول المباريات تلقائياً من Open-Matchday (75 مسابقة) ويدمج ما يتوفر من ESPN وTheSportsDB. لا يلزم إنشاء حساب أو نسخ أي مفتاح لتشغيل جلب المباريات والتحليل الأساسي.", color = Muted, fontSize = 12.sp)
                    Spacer(Modifier.height(8.dp))
                    Text("يبدأ التحديث عند فتح التطبيق، ويستخدم التخزين المؤقت لتقليل الطلبات. جدول Open-Matchday يتحدث يومياً وليس بثاً مباشراً، بينما تتفاوت تغطية ESPN حسب البطولة.", color = Green, fontSize = 11.sp)
                    Text("المباريات التي لا تتوفر لها معرفات فرق أو سجل تاريخي كافٍ ستظهر، لكن محرك التوقعات سيستبعدها بدلاً من اختلاق تحليل لها.", color = Muted, fontSize = 10.sp)
                }
            }
            Surface(color = Panel, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("أسعار شركات المراهنات", color = White, fontWeight = FontWeight.Bold)
                    Text("لا يوجد حالياً مصدر موثوق لأسعار كرة القدم المباشرة يوفر تغطية عالمية مجانية بلا مفتاح أو تسجيل. لذلك لا يطلب التطبيق منك مفاتيح أو بيانات اعتماد، ولن يعرض أسعاراً مصطنعة. تبقى هذه الميزة غير مفعّلة في وضع التشغيل التلقائي المجاني.", color = Muted, fontSize = 12.sp)
                }
            }
            Surface(color = Panel, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("معلومات التطبيق", color = White, fontWeight = FontWeight.Bold)
                    Text("BET SLIP PRO • الإصدار 2.1.0", color = Muted)
                    Text("© 2026 Mustafa J. Burhan. All rights reserved.", color = Green, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    Text("البيانات والقسائم محفوظة محلياً على الجهاز.", color = Muted, fontSize = 12.sp)
                    Text("النسخة المجانية التلقائية تجلب المباريات والنتائج التاريخية المتاحة فقط؛ لا تتضمن أسعار مراهنات مباشرة أو بيانات إصابات وتشكيلات غير متاحة مجاناً.", color = Muted, fontSize = 11.sp)
                }
            }
        }
    }
}

@Composable fun AddFixtureDialog(onDismiss: () -> Unit, onAdd: (String, String, String, String) -> Unit) {
    var home by remember { mutableStateOf("") }; var away by remember { mutableStateOf("") }; var league by remember { mutableStateOf("") }; var date by remember { mutableStateOf(today()) }
    AlertDialog(onDismissRequest = onDismiss, containerColor = Panel, title = { Text("إضافة مباراة", color = White) }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(home, { home = it }, label = { Text("الفريق صاحب الأرض") }, singleLine = true)
            OutlinedTextField(away, { away = it }, label = { Text("الفريق الضيف") }, singleLine = true)
            OutlinedTextField(league, { league = it }, label = { Text("البطولة") }, singleLine = true)
            OutlinedTextField(date, { date = it }, label = { Text("التاريخ") }, singleLine = true)
        }
    }, confirmButton = { TextButton(onClick = { if (home.isNotBlank() && away.isNotBlank()) onAdd(home.trim(), away.trim(), league.trim(), date.trim()) }) { Text("إضافة", color = Green) } }, dismissButton = { TextButton(onClick = onDismiss) { Text("إلغاء") } })
}
@Composable fun NameDialog(onDismiss: () -> Unit, onSave: (String) -> Unit) { var name by remember { mutableStateOf("قسيمة جديدة") }; AlertDialog(onDismissRequest = onDismiss, containerColor = Panel, title = { Text("حفظ القسيمة", color = White) }, text = { OutlinedTextField(name, { name = it }, label = { Text("اسم القسيمة") }, singleLine = true) }, confirmButton = { TextButton(onClick = { onSave(name.ifBlank { "قسيمة جديدة" }) }) { Text("حفظ", color = Green) } }, dismissButton = { TextButton(onClick = onDismiss) { Text("إلغاء") } }) }

internal fun today(): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
private fun nowText(): String = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())
internal fun legacyLoadFixtures(prefs: android.content.SharedPreferences): List<Fixture> = try { val a = JSONArray(prefs.getString("fixtures", "[]")); (0 until a.length()).map { val o = a.getJSONObject(it); Fixture(o.getLong("id"), o.getString("home"), o.getString("away"), o.optString("league"), o.optString("date"), o.optString("homeTeamId"), o.optString("awayTeamId"), o.optString("sourceLeagueCode")) } } catch (_: Exception) { emptyList() }
internal fun selectionJson(s: Selection) = JSONObject().put("id", s.id).put("home", s.fixture.home).put("away", s.fixture.away).put("league", s.fixture.league).put("date", s.fixture.date).put("homeTeamId", s.fixture.homeTeamId).put("awayTeamId", s.fixture.awayTeamId).put("sourceLeagueCode", s.fixture.sourceLeagueCode).put("market", s.market).put("pick", s.pick).put("odds", s.odds)
internal fun jsonSelection(o: JSONObject): Selection { val f = Fixture(o.getLong("id"), o.getString("home"), o.getString("away"), o.optString("league"), o.optString("date"), o.optString("homeTeamId"), o.optString("awayTeamId"), o.optString("sourceLeagueCode")); return Selection(o.getLong("id"), f, o.optString("market"), o.optString("pick"), o.optDouble("odds", 1.0)) }
internal fun legacyLoadSlips(prefs: android.content.SharedPreferences): List<SavedSlip> = try { val a = JSONArray(prefs.getString("slips", "[]")); (0 until a.length()).map { val o = a.getJSONObject(it); val p = o.getJSONArray("selections"); SavedSlip(o.getLong("id"), o.getString("title"), o.optDouble("stake"), (0 until p.length()).map { j -> jsonSelection(p.getJSONObject(j)) }, o.optString("created")) } } catch (_: Exception) { emptyList() }

internal fun legacyLoadPredictionRecords(prefs: android.content.SharedPreferences): List<PredictionRecord> = try {
    val array = JSONArray(prefs.getString("prediction_records", "[]"))
    (0 until array.length()).mapNotNull { i ->
        val o = array.optJSONObject(i) ?: return@mapNotNull null
        val f = o.optJSONObject("fixture") ?: return@mapNotNull null
        val home = if (o.isNull("actualHome")) null else o.optInt("actualHome")
        val away = if (o.isNull("actualAway")) null else o.optInt("actualAway")
        PredictionRecord(
            o.optLong("id"), Fixture(f.optLong("id"), f.optString("home"), f.optString("away"), f.optString("league"), f.optString("date"), f.optString("homeTeamId"), f.optString("awayTeamId"), f.optString("sourceLeagueCode")),
            o.optString("market"), o.optString("pick"), o.optDouble("probability").coerceIn(0.0, 1.0), o.optString("expectedScore"), o.optString("modelVersion", "Poisson-1.0"), o.optString("created"), home, away, if (o.isNull("rawProbability")) null else o.optDouble("rawProbability").coerceIn(0.0, 1.0)
        )
    }
} catch (_: Exception) { emptyList() }

/** Free public soccer scoreboard catalog. ESPN endpoints are unofficial/public and may change. */
private data class LeagueOption(val code: String, val name: String)

private val ESPN_LEAGUES = listOf(
    LeagueOption("eng.1", "إنجلترا - الدوري الممتاز"),
    LeagueOption("eng.2", "إنجلترا - التشامبيونشيب"),
    LeagueOption("eng.3", "إنجلترا - League One"),
    LeagueOption("esp.1", "إسبانيا - La Liga"),
    LeagueOption("esp.2", "إسبانيا - الدرجة الثانية"),
    LeagueOption("ger.1", "ألمانيا - Bundesliga"),
    LeagueOption("ger.2", "ألمانيا - الدرجة الثانية"),
    LeagueOption("ita.1", "إيطاليا - Serie A"),
    LeagueOption("ita.2", "إيطاليا - Serie B"),
    LeagueOption("fra.1", "فرنسا - Ligue 1"),
    LeagueOption("fra.2", "فرنسا - Ligue 2"),
    LeagueOption("ned.1", "هولندا - Eredivisie"),
    LeagueOption("por.1", "البرتغال - Primeira Liga"),
    LeagueOption("sco.1", "اسكتلندا - Premiership"),
    LeagueOption("bel.1", "بلجيكا - Pro League"),
    LeagueOption("tur.1", "تركيا - Super Lig"),
    LeagueOption("gre.1", "اليونان - Super League"),
    LeagueOption("aut.1", "النمسا - Bundesliga"),
    LeagueOption("sui.1", "سويسرا - Super League"),
    LeagueOption("den.1", "الدنمارك - Superliga"),
    LeagueOption("nor.1", "النرويج - Eliteserien"),
    LeagueOption("swe.1", "السويد - Allsvenskan"),
    LeagueOption("pol.1", "بولندا - Ekstraklasa"),
    LeagueOption("rsa.1", "جنوب أفريقيا - Premier Soccer League"),
    LeagueOption("chn.1", "الصين - Super League"),
    LeagueOption("usa.1", "الولايات المتحدة - MLS"),
    LeagueOption("usa.nwsl", "الولايات المتحدة - NWSL للسيدات"),
    LeagueOption("mex.1", "المكسيك - Liga MX"),
    LeagueOption("bra.1", "البرازيل - Serie A"),
    LeagueOption("arg.1", "الأرجنتين - Primera División"),
    LeagueOption("ksa.1", "السعودية - دوري المحترفين"),
    LeagueOption("aus.1", "أستراليا - A-League"),
    LeagueOption("jpn.1", "اليابان - J League"),
    LeagueOption("kor.1", "كوريا الجنوبية - K League"),
    LeagueOption("uefa.champions", "UEFA Champions League"),
    LeagueOption("uefa.europa", "UEFA Europa League"),
    LeagueOption("uefa.europa.conf", "UEFA Conference League"),
    LeagueOption("conmebol.libertadores", "Copa Libertadores"),
    LeagueOption("conmebol.sudamericana", "Copa Sudamericana"),
    LeagueOption("fifa.world", "كأس العالم"),
    LeagueOption("fifa.worldq.uefa", "تصفيات كأس العالم - أوروبا"),
    LeagueOption("uefa.euro", "بطولة أمم أوروبا"),
    LeagueOption("uefa.nations", "دوري الأمم الأوروبية")
)

/** Fetches a date from multiple free sources. ALL means every competition in our maintained ESPN catalog. */
internal suspend fun fetchCombinedFixtures(token: String, date: String, leagueCode: String, cacheDir: java.io.File): Result<List<Fixture>> = withContext(Dispatchers.IO) {
    runCatching {
        require(date.matches(Regex("\\d{4}-\\d{2}-\\d{2}"))) { "صيغة التاريخ يجب أن تكون YYYY-MM-DD" }
        val collected = mutableListOf<Fixture>()
        val sourceErrors = mutableListOf<String>()

        // Keyless Open-Matchday feed: 75 competitions, cached locally for 8 hours.
        // Its CSV schedule is daily/delayed, so ESPN remains the preferred source for IDs and richer live scoreboards.
        if (leagueCode == "ALL") {
            runCatching { OpenMatchdayProvider.fetchFixtures(date, cacheDir).getOrThrow() }
                .onSuccess { rows -> collected += rows.map { row ->
                    Fixture(
                        id = Ids.stable(row.folder, row.date, row.home, row.away),
                        home = row.home, away = row.away, league = row.league,
                        date = listOf(row.date, row.time).filter { it.isNotBlank() }.joinToString(" "),
                        sourceLeagueCode = ""
                    )
                } }
                .onFailure { sourceErrors += "Open-Matchday: ${it.message ?: "unavailable"}" }
        }

        // ESPN public scoreboard covers a broad catalog without requiring an API key.
        val codes = if (leagueCode == "ALL") ESPN_LEAGUES.map { it.code } else listOf(leagueCode)
        codes.distinct().chunked(4).forEach { batch ->
            val batchResults = batch.map { code -> async(Dispatchers.IO) { runCatching { fetchEspnFixtures(code, date) } } }.awaitAll()
            batchResults.forEach { result ->
                result.onSuccess { collected += it }.onFailure { sourceErrors += (it.message ?: "ESPN error") }
            }
        }

        // These sources supplement ESPN and may fill coverage gaps. Their coverage is not global.
        if (leagueCode == "ALL") {
            runCatching { fetchTheSportsDbDay(date) }.onSuccess { collected += it }.onFailure { sourceErrors += "TheSportsDB" }
            if (token.isNotBlank()) runCatching { fetchFootballDataFixtures(token.trim(), date) }
                .onSuccess { collected += it }.onFailure { sourceErrors += "football-data.org" }
        }
        // For a single-league request, use the exact ESPN competition endpoint to avoid mixing leagues.

        val unique = collected.groupBy {
            it.home.trim().lowercase(Locale.ROOT) + "|" + it.away.trim().lowercase(Locale.ROOT) + "|" + it.date.take(10)
        }.values.map { duplicates ->
            // Prefer ESPN rows carrying team IDs, which are required by the historical analysis engine.
            duplicates.firstOrNull { it.homeTeamId.isNotBlank() && it.awayTeamId.isNotBlank() } ?: duplicates.first()
        }.sortedBy { it.date }
        if (unique.isEmpty() && sourceErrors.isNotEmpty()) throw IllegalStateException("لم تستجب مصادر البيانات. تحقق من الإنترنت أو جرّب بطولة أخرى.")
        unique
    }
}

private fun fetchEspnFixtures(leagueCode: String, date: String): List<Fixture> {
    val compactDate = date.replace("-", "")
    val body = readHttp("https://site.api.espn.com/apis/site/v2/sports/soccer/$leagueCode/scoreboard?dates=$compactDate")
    val root = JSONObject(body)
    val events = root.optJSONArray("events") ?: return emptyList()
    val leagueName = root.optJSONArray("leagues")?.optJSONObject(0)?.optString("name")
        ?.takeIf { it.isNotBlank() } ?: ESPN_LEAGUES.firstOrNull { it.code == leagueCode }?.name ?: leagueCode
    return (0 until events.length()).mapNotNull { index ->
        val event = events.optJSONObject(index) ?: return@mapNotNull null
        val competition = event.optJSONArray("competitions")?.optJSONObject(0) ?: return@mapNotNull null
        val competitors = competition.optJSONArray("competitors") ?: return@mapNotNull null
        var home = ""
        var away = ""
        var homeId = ""
        var awayId = ""
        for (i in 0 until competitors.length()) {
            val competitor = competitors.optJSONObject(i) ?: continue
            val teamName = competitor.optJSONObject("team")?.optString("displayName")?.takeIf { it.isNotBlank() } ?: continue
            when (competitor.optString("homeAway")) {
                "home" -> { home = teamName; homeId = competitor.optJSONObject("team")?.optString("id", "") ?: "" }
                "away" -> { away = teamName; awayId = competitor.optJSONObject("team")?.optString("id", "") ?: "" }
            }
        }
        if (home.isBlank() || away.isBlank()) return@mapNotNull null
        val utcDate = event.optString("date", "")
        val readableDate = utcDate.take(16).replace("T", " ").ifBlank { date }
        val id = event.optString("id").toLongOrNull() ?: Ids.stable(leagueCode, home, away, date)
        Fixture(id, home, away, leagueName, readableDate, homeId, awayId, leagueCode)
    }
}


/**
 * Whole-competition analysis. For every league that has fixtures we download ~150 days of finished results
 * (so opponents can be rated too), fit opponent-adjusted strengths + Elo, and rank fixtures with the
 * Dixon-Coles model. When free-tier odds are available the 1X2 probabilities are blended with the market.
 */
/**
 * Preferred path: predictions computed by the free GitHub Actions engine (see RemoteFeed).
 * If the feed is unreachable or not configured, falls back to the on-device ESPN analysis below.
 */
internal suspend fun analyzeTopPredictions(
    fixtures: List<Fixture>,
    history: List<PredictionRecord>,
    oddsEvents: List<OddsEvent> = emptyList(),
    marketWeight: Double = MarketBlend.DEFAULT_MARKET_WEIGHT
): Result<List<AutoPrediction>> {
    val feed = withContext(Dispatchers.IO) { RemoteFeed.fetch() }
    feed.getOrNull()?.let { if (it.picks.isNotEmpty()) return Result.success(it.picks) }
    return analyzeTopPredictionsLocal(fixtures, history, oddsEvents, marketWeight)
}

internal suspend fun analyzeTopPredictionsLocal(
    fixtures: List<Fixture>,
    history: List<PredictionRecord>,
    oddsEvents: List<OddsEvent> = emptyList(),
    marketWeight: Double = MarketBlend.DEFAULT_MARKET_WEIGHT
): Result<List<AutoPrediction>> = withContext(Dispatchers.IO) {
    runCatching {
        val eligible = fixtures.filter { it.sourceLeagueCode.isNotBlank() && it.homeTeamId.isNotBlank() && it.awayTeamId.isNotBlank() }
        if (eligible.isEmpty()) return@runCatching emptyList()
        val teamKeys = eligible.flatMap { listOf(it.sourceLeagueCode to it.homeTeamId, it.sourceLeagueCode to it.awayTeamId) }.distinct()
        val histories = mutableMapOf<Pair<String, String>, AdvancedTeamHistory>()
        teamKeys.chunked(4).forEach { batch ->
            val responses = batch.map { key -> async(Dispatchers.IO) { key to runCatching { fetchEspnTeamHistory(key.first, key.second) } } }.awaitAll()
            responses.forEach { (key, response) -> response.getOrNull()?.let { histories[key] = it } }
        }

        val leagues = eligible.map { it.sourceLeagueCode }.distinct()
        val leagueResults = mutableMapOf<String, List<EloMatch>>()
        leagues.chunked(4).forEach { batch ->
            val responses = batch.map { code -> async(Dispatchers.IO) { code to runCatching { fetchEspnLeagueResults(code, 150) }.getOrDefault(emptyList()) } }.awaitAll()
            responses.forEach { (code, rows) -> leagueResults[code] = rows }
        }

        // Per competition: league-wide results + the teams' own schedules, de-duplicated by event id.
        val groupedHistories = histories.entries.groupBy { it.key.first }
        val matchesByLeague = leagues.associateWith { code ->
            val fromTeams = groupedHistories[code].orEmpty().flatMap { it.value.matches }.map { row ->
                if (row.wasHome) EloMatch(row.eventId, row.date, row.teamId, row.opponentId, row.goalsFor, row.goalsAgainst)
                else EloMatch(row.eventId, row.date, row.opponentId, row.teamId, row.goalsAgainst, row.goalsFor)
            }
            (leagueResults[code].orEmpty() + fromTeams).distinctBy { it.eventId }
        }
        val eloByLeague = matchesByLeague.mapValues { EloRatingEngine.calculate(it.value) }
        val nowDay = StrengthModel.epochDay(today()) ?: 0L
        val strengthByLeague = matchesByLeague.mapValues { StrengthModel.fit(it.value, nowDay) }
        val leagueAverages = matchesByLeague.mapValues { (_, rows) ->
            val homeAverage = rows.map { it.homeGoals }.takeIf { it.size >= 5 }?.average()?.coerceIn(0.2, 4.0) ?: 1.50
            val awayAverage = rows.map { it.awayGoals }.takeIf { it.size >= 5 }?.average()?.coerceIn(0.2, 4.0) ?: 1.15
            homeAverage to awayAverage
        }

        eligible.mapNotNull { fixture ->
            val home = histories[fixture.sourceLeagueCode to fixture.homeTeamId] ?: return@mapNotNull null
            val away = histories[fixture.sourceLeagueCode to fixture.awayTeamId] ?: return@mapNotNull null
            if (home.matches.size < 5 || away.matches.size < 5) return@mapNotNull null
            val elo = eloByLeague[fixture.sourceLeagueCode].orEmpty()
            val fit = strengthByLeague[fixture.sourceLeagueCode]
            val leagueAverage = leagueAverages[fixture.sourceLeagueCode] ?: (1.50 to 1.15)
            val advanced = fit?.let { AdvancedPredictionEngine.predictWithStrengths(home, away, it, elo) }
                ?: AdvancedPredictionEngine.predict(home, away, elo, leagueAverage.first, leagueAverage.second)
            val prediction = advanced.probabilities

            val marketTriple = MarketBlend.findEvent(oddsEvents, fixture.home, fixture.away)?.let { MarketBlend.marketTriple(it) }
            val blended = marketTriple?.let { MarketBlend.blend(Triple(prediction.homeWin, prediction.draw, prediction.awayWin), it, marketWeight) }
            val pHome = blended?.first ?: prediction.homeWin
            val pDraw = blended?.second ?: prediction.draw
            val pAway = blended?.third ?: prediction.awayWin

            val candidates = listOf(
                "1X2" to ("فوز ${fixture.home}" to pHome),
                "1X2" to ("تعادل" to pDraw),
                "1X2" to ("فوز ${fixture.away}" to pAway),
                "الأهداف" to ("أكثر من 1.5" to prediction.over15),
                "الأهداف" to ("أقل من 1.5" to (1.0 - prediction.over15)),
                "الأهداف" to ("أكثر من 2.5" to prediction.over25),
                "الأهداف" to ("أقل من 2.5" to (1.0 - prediction.over25)),
                "يسجل الفريقان" to ("نعم" to prediction.bttsYes),
                "يسجل الفريقان" to ("لا" to (1.0 - prediction.bttsYes))
            )
            val best = candidates.maxByOrNull { it.second.second } ?: return@mapNotNull null
            val calibrated = PredictionIntelligence.calibrateProbability(best.second.second, fixture.league, best.first, fixture.date, history)
            val homeForm = TeamForm(home.matches.size, weightedRecentAverage(home.matches.sortedByDescending { it.date }.take(10).map { it.goalsFor }), weightedRecentAverage(home.matches.sortedByDescending { it.date }.take(10).map { it.goalsAgainst }))
            val awayForm = TeamForm(away.matches.size, weightedRecentAverage(away.matches.sortedByDescending { it.date }.take(10).map { it.goalsFor }), weightedRecentAverage(away.matches.sortedByDescending { it.date }.take(10).map { it.goalsAgainst }))
            val audit = PredictionQuality.audit(homeForm, awayForm, calibrated)
            val details = "${advanced.modelLabel} • Elo ${advanced.homeElo.toInt()} : ${advanced.awayElo.toInt()} • فرق ${advanced.eloDifference.toInt()}" +
                (if (blended != null) " • مدموج مع أسعار السوق" else "")
            val notes = advanced.warnings.joinToString("؛ ")
            AutoPrediction(
                fixture, best.first, best.second.first, calibrated, prediction.mostLikelyScore,
                "آخر ${home.matches.size} / ${away.matches.size} مباريات • ${prediction.dataQuality}" + if (notes.isNotBlank()) " • $notes" else "",
                best.second.second, homeForm, awayForm, audit, details
            )
        }.filter { it.probability >= 0.52 }.sortedByDescending { it.probability }.take(10)
    }
}

/** Finished results of a whole competition for the last [daysBack] days (one request; ESPN supports date ranges). */
internal fun fetchEspnLeagueResults(leagueCode: String, daysBack: Int): List<EloMatch> {
    val utc = java.util.TimeZone.getTimeZone("UTC")
    val fmt = SimpleDateFormat("yyyyMMdd", Locale.US).apply { timeZone = utc }
    val cal = java.util.Calendar.getInstance(utc)
    val end = fmt.format(cal.time)
    cal.add(java.util.Calendar.DAY_OF_YEAR, -daysBack)
    val start = fmt.format(cal.time)
    val root = JSONObject(readHttp("https://site.api.espn.com/apis/site/v2/sports/soccer/$leagueCode/scoreboard?dates=$start-$end&limit=500"))
    val events = root.optJSONArray("events") ?: return emptyList()
    val out = mutableListOf<EloMatch>()
    for (i in 0 until events.length()) {
        val event = events.optJSONObject(i) ?: continue
        val statusType = event.optJSONObject("status")?.optJSONObject("type")
        if (statusType?.optBoolean("completed", false) != true) continue
        val competitors = event.optJSONArray("competitions")?.optJSONObject(0)?.optJSONArray("competitors") ?: continue
        var homeId = ""; var awayId = ""; var hg: Int? = null; var ag: Int? = null
        for (j in 0 until competitors.length()) {
            val c = competitors.optJSONObject(j) ?: continue
            val id = c.optJSONObject("team")?.optString("id", "") ?: ""
            val goals = c.optString("score", "").toIntOrNull() ?: continue
            when (c.optString("homeAway")) { "home" -> { homeId = id; hg = goals }; "away" -> { awayId = id; ag = goals } }
        }
        val h = hg ?: continue; val a = ag ?: continue
        val eventId = event.optString("id")
        if (eventId.isBlank() || homeId.isBlank() || awayId.isBlank()) continue
        out += EloMatch(eventId, event.optString("date"), homeId, awayId, h, a)
    }
    return out
}

/** Reads completed fixtures only and retains venue/opponent context for the advanced model. */
private fun fetchEspnTeamHistory(leagueCode: String, teamId: String): AdvancedTeamHistory {
    val body = readHttp("https://site.api.espn.com/apis/site/v2/sports/soccer/$leagueCode/teams/$teamId/schedule")
    val events = JSONObject(body).optJSONArray("events") ?: JSONArray()
    val completed = mutableListOf<TeamMatchResult>()
    for (i in 0 until events.length()) {
        val event = events.optJSONObject(i) ?: continue
        val statusType = event.optJSONObject("status")?.optJSONObject("type")
        val isComplete = statusType?.optBoolean("completed", false) == true || statusType?.optString("state") == "post"
        if (!isComplete) continue
        val competition = event.optJSONArray("competitions")?.optJSONObject(0) ?: continue
        val competitors = competition.optJSONArray("competitors") ?: continue
        var ownScore: Int? = null
        var opponentScore: Int? = null
        var opponentId = ""
        var wasHome = false
        for (j in 0 until competitors.length()) {
            val competitor = competitors.optJSONObject(j) ?: continue
            val id = competitor.optJSONObject("team")?.optString("id", "") ?: ""
            val score = competitor.optString("score").toIntOrNull() ?: continue
            if (id == teamId) {
                ownScore = score
                wasHome = competitor.optString("homeAway") == "home"
            } else {
                opponentScore = score
                opponentId = id
            }
        }
        if (ownScore != null && opponentScore != null && opponentId.isNotBlank()) {
            val stablePair = listOf(teamId, opponentId).sorted().joinToString(":")
            val eventId = event.optString("id").ifBlank { "${event.optString("date")}:$stablePair" }
            completed += TeamMatchResult(eventId, event.optString("date"), teamId, opponentId, ownScore, opponentScore, wasHome)
        }
    }
    return AdvancedTeamHistory(teamId, completed.sortedByDescending { it.date }.take(40))
}

/** Pull final scores for tracked ESPN fixtures. Other sources remain manually gradeable. */
internal suspend fun syncCompletedPredictionResults(records: List<PredictionRecord>): Map<Long, Pair<Int, Int>> = withContext(Dispatchers.IO) {
    val groups = records.filter { it.fixture.sourceLeagueCode.isNotBlank() && it.fixture.date.length >= 10 }
        .groupBy { it.fixture.sourceLeagueCode to it.fixture.date.take(10) }
    val found = mutableMapOf<Long, Pair<Int, Int>>()
    runCatching { RemoteFeed.fetch() }
    val feedResults = RemoteFeed.cachedResults()
    records.forEach { r -> feedResults[r.fixture.id]?.let { found[r.id] = it } }
    groups.entries.toList().chunked(3).forEach { batch ->
        val results = batch.map { entry -> async(Dispatchers.IO) {
            entry.key to runCatching { fetchEspnCompletedScores(entry.key.first, entry.key.second) }
        } }.awaitAll()
        results.forEach { (key, result) ->
            val scoreMap = result.getOrNull() ?: return@forEach
            groups[key].orEmpty().forEach { record ->
                val finalScore = scoreMap[record.fixture.id]
                    ?: scoreMap.values.firstOrNull { it.homeId == record.fixture.homeTeamId && it.awayId == record.fixture.awayTeamId }
                if (finalScore != null) found[record.id] = finalScore.homeGoals to finalScore.awayGoals
            }
        }
    }
    found
}

private data class EspnFinalScore(val homeId: String, val awayId: String, val homeGoals: Int, val awayGoals: Int)

private fun fetchEspnCompletedScores(leagueCode: String, date: String): Map<Long, EspnFinalScore> {
    val compact = date.replace("-", "")
    val root = JSONObject(readHttp("https://site.api.espn.com/apis/site/v2/sports/soccer/$leagueCode/scoreboard?dates=$compact"))
    val events = root.optJSONArray("events") ?: JSONArray()
    val output = mutableMapOf<Long, EspnFinalScore>()
    for (i in 0 until events.length()) {
        val event = events.optJSONObject(i) ?: continue
        val completed = event.optJSONObject("status")?.optJSONObject("type")?.optBoolean("completed", false) == true
        if (!completed) continue
        val competition = event.optJSONArray("competitions")?.optJSONObject(0) ?: continue
        val competitors = competition.optJSONArray("competitors") ?: continue
        var homeId = ""; var awayId = ""; var homeGoals: Int? = null; var awayGoals: Int? = null
        for (j in 0 until competitors.length()) {
            val c = competitors.optJSONObject(j) ?: continue
            val team = c.optJSONObject("team") ?: continue
            val id = team.optString("id", "")
            val goals = c.optString("score", "").toIntOrNull() ?: continue
            if (c.optString("homeAway") == "home") { homeId = id; homeGoals = goals }
            else if (c.optString("homeAway") == "away") { awayId = id; awayGoals = goals }
        }
        val hg = homeGoals ?: continue; val ag = awayGoals ?: continue
        val eventId = event.optString("id").toLongOrNull() ?: continue
        output[eventId] = EspnFinalScore(homeId, awayId, hg, ag)
    }
    return output
}

internal fun readHttp(urlText: String, headers: Map<String, String> = emptyMap()): String {
    val conn = (URL(urlText).openConnection() as HttpURLConnection).apply {
        requestMethod = "GET"; connectTimeout = 15000; readTimeout = 20000
        headers.forEach { (k, v) -> setRequestProperty(k, v) }
    }
    try {
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        if (code !in 200..299) throw IllegalStateException("HTTP $code")
        return body
    } finally { conn.disconnect() }
}

private fun fetchTheSportsDbDay(date: String): List<Fixture> {
    val body = readHttp("https://www.thesportsdb.com/api/v1/json/123/eventsday.php?d=$date&s=Soccer")
    val events = JSONObject(body).optJSONArray("events") ?: return emptyList()
    return (0 until events.length()).mapNotNull { i ->
        val e = events.optJSONObject(i) ?: return@mapNotNull null
        val home = e.optString("strHomeTeam").takeIf { it.isNotBlank() && it != "null" } ?: return@mapNotNull null
        val away = e.optString("strAwayTeam").takeIf { it.isNotBlank() && it != "null" } ?: return@mapNotNull null
        val eventId = e.optString("idEvent").toLongOrNull() ?: Ids.stable(home, away, date)
        val time = e.optString("strTime").takeIf { it.isNotBlank() && it != "null" } ?: ""
        Fixture(eventId, home, away, e.optString("strLeague", "كرة القدم"), "$date $time".trim())
    }
}

private fun fetchFootballDataFixtures(token: String, date: String): List<Fixture> {
    val body = readHttp("https://api.football-data.org/v4/matches?dateFrom=$date&dateTo=$date", mapOf("X-Auth-Token" to token))
    val matches = JSONObject(body).optJSONArray("matches") ?: return emptyList()
    return (0 until matches.length()).mapNotNull { i ->
        val m = matches.optJSONObject(i) ?: return@mapNotNull null
        val home = m.optJSONObject("homeTeam")?.optString("name")?.takeIf { !it.isNullOrBlank() && it != "null" } ?: return@mapNotNull null
        val away = m.optJSONObject("awayTeam")?.optString("name")?.takeIf { !it.isNullOrBlank() && it != "null" } ?: return@mapNotNull null
        val comp = m.optJSONObject("competition")?.optString("name") ?: ""
        val utc = m.optString("utcDate").take(16).replace("T", " ")
        val id = m.optLong("id", Ids.stable(home, away, date))
        Fixture(id, home, away, comp, utc)
    }
}

private fun slipText(title: String, stake: Double, picks: List<Selection>): String {
    val odds = picks.fold(1.0) { a, s -> a * s.odds }; val payout = odds * stake
    return buildString { appendLine("BET SLIP PRO | $title"); appendLine("────────────────────"); picks.forEachIndexed { i, s -> appendLine("${i + 1}. ${s.fixture.home} vs ${s.fixture.away}"); appendLine("   ${s.market}: ${s.pick} | Odds: ${s.odds}") }; appendLine("────────────────────"); appendLine("Total odds: ${String.format(Locale.US, "%.2f", odds)}"); appendLine("Stake: ${String.format(Locale.US, "%.0f", stake)}"); appendLine("Potential return: ${String.format(Locale.US, "%.0f", payout)}"); appendLine("For entertainment and estimation only.") }
}
private fun shareText(context: android.content.Context, text: String) { val i = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, text) }; context.startActivity(Intent.createChooser(i, "مشاركة القسيمة")) }

private fun exportPng(context: android.content.Context, title: String, stake: Double, picks: List<Selection>) {
    try {
        val width = 1080
        val height = (360 + picks.size * 150).coerceAtMost(3000)
        val bitmap = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        canvas.drawColor(android.graphics.Color.rgb(11, 17, 24))
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.rgb(32, 201, 151); textSize = 46f; isFakeBoldText = true }
        canvas.drawText("BET SLIP PRO", 60f, 90f, paint)
        paint.color = android.graphics.Color.WHITE; paint.textSize = 34f; paint.isFakeBoldText = true
        canvas.drawText(title, 60f, 155f, paint)
        paint.textSize = 27f; paint.isFakeBoldText = false; paint.color = android.graphics.Color.LTGRAY
        var y = 225f
        picks.forEachIndexed { i, s ->
            canvas.drawRoundRect(40f, y - 45f, 1040f, y + 80f, 22f, 22f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.rgb(20, 31, 43) })
            paint.color = android.graphics.Color.WHITE
            canvas.drawText("${i + 1}. ${s.fixture.home}  vs  ${s.fixture.away}", 70f, y, paint)
            paint.color = android.graphics.Color.rgb(32, 201, 151)
            canvas.drawText("${s.market}: ${s.pick}     Odds: ${s.odds}", 70f, y + 48f, paint)
            y += 150f
        }
        val total = picks.fold(1.0) { a, s -> a * s.odds }
        paint.color = android.graphics.Color.WHITE; paint.textSize = 30f
        canvas.drawText("Total odds: ${String.format(Locale.US, "%.2f", total)}", 60f, y + 10f, paint)
        canvas.drawText("Stake: ${String.format(Locale.US, "%.0f", stake)}", 60f, y + 60f, paint)
        paint.color = android.graphics.Color.rgb(32, 201, 151); paint.isFakeBoldText = true
        canvas.drawText("Potential return: ${String.format(Locale.US, "%.0f", total * stake)}", 60f, y + 115f, paint)
        val file = java.io.File(context.cacheDir, "bet-slip-${System.currentTimeMillis()}.png")
        file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        val uri = androidx.core.content.FileProvider.getUriForFile(context, context.packageName + ".provider", file)
        val intent = Intent(Intent.ACTION_SEND).apply { type = "image/png"; putExtra(Intent.EXTRA_STREAM, uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        context.startActivity(Intent.createChooser(intent, "مشاركة صورة القسيمة"))
    } catch (e: Exception) { shareText(context, "تعذر إنشاء الصورة: ${e.message}") }
}

private fun exportPdf(context: android.content.Context, title: String, stake: Double, picks: List<Selection>) {
    try {
        val doc = PdfDocument(); val page = doc.startPage(PdfDocument.PageInfo.Builder(595, 842, 1).create()); val c = page.canvas; val p = Paint().apply { color = android.graphics.Color.rgb(20, 40, 52); textSize = 22f; isFakeBoldText = true }; c.drawText("BET SLIP PRO", 40f, 55f, p)
        p.textSize = 16f; p.color = android.graphics.Color.DKGRAY; c.drawText(title, 40f, 90f, p); var y = 135f; p.textSize = 13f
        picks.forEachIndexed { i, s -> c.drawText("${i + 1}. ${s.fixture.home} vs ${s.fixture.away}", 40f, y, p); y += 20; c.drawText("${s.market}: ${s.pick}     Odds: ${s.odds}", 55f, y, p); y += 28 }
        val odds = picks.fold(1.0) { a, s -> a * s.odds }; y += 10; c.drawText("Total odds: ${String.format(Locale.US, "%.2f", odds)}", 40f, y, p); y += 24; c.drawText("Stake: ${String.format(Locale.US, "%.0f", stake)}", 40f, y, p); y += 24; c.drawText("Potential return: ${String.format(Locale.US, "%.0f", odds * stake)}", 40f, y, p); y += 40; p.textSize = 10f; c.drawText("Estimates only. No outcome is guaranteed.", 40f, y, p)
        doc.finishPage(page); val file = java.io.File(context.cacheDir, "bet-slip-${System.currentTimeMillis()}.pdf"); file.outputStream().use { doc.writeTo(it) }; doc.close()
        val uri: Uri = androidx.core.content.FileProvider.getUriForFile(context, context.packageName + ".provider", file)
        val intent = Intent(Intent.ACTION_SEND).apply { type = "application/pdf"; putExtra(Intent.EXTRA_STREAM, uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }; context.startActivity(Intent.createChooser(intent, "مشاركة PDF"))
    } catch (e: Exception) { shareText(context, "تعذر إنشاء PDF: ${e.message}") }
}
