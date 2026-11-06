package com.wardriver.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncTrackerTest {
    private val quiet = 3_000L
    private fun tracker() = SyncTracker(quietMs = quiet, timeoutMs = 60_000)

    @Test fun `termina depois da sessao nova, blocos fechados e silencio`() {
        val t = tracker()
        t.begin(0)
        t.onSession(100)
        t.onBlockBegin(200); t.onBlockEnd(900, confirmed = true)
        assertEquals(SyncTracker.Outcome.Pending, t.evaluate(3_000))
        assertEquals(SyncTracker.Outcome.Done(1, 0), t.evaluate(900 + quiet))
    }

    @Test fun `nao termina com bloco aberto mesmo com silencio`() {
        val t = tracker()
        t.begin(0); t.onSession(10); t.onBlockBegin(20)
        assertEquals(SyncTracker.Outcome.Pending, t.evaluate(30_000))
    }

    @Test fun `nao termina sem a sessao nova anunciada`() {
        val t = tracker()
        t.begin(0)
        assertEquals(SyncTracker.Outcome.Pending, t.evaluate(10_000))
    }

    @Test fun `bloco nao confirmado conta como problema`() {
        val t = tracker()
        t.begin(0); t.onSession(1); t.onBlockBegin(2); t.onBlockEnd(3, confirmed = false)
        assertEquals(SyncTracker.Outcome.Done(1, 1), t.evaluate(3 + quiet))
    }

    @Test fun `sem nada para enviar o ESP32 so anuncia a sessao`() {
        val t = tracker()
        t.begin(0); t.onSession(50)
        assertEquals(SyncTracker.Outcome.Done(0, 0), t.evaluate(50 + quiet))
    }

    @Test fun `erro do dispositivo falha na hora`() {
        val t = tracker()
        t.begin(0); t.onDeviceError("ERRO backlog cheio, confirme os ACKs pendentes antes de sincronizar de novo")
        val out = t.evaluate(1) as SyncTracker.Outcome.Failed
        assertTrue(out.reason.contains("backlog cheio"))
    }

    @Test fun `queda da conexao falha`() {
        val t = tracker()
        t.begin(0); t.onSession(1); t.onBlockBegin(2); t.onDisconnected()
        assertTrue(t.evaluate(3) is SyncTracker.Outcome.Failed)
    }

    @Test fun `estoura o tempo maximo`() {
        val t = SyncTracker(quietMs = quiet, timeoutMs = 10_000)
        t.begin(0)
        assertEquals(SyncTracker.Outcome.Pending, t.evaluate(9_999))
        assertEquals(SyncTracker.Outcome.TimedOut, t.evaluate(10_000))
    }

    @Test fun `eventos fora de uma sincronizacao sao ignorados`() {
        val t = tracker()
        t.onSession(1); t.onBlockBegin(2); t.onBlockEnd(3, true)
        assertTrue(t.evaluate(10) is SyncTracker.Outcome.Failed)
        t.begin(100)
        assertEquals(SyncTracker.Outcome.Pending, t.evaluate(100 + quiet))
    }

    @Test fun `varios blocos de backlog`() {
        val t = tracker()
        t.begin(0); t.onSession(10)
        for (i in 0 until 3) { t.onBlockBegin(100L * (i + 1)); t.onBlockEnd(100L * (i + 1) + 50, true) }
        assertEquals(SyncTracker.Outcome.Done(3, 0), t.evaluate(350 + quiet))
    }
}
