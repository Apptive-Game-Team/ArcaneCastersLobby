package com.wordonline.matching.preview

import com.wordonline.matching.server.entity.Server
import com.wordonline.matching.server.service.GameServerManagementService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.Mockito.*
import org.springframework.http.HttpStatus
import org.springframework.web.reactive.function.client.ClientResponse
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.server.ResponseStatusException
import reactor.core.publisher.Mono
import java.security.MessageDigest
import java.time.Duration

class PreviewServiceTest {
    private val url = "http://preview-server:9090"
    private val servers = mock(GameServerManagementService::class.java)
    private val server = Server(id = 7)
    private val revision = "b".repeat(64)
    private val payload = "{\"version\":2,\"magic\":\"fire_shot\"}"
    private val hash = MessageDigest.getInstance("SHA-256").digest(payload.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun client(handler: (String) -> Mono<ClientResponse>, timeout: Duration = Duration.ofSeconds(2)): PreviewClient =
        PreviewClient(WebClient.builder().exchangeFunction { request -> handler(request.url().toString()) }, PreviewProperties(timeout))

    private fun prepare() {
        `when`(servers.getAvailableServer()).thenReturn(server)
        `when`(servers.getAvailableServers()).thenReturn(listOf(server))
        `when`(servers.callUrl(server)).thenReturn(url)
    }

    @Test fun `catalog pins the selected registry ID and preserves ready metadata`() = runBlocking<Unit> {
        prepare()
        val client = client({ requested ->
            assertThat(requested).isEqualTo("$url/api/server/magic-previews")
            Mono.just(ClientResponse.create(HttpStatus.OK).header("Content-Type", "application/json")
                .body("""{"status":"ready","revision":"$revision","magics":[{"name":"fire_shot","hash":"$hash","bytes":${payload.length},"available":true}]}""").build())
        })
        val catalog = PreviewService(servers, client).catalog()
        assertThat(catalog.serverId).isEqualTo(7)
        assertThat(catalog.magics.single().hash).isEqualTo(hash)
    }

    @Test fun `download remains pinned to catalog server and verifies content hash`() = runBlocking<Unit> {
        prepare()
        val client = client({ requested ->
            assertThat(requested).contains("/api/server/magic-previews/fire_shot?revision=$revision")
            Mono.just(ClientResponse.create(HttpStatus.OK).header("Content-Type", "application/json").body(payload).build())
        })
        val service = PreviewService(servers, client)
        assertThat(service.recording(7, "fire_shot", revision, hash).toString(Charsets.UTF_8)).isEqualTo(payload)
        assertThatThrownBy { runBlocking { service.recording(7, "fire_shot", revision, "a".repeat(64)) } }
            .isInstanceOf(ResponseStatusException::class.java)
        assertThatThrownBy { runBlocking { service.recording(8, "fire_shot", revision, hash) } }
            .isInstanceOf(ResponseStatusException::class.java)
    }

    @Test fun `no available server and upstream timeout return unavailable catalogs`() = runBlocking<Unit> {
        val client = client({ Mono.never() }, Duration.ofMillis(25))
        val service = PreviewService(servers, client)
        assertThat(service.catalog().status).isEqualTo("unavailable")
        prepare()
        assertThat(service.catalog().status).isEqualTo("unavailable")
    }

    @Test fun `a changed server revision is returned as conflict instead of another server retry`() = runBlocking<Unit> {
        prepare()
        val client = client({ Mono.just(ClientResponse.create(HttpStatus.CONFLICT).build()) })
        try {
            PreviewService(servers, client).recording(7, "fire_shot", revision, hash)
            throw AssertionError("Expected conflict")
        } catch (e: ResponseStatusException) { assertThat(e.statusCode.value()).isEqualTo(409) }
    }

    @Test fun `caller cancellation is never converted into unavailable`() = runBlocking<Unit> {
        prepare()
        val client = client({ Mono.error(CancellationException("cancelled")) })
        try {
            PreviewService(servers, client).catalog()
            throw AssertionError("Expected cancellation")
        } catch (e: CancellationException) { assertThat(e.message).isEqualTo("cancelled") }
    }
}
