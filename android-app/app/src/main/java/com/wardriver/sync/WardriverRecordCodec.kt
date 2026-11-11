package com.wardriver.sync

import java.nio.ByteBuffer
import java.nio.ByteOrder

object WardriverRecordCodec {
    const val SIZE = 71

    const val LIVE_RECORD_MARKER: Byte = 0x01

    private const val SSID_LEN = 33

    private val ENCRYPTION_NAMES = arrayOf("OPEN", "WEP", "WPA", "WPA2", "WPA3")

    private const val FLAG_SSID_HIDDEN = 1 shl 0
    private const val FLAG_HAS_FIX = 1 shl 1
    private const val FLAG_WPS_PRESENT = 1 shl 2

    fun decode(bytes: ByteArray, sessionId: String): NetworkRecord {
        require(bytes.size == SIZE) { "registro binario com tamanho invalido: ${bytes.size} (esperado $SIZE)" }
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

        val bssidBytes = ByteArray(6)
        buf.get(bssidBytes)
        val bssid = bssidBytes.joinToString(":") { "%02X".format(it) }

        val ssidBytes = ByteArray(SSID_LEN)
        buf.get(ssidBytes)
        val ssidEnd = ssidBytes.indexOf(0).let { if (it < 0) SSID_LEN else it }
        val ssid = String(ssidBytes, 0, ssidEnd, Charsets.UTF_8)

        val flags = buf.get().toInt() and 0xFF
        val ssidHidden = (flags and FLAG_SSID_HIDDEN) != 0
        val hasFix = (flags and FLAG_HAS_FIX) != 0
        val wpsPresent = (flags and FLAG_WPS_PRESENT) != 0

        val channel = buf.get().toInt() and 0xFF
        val rssi = buf.get().toInt()
        val encryptionCode = buf.get().toInt() and 0xFF

        require(channel in 1..14) { "canal fora da faixa: $channel" }
        require(rssi in -100..0) { "RSSI fora da faixa: $rssi" }
        require(encryptionCode in ENCRYPTION_NAMES.indices) { "encryption invalida: $encryptionCode" }

        val timesSeen = buf.int.toLong() and 0xFFFFFFFFL
        buf.short
        val latE7 = buf.int
        val lonE7 = buf.int
        buf.short
        buf.short
        buf.int
        buf.get()
        buf.get()
        buf.int

        return NetworkRecord(
            bssid = bssid,
            ssid = ssid,
            ssidHidden = ssidHidden,
            channel = channel,
            rssi = rssi,
            encryption = ENCRYPTION_NAMES.getOrElse(encryptionCode) { "UNKNOWN" },
            timesSeen = timesSeen,
            wpsActive = wpsPresent,
            lat = if (hasFix) latE7 / 1e7 else null,
            lon = if (hasFix) lonE7 / 1e7 else null,
            lastSessionId = sessionId,
            lastSeenAtMs = System.currentTimeMillis(),
        )
    }
}
