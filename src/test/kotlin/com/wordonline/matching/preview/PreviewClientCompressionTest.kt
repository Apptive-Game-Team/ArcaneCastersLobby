package com.wordonline.matching.preview

import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.web.reactive.function.client.WebClient
import reactor.core.publisher.Mono
import reactor.netty.http.server.HttpServer
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream

class PreviewClientCompressionTest {
    @Test fun `compressed service response is decoded before replay hash verification`() = runBlocking<Unit> {
        val payload = "{\"version\":2,\"magic\":\"fire_shot\"}".toByteArray()
        val buffer = ByteArrayOutputStream()
        GZIPOutputStream(buffer).use { it.write(payload) }
        var authorized = false
        val server = HttpServer.create().host("127.0.0.1").port(0).handle { request, response ->
            authorized = request.requestHeaders().get(HttpHeaders.AUTHORIZATION) == "Bearer test-service"
            response.header(HttpHeaders.CONTENT_TYPE, "application/json").header(HttpHeaders.CONTENT_ENCODING, "gzip")
                .sendByteArray(Mono.just(buffer.toByteArray()))
        }.bindNow()
        try {
            val client = PreviewClient(WebClient.builder().defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer test-service"), PreviewProperties())
            val bytes = client.recording("http://127.0.0.1:${server.port()}", "fire_shot", "b".repeat(64))
            assertThat(bytes).isEqualTo(payload)
            assertThat(authorized).isTrue()
        } finally { server.disposeNow() }
    }
}
