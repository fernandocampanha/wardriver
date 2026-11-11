package com.wardriver.sync

object IngestJson {

    private val ENCRYPTIONS = setOf("OPEN", "WEP", "WPA", "WPA2", "WPA3", "UNKNOWN")

    fun encode(device: String, records: List<NetworkRecord>): String {
        val sb = StringBuilder(records.size * 200 + 64)
        sb.append("{\"device\":")
        quote(device.take(64), sb)
        sb.append(",\"networks\":[")
        records.forEachIndexed { i, r ->
            if (i > 0) sb.append(',')
            network(r, sb)
        }
        sb.append("]}")
        return sb.toString()
    }

    private fun network(r: NetworkRecord, sb: StringBuilder) {
        val unnamed = r.ssidHidden || r.ssid.isEmpty()
        val lat = r.lat
        val lon = r.lon
        val hasFix = lat != null && lon != null && lat.isFinite() && lon.isFinite() &&
            lat in -90.0..90.0 && lon in -180.0..180.0 && !(lat == 0.0 && lon == 0.0)
        sb.append("{\"bssid\":"); quote(r.bssid.uppercase(), sb)
        sb.append(",\"ssid\":"); quote(if (unnamed) "" else r.ssid, sb)
        sb.append(",\"hidden\":").append(unnamed)
        sb.append(",\"channel\":").append(r.channel.coerceIn(0, 196))
        sb.append(",\"rssi\":").append(r.rssi.coerceIn(-127, 0))
        sb.append(",\"enc\":"); quote(if (r.encryption in ENCRYPTIONS) r.encryption else "UNKNOWN", sb)
        sb.append(",\"seen\":").append(r.timesSeen.coerceIn(1L, 2_000_000_000L))
        sb.append(",\"wps\":").append(r.wpsActive)
        sb.append(",\"lat\":").append(if (hasFix) lat.toString() else "null")
        sb.append(",\"lon\":").append(if (hasFix) lon.toString() else "null")
        sb.append(",\"session\":"); quote(r.lastSessionId.take(64), sb)
        sb.append(",\"last\":").append(r.lastSeenAtMs.coerceAtLeast(0L))
        sb.append('}')
    }

    internal fun quote(s: String, sb: StringBuilder) {
        sb.append('"')
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c == '"' -> sb.append("\\\"")
                c == '\\' -> sb.append("\\\\")
                c == '\n' -> sb.append("\\n")
                c == '\r' -> sb.append("\\r")
                c == '\t' -> sb.append("\\t")
                c.code < 0x20 || c == ' ' || c == ' ' -> sb.append(String.format("\\u%04x", c.code))
                Character.isHighSurrogate(c) ->
                    if (i + 1 < s.length && Character.isLowSurrogate(s[i + 1])) {
                        sb.append(c).append(s[i + 1])
                        i++
                    } else sb.append('�')
                Character.isLowSurrogate(c) -> sb.append('�')
                else -> sb.append(c)
            }
            i++
        }
        sb.append('"')
    }
}
