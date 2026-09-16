package com.wordonline.matching.server.service

import com.wordonline.matching.server.client.GameServerClient
import com.wordonline.matching.server.config.GameServerProperties
import com.wordonline.matching.server.dto.RoomInfoDto
import com.wordonline.matching.server.dto.RoomListDto
import com.wordonline.matching.server.entity.Server
import com.wordonline.matching.server.entity.ServerState
import com.wordonline.matching.server.entity.ServerType
import com.wordonline.matching.session.repository.ServerRepository
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.time.Instant

/**
 * [GameSessionService.fetchGameSessionsFromServer] is the one call site this feature must
 * NOT flip fully to the internal address: the outbound request may go internal, but
 * [RoomInfoDto.serverUrl] rides [com.wordonline.matching.matching.dto.MatchedInfoDto.server]
 * to the Unity client, which cannot reach the docker network.
 */
class GameSessionServiceTest {

    private val serverRepository: ServerRepository = mock()
    private val gameServerClient: GameServerClient = mock()
    private val serverHealthRegistry = ServerHealthRegistry(GameServerProperties())
    private val gameSessionService = GameSessionService(serverRepository, gameServerClient, serverHealthRegistry)

    private fun server(id: Long, domain: String, internalBaseUrl: String? = null) = Server(
        id = id,
        protocol = "http",
        domain = domain,
        port = 9090,
        state = ServerState.ACTIVE,
        type = ServerType.GAME,
        internalBaseUrl = internalBaseUrl,
    )

    private fun stubDiscovery(vararg servers: Server) {
        whenever(serverRepository.findAllByTypeAndState(ServerType.GAME, ServerState.ACTIVE))
            .thenReturn(Flux.fromIterable(servers.toList()))
    }

    private fun room(serverUrl: String) = RoomListDto(
        listOf(RoomInfoDto("session-1", 1L, 2L, serverUrl, Instant.now())),
    )

    @Test
    fun `internal_base_url이 있으면 요청은 내부 주소로 나가고 응답의 room에는 공개 주소가 담긴다`() = runTest {
        val internalUrl = "http://ac-game-alpha:8080"
        val publicUrl = "http://alpha:9090"
        stubDiscovery(server(1L, "alpha", internalBaseUrl = internalUrl))
        whenever(gameServerClient.getGameSessionsOrThrow(eq(internalUrl))).thenReturn(Mono.just(room("ignored")))

        val result = gameSessionService.getAllGameSessions().awaitSingle()

        verify(gameServerClient).getGameSessionsOrThrow(internalUrl)
        verify(gameServerClient, never()).getGameSessionsOrThrow(publicUrl)
        assertThat(result.rooms().map { it.serverUrl() })
            .`as`("클라이언트로 나가는 RoomInfoDto.serverUrl은 항상 공개 주소여야 한다")
            .containsExactly(publicUrl)
    }

    @Test
    fun `internal_base_url이 없으면 요청과 응답 모두 공개 주소를 쓴다`() = runTest {
        val publicUrl = "http://alpha:9090"
        stubDiscovery(server(1L, "alpha", internalBaseUrl = null))
        whenever(gameServerClient.getGameSessionsOrThrow(eq(publicUrl))).thenReturn(Mono.just(room("ignored")))

        val result = gameSessionService.getAllGameSessions().awaitSingle()

        verify(gameServerClient).getGameSessionsOrThrow(publicUrl)
        assertThat(result.rooms().map { it.serverUrl() }).containsExactly(publicUrl)
    }

    @Test
    fun `요청이 내부 주소로 실패하면 공개 주소로 한 번 재시도하고 room의 serverUrl은 공개 주소다`() = runTest {
        val internalUrl = "http://ac-game-alpha:8080"
        val publicUrl = "http://alpha:9090"
        stubDiscovery(server(1L, "alpha", internalBaseUrl = internalUrl))
        whenever(gameServerClient.getGameSessionsOrThrow(eq(internalUrl)))
            .thenReturn(Mono.error(IllegalStateException("connection refused")))
        whenever(gameServerClient.getGameSessionsOrThrow(eq(publicUrl))).thenReturn(Mono.just(room("ignored")))

        val result = gameSessionService.getAllGameSessions().awaitSingle()

        verify(gameServerClient).getGameSessionsOrThrow(internalUrl)
        verify(gameServerClient).getGameSessionsOrThrow(publicUrl)
        assertThat(result.rooms().map { it.serverUrl() })
            .`as`("내부 주소 재시도 이후에도 room의 serverUrl은 공개 주소여야 한다")
            .containsExactly(publicUrl)
    }

    @Test
    fun `registry가 기록한 응답 주소가 있으면 요청은 그 주소로 나간다`() = runTest {
        val internalUrl = "http://ac-game-alpha:8080"
        val publicUrl = "http://alpha:9090"
        val target = server(1L, "alpha", internalBaseUrl = internalUrl)
        stubDiscovery(target)
        // A previous health probe found only the public address reachable; the request path
        // must prefer that recorded address over re-deriving Server.callUrl from scratch.
        serverHealthRegistry.replaceServers(listOf(target))
        serverHealthRegistry.recordProbe(1L, healthy = true, respondingUrl = publicUrl)
        whenever(gameServerClient.getGameSessionsOrThrow(eq(publicUrl))).thenReturn(Mono.just(room("ignored")))

        val result = gameSessionService.getAllGameSessions().awaitSingle()

        verify(gameServerClient).getGameSessionsOrThrow(publicUrl)
        verify(gameServerClient, never()).getGameSessionsOrThrow(internalUrl)
        assertThat(result.rooms().map { it.serverUrl() }).containsExactly(publicUrl)
    }

    @Test
    fun `내부 주소와 공개 주소 모두 실패하면 빈 room 목록을 반환한다`() = runTest {
        val publicUrl = "http://alpha:9090"
        stubDiscovery(server(1L, "alpha", internalBaseUrl = null))
        whenever(gameServerClient.getGameSessionsOrThrow(eq(publicUrl)))
            .thenReturn(Mono.error(IllegalStateException("connection refused")))

        val result = gameSessionService.getAllGameSessions().awaitSingle()

        assertThat(result.rooms()).isEmpty()
    }
}
