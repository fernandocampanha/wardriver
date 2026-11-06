package com.wardriver.sync

import org.junit.Assert.assertEquals
import org.junit.Test

class HiddenSiblingsTest {

    private fun net(bssid: String, ssid: String) =
        NetworkRecord(bssid, ssid, ssid.isEmpty(), 6, -60, "WPA2", 1, false, null, null, "s", 0)

    @Test
    fun `interface extra do mesmo roteador conta como irma da visivel`() {

        val result = HiddenSiblings.analyze(
            listOf(net("70:AA:BB:FC:84:30", "Casa"), net("72:AA:BB:CC:84:30", ""))
        )
        assertEquals(1, result.unnamed)
        assertEquals(1, result.withVisibleSibling)
        assertEquals(1, result.locallyAdministered)
    }

    @Test
    fun `ultimo byte diferente tambem conta`() {
        val result = HiddenSiblings.analyze(listOf(net("AC:15:A2:01:02:10", "Casa"), net("AC:15:A2:01:02:11", "")))
        assertEquals(1, result.withVisibleSibling)
    }

    @Test
    fun `oculta de outro fabricante ou muito diferente nao conta`() {
        val result = HiddenSiblings.analyze(
            listOf(
                net("70:AA:BB:FC:84:30", "Casa"),
                net("00:11:22:FC:84:30", ""),
                net("70:AA:BB:01:02:03", ""),
            )
        )
        assertEquals(2, result.unnamed)
        assertEquals(0, result.withVisibleSibling)
    }

    @Test
    fun `duas ocultas entre si nao sao irmas de nada`() {
        val result = HiddenSiblings.analyze(listOf(net("70:AA:BB:FC:84:30", ""), net("72:AA:BB:CC:84:30", "")))
        assertEquals(2, result.unnamed)
        assertEquals(0, result.withVisibleSibling)
    }

    @Test
    fun `sem redes nao quebra`() {
        assertEquals(HiddenSiblings.Result(0, 0, 0), HiddenSiblings.analyze(emptyList()))
    }
}
