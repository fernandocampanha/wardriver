package com.wardriver.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkSearchTest {

    private fun net(ssid: String, bssid: String = "AA:BB:CC:11:22:33", hidden: Boolean = false) =
        NetworkRecord(bssid, ssid, hidden, 6, -60, "WPA2", 1, false, null, null, "s", 0)

    private fun matches(query: String, n: NetworkRecord) = NetworkSearch.Query.parse(query).matches(n)

    @Test
    fun `ignora maiusculas e acentos`() {
        assertTrue(matches("cafe", net("Café do Zé")))
        assertTrue(matches("CAFÉ", net("cafe do ze")))
        assertTrue(matches("ze", net("Café do Zé")))
    }

    @Test
    fun `varias palavras precisam bater todas, em qualquer ordem`() {
        val n = net("VIVO-FIBRA 5G")
        assertTrue(matches("vivo 5g", n))
        assertTrue(matches("5g vivo", n))
        assertFalse(matches("vivo claro", n))
    }

    @Test
    fun `bssid bate com e sem dois-pontos, inclusive parcial`() {
        val n = net("X", bssid = "70:AA:BB:FC:84:30")
        assertTrue(matches("70:aa:bb", n))
        assertTrue(matches("70AABB", n))
        assertTrue(matches("fc:84", n))
        assertTrue(matches("fc84", n))
        assertFalse(matches("ff:ff", n))
    }

    @Test
    fun `rede sem nome bate com oculta`() {
        assertTrue(matches("oculta", net("", hidden = true)))
        assertTrue(matches("hidden", net("", hidden = true)))
        assertFalse(matches("oculta", net("Casa")))
    }

    @Test
    fun `busca vazia ou so espacos bate com tudo`() {
        assertTrue(NetworkSearch.Query.parse("").isEmpty)
        assertTrue(NetworkSearch.Query.parse("   ").isEmpty)
        assertTrue(matches("   ", net("Qualquer")))
    }

    @Test
    fun `isUnnamed considera flag ou ssid vazio`() {
        assertTrue(NetworkSearch.isUnnamed(net("", hidden = false)))
        assertTrue(NetworkSearch.isUnnamed(net("x", hidden = true)))
        assertFalse(NetworkSearch.isUnnamed(net("x")))
    }

    @Test
    fun `normalize remove acentos`() {
        assertEquals("acao", NetworkSearch.normalize("Ação"))
    }
}
