package com.wordonline.matching.preview

import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.withTimeoutOrNull
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import org.springframework.http.client.reactive.ReactorClientHttpConnector
import reactor.netty.http.client.HttpClient
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient

@Component
class PreviewClient(private val builder: WebClient.Builder, private val properties: PreviewProperties) {
    private fun client(url: String) = builder.clone().baseUrl(url)
        .clientConnector(ReactorClientHttpConnector(HttpClient.create().compress(true)))
        .codecs { it.defaultCodecs().maxInMemorySize(properties.maxClipBytes) }.build()

    suspend fun catalog(url: String): PreviewCatalog = withTimeoutOrNull(properties.timeout.toMillis()) {
        client(url).get().uri("/api/server/magic-previews").retrieve()
            .bodyToMono(PreviewCatalog::class.java).awaitSingle()
    } ?: throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE)

    suspend fun recording(url: String, name: String, revision: String): ByteArray =
        withTimeoutOrNull(properties.timeout.toMillis()) {
            client(url).get().uri { uri ->
                uri.path("/api/server/magic-previews/{name}").queryParam("revision", revision).build(name)
            }.retrieve().bodyToMono(ByteArray::class.java).awaitSingle()
        } ?: throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE)
}
