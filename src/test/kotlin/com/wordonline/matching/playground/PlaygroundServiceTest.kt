package com.wordonline.matching.playground

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.wordonline.matching.server.entity.Server
import com.wordonline.matching.server.service.GameServerManagementService
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.web.reactive.function.client.ClientResponse
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.server.ResponseStatusException
import reactor.core.publisher.Mono
import java.time.Instant

class PlaygroundServiceTest {
    private val servers: GameServerManagementService = mock()
    private val sent = mutableListOf<String>()
    private val first = Server(id = 1, protocol = "http", domain = "localhost", port = 7001)
    private val second = Server(id = 2, protocol = "http", domain = "localhost", port = 7002)
    private val expiry = Instant.now().plusSeconds(300)
    private fun ready(ownerId: Long = 7) =
        """{"sessionId":"playground-test","server":"http://localhost:7002","webSocketUrl":"http://localhost:7002/ws","ownerId":$ownerId,"expiresAt":"$expiry"}"""

    private fun service(respond: (String) -> ClientResponse): PlaygroundService {
        whenever(servers.getAvailableServers()).thenReturn(listOf(first, second))
        whenever(servers.callUrl(first)).thenReturn(first.url)
        whenever(servers.callUrl(second)).thenReturn(second.url)
        val builder = WebClient.builder().exchangeFunction { request ->
            val url = request.url().toString()
            sent += url
            Mono.just(respond(url))
        }
        return PlaygroundService(servers, builder, PlaygroundProperties(enabled = true))
    }

    @Test fun disabledFeatureRegistersNoRoutesOrSessionService() {
        ApplicationContextRunner().withUserConfiguration(PlaygroundController::class.java, PlaygroundService::class.java)
            .run { context ->
                assertThat(context).hasNotFailed().doesNotHaveBean(PlaygroundController::class.java)
                    .doesNotHaveBean(PlaygroundService::class.java)
            }
    }

    @Test fun skipsUnsupportedServersAndReturnsTheAcceptingServerWithoutOrdinaryMatchDependencies() = runTest {
        val service = service { url ->
            if (url.contains(":7001")) ClientResponse.create(HttpStatus.NOT_FOUND).build()
            else ClientResponse.create(HttpStatus.OK).header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .body(ready()).build()
        }
        val response = service.create(7)
        assertThat(response.ownerId).isEqualTo(7)
        assertThat(response.server).isEqualTo("http://localhost:7002")
        assertThat(response.expiresAt).isEqualTo(expiry)
        assertThat(sent).containsExactly("http://localhost:7001/api/server/playgrounds", "http://localhost:7002/api/server/playgrounds")
    }

    @Test fun doesNotAcceptWrongOwnerAndReportsNoDevelopmentServer() = runTest {
        val service = service {
            ClientResponse.create(HttpStatus.OK).header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .body(ready(8)).build()
        }
        val error = expectFailure { service.create(7) }
        assertThat(error.statusCode).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)
    }

    @Test fun missingServersAndInvalidOwnerFailWithoutSendingRequests() = runTest {
        val service = service { error("No request expected") }
        whenever(servers.getAvailableServers()).thenReturn(emptyList())
        expectFailure { service.create(7) }
        expectFailure { service.create(-1) }
        assertThat(sent).isEmpty()
    }

    @Test fun serverCreationContractUsesOnlyTrustedOwnerIdentity() {
        val mapper = jacksonObjectMapper()
        assertThat(mapper.writeValueAsString(CreatePlaygroundRequest(7))).isEqualTo("""{"ownerId":7}""")
    }

    private suspend fun expectFailure(action: suspend () -> Unit): ResponseStatusException {
        try { action() } catch (error: ResponseStatusException) { return error }
        throw AssertionError("Expected ResponseStatusException")
    }
}
