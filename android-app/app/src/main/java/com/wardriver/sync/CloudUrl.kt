package com.wardriver.sync

import java.net.URI
import java.net.URISyntaxException







object CloudUrl {

    data class Endpoint(val base: String) {
        val ingest: String get() = "$base/api/v1/ingest"
        val ping: String get() = "$base/api/v1/ping"
    }

    sealed class Parsed {
        data class Ok(val endpoint: Endpoint) : Parsed()
        data class Error(val message: String) : Parsed()
    }

    private val LOOPBACK_HOSTS = setOf("localhost", "127.0.0.1", "10.0.2.2")

    fun parse(raw: String): Parsed {
        val text = raw.trim()
        if (text.isEmpty()) return Parsed.Error("Informe o endereço do servidor")
        val uri = try {
            URI(text)
        } catch (e: URISyntaxException) {
            return Parsed.Error("Endereço inválido")
        }
        val scheme = uri.scheme?.lowercase() ?: return Parsed.Error("Comece com https://")
        val host = uri.host?.lowercase() ?: return Parsed.Error("Endereço sem servidor")
        if (uri.userInfo != null) return Parsed.Error("Não coloque usuário/senha no endereço")
        if (uri.rawQuery != null || uri.rawFragment != null) return Parsed.Error("Use só o endereço base, sem ? nem #")
        when (scheme) {
            "https" -> {}
            "http" -> if (host !in LOOPBACK_HOSTS) {
                return Parsed.Error("Só https é aceito (http apenas para localhost, 127.0.0.1 e 10.0.2.2)")
            }
            else -> return Parsed.Error("Só https é aceito")
        }
        val port = if (uri.port == -1) "" else ":${uri.port}"
        val path = uri.rawPath.orEmpty().trimEnd('/')
        return Parsed.Ok(Endpoint("$scheme://$host$port$path"))
    }
}
