package com.wardriver.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IngestJsonTest {
    private fun rec(
        bssid: String = "70:aa:bb:00:00:01", ssid: String = "Loja", hidden: Boolean = false, channel: Int = 6, rssi: Int = -60,
        enc: String = "WPA2", seen: Long = 3, wps: Boolean = false, lat: Double? = -10.2, lon: Double? = -50.3,
        session: String = "s-1", last: Long = 1_760_000_000_000,
    ) = NetworkRecord(bssid, ssid, hidden, channel, rssi, enc, seen, wps, lat, lon, session, last)

    @Suppress("UNCHECKED_CAST")
    private fun parseNet(r: NetworkRecord): Map<String, Any?> =
        ((MiniJson.parse(IngestJson.encode("tel", listOf(r))) as Map<String, Any?>)["networks"] as List<Map<String, Any?>>)[0]

    @Test fun `campos e tipos batem com o schema do backend`() {
        val n = parseNet(rec())
        assertEquals("70:AA:BB:00:00:01", n["bssid"])
        assertEquals("Loja", n["ssid"])
        assertEquals(false, n["hidden"])
        assertEquals(6L, n["channel"])
        assertEquals(-60L, n["rssi"])
        assertEquals("WPA2", n["enc"])
        assertEquals(3L, n["seen"])
        assertEquals(false, n["wps"])
        assertEquals(-10.2, n["lat"])
        assertEquals(-50.3, n["lon"])
        assertEquals("s-1", n["session"])
        assertEquals(1_760_000_000_000L, n["last"])
        assertEquals(
            setOf("bssid", "ssid", "hidden", "channel", "rssi", "enc", "seen", "wps", "lat", "lon", "session", "last"), n.keys,
        )
    }

    @Test fun `envelope leva o dispositivo e a lista`() {
        @Suppress("UNCHECKED_CAST")
        val root = MiniJson.parse(IngestJson.encode("celular-1", listOf(rec(), rec(bssid = "70:AA:BB:00:00:02")))) as Map<String, Any?>
        assertEquals("celular-1", root["device"])
        assertEquals(2, (root["networks"] as List<*>).size)
    }

    @Test fun `SSID hostil e escapado e volta identico`() {
        val nasty = "aspas\" barra\\ nl\n cr\r tab\t nul\u0000 ctrl\u001f sep   </script> emoji 😀 acento é"
        assertEquals(nasty, parseNet(rec(ssid = nasty))["ssid"])
    }

    @Test fun `surrogate solto vira caractere de substituicao (o servidor recusaria)`() {
        assertEquals("a�b�", parseNet(rec(ssid = "a\uD800b\uDC00"))["ssid"])
    }

    @Test fun `rede oculta vai com nome vazio e hidden true`() {
        val n = parseNet(rec(ssid = "Nome que sobrou", hidden = true))
        assertEquals("", n["ssid"]); assertEquals(true, n["hidden"])
        val m = parseNet(rec(ssid = ""))
        assertEquals(true, m["hidden"])
    }

    @Test fun `sem fix vira null e zero-zero tambem`() {
        for (r in listOf(rec(lat = null, lon = null), rec(lat = 0.0, lon = 0.0), rec(lat = -10.0, lon = null),
            rec(lat = Double.NaN, lon = 1.0), rec(lat = 91.0, lon = 1.0), rec(lat = 1.0, lon = 200.0))) {
            val n = parseNet(r)
            assertNull(n["lat"]); assertNull(n["lon"])
        }
    }

    @Test fun `valores fora da faixa sao limitados como o servidor exige`() {
        val n = parseNet(rec(channel = 900, rssi = 20, seen = 0, last = -5, enc = "WPA9"))
        assertEquals(196L, n["channel"]); assertEquals(0L, n["rssi"]); assertEquals(1L, n["seen"])
        assertEquals(0L, n["last"]); assertEquals("UNKNOWN", n["enc"])
    }

    @Test fun `campos longos sao truncados`() {
        val n = parseNet(rec(session = "x".repeat(200)))
        assertEquals(64, (n["session"] as String).length)
    }

    @Test fun `numero muito pequeno continua JSON valido`() {
        val json = IngestJson.encode("t", listOf(rec(lat = 1e-9, lon = -1.5e-7)))
        assertFalse(json.contains("NaN")); assertFalse(json.contains("Infinity"))
        assertTrue(MiniJson.parse(json) != null)
    }
}
