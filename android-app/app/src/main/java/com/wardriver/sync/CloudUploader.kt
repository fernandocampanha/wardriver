package com.wardriver.sync

import kotlinx.coroutines.delay
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

data class HttpResult(val status: Int, val body: String)


fun interface HttpPoster {
    fun request(method: String, url: String, token: String, json: String?): HttpResult
}


class HttpUrlPoster(
    private val connectTimeoutMs: Int = 10_000,
    private val readTimeoutMs: Int = 30_000,
) : HttpPoster {
    override fun request(method: String, url: String, token: String, json: String?): HttpResult {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = connectTimeoutMs
            conn.readTimeout = readTimeoutMs
            conn.instanceFollowRedirects = false
            conn.setRequestProperty("Authorization", "Bearer $token")
            conn.setRequestProperty("User-Agent", "WardriverSync/1.0")
            conn.setRequestProperty("Accept", "application/json")
            if (json != null) {
                val bytes = json.toByteArray(Charsets.UTF_8)
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                conn.setFixedLengthStreamingMode(bytes.size)
                conn.outputStream.use { it.write(bytes) }
            }
            val status = conn.responseCode
            val stream = if (status >= 400) conn.errorStream else conn.inputStream
            val body = stream?.use { it.readBytes().copyOf().let { b -> String(b, 0, minOf(b.size, MAX_BODY), Charsets.UTF_8) } } ?: ""
            return HttpResult(status, body)
        } finally {
            conn.disconnect()
        }
    }

    private companion object { const val MAX_BODY = 4096 }
}


fun interface PendingSource {
    suspend fun seenBetween(since: Long, until: Long): List<NetworkRecord>
}


interface Watermark {
    var value: Long
}

data class UploadResult(
    val sent: Int,

    val rejected: Int,
    val chunks: Int,
    val ok: Boolean,
    val error: String? = null,
) {
    val nothingToSend: Boolean get() = ok && sent == 0 && rejected == 0 && chunks == 0
}














class CloudUploader(
    private val poster: HttpPoster,
    private val chunkSize: Int = 1000,
    private val retryDelaysMs: List<Long> = listOf(2_000L, 5_000L),
    private val sleeper: suspend (Long) -> Unit = { delay(it) },
) {
    private class Failure(override val message: String) : Exception(message)

    suspend fun uploadPending(
        endpoint: CloudUrl.Endpoint,
        token: String,
        device: String,
        source: PendingSource,
        watermark: Watermark,
        untilMs: Long,
    ): UploadResult {
        val pending = source.seenBetween(watermark.value, untilMs)
        if (pending.isEmpty()) {
            watermark.value = maxOf(watermark.value, untilMs)
            return UploadResult(0, 0, 0, ok = true)
        }
        var sent = 0
        var rejected = 0
        var chunks = 0
        for (chunk in pending.chunked(chunkSize)) {
            try {
                val counts = sendChunk(endpoint, token, device, chunk)
                sent += counts.first
                rejected += counts.second
                chunks++
                watermark.value = maxOf(watermark.value, chunk.last().lastSeenAtMs)
            } catch (f: Failure) {
                return UploadResult(sent, rejected, chunks, ok = false, error = f.message)
            }
        }
        watermark.value = maxOf(watermark.value, untilMs)
        return UploadResult(sent, rejected, chunks, ok = true)
    }


    private suspend fun sendChunk(endpoint: CloudUrl.Endpoint, token: String, device: String, chunk: List<NetworkRecord>): Pair<Int, Int> {
        val json = IngestJson.encode(device, chunk)
        var attempt = 0
        while (true) {
            val res = try {
                poster.request("POST", endpoint.ingest, token, json)
            } catch (e: IOException) {
                if (attempt >= retryDelaysMs.size) throw Failure("sem rede para a nuvem (${e.javaClass.simpleName})")
                sleeper(retryDelaysMs[attempt++])
                continue
            }
            when (res.status) {
                in 200..299 -> return chunk.size to 0
                401, 403 -> throw Failure("token de envio recusado pelo servidor (HTTP ${res.status})")
                413, 422 -> {
                    if (chunk.size == 1) return 0 to 1
                    val mid = chunk.size / 2
                    val a = sendChunk(endpoint, token, device, chunk.subList(0, mid))
                    val b = sendChunk(endpoint, token, device, chunk.subList(mid, chunk.size))
                    return (a.first + b.first) to (a.second + b.second)
                }
                408, 429, in 500..599 -> {
                    if (attempt >= retryDelaysMs.size) throw Failure("servidor indisponível (HTTP ${res.status})")
                    sleeper(retryDelaysMs[attempt++])
                }
                in 300..399 -> throw Failure("o servidor redirecionou (HTTP ${res.status}); use o endereço final em https")
                else -> throw Failure("HTTP ${res.status}: ${res.body.take(160)}")
            }
        }
    }


    fun ping(endpoint: CloudUrl.Endpoint, token: String): String? = try {
        val res = poster.request("GET", endpoint.ping, token, null)
        when (res.status) {
            in 200..299 -> null
            401, 403 -> "token recusado pelo servidor (HTTP ${res.status})"
            404 -> "o servidor não tem /api/v1/ping (endereço errado?)"
            in 300..399 -> "o servidor redirecionou (HTTP ${res.status}); use o endereço final em https"
            else -> "HTTP ${res.status}"
        }
    } catch (e: IOException) {
        "sem conexão com o servidor (${e.javaClass.simpleName}: ${e.message ?: "?"})"
    }
}
