package com.wardriver.sync

import java.io.BufferedReader
import java.io.Reader
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone





















object CsvImporter {

    data class ParseResult(

        val records: List<NetworkRecord>,

        val rowsRead: Int,

        val rowsSkipped: Int,

        val layout: String,
    )


    private val ALIASES: Map<String, Set<String>> = mapOf(
        "bssid" to setOf("bssid", "mac", "macaddress", "address"),
        "ssid" to setOf("ssid", "essid", "name", "networkname", "nome", "rede"),
        "hidden" to setOf("ssidhidden", "hidden", "oculta", "oculto"),
        "channel" to setOf("channel", "ch", "canal"),
        "freq" to setOf("frequency", "freq"),
        "rssi" to setOf("rssi", "signal", "signaldbm", "level", "dbm"),
        "encryption" to setOf("encryption", "security", "auth", "authmode", "capabilities", "criptografia"),
        "seen" to setOf("timesseen", "seen", "count", "vistas"),
        "wps" to setOf("wpsactive", "wps"),
        "lat" to setOf("lat", "latitude", "currentlatitude", "bestlat", "trilat"),
        "lon" to setOf("lon", "lng", "long", "longitude", "currentlongitude", "bestlon", "trilong"),
        "session" to setOf("lastsessionid", "sessionid", "session"),
        "time" to setOf("lastseenatms", "lastseen", "timestamp", "time", "firstseen", "seenat", "date"),
    )


    private val LEGACY_COLUMNS = mapOf(
        "bssid" to 0, "ssid" to 1, "channel" to 3, "rssi" to 4, "lat" to 5, "lon" to 6, "time" to 7,
    )
    private const val LEGACY_MIN_COLUMNS = 5

    private val HIDDEN_LITERALS = setOf("hidden", "<hidden>", "(hidden)", "(oculta)", "<oculta>", "oculta")
    private const val MIN_PLAUSIBLE_EPOCH_S = 946_684_800L

    private val BSSID_REGEX = Regex("^(?:[0-9A-Fa-f]{2}([:-])(?:[0-9A-Fa-f]{2}\\1){4}[0-9A-Fa-f]{2}|[0-9A-Fa-f]{12})$")

    fun parse(input: Reader, sessionTag: String = "import"): ParseResult {
        val reader = BufferedReader(input, 1 shl 16)
        val delimiter = detectDelimiter(reader)

        var columns: Map<String, Int>? = null
        var layout = "generico"
        var firstRow = true
        var rowsRead = 0
        var rowsSkipped = 0
        val merged = LinkedHashMap<String, NetworkRecord>()

        readRows(reader, delimiter) { rawRow ->
            var row = rawRow
            if (firstRow) {
                row = listOf(row[0].removePrefix("\uFEFF")) + row.drop(1)
                firstRow = false
            }

            if (columns == null) {

                if (row[0].trim().startsWith("WigleWifi", ignoreCase = true)) {
                    layout = "WiGLE"
                    return@readRows
                }

                if (looksLikeBssid(row[0])) {



                    require(
                        row.size >= LEGACY_MIN_COLUMNS &&
                            row[3].trim().toIntOrNull() in 1..196 &&
                            row[4].trim().toIntOrNull() in -127..0
                    ) { "CSV sem cabecalho em formato desconhecido (esperado: bssid,ssid,n,canal,rssi,lat,lon)" }
                    columns = LEGACY_COLUMNS
                    layout = "CSV antigo do ESP32 (sem cabecalho)"
                } else {
                    val header = buildHeader(row)
                    require("bssid" in header) { "CSV sem coluna de BSSID/MAC (colunas: ${row.joinToString()})" }
                    columns = header
                    if (layout != "WiGLE") {
                        layout = if (row.any { it.trim().equals("last_session_id", true) }) "Wardriver Sync" else "generico"
                    }
                    return@readRows
                }
            }

            val cols = columns ?: return@readRows
            rowsRead++
            val record = rowToRecord(row, cols, sessionTag, layout == "WiGLE")
            if (record == null) {
                rowsSkipped++
            } else {
                merged[record.bssid] = NetworkMerge.mergeObservations(merged[record.bssid], record)
            }
        }

        return ParseResult(merged.values.toList(), rowsRead, rowsSkipped, layout)
    }





    private fun rowToRecord(
        row: List<String>,
        cols: Map<String, Int>,
        sessionTag: String,
        isWigle: Boolean,
    ): NetworkRecord? {
        fun field(key: String): String? = cols[key]?.let { row.getOrNull(it) }?.trim()?.takeIf { it.isNotEmpty() }

        val bssid = normalizeBssid(field("bssid") ?: return null) ?: return null

        val hiddenColumn = field("hidden")
        val rawSsid = (cols["ssid"]?.let { row.getOrNull(it) } ?: "").replace("\u0000", "")
        val literalHidden = hiddenColumn == null && rawSsid.trim().lowercase(Locale.ROOT) in HIDDEN_LITERALS
        val hidden = when {
            hiddenColumn != null -> parseBool(hiddenColumn) || rawSsid.isEmpty()
            else -> literalHidden || rawSsid.isBlank()
        }
        val ssid = if (hidden) "" else rawSsid

        val channel = field("channel")?.toDoubleOrNull()?.toInt()
            ?: field("freq")?.let { channelFromFrequency(it) }
            ?: 0

        val rssi = field("rssi")?.toDoubleOrNull()?.toInt()?.takeIf { it in -127..0 } ?: DEFAULT_RSSI

        val authRaw = field("encryption") ?: ""
        val encryption = parseEncryption(authRaw)

        val lat = parseCoordinate(field("lat"), 90.0)
        val lon = parseCoordinate(field("lon"), 180.0)
        val hasFix = lat != null && lon != null && !(lat == 0.0 && lon == 0.0)

        return NetworkRecord(
            bssid = bssid,
            ssid = ssid,
            ssidHidden = ssid.isEmpty(),
            channel = channel,
            rssi = rssi,
            encryption = encryption,
            timesSeen = field("seen")?.toLongOrNull()?.coerceAtLeast(1) ?: 1L,
            wpsActive = (field("wps")?.let { parseBool(it) } ?: false) ||
                (isWigle && authRaw.uppercase(Locale.ROOT).contains("WPS")),
            lat = if (hasFix) lat else null,
            lon = if (hasFix) lon else null,
            lastSessionId = field("session") ?: sessionTag,
            lastSeenAtMs = parseTime(field("time")) ?: UNKNOWN_TIME_MS,
        )
    }

    const val DEFAULT_RSSI = -100



    const val UNKNOWN_TIME_MS = 0L

    fun looksLikeBssid(text: String): Boolean = BSSID_REGEX.matches(text.trim())


    fun normalizeBssid(text: String): String? {
        val t = text.trim()
        if (!BSSID_REGEX.matches(t)) return null
        val hex = t.filter { it.isLetterOrDigit() }.uppercase(Locale.ROOT)
        return hex.chunked(2).joinToString(":")
    }

    fun parseEncryption(raw: String): String {
        val s = raw.uppercase(Locale.ROOT).trim()
        return when {
            s.isEmpty() -> NetworkMerge.UNKNOWN_ENCRYPTION
            s.contains("WPA3") || s.contains("SAE") -> "WPA3"
            s.contains("WPA2") || s.contains("RSN") -> "WPA2"
            s.contains("WPA") -> "WPA"
            s.contains("WEP") -> "WEP"
            s.contains("OPEN") || s.contains("NONE") || s == "[ESS]" || s == "ESS" -> "OPEN"
            else -> NetworkMerge.UNKNOWN_ENCRYPTION
        }
    }

    private fun parseBool(raw: String): Boolean =
        raw.trim().lowercase(Locale.ROOT) in setOf("true", "1", "yes", "y", "sim", "s", "t")


    private fun parseCoordinate(raw: String?, limit: Double): Double? {
        val v = raw?.replace(',', '.')?.toDoubleOrNull() ?: return null
        return v.takeIf { it.isFinite() && kotlin.math.abs(it) <= limit }
    }

    private fun channelFromFrequency(raw: String): Int? {
        val f = raw.toDoubleOrNull()?.toInt() ?: return null
        return when (f) {
            2484 -> 14
            in 2412..2472 -> (f - 2407) / 5
            in 5000..5900 -> (f - 5000) / 5
            else -> null
        }
    }


    private fun parseTime(raw: String?): Long? {
        if (raw == null) return null
        raw.toLongOrNull()?.let { n ->
            return when {
                n > 100_000_000_000L -> n
                n >= MIN_PLAUSIBLE_EPOCH_S -> n * 1000
                else -> null
            }
        }
        for (pattern in DATE_PATTERNS) {
            try {
                val fmt = SimpleDateFormat(pattern, Locale.US)
                fmt.timeZone = TimeZone.getTimeZone("UTC")
                fmt.isLenient = false
                val t = fmt.parse(raw)?.time
                if (t != null && t / 1000 >= MIN_PLAUSIBLE_EPOCH_S) return t
            } catch (_: java.text.ParseException) {

            }
        }
        return null
    }

    private val DATE_PATTERNS = listOf(
        "yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd'T'HH:mm:ss'Z'", "yyyy-MM-dd'T'HH:mm:ss", "yyyy-MM-dd",
    )





    private fun buildHeader(row: List<String>): Map<String, Int> {
        val map = HashMap<String, Int>()
        row.forEachIndexed { index, name ->
            val key = name.lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }
            for ((canonical, names) in ALIASES) {


                if (key in names && canonical !in map) map[canonical] = index
            }
        }
        return map
    }


    private fun detectDelimiter(reader: BufferedReader): Char {
        reader.mark(1 shl 15)
        val sample = CharArray(8192)
        val n = reader.read(sample)
        reader.reset()
        if (n <= 0) return ','

        val counts = mutableMapOf(',' to 0, ';' to 0, '\t' to 0)
        var inQuotes = false
        for (i in 0 until n) {
            val c = sample[i]
            if (c == '"') inQuotes = !inQuotes
            else if (!inQuotes && c in counts) counts[c] = counts.getValue(c) + 1
        }
        return counts.maxByOrNull { it.value }?.takeIf { it.value > 0 }?.key ?: ','
    }


    private fun readRows(reader: Reader, delimiter: Char, onRow: (List<String>) -> Unit) {
        val fields = ArrayList<String>()
        val current = StringBuilder()
        var inQuotes = false
        var pendingQuote = false

        fun endField() {
            fields.add(current.toString())
            current.setLength(0)
        }

        fun endRow() {
            endField()
            if (fields.size > 1 || fields[0].isNotEmpty()) onRow(ArrayList(fields))
            fields.clear()
        }

        var c = reader.read()
        var sawAny = false
        while (c != -1) {
            val ch = c.toChar()
            sawAny = true
            if (pendingQuote) {
                pendingQuote = false
                if (ch == '"') {
                    current.append('"')
                    c = reader.read()
                    continue
                }
                inQuotes = false
            }
            when {
                inQuotes -> if (ch == '"') pendingQuote = true else current.append(ch)
                ch == '"' && current.isEmpty() -> inQuotes = true
                ch == delimiter -> endField()
                ch == '\n' -> endRow()
                ch == '\r' -> {  }
                else -> current.append(ch)
            }
            c = reader.read()
        }
        if (sawAny && (fields.isNotEmpty() || current.isNotEmpty() || pendingQuote)) endRow()
    }
}
