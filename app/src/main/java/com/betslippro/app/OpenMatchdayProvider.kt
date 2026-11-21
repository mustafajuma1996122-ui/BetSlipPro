package com.betslippro.app

import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Callable
import java.util.concurrent.Executors

/**
 * Keyless public football-data provider.
 * Source: oladeeayo/open-matchday (75 competitions; daily CSV refresh).
 * This dataset is delayed/daily, not a live-score or bookmaker-odds feed.
 */
data class OpenMatchdayFixture(
    val home: String,
    val away: String,
    val league: String,
    val date: String,
    val time: String,
    val folder: String
)

object OpenMatchdayProvider {
    private const val RAW = "https://raw.githubusercontent.com/oladeeayo/open-matchday/master"
    private const val INDEX_URL = "$RAW/LEAGUE_INDEX.md"

    fun fetchFixtures(date: String, cacheDir: File): Result<List<OpenMatchdayFixture>> = runCatching {
        require(date.matches(Regex("\\d{4}-\\d{2}-\\d{2}"))) { "Invalid date" }
        val cache = File(cacheDir, "open_matchday").apply { mkdirs() }
        val indexFile = File(cache, "league_index.md")
        val index = cachedText(INDEX_URL, indexFile, 24 * 60 * 60 * 1000L)
        val leagues = parseLeagueIndex(index)
        require(leagues.isNotEmpty()) { "League index is empty or has changed format" }

        val pool = Executors.newFixedThreadPool(8)
        try {
            val jobs = leagues.map { league -> Callable {
                runCatching {
                    val season = seasonCode(date, league.folder)
                    val file = File(cache, "${league.folder}_${season}_fixtures.csv")
                    val url = "$RAW/data/${league.folder}/fixtures_${season}.csv"
                    val csv = cachedText(url, file, 8 * 60 * 60 * 1000L)
                    parseFixtures(csv, league.name, league.folder, date)
                }.getOrDefault(emptyList())
            } }
            val rows = pool.invokeAll(jobs).flatMap { it.get() }
            rows.distinctBy { "${it.date}|${it.home.trim().lowercase()}|${it.away.trim().lowercase()}" }
        } finally {
            pool.shutdownNow()
        }
    }

    internal data class League(val folder: String, val name: String)

    internal fun parseLeagueIndex(markdown: String): List<League> = markdown.lineSequence().mapNotNull { line ->
        if (!line.trimStart().startsWith("|")) return@mapNotNull null
        val cells = line.trim().trim('|').split('|').map { it.trim() }
        if (cells.size < 2) return@mapNotNull null
        val folder = cells[0].removePrefix("`").removeSuffix("`").trim()
        val name = cells[1].trim()
        if (!folder.matches(Regex("[A-Z0-9_]+_[a-z0-9-]+")) || name.isBlank() || name == "League") null
        else League(folder, name)
    }.distinctBy { it.folder }.toList()

    internal fun seasonCode(date: String, folder: String): String {
        val year = date.substring(0, 4).toInt()
        val month = date.substring(5, 7).toInt()
        val prefix = folder.substringBefore('_')
        val calendarYear = prefix in setOf("BRA", "ARG", "MEX", "USA", "JPN", "CHN")
        if (calendarYear) return year.toString()
        val start = if (month >= 7) year else year - 1
        return "%02d%02d".format(start % 100, (start + 1) % 100)
    }

    internal fun parseFixtures(csv: String, league: String, folder: String, targetDate: String): List<OpenMatchdayFixture> {
        val lines = csv.lineSequence().filter { it.isNotBlank() }.toList()
        if (lines.size < 2) return emptyList()
        val header = parseCsvLine(lines.first()).map { it.trim().lowercase() }
        val dateIndex = header.indexOf("date")
        val homeIndex = header.indexOf("home")
        val awayIndex = header.indexOf("away")
        val timeIndex = header.indexOf("time")
        if (dateIndex < 0 || homeIndex < 0 || awayIndex < 0) return emptyList()
        return lines.drop(1).mapNotNull { line ->
            val cells = parseCsvLine(line)
            fun value(index: Int) = cells.getOrNull(index)?.trim().orEmpty()
            val matchDate = value(dateIndex).take(10)
            val home = value(homeIndex)
            val away = value(awayIndex)
            if (matchDate != targetDate || home.isBlank() || away.isBlank()) null
            else OpenMatchdayFixture(home, away, league, matchDate, if (timeIndex >= 0) value(timeIndex) else "", folder)
        }
    }

    internal fun parseCsvLine(line: String): List<String> {
        val out = mutableListOf<String>()
        val cell = StringBuilder()
        var quoted = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                c == '"' && quoted && i + 1 < line.length && line[i + 1] == '"' -> { cell.append('"'); i++ }
                c == '"' -> quoted = !quoted
                c == ',' && !quoted -> { out += cell.toString(); cell.setLength(0) }
                else -> cell.append(c)
            }
            i++
        }
        out += cell.toString()
        return out
    }

    private fun cachedText(url: String, file: File, ttlMs: Long): String {
        if (file.exists() && System.currentTimeMillis() - file.lastModified() < ttlMs) {
            return file.readText(Charsets.UTF_8)
        }
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 9000
            readTimeout = 14000
            setRequestProperty("User-Agent", "BET-SLIP-PRO-Android/2.0")
            setRequestProperty("Accept", "text/plain,*/*")
        }
        try {
            val code = connection.responseCode
            if (code !in 200..299) throw IllegalStateException("HTTP $code")
            val body = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            file.parentFile?.mkdirs()
            file.writeText(body, Charsets.UTF_8)
            return body
        } finally {
            connection.disconnect()
        }
    }
}
