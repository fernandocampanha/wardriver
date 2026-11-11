package com.wardriver.sync

object NetworkMerge {

    const val UNKNOWN_ENCRYPTION = "UNKNOWN"

    fun hasName(r: NetworkRecord): Boolean = r.ssid.isNotEmpty() && !r.ssidHidden
    fun hasLocation(r: NetworkRecord): Boolean = r.lat != null && r.lon != null

    fun mergeCapture(existing: NetworkRecord?, incoming: NetworkRecord): NetworkRecord {
        if (existing == null) return incoming

        val name = when {
            hasName(incoming) -> incoming.ssid
            hasName(existing) -> existing.ssid
            else -> ""
        }

        val takeIncomingFix = hasLocation(incoming) && (!hasLocation(existing) || incoming.rssi > existing.rssi)
        val keepExistingFix = !takeIncomingFix && hasLocation(existing)
        val lat = if (takeIncomingFix) incoming.lat else if (keepExistingFix) existing.lat else null
        val lon = if (takeIncomingFix) incoming.lon else if (keepExistingFix) existing.lon else null
        val rssi = when {
            takeIncomingFix -> incoming.rssi
            keepExistingFix -> existing.rssi
            else -> maxOf(existing.rssi, incoming.rssi)
        }

        return NetworkRecord(
            bssid = existing.bssid,
            ssid = name,
            ssidHidden = name.isEmpty(),
            channel = if (incoming.channel > 0) incoming.channel else existing.channel,
            rssi = rssi,
            encryption = if (incoming.encryption != UNKNOWN_ENCRYPTION) incoming.encryption else existing.encryption,
            timesSeen = maxOf(existing.timesSeen, incoming.timesSeen),
            wpsActive = existing.wpsActive || incoming.wpsActive,
            lat = lat,
            lon = lon,
            lastSessionId = incoming.lastSessionId,
            lastSeenAtMs = incoming.lastSeenAtMs,
        )
    }

    fun mergeObservations(existing: NetworkRecord?, incoming: NetworkRecord): NetworkRecord {
        if (existing == null) return incoming.copy()

        val name = when {
            hasName(existing) && hasName(incoming) ->
                if (incoming.lastSeenAtMs > existing.lastSeenAtMs) incoming.ssid else existing.ssid
            hasName(existing) -> existing.ssid
            hasName(incoming) -> incoming.ssid
            else -> ""
        }

        val useIncomingFix = when {
            !hasLocation(incoming) -> false
            !hasLocation(existing) -> true
            else -> incoming.rssi > existing.rssi
        }
        val lat = if (useIncomingFix) incoming.lat else existing.lat
        val lon = if (useIncomingFix) incoming.lon else existing.lon
        val rssi = when {
            hasLocation(existing) || hasLocation(incoming) ->
                if (useIncomingFix) incoming.rssi else existing.rssi
            else -> maxOf(existing.rssi, incoming.rssi)
        }

        val newer = if (incoming.lastSeenAtMs > existing.lastSeenAtMs) incoming else existing

        return NetworkRecord(
            bssid = existing.bssid,
            ssid = name,
            ssidHidden = name.isEmpty(),
            channel = if (existing.channel > 0) existing.channel else incoming.channel,
            rssi = rssi,
            encryption = if (existing.encryption != UNKNOWN_ENCRYPTION) existing.encryption else incoming.encryption,
            timesSeen = maxOf(existing.timesSeen, incoming.timesSeen),
            wpsActive = existing.wpsActive || incoming.wpsActive,
            lat = lat,
            lon = lon,
            lastSessionId = newer.lastSessionId,
            lastSeenAtMs = newer.lastSeenAtMs,
        )
    }
}
