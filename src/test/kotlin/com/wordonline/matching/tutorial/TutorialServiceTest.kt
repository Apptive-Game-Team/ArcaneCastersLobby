package com.wordonline.matching.tutorial

import com.wordonline.matching.progression.ProgressionRepository
import com.wordonline.matching.server.entity.Server
import com.wordonline.matching.server.service.GameServerManagementService
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.*
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.web.reactive.function.client.ClientResponse
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.server.ResponseStatusException
import reactor.core.publisher.Mono
import java.time.Instant
import java.util.UUID

class TutorialServiceTest {
    private val progression: ProgressionRepository = mock()
    private val servers: GameServerManagementService = mock()
    private val first = Server(id = 1, protocol = "http", domain = "localhost", port = 7001)
    private val second = Server(id = 2, protocol = "http", domain = "localhost", port = 7002)
    private val sent = mutableListOf<String>()
    private fun service(respond: (Int) -> ClientResponse): TutorialService {
        whenever(servers.getAvailableServers()).thenReturn(listOf(first, second))
        whenever(servers.callUrl(any())).thenAnswer { (it.arguments[0] as Server).url }
        val builder = WebClient.builder().exchangeFunction { request ->
            sent += request.url().toString()
            Mono.just(respond(sent.size))
        }
        return TutorialService(progression, servers, builder, TutorialProperties())
    }
    private fun ready(owner: Long = 7, faction: String = "HELL"): ClientResponse = ClientResponse.create(HttpStatus.OK)
        .header("Content-Type", MediaType.APPLICATION_JSON_VALUE).body("""{"battleId":"tutorial-test","ownerId":$owner,"faction":"$faction","server":"http://localhost:7002","expiresAt":"${Instant.now().plusSeconds(1200)}"}""").build()

    @Test fun creationUsesStoredFactionAndKeepsTheSameServerAfterLostResponse(): Unit = runTest {
        whenever(progression.faction(7)).thenReturn("HELL")
        val service = service { if (it == 1) ClientResponse.create(HttpStatus.SERVICE_UNAVAILABLE).build() else ready() }
        val request = CreateTutorial(UUID.randomUUID().toString())
        assertThat(runCatching { service.create(7, request) }.exceptionOrNull()).isNotNull()
        whenever(servers.getAvailableServers()).thenReturn(listOf(first))
        val result = service.create(7, request)
        service.create(7, CreateTutorial(UUID.randomUUID().toString()))
        assertThat(result.faction).isEqualTo("HELL")
        assertThat(sent).hasSize(3)
        assertThat(sent[0]).isEqualTo(sent[1]).endsWith("/api/server/tutorial/battles")
        assertThat(sent[2]).isEqualTo(sent[0])
    }

    @Test fun unselectedFactionCannotStartAndDoesNotCallGameServer(): Unit = runTest {
        whenever(progression.faction(7)).thenReturn(null)
        val service = service { ready() }
        val failure = runCatching { service.create(7, CreateTutorial(UUID.randomUUID().toString())) }.exceptionOrNull() as ResponseStatusException
        assertThat(failure.statusCode).isEqualTo(HttpStatus.CONFLICT)
        assertThat(sent).isEmpty()
    }

    @Test fun mismatchedOwnerOrFactionIsNotReturnedToTheClient(): Unit = runTest {
        whenever(progression.faction(7)).thenReturn("NATURE")
        val service = service { ready(owner = 8, faction = "HELL") }
        val failure = runCatching { service.create(7, CreateTutorial(UUID.randomUUID().toString())) }.exceptionOrNull() as ResponseStatusException
        assertThat(failure.statusCode).isEqualTo(HttpStatus.BAD_GATEWAY)
    }
}
