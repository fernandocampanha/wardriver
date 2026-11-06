package com.wardriver.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.StringReader

class CsvImporterTest {

    private fun parse(text: String) = CsvImporter.parse(StringReader(text))
    private fun recordOf(text: String, bssid: String) = parse(text).records.single { it.bssid == bssid }



    @Test
    fun `le o CSV exportado pelo proprio app`() {
        val csv = """
            bssid,ssid,ssid_hidden,channel,rssi,encryption,times_seen,wps_active,lat,lon,last_session_id,last_seen_at_ms
            AA:BB:CC:DD:EE:01,RedeA,false,6,-55,WPA2,4,true,-10.100000,-50.900000,sess_0007,1720000000000
            AA:BB:CC:DD:EE:02,,true,11,-80,OPEN,1,false,,,sess_0007,1720000001000
        """.trimIndent()

        val result = parse(csv)
        assertEquals("Wardriver Sync", result.layout)
        assertEquals(2, result.records.size)

        val a = result.records.first { it.bssid == "AA:BB:CC:DD:EE:01" }
        assertEquals("RedeA", a.ssid)
        assertFalse(a.ssidHidden)
        assertEquals(6, a.channel)
        assertEquals(-55, a.rssi)
        assertEquals("WPA2", a.encryption)
        assertEquals(4L, a.timesSeen)
        assertTrue(a.wpsActive)
        assertEquals(-10.1, a.lat!!, 1e-9)
        assertEquals(-50.9, a.lon!!, 1e-9)
        assertEquals("sess_0007", a.lastSessionId)
        assertEquals(1720000000000L, a.lastSeenAtMs)

        val b = result.records.first { it.bssid == "AA:BB:CC:DD:EE:02" }
        assertTrue(b.ssidHidden)
        assertEquals("", b.ssid)
        assertNull(b.lat)
        assertNull(b.lon)
    }

    @Test
    fun `ssid com virgula e aspas escapadas sobrevive ao ciclo`() {
        val csv = "bssid,ssid,ssid_hidden\n" +
            "AA:BB:CC:DD:EE:01,\"Casa, do \"\"Zé\"\"\",false\n"
        assertEquals("Casa, do \"Zé\"", recordOf(csv, "AA:BB:CC:DD:EE:01").ssid)
    }

    @Test
    fun `quebra de linha dentro de campo entre aspas nao parte a linha`() {
        val csv = "bssid,ssid\nAA:BB:CC:DD:EE:01,\"linha1\nlinha2\"\nAA:BB:CC:DD:EE:02,Outra\n"
        val result = parse(csv)
        assertEquals(2, result.records.size)
        assertEquals("linha1\nlinha2", result.records.first { it.bssid.endsWith("01") }.ssid)
    }



    @Test
    fun `le o CSV antigo sem cabecalho e trata Hidden como oculta`() {
        val csv = "AA:BB:CC:DD:EE:01,RedeA,241,7,-54,-10.123450,-50.987650,\n" +
            "AA:BB:CC:DD:EE:02,Hidden,242,9,-54,-10.123460,-50.987660,\n"

        val result = parse(csv)
        assertEquals("CSV antigo do ESP32 (sem cabecalho)", result.layout)
        assertEquals(2, result.rowsRead)

        val a = result.records.first { it.bssid.endsWith("01") }
        assertEquals("RedeA", a.ssid)
        assertEquals(7, a.channel)
        assertEquals(-54, a.rssi)
        assertEquals(-10.12345, a.lat!!, 1e-9)
        assertEquals(-50.98765, a.lon!!, 1e-9)
        assertEquals(NetworkMerge.UNKNOWN_ENCRYPTION, a.encryption)

        val h = result.records.first { it.bssid.endsWith("02") }
        assertTrue(h.ssidHidden)
        assertEquals("", h.ssid)
    }

    @Test
    fun `varias amostras do mesmo BSSID viram um so, com a posicao do sinal mais forte e o nome preservado`() {
        val csv = "AA:BB:CC:DD:EE:01,Hidden,1,6,-90,-10.000000,-50.000000,\n" +
            "AA:BB:CC:DD:EE:01,RedeA,2,6,-40,-10.500000,-50.500000,\n" +
            "AA:BB:CC:DD:EE:01,Hidden,3,6,-70,-10.900000,-50.900000,\n"

        val result = parse(csv)
        assertEquals(3, result.rowsRead)
        assertEquals(1, result.records.size)
        val r = result.records.single()
        assertEquals("RedeA", r.ssid)
        assertFalse(r.ssidHidden)
        assertEquals(-40, r.rssi)
        assertEquals(-10.5, r.lat!!, 1e-9)
        assertEquals(-50.5, r.lon!!, 1e-9)
    }

    @Test
    fun `sem cabecalho e em formato desconhecido da erro claro em vez de lixo`() {

        val csv = "AA:BB:CC:DD:EE:01,RedeA,foo,bar,baz\n"
        try {
            parse(csv)
            fail("deveria recusar")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("formato desconhecido"))
        }
    }



    @Test
    fun `le WiGLE, incluindo preambulo, criptografia e frequencia`() {
        val csv = """
            WigleWifi-1.4,appRelease=2.0,model=X,release=14,device=x,display=x,board=x,brand=x
            MAC,SSID,AuthMode,FirstSeen,Channel,RSSI,CurrentLatitude,CurrentLongitude,AltitudeMeters,AccuracyMeters,Type
            aa:bb:cc:dd:ee:01,Vizinha,[WPA2-PSK-CCMP][WPS][ESS],2024-06-01 12:30:00,11,-61,-10.2,-50.9,500,5,WIFI
            aa:bb:cc:dd:ee:02,Aberta,[ESS],2024-06-01 12:31:00,1,-70,-10.3,-50.8,500,5,WIFI
        """.trimIndent()

        val result = parse(csv)
        assertEquals("WiGLE", result.layout)
        val v = result.records.first { it.bssid == "AA:BB:CC:DD:EE:01" }
        assertEquals("WPA2", v.encryption)
        assertTrue(v.wpsActive)
        assertEquals(11, v.channel)
        assertTrue(v.lastSeenAtMs > 1_700_000_000_000L)
        assertEquals("OPEN", result.records.first { it.bssid.endsWith("02") }.encryption)
    }



    @Test
    fun `ponto e virgula com virgula decimal, BOM e CRLF`() {
        val csv = "\uFEFFbssid;ssid;lat;lon\r\nAA-BB-CC-DD-EE-01;RedeA;-10,5;-50,25\r\n"
        val r = recordOf(csv, "AA:BB:CC:DD:EE:01")
        assertEquals("RedeA", r.ssid)
        assertEquals(-10.5, r.lat!!, 1e-9)
        assertEquals(-50.25, r.lon!!, 1e-9)
    }

    @Test
    fun `colunas em outra ordem e com nomes alternativos`() {
        val csv = "Signal,Latitude,Longitude,MAC,Name\n-48,-10.7,-50.7,aabbccddee01,Xis\n"
        val r = recordOf(csv, "AA:BB:CC:DD:EE:01")
        assertEquals("Xis", r.ssid)
        assertEquals(-48, r.rssi)
        assertEquals(-10.7, r.lat!!, 1e-9)
    }

    @Test
    fun `linha sem BSSID valido e ignorada e contada, o resto entra`() {
        val csv = "bssid,ssid\nnao-e-mac,X\nAA:BB:CC:DD:EE:01,Ok\n,Vazio\n"
        val result = parse(csv)
        assertEquals(1, result.records.size)
        assertEquals(3, result.rowsRead)
        assertEquals(2, result.rowsSkipped)
    }

    @Test
    fun `coordenada invalida, fora da faixa ou 0,0 vira sem posicao`() {
        val csv = "bssid,lat,lon\n" +
            "AA:BB:CC:DD:EE:01,95.0,-50.0\n" +
            "AA:BB:CC:DD:EE:02,0,0\n" +
            "AA:BB:CC:DD:EE:03,abc,-50.0\n" +
            "AA:BB:CC:DD:EE:04,-10.0,-50.0\n"
        val byBssid = parse(csv).records.associateBy { it.bssid }
        assertNull(byBssid.getValue("AA:BB:CC:DD:EE:01").lat)
        assertNull(byBssid.getValue("AA:BB:CC:DD:EE:02").lat)
        assertNull(byBssid.getValue("AA:BB:CC:DD:EE:03").lat)
        assertNotNull(byBssid.getValue("AA:BB:CC:DD:EE:04").lat)
    }

    @Test
    fun `arquivo sem coluna de BSSID da erro claro`() {
        try {
            parse("nome,valor\nx,1\n")
            fail("deveria recusar")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("BSSID"))
        }
    }

    @Test
    fun `arquivo vazio nao quebra`() {
        val result = parse("")
        assertEquals(0, result.records.size)
        assertEquals(0, result.rowsRead)
    }



    private fun rec(
        bssid: String = "AA:BB:CC:DD:EE:01", ssid: String = "", rssi: Int = -70,
        lat: Double? = null, lon: Double? = null, seen: Long = 1, enc: String = "UNKNOWN", channel: Int = 0,
    ) = NetworkRecord(bssid, ssid, ssid.isEmpty(), channel, rssi, enc, seen, false, lat, lon, "s", 1L)

    @Test
    fun `importar nunca apaga um nome ja conhecido`() {
        val existing = rec(ssid = "Casa", lat = -10.0, lon = -50.0, rssi = -50)
        val merged = NetworkMerge.mergeObservations(existing, rec(ssid = "", rssi = -30))
        assertEquals("Casa", merged.ssid)
        assertFalse(merged.ssidHidden)
    }

    @Test
    fun `importar preenche o nome de uma rede que estava oculta`() {
        val merged = NetworkMerge.mergeObservations(rec(ssid = ""), rec(ssid = "Descoberta"))
        assertEquals("Descoberta", merged.ssid)
        assertFalse(merged.ssidHidden)
    }

    @Test
    fun `posicao existente so e trocada por uma com sinal mais forte, e quem nao tem posicao ganha`() {
        val existing = rec(lat = -10.0, lon = -50.0, rssi = -50)
        assertEquals(-10.0, NetworkMerge.mergeObservations(existing, rec(lat = -22.0, lon = -49.0, rssi = -80)).lat!!, 1e-9)
        assertEquals(-22.0, NetworkMerge.mergeObservations(existing, rec(lat = -22.0, lon = -49.0, rssi = -40)).lat!!, 1e-9)
        assertEquals(-22.0, NetworkMerge.mergeObservations(rec(), rec(lat = -22.0, lon = -49.0, rssi = -80)).lat!!, 1e-9)
        assertEquals(-10.0, NetworkMerge.mergeObservations(existing, rec()).lat!!, 1e-9)
    }

    @Test
    fun `reimportar o mesmo dado nao muda nada`() {
        val existing = rec(ssid = "Casa", lat = -10.0, lon = -50.0, rssi = -50, seen = 5, enc = "WPA2", channel = 6)
        val again = NetworkMerge.mergeObservations(existing, existing.copy())
        assertEquals(existing, again)
    }

    @Test
    fun `criptografia e canal so preenchem o que era desconhecido`() {
        val merged = NetworkMerge.mergeObservations(rec(enc = "UNKNOWN", channel = 0), rec(enc = "WPA3", channel = 11))
        assertEquals("WPA3", merged.encryption)
        assertEquals(11, merged.channel)
        val kept = NetworkMerge.mergeObservations(rec(enc = "WPA2", channel = 6), rec(enc = "OPEN", channel = 1))
        assertEquals("WPA2", kept.encryption)
        assertEquals(6, kept.channel)
    }
}
