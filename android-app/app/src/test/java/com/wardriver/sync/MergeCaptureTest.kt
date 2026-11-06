package com.wardriver.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MergeCaptureTest {

    private fun cap(
        ssid: String = "Casa", rssi: Int = -70, lat: Double? = null, lon: Double? = null, seen: Long = 1,
        enc: String = "WPA2", channel: Int = 6, session: String = "s1", at: Long = 1000L, wps: Boolean = false,
    ) = NetworkRecord("AA:BB:CC:DD:EE:01", ssid, ssid.isEmpty(), channel, rssi, enc, seen, wps, lat, lon, session, at)

    private fun merge(a: NetworkRecord?, b: NetworkRecord) = NetworkMerge.mergeCapture(a, b)

    @Test
    fun `primeira captura entra como veio`() {
        val c = cap(lat = -21.0, lon = -48.0)
        assertEquals(c, merge(null, c))
    }

    @Test
    fun `captura mais forte assume posicao e rssi`() {
        val m = merge(cap(rssi = -85, lat = -21.0, lon = -48.0), cap(rssi = -60, lat = -21.5, lon = -48.5, at = 2000))
        assertEquals(-60, m.rssi)
        assertEquals(-21.5, m.lat!!, 1e-9)
        assertEquals(-48.5, m.lon!!, 1e-9)
    }

    @Test
    fun `captura mais fraca depois NAO sobrescreve posicao nem rssi, mas atualiza o resto`() {
        val m = merge(
            cap(rssi = -60, lat = -21.0, lon = -48.0, seen = 3, session = "s1", at = 1000),
            cap(rssi = -90, lat = -21.9, lon = -48.9, seen = 7, session = "s2", at = 5000),
        )
        assertEquals(-60, m.rssi)
        assertEquals(-21.0, m.lat!!, 1e-9)
        assertEquals(-48.0, m.lon!!, 1e-9)
        assertEquals(7L, m.timesSeen)
        assertEquals("s2", m.lastSessionId)
        assertEquals(5000L, m.lastSeenAtMs)
    }

    @Test
    fun `empate de rssi mantem a posicao existente`() {
        val m = merge(cap(rssi = -70, lat = -21.0, lon = -48.0), cap(rssi = -70, lat = -22.0, lon = -49.0))
        assertEquals(-21.0, m.lat!!, 1e-9)
    }

    @Test
    fun `captura sem fix nunca apaga posicao conhecida`() {
        val m = merge(cap(rssi = -80, lat = -21.0, lon = -48.0), cap(rssi = -50, lat = null, lon = null))
        assertEquals(-21.0, m.lat!!, 1e-9)
        assertEquals(-80, m.rssi)
    }

    @Test
    fun `rede sem posicao ganha a primeira posicao mesmo com sinal mais fraco`() {
        val m = merge(cap(rssi = -50, lat = null, lon = null), cap(rssi = -95, lat = -21.0, lon = -48.0))
        assertEquals(-21.0, m.lat!!, 1e-9)
        assertEquals(-95, m.rssi)
    }

    @Test
    fun `sem posicao nas duas, o rssi e o maior`() {
        val m = merge(cap(rssi = -80), cap(rssi = -60))
        assertNull(m.lat)
        assertEquals(-60, m.rssi)
    }

    @Test
    fun `captura sem nome nunca apaga nome ja descoberto`() {
        val m = merge(cap(ssid = "Descoberta"), cap(ssid = ""))
        assertEquals("Descoberta", m.ssid)
        assertFalse(m.ssidHidden)
    }

    @Test
    fun `nome novo atualiza, inclusive se a rede mudou de nome`() {
        assertEquals("Nova", merge(cap(ssid = "Velha"), cap(ssid = "Nova")).ssid)
        val revealed = merge(cap(ssid = ""), cap(ssid = "Revelada"))
        assertEquals("Revelada", revealed.ssid)
        assertFalse(revealed.ssidHidden)
    }

    @Test
    fun `duas capturas sem nome continuam ocultas`() {
        val m = merge(cap(ssid = ""), cap(ssid = ""))
        assertTrue(m.ssidHidden)
        assertEquals("", m.ssid)
    }

    @Test
    fun `criptografia e canal seguem a captura mais recente, exceto desconhecido`() {
        val m = merge(cap(enc = "WPA2", channel = 6), cap(enc = "WPA3", channel = 11))
        assertEquals("WPA3", m.encryption)
        assertEquals(11, m.channel)
        val keep = merge(cap(enc = "WPA2", channel = 6), cap(enc = "UNKNOWN", channel = 0))
        assertEquals("WPA2", keep.encryption)
        assertEquals(6, keep.channel)
    }

    @Test
    fun `wps uma vez visto continua marcado`() {
        assertTrue(merge(cap(wps = true), cap(wps = false)).wpsActive)
    }

    @Test
    fun `visto N vezes fica com o maior (contador do firmware recomeca)`() {
        assertEquals(9L, merge(cap(seen = 9), cap(seen = 2)).timesSeen)
    }
}
