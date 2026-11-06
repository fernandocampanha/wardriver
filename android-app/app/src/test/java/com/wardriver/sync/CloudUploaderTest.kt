package com.wardriver.sync

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.IOException
import java.io.ByteArrayOutputStream
import java.net.ServerSocket
import kotlin.concurrent.thread

class CloudUploaderTest {
    private val endpoint = (CloudUrl.parse("https://x.example.com") as CloudUrl.Parsed.Ok).endpoint
    private val token = "TOKEN-SECRETO-123"

    private fun rec(i: Int, last: Long = 1000L + i) =
        NetworkRecord("70:AA:BB:00:%02X:%02X".format(i / 256, i % 256), "Rede $i", false, 6, -60, "WPA2", 1, false, -10.0, -50.0, "s", last)

    private class Mark(override var value: Long = 0) : Watermark

    private class Source(val all: List<NetworkRecord>) : PendingSource {
        var lastQuery: Pair<Long, Long>? = null
        override suspend fun seenBetween(since: Long, until: Long): List<NetworkRecord> {
            lastQuery = since to until
            return all.filter { it.lastSeenAtMs in since..until }.sortedWith(compareBy({ it.lastSeenAtMs }, { it.bssid }))
        }
    }


    private class FakePoster(val script: (call: Int, json: String?) -> HttpResult) : HttpPoster {
        val calls = ArrayList<Triple<String, String, String?>>()
        val tokens = ArrayList<String>()
        override fun request(method: String, url: String, token: String, json: String?): HttpResult {
            calls += Triple(method, url, json); tokens += token
            return script(calls.size, json)
        }
    }

    private fun uploader(p: HttpPoster, chunk: Int = 1000, sleeps: MutableList<Long> = ArrayList()) =
        CloudUploader(p, chunk, listOf(10L, 20L)) { sleeps += it }

    private fun countNets(json: String?): Int = ((MiniJson.parse(json!!) as Map<*, *>)["networks"] as List<*>).size

    @Test fun `envia em lotes e avanca a marca d'agua`() = runBlocking {
        val p = FakePoster { _, _ -> HttpResult(200, "{}") }
        val mark = Mark()
        val src = Source((0 until 25).map { rec(it) })
        val r = uploader(p, chunk = 10).uploadPending(endpoint, token, "tel", src, mark, untilMs = 5_000)
        assertTrue(r.ok); assertEquals(25, r.sent); assertEquals(3, r.chunks)
        assertEquals(listOf(10, 10, 5), p.calls.map { countNets(it.third) })
        assertTrue(p.calls.all { it.first == "POST" && it.second == "https://x.example.com/api/v1/ingest" })
        assertEquals(5_000L, mark.value)
        assertTrue(p.tokens.all { it == token })
    }

    @Test fun `nada novo nao chama a rede`() = runBlocking {
        val p = FakePoster { _, _ -> error("nao deveria chamar") }
        val mark = Mark(2_000)
        val r = uploader(p).uploadPending(endpoint, token, "tel", Source(listOf(rec(1, last = 1500))), mark, untilMs = 3_000)
        assertTrue(r.ok && r.nothingToSend)
        assertEquals(3_000L, mark.value)
        assertTrue(p.calls.isEmpty())
    }

    @Test fun `so envia o que foi visto depois da marca e ate o corte do ciclo`() = runBlocking {
        val p = FakePoster { _, _ -> HttpResult(200, "{}") }
        val src = Source(listOf(rec(1, last = 100), rec(2, last = 500), rec(3, last = 900)))
        val mark = Mark(400)
        uploader(p).uploadPending(endpoint, token, "tel", src, mark, untilMs = 800)
        assertEquals(1, countNets(p.calls.single().third))
        assertEquals(400L to 800L, src.lastQuery)
        assertEquals(800L, mark.value)
    }

    @Test fun `falha de rede tenta de novo, com espera, e depois desiste sem avancar a marca`() = runBlocking {
        val p = FakePoster { _, _ -> throw IOException("timeout") }
        val sleeps = ArrayList<Long>()
        val mark = Mark(100)
        val r = uploader(p, sleeps = sleeps).uploadPending(endpoint, token, "tel", Source(listOf(rec(1, last = 500))), mark, 900)
        assertFalse(r.ok); assertEquals(0, r.sent)
        assertEquals(3, p.calls.size); assertEquals(listOf(10L, 20L), sleeps)
        assertEquals(100L, mark.value)
        assertTrue(r.error!!.contains("sem rede"))
    }

    @Test fun `se o segundo lote falha, o primeiro fica entregue e a marca para nele`() = runBlocking {
        val p = FakePoster { call, _ -> if (call == 1) HttpResult(200, "{}") else HttpResult(503, "") }
        val mark = Mark(0)
        val src = Source((0 until 6).map { rec(it, last = 1000L + it * 100) })
        val r = uploader(p, chunk = 3).uploadPending(endpoint, token, "tel", src, mark, 9_000)
        assertFalse(r.ok); assertEquals(3, r.sent); assertEquals(1, r.chunks)
        assertEquals(1200L, mark.value)
    }

    @Test fun `servidor com erro 5xx se recupera na segunda tentativa`() = runBlocking {
        val p = FakePoster { call, _ -> if (call < 2) HttpResult(502, "") else HttpResult(200, "{}") }
        val r = uploader(p).uploadPending(endpoint, token, "tel", Source(listOf(rec(1))), Mark(), 9_000)
        assertTrue(r.ok); assertEquals(1, r.sent); assertEquals(2, p.calls.size)
    }

    @Test fun `token recusado para na hora, sem insistir e sem vazar o token`() = runBlocking {
        for (status in listOf(401, 403)) {
            val p = FakePoster { _, _ -> HttpResult(status, "{\"error\":\"token de upload ausente ou invalido\"}") }
            val r = uploader(p).uploadPending(endpoint, token, "tel", Source(listOf(rec(1))), Mark(), 9_000)
            assertFalse(r.ok); assertEquals(1, p.calls.size)
            assertTrue(r.error!!.contains("token"))
            assertFalse(r.error!!.contains(token))
        }
    }

    @Test fun `422 isola o registro invalido por bisseccao e envia os demais`() = runBlocking {
        val bad = rec(3).bssid
        val p = FakePoster { _, json -> if (json!!.contains(bad)) HttpResult(422, "{}") else HttpResult(200, "{}") }
        val mark = Mark()
        val r = uploader(p, chunk = 100).uploadPending(endpoint, token, "tel", Source((0 until 8).map { rec(it) }), mark, 9_000)
        assertTrue(r.ok); assertEquals(7, r.sent); assertEquals(1, r.rejected)
        assertEquals(9_000L, mark.value)
        assertTrue(r.summaryHasRejected())
    }

    private fun UploadResult.summaryHasRejected() = CycleResult(HardwareOutcome.Synced(0, 0), this).summary().contains("1 recusada(s)")

    @Test fun `413 divide o lote ao meio`() = runBlocking {
        val p = FakePoster { _, json -> if (countNets(json) > 4) HttpResult(413, "") else HttpResult(200, "{}") }
        val r = uploader(p, chunk = 100).uploadPending(endpoint, token, "tel", Source((0 until 8).map { rec(it) }), Mark(), 9_000)
        assertTrue(r.ok); assertEquals(8, r.sent)
    }

    @Test fun `redirecionamento e erro claro, nunca seguido`() = runBlocking {
        val p = FakePoster { _, _ -> HttpResult(301, "") }
        val r = uploader(p).uploadPending(endpoint, token, "tel", Source(listOf(rec(1))), Mark(), 9_000)
        assertFalse(r.ok); assertTrue(r.error!!.contains("redirecionou"))
    }

    @Test fun `ping diagnostica endereco e token`() {
        fun ping(status: Int) = CloudUploader(FakePoster { _, _ -> HttpResult(status, "") }).ping(endpoint, token)
        assertNull(ping(200))
        assertTrue(ping(401)!!.contains("token"))
        assertTrue(ping(404)!!.contains("endereço"))
        assertTrue(ping(301)!!.contains("redirecionou"))
        assertNotNull(CloudUploader(HttpPoster { _, _, _, _ -> throw IOException("sem rota") }).ping(endpoint, token))
    }



    private class Req(val method: String, val path: String, val headers: Map<String, String>, val body: ByteArray)
    private class Resp(val status: Int, val body: String = "", val headers: Map<String, String> = emptyMap())


    private fun withServer(handler: (Req) -> Resp, block: (Int) -> Unit) {
        val ss = ServerSocket(0, 5, java.net.InetAddress.getByName("127.0.0.1"))
        val t = thread(isDaemon = true) {
            while (!ss.isClosed) {
                try {
                    ss.accept().use { sock ->
                        val inp = sock.getInputStream().buffered()
                        fun line(): String {
                            val b = ByteArrayOutputStream()
                            while (true) { val c = inp.read(); if (c < 0 || c == '\n'.code) break; if (c != '\r'.code) b.write(c) }
                            return b.toString(Charsets.ISO_8859_1)
                        }
                        val (method, path) = line().split(" ").let { it[0] to it[1] }
                        val headers = HashMap<String, String>()
                        while (true) { val h = line(); if (h.isEmpty()) break; headers[h.substringBefore(":").lowercase()] = h.substringAfter(":").trim() }
                        val body = ByteArray(headers["content-length"]?.toInt() ?: 0).also { var n = 0; while (n < it.size) n += inp.read(it, n, it.size - n) }
                        val resp = handler(Req(method, path, headers, body))
                        val bytes = resp.body.toByteArray(Charsets.UTF_8)
                        val head = StringBuilder("HTTP/1.1 ${resp.status} X\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n")
                        resp.headers.forEach { (k, v) -> head.append("$k: $v\r\n") }
                        head.append("\r\n")
                        sock.getOutputStream().apply { write(head.toString().toByteArray(Charsets.ISO_8859_1)); write(bytes); flush() }
                    }
                } catch (e: java.net.SocketException) {  }
            }
        }
        try { block(ss.localPort) } finally { ss.close(); t.join(2000) }
    }

    @Test fun `HttpUrlPoster manda cabecalhos, corpo UTF-8 e le a resposta`() {
        var seen: Req? = null
        withServer({ seen = it; Resp(200, "{\"ok\":true}") }) { port ->
            val res = HttpUrlPoster().request("POST", "http://127.0.0.1:$port/api/v1/ingest", "abc", "{\"ssid\":\"café ☕\"}")
            assertEquals(200, res.status); assertEquals("{\"ok\":true}", res.body)
        }
        val r = seen!!
        assertEquals("POST", r.method); assertEquals("/api/v1/ingest", r.path)
        assertEquals("Bearer abc", r.headers["authorization"]); assertEquals("application/json; charset=utf-8", r.headers["content-type"])
        assertEquals("{\"ssid\":\"café ☕\"}", r.body.toString(Charsets.UTF_8))
    }

    @Test fun `HttpUrlPoster nao segue redirecionamento (o token nao pode ir para outro lugar)`() {
        var followed = false
        withServer({ if (it.path == "/dest") { followed = true; Resp(200) } else Resp(302, headers = mapOf("Location" to "/dest")) }) { port ->
            val res = HttpUrlPoster().request("POST", "http://127.0.0.1:$port/api/v1/ingest", "abc", "{}")
            assertEquals(302, res.status)
        }
        assertFalse(followed)
    }

    @Test fun `HttpUrlPoster devolve o corpo de erro e o GET nao envia corpo`() {
        var seen: Req? = null
        withServer({ seen = it; Resp(401, "{\"error\":\"nao autenticado\"}") }) { port ->
            val res = HttpUrlPoster().request("GET", "http://127.0.0.1:$port/api/v1/ping", "x", null)
            assertEquals(401, res.status); assertTrue(res.body.contains("nao autenticado"))
        }
        assertEquals("GET", seen!!.method); assertEquals(0, seen!!.body.size)
    }

    @Test fun `HttpUrlPoster levanta IOException quando nao ha servidor`() {
        val port = ServerSocket(0).use { it.localPort }
        try {
            HttpUrlPoster(connectTimeoutMs = 500, readTimeoutMs = 500).request("GET", "http://127.0.0.1:$port/", "x", null)
            org.junit.Assert.fail("deveria lancar")
        } catch (e: IOException) {  }
    }





    @Test fun `contrato com o backend real`() = runBlocking {
        val url = System.getenv("WARDRIVER_IT_URL"); val tok = System.getenv("WARDRIVER_IT_TOKEN")
        assumeTrue("defina WARDRIVER_IT_URL e WARDRIVER_IT_TOKEN para rodar contra o backend", !url.isNullOrBlank() && !tok.isNullOrBlank())
        val ep = (CloudUrl.parse(url!!) as CloudUrl.Parsed.Ok).endpoint
        val up = CloudUploader(HttpUrlPoster())
        assertNull("ping", up.ping(ep, tok!!))
        val nasty = NetworkRecord("70:AA:BB:EE:00:01", "aspas\" barra\\ nl\n emoji 😀 </script>", false, 6, -55, "WPA2", 4, true, -10.2, -50.3, "it", 1_760_000_000_000)
        val hidden = NetworkRecord("70:AA:BB:EE:00:02", "", true, 11, -80, "OPEN", 1, false, null, null, "it", 1_760_000_000_500)
        val src = Source(listOf(nasty, hidden))
        val first = up.uploadPending(ep, tok, "teste-jvm", src, Mark(), 2_000_000_000_000)
        assertTrue("primeiro envio: ${first.error}", first.ok); assertEquals(2, first.sent); assertEquals(0, first.rejected)
        val second = up.uploadPending(ep, tok, "teste-jvm", src, Mark(), 2_000_000_000_000)
        assertTrue(second.ok); assertEquals(2, second.sent)
    }
}
