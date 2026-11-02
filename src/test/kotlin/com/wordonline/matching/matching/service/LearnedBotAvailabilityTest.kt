package com.wordonline.matching.matching.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.wordonline.matching.matching.config.LearnedBotProperties
import com.wordonline.matching.server.entity.Server
import com.wordonline.matching.server.entity.ServerState
import com.wordonline.matching.server.entity.ServerType
import com.wordonline.matching.server.service.GameServerManagementService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.web.reactive.function.client.ClientResponse
import org.springframework.web.reactive.function.client.WebClient
import reactor.core.publisher.Mono
import java.nio.file.Path
import java.nio.file.Files
import java.time.Duration

class LearnedBotAvailabilityTest {
    @TempDir lateinit var directory: Path

    private fun service(response: () -> Mono<ClientResponse>): LearnedBotAvailability {
        val file = directory.resolve("game-config.json")
        Files.writeString(file, """{"assignments":{"-7":"test-v9"}}""")
        val server = Server(id = 1, protocol = "http", domain = "game.test", port = 80,
            state = ServerState.ACTIVE, type = ServerType.GAME)
        val servers: GameServerManagementService = mock()
        whenever(servers.getAvailableServers()).thenReturn(listOf(server))
        whenever(servers.callUrl(server)).thenReturn("http://game.test")
        return LearnedBotAvailability(WebClient.builder().exchangeFunction { response() }, servers,
            LearnedBotProperties(file.toString(), Duration.ofMillis(30)), ObjectMapper())
    }

    private fun ready(body: String) = Mono.just(ClientResponse.create(HttpStatus.OK)
        .header("Content-Type", MediaType.APPLICATION_JSON_VALUE).body(body).build())

    @Test
    fun `모델 부재와 재시작을 매 요청에서 확인하고 일반 봇은 허용한다`() = runTest {
        var body = "{}"
        val service = service { ready(body) }
        assertThat(service.unavailableBotIds()).containsExactly(-7L)
        assertThat(service.permits("http://game.test", listOf(-7))).isFalse()
        assertThat(service.permits("http://game.test", listOf(-8, 1))).isTrue()
        body = """{"-7":"test-v9"}"""
        assertThat(service.unavailableBotIds()).isEmpty()
        assertThat(service.permits("http://game.test", listOf(-7))).isTrue()
        body = "{}"
        assertThat(service.permits("http://game.test", listOf(-7))).isFalse()
    }

    @Test
    fun `구버전 서버와 잘못된 버전 및 연결 오류는 모델 봇을 제외한다`() = runTest {
        for (response in listOf<() -> Mono<ClientResponse>>(
            { Mono.just(ClientResponse.create(HttpStatus.NOT_FOUND).build()) },
            { ready("""{"-7":"other"}""") },
            { ready("invalid json") },
            { Mono.error(IllegalStateException("offline")) },
            { Mono.never() },
        )) {
            assertThat(service(response).unavailableBotIds()).containsExactly(-7L)
        }
    }

    @Test
    fun `취소는 가용성 실패로 삼키지 않는다`() = runTest {
        val service = service { Mono.error(CancellationException("cancelled")) }
        val failure = runCatching { service.permits("http://game.test", listOf(-7)) }.exceptionOrNull()
        assertThat(failure).isInstanceOf(CancellationException::class.java)
    }
}
