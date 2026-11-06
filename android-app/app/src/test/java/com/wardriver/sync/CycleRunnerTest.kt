package com.wardriver.sync

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CycleRunnerTest {
    private val ok = UploadResult(sent = 12, rejected = 0, chunks = 1, ok = true)

    @Test fun `sincroniza o hardware ANTES de enviar`() = runBlocking {
        val order = ArrayList<String>()
        val r = CycleRunner(
            syncHardware = { order += "hw"; HardwareOutcome.Synced(1, 0) },
            upload = { order += "cloud"; ok },
        ).run()
        assertEquals(listOf("hw", "cloud"), order)
        assertTrue(r.summary().contains("ESP32: sincronizado"))
        assertTrue(r.summary().contains("12 rede(s) enviada(s)"))
    }

    @Test fun `hardware pulado ou falho nao impede o envio`() = runBlocking {
        for (hw in listOf<HardwareOutcome>(HardwareOutcome.Skipped("sem conexão"), HardwareOutcome.Failed("caiu"))) {
            var uploaded = false
            val r = CycleRunner({ hw }, { uploaded = true; ok }).run()
            assertTrue(uploaded)
            assertEquals(hw, r.hardware)
        }
    }

    @Test fun `excecao no hardware vira falha e o envio segue`() = runBlocking {
        val r = CycleRunner({ throw IllegalStateException("x") }, { ok }).run()
        assertTrue(r.hardware is HardwareOutcome.Failed)
        assertTrue(r.upload!!.ok)
    }

    @Test fun `excecao no envio vira falha sem derrubar o ciclo`() = runBlocking {
        val r = CycleRunner({ HardwareOutcome.Synced(0, 0) }, { throw RuntimeException("boom") }).run()
        assertTrue(!r.upload!!.ok)
        assertTrue(r.summary().contains("FALHOU"))
    }

    @Test fun `resumo cobre nuvem desligada, nada novo e recusadas`() {
        val hw = HardwareOutcome.Synced(0, 0)
        assertTrue(CycleResult(hw, null).summary().contains("nuvem: desligada"))
        assertTrue(CycleResult(hw, UploadResult(0, 0, 0, true)).summary().contains("nada novo"))
        assertTrue(CycleResult(hw, UploadResult(5, 2, 1, true)).summary().contains("2 recusada(s)"))
        assertTrue(CycleResult(HardwareOutcome.Synced(2, 1), null).summary().contains("1 bloco(s) sem confirmação"))
    }
}
