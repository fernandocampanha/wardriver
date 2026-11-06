package com.wardriver.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class FirmwareStatsLogTest {

    @Test
    fun `le uma linha STATS do firmware`() {
        val line = "2026-09-19 11:02:03 STATS t=120 heap=57000 redes=55 tabela=40 sem_nome=18 beacon=900 probe=12 " +
            "tam0=30 zeros=5 rev_probe=2 rev_beacon=1 preservado=7 falhas_alloc=0 gps=modulo mod_fix=1 mod_sats=6 " +
            "mod_view=9 mod_trk=7 mod_snr=34 mod_hdop=1.4"
        val e = FirmwareStatsLog.parseLine(line)
        assertNotNull(e)
        assertEquals("2026-09-19 11:02:03", e!!.stamp)
        assertEquals("120", e.values["t"])
        assertEquals("modulo", e.values["gps"])
        assertEquals("1.4", e.values["mod_hdop"])
        assertEquals(20, e.values.size)
    }

    @Test
    fun `campo ch com pares canal-contagem e lido inteiro`() {
        val line = "2026-09-20 11:02:03 STATS t=90 hops=140 hopfail=0 assoc=7 rev_assoc=2 ch=1:120,2:15,3:8,4:9,5:11,6:300,7:12,8:6,9:4,10:5,11:180,12:2,13:1"
        val e = FirmwareStatsLog.parseLine(line)!!
        assertEquals("1:120,2:15,3:8,4:9,5:11,6:300,7:12,8:6,9:4,10:5,11:180,12:2,13:1", e.values["ch"])
        assertEquals("140", e.values["hops"])
        assertEquals("2", e.values["rev_assoc"])
    }

    @Test
    fun `linha que nao e STATS ou esta truncada nao vira entrada`() {
        assertNull(FirmwareStatsLog.parseLine("2026-09-19 11:02:03 verificado e confirmado: sess_0001.wdr"))
        assertNull(FirmwareStatsLog.parseLine("STATS t=1"))
        assertNull(FirmwareStatsLog.parseLine(""))
    }

    @Test
    fun `linha STATS truncada no meio ainda entrega os pares completos`() {
        val e = FirmwareStatsLog.parseLine("2026-09-19 11:02:03 STATS t=5 heap=5000 redes=3 tab")
        assertNotNull(e)
        assertEquals(mapOf("t" to "5", "heap" to "5000", "redes" to "3"), e!!.values)
    }
}
