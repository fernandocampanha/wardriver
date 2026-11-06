package com.wardriver.sync

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test


private class FakeDao : NetworkDao {
    val rows = LinkedHashMap<String, NetworkRecord>()
    var failUpsert = false
    override suspend fun upsert(record: NetworkRecord) { upsertAll(listOf(record)) }
    override suspend fun upsertAll(records: List<NetworkRecord>) {
        if (failUpsert) throw IllegalStateException("disco cheio (simulado)")
        for (r in records) rows[r.bssid] = r
    }
    override suspend fun getAll(): List<NetworkRecord> = rows.values.sortedBy { it.bssid }
    override suspend fun getAllWithCoordinates(): List<NetworkRecord> = getAll().filter { it.lat != null && it.lon != null }
    override suspend fun count(): Int = rows.size
    override suspend fun getSeenBetween(since: Long, until: Long): List<NetworkRecord> =
        rows.values.filter { it.lastSeenAtMs in since..until }.sortedWith(compareBy({ it.lastSeenAtMs }, { it.bssid }))
    override fun observeChanges(): Flow<Int> = emptyFlow()
}

class NetworkStoreTest {

    private val bssid = "AA:BB:CC:DD:EE:01"

    private fun rec(
        ssid: String = "", rssi: Int = -70, lat: Double? = -10.0, lon: Double? = -50.0,
        at: Long = 1000L, b: String = bssid,
    ) = NetworkRecord(b, ssid, ssid.isEmpty(), 6, rssi, "WPA2", 1, false, lat, lon, "s", at)

    private fun store(dao: FakeDao = FakeDao()) = NetworkStore(dao) to dao



    @Test
    fun `rede oculta vista de novo COM nome passa a ter o nome, na memoria e no banco`() = runBlocking {
        val (store, dao) = store()
        store.applyCaptures(listOf(rec(ssid = "")))
        val result = store.applyCaptures(listOf(rec(ssid = "Descoberta", at = 2000)))

        assertEquals(listOf(NameChange(bssid, "Descoberta", null)), result.nameChanges)
        assertTrue(result.nameChanges.single().wasHidden)
        assertEquals("Descoberta", store.values.single().ssid)
        assertFalse(store.values.single().ssidHidden)
        assertEquals("Descoberta", dao.rows.getValue(bssid).ssid)
        assertFalse(dao.rows.getValue(bssid).ssidHidden)
        assertEquals(1, store.namesRevealed)
    }

    @Test
    fun `rede que ja tinha nome e volta sem nome NAO perde o nome`() = runBlocking {
        val (store, dao) = store()
        store.applyCaptures(listOf(rec(ssid = "Casa")))
        val result = store.applyCaptures(listOf(rec(ssid = "", at = 2000)))
        assertTrue(result.nameChanges.isEmpty())
        assertEquals("Casa", store.values.single().ssid)
        assertEquals("Casa", dao.rows.getValue(bssid).ssid)
        assertEquals(0, store.namesRevealed)
    }

    @Test
    fun `rede renomeada passa a ter o nome novo e a mudanca e informada`() = runBlocking {
        val (store, dao) = store()
        store.applyCaptures(listOf(rec(ssid = "Velha")))
        val result = store.applyCaptures(listOf(rec(ssid = "Nova", at = 2000)))
        assertEquals(listOf(NameChange(bssid, "Nova", "Velha")), result.nameChanges)
        assertFalse(result.nameChanges.single().wasHidden)
        assertEquals("Nova", dao.rows.getValue(bssid).ssid)
        assertEquals(0, store.namesRevealed)
    }

    @Test
    fun `lote com oculta e depois nomeada da mesma rede termina nomeada, e revela uma vez so`() = runBlocking {
        val (store, dao) = store()
        store.applyCaptures(listOf(rec(ssid = "")))
        val result = store.applyCaptures(listOf(rec(ssid = "", at = 2000), rec(ssid = "Achou", at = 3000)))
        assertEquals(1, result.nameChanges.size)
        assertEquals("Achou", dao.rows.getValue(bssid).ssid)
        assertEquals(1, store.namesRevealed)
    }

    @Test
    fun `lote com nomeada e depois oculta da mesma rede mantem o nome`() = runBlocking {
        val (store, dao) = store()
        store.applyCaptures(listOf(rec(ssid = "")))
        store.applyCaptures(listOf(rec(ssid = "Achou", at = 2000), rec(ssid = "", at = 3000)))
        assertEquals("Achou", dao.rows.getValue(bssid).ssid)
    }

    @Test
    fun `rede nova ja nomeada e so adicionada, nao conta como revelacao`() = runBlocking {
        val (store, _) = store()
        val result = store.applyCaptures(listOf(rec(ssid = "Nova")))
        assertEquals(1, result.added)
        assertTrue(result.nameChanges.isEmpty())
        assertEquals(0, store.namesRevealed)
    }

    @Test
    fun `revelacoes se acumulam entre lotes e entre redes diferentes`() = runBlocking {
        val (store, _) = store()
        val a = "AA:BB:CC:DD:EE:0A"; val b = "AA:BB:CC:DD:EE:0B"
        store.applyCaptures(listOf(rec(b = a), rec(b = b)))
        store.applyCaptures(listOf(rec(ssid = "A", b = a, at = 2000)))
        store.applyCaptures(listOf(rec(ssid = "B", b = b, at = 3000)))
        assertEquals(2, store.namesRevealed)
    }

    @Test
    fun `revelar o nome NAO mexe na posicao da captura mais forte`() = runBlocking {
        val (store, dao) = store()
        store.applyCaptures(listOf(rec(ssid = "", rssi = -50, lat = -10.0, lon = -50.0)))

        store.applyCaptures(listOf(rec(ssid = "Achou", rssi = -90, lat = -11.0, lon = -51.0, at = 2000)))
        val row = dao.rows.getValue(bssid)
        assertEquals("Achou", row.ssid)
        assertEquals(-10.0, row.lat!!, 1e-9)
        assertEquals(-50, row.rssi)
    }



    @Test
    fun `se o banco falhar a excecao sobe e a memoria continua igual ao que esta salvo`() = runBlocking {
        val (store, dao) = store()
        store.applyCaptures(listOf(rec(ssid = "")))
        dao.failUpsert = true
        try {
            store.applyCaptures(listOf(rec(ssid = "Achou", at = 2000)))
            fail("deveria propagar a falha de gravacao")
        } catch (e: IllegalStateException) {

        }
        assertEquals("", store.values.single().ssid)
        assertEquals(0, store.namesRevealed)
        dao.failUpsert = false
        store.applyCaptures(listOf(rec(ssid = "Achou", at = 3000)))
        assertEquals("Achou", store.values.single().ssid)
        assertEquals(1, store.namesRevealed)
    }

    @Test
    fun `load traz o banco para a memoria e nao sobrescreve o que ja chegou`() = runBlocking {
        val dao = FakeDao(); dao.rows[bssid] = rec(ssid = "Velho").copy(lastSessionId = "db")
        val store = NetworkStore(dao)
        store.applyCaptures(listOf(rec(ssid = "Novo", at = 5000, b = bssid).copy(lastSessionId = "live")))
        store.load()
        assertEquals("Novo", store.values.single().ssid)
        val fresh = NetworkStore(FakeDao().also { it.rows["X"] = rec(b = "AA:BB:CC:00:00:01") })
        fresh.load()
        assertEquals(1, fresh.size)
    }



    @Test
    fun `importar CSV preenche o nome de rede oculta e conta quantas foram preenchidas`() = runBlocking {
        val (store, dao) = store()
        store.applyCaptures(listOf(rec(ssid = ""), rec(ssid = "", b = "AA:BB:CC:DD:EE:02")))
        val result = store.applyImport(listOf(rec(ssid = "DoCsv", at = 0), rec(ssid = "", b = "AA:BB:CC:DD:EE:02", at = 0)))
        assertEquals(1, result.namesRevealed)
        assertEquals("DoCsv", dao.rows.getValue(bssid).ssid)
        assertEquals("", dao.rows.getValue("AA:BB:CC:DD:EE:02").ssid)
    }

    @Test
    fun `reimportar o mesmo CSV nao altera nada`() = runBlocking {
        val (store, _) = store()
        store.applyCaptures(listOf(rec(ssid = "Casa")))
        val again = store.applyImport(listOf(rec(ssid = "Casa")))
        assertEquals(0, again.updated)
        assertTrue(again.nameChanges.isEmpty())
    }

    @Test
    fun `importacao com nome MAIS NOVO e diferente renomeia, com nome mais velho ou sem data nao`() = runBlocking {
        val (store, dao) = store()
        store.applyCaptures(listOf(rec(ssid = "Atual", at = 5000)))
        store.applyImport(listOf(rec(ssid = "Antigo", at = 1000)))
        assertEquals("Atual", dao.rows.getValue(bssid).ssid)
        store.applyImport(listOf(rec(ssid = "SemData", at = 0)))
        assertEquals("Atual", dao.rows.getValue(bssid).ssid)
        val result = store.applyImport(listOf(rec(ssid = "Renomeada", at = 9000)))
        assertEquals("Renomeada", dao.rows.getValue(bssid).ssid)
        assertEquals("Atual", result.nameChanges.single().previousSsid)
    }

    @Test
    fun `lote vazio nao toca no banco`() = runBlocking {
        val (store, dao) = store()
        dao.failUpsert = true
        assertEquals(ApplyResult.EMPTY, store.applyCaptures(emptyList()))
        assertNull(store.values.firstOrNull())
    }
}
