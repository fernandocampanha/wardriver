package com.wardriver.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudUrlTest {
    private fun ok(raw: String) = (CloudUrl.parse(raw) as CloudUrl.Parsed.Ok).endpoint
    private fun error(raw: String) = (CloudUrl.parse(raw) as CloudUrl.Parsed.Error).message

    @Test fun `https normaliza barra final e maiusculas do host`() {
        assertEquals("https://wardriver.example.com", ok("  https://Wardriver.Example.com/  ").base)
        assertEquals("https://wardriver.example.com:8443/app", ok("https://wardriver.example.com:8443/app/").base)
    }

    @Test fun `monta os enderecos da API`() {
        val e = ok("https://x.example.com")
        assertEquals("https://x.example.com/api/v1/ingest", e.ingest)
        assertEquals("https://x.example.com/api/v1/ping", e.ping)
    }

    @Test fun `http so para loopback`() {
        assertEquals("http://localhost:8080", ok("http://localhost:8080").base)
        assertEquals("http://127.0.0.1:8080", ok("http://127.0.0.1:8080").base)
        assertEquals("http://10.0.2.2:8080", ok("http://10.0.2.2:8080").base)
        assertTrue(error("http://wardriver.example.com").contains("https"))
        assertTrue(error("http://192.168.1.10:8080").contains("https"))
    }

    @Test fun `recusa o que poderia vazar o token ou confundir`() {
        assertTrue(error("https://usuario:senha@x.example.com").contains("usuário"))
        assertTrue(error("https://x.example.com/?a=1").contains("sem ?"))
        assertTrue(error("https://x.example.com/#frag").contains("sem ?"))
        assertTrue(error("ftp://x.example.com").contains("https"))
        assertTrue(error("x.example.com").isNotEmpty())
        assertTrue(error("").isNotEmpty())
        assertTrue(error("https://").isNotEmpty())
        assertTrue(error("https://ex ample.com").isNotEmpty())
    }
}
