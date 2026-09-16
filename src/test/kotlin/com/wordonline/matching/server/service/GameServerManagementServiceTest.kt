package com.wordonline.matching.server.service

import com.wordonline.matching.server.client.GameServerClient
import com.wordonline.matching.server.config.GameServerProperties
import com.wordonline.matching.server.entity.Server
import com.wordonline.matching.server.entity.ServerState
import com.wordonline.matching.server.entity.ServerType
import com.wordonline.matching.session.repository.ServerRepository
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.stub
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import reactor.core.publisher.Flux

class GameServerManagementServiceTest {

    private val failureThreshold = 3
    private val alphaUrl = "http://alpha:9090"
    private val betaUrl = "http://beta:9090"

    private val serverRepository: ServerRepository = mock()
    private val gameServerClient: GameServerClient = mock()

    private val properties = GameServerProperties(failureThreshold = failureThreshold)
    private val serverHealthRegistry = ServerHealthRegistry(properties)
    private val gameServerManagementService =
        GameServerManagementService(serverRepository, gameServerClient, serverHealthRegistry)

    private fun server(id: Long, domain: String, state: ServerState, internalBaseUrl: String? = null): Server =
        Server(
            id = id,
            protocol = "http",
            domain = domain,
            port = 9090,
            state = state,
            type = ServerType.GAME,
            internalBaseUrl = internalBaseUrl,
        )

    private fun stubDiscovery(vararg servers: Server) {
        // re-created per call: every refresh re-reads the row and hands back a brand new
        // Server instance, so health history can only survive if it is keyed by server id.
        whenever(serverRepository.findAllByType(ServerType.GAME))
            .thenAnswer { Flux.fromIterable(servers.toList()) }
    }

    private fun stubHealthcheck(serverUrl: String, healthy: Boolean) {
        gameServerClient.stub { onBlocking { healthcheck(serverUrl) } doReturn healthy }
    }

    private fun stubHealthcheckThrows(serverUrl: String, error: Throwable) {
        gameServerClient.stub { onBlocking { healthcheck(serverUrl) } doThrow error }
    }

    private fun availableIds(): List<Long> =
        gameServerManagementService.getAvailableServers().mapNotNull { it.id }

    @Test
    fun `한 서버의 헬스체크 실패가 다른 서버의 상태 갱신을 막지 않는다`() = runTest {
        stubDiscovery(
            server(1L, "alpha", ServerState.ACTIVE),
            server(2L, "beta", ServerState.ACTIVE),
        )
        stubHealthcheckThrows(alphaUrl, IllegalStateException("boom"))
        stubHealthcheck(betaUrl, true)

        gameServerManagementService.refresh()

        assertThat(availableIds()).containsExactly(2L)
        assertThat(serverHealthRegistry.consecutiveFailures(1L)).isEqualTo(1)
    }

    @Test
    fun `DB가 INACTIVE로 표시한 서버도 헬스체크에 성공하면 배정 대상이 된다`() = runTest {
        stubDiscovery(server(1L, "alpha", ServerState.INACTIVE))
        stubHealthcheck(alphaUrl, true)

        gameServerManagementService.refresh()

        assertThat(availableIds()).containsExactly(1L)
    }

    @Test
    fun `DRAINING 서버는 헬스체크에 성공해도 배정 대상에서 제외된다`() = runTest {
        stubDiscovery(
            server(1L, "alpha", ServerState.DRAINING),
            server(2L, "beta", ServerState.ACTIVE),
        )
        stubHealthcheck(alphaUrl, true)
        stubHealthcheck(betaUrl, true)

        gameServerManagementService.refresh()

        assertThat(availableIds()).containsExactly(2L)
        assertThat(gameServerManagementService.getAvailableServer()?.id).isEqualTo(2L)
    }

    @Test
    fun `연속 실패가 임계값에 도달해야 사용 불가로 판정하고 성공 한 번에 즉시 복구한다`() = runTest {
        stubDiscovery(server(1L, "alpha", ServerState.ACTIVE))

        stubHealthcheck(alphaUrl, true)
        gameServerManagementService.refresh()
        assertThat(availableIds()).containsExactly(1L)

        stubHealthcheck(alphaUrl, false)
        gameServerManagementService.refresh()
        assertThat(availableIds()).`as`("1회 실패로는 내리지 않는다").containsExactly(1L)
        gameServerManagementService.refresh()
        assertThat(availableIds()).`as`("2회 실패로도 내리지 않는다").containsExactly(1L)
        gameServerManagementService.refresh()
        assertThat(availableIds()).`as`("3회 연속 실패면 내린다").isEmpty()

        stubHealthcheck(alphaUrl, true)
        gameServerManagementService.refresh()
        assertThat(availableIds()).`as`("성공 1회면 즉시 복구한다").containsExactly(1L)
        assertThat(serverHealthRegistry.consecutiveFailures(1L)).isZero()
    }

    @Test
    fun `한 번도 성공하지 못한 서버는 배정 대상이 아니다`() = runTest {
        stubDiscovery(server(1L, "alpha", ServerState.ACTIVE))
        stubHealthcheck(alphaUrl, false)

        gameServerManagementService.refresh()

        assertThat(availableIds()).isEmpty()
    }

    @Test
    fun `리로드해도 헬스 이력이 유지된다`() = runTest {
        stubDiscovery(server(1L, "alpha", ServerState.ACTIVE))

        stubHealthcheck(alphaUrl, true)
        gameServerManagementService.refresh()

        stubHealthcheck(alphaUrl, false)
        gameServerManagementService.refresh()
        assertThat(serverHealthRegistry.consecutiveFailures(1L)).isEqualTo(1)
        gameServerManagementService.refresh()
        assertThat(serverHealthRegistry.consecutiveFailures(1L)).isEqualTo(2)
        assertThat(availableIds()).containsExactly(1L)
        gameServerManagementService.refresh()
        assertThat(serverHealthRegistry.consecutiveFailures(1L)).isEqualTo(failureThreshold)
        assertThat(availableIds()).isEmpty()
    }

    @Test
    fun `internal_base_url이 있는 서버는 healthcheck을 내부 주소로 보낸다`() = runTest {
        val internalUrl = "http://ac-game-alpha:8080"
        stubDiscovery(server(1L, "alpha", ServerState.ACTIVE, internalBaseUrl = internalUrl))
        stubHealthcheck(internalUrl, true)

        gameServerManagementService.refresh()

        assertThat(availableIds()).containsExactly(1L)
        verify(gameServerClient, never()).healthcheck(alphaUrl)
    }

    @Test
    fun `내부 주소로 healthcheck이 성공하면 그 주소가 응답 주소로 기록된다`() = runTest {
        val internalUrl = "http://ac-game-alpha:8080"
        stubDiscovery(server(1L, "alpha", ServerState.ACTIVE, internalBaseUrl = internalUrl))
        stubHealthcheck(internalUrl, true)

        gameServerManagementService.refresh()

        assertThat(serverHealthRegistry.respondingUrl(1L)).isEqualTo(internalUrl)
    }

    @Test
    fun `내부 주소가 실패하고 공개 주소가 성공하면 healthy로 남고 공개 주소가 기록된다`() = runTest {
        val internalUrl = "http://ac-game-alpha:8080"
        stubDiscovery(server(1L, "alpha", ServerState.ACTIVE, internalBaseUrl = internalUrl))
        // The internal address is configured but unreachable from this lobby - e.g. the game
        // server lives on separate infrastructure - while the public address still works.
        stubHealthcheck(internalUrl, false)
        stubHealthcheck(alphaUrl, true)

        gameServerManagementService.refresh()

        assertThat(availableIds())
            .`as`("공개 주소로만 닿아도 서버는 healthy로 남아야 한다")
            .containsExactly(1L)
        assertThat(serverHealthRegistry.respondingUrl(1L))
            .`as`("응답 주소는 실제로 답한 공개 주소여야 한다 - 이 상태가 misconfigured internal_base_url을 WARN으로 남기는 조건이다")
            .isEqualTo(alphaUrl)
    }

    @Test
    fun `내부 주소와 공개 주소 모두 healthcheck에 실패하면 unhealthy로 남는다`() = runTest {
        val internalUrl = "http://ac-game-alpha:8080"
        stubDiscovery(server(1L, "alpha", ServerState.ACTIVE, internalBaseUrl = internalUrl))
        stubHealthcheck(internalUrl, false)
        stubHealthcheck(alphaUrl, false)

        gameServerManagementService.refresh()

        assertThat(availableIds()).isEmpty()
        assertThat(serverHealthRegistry.isHealthy(1L)).isFalse()
    }

    @Test
    fun `사라진 서버의 헬스 이력은 정리된다`() = runTest {
        stubDiscovery(server(1L, "alpha", ServerState.ACTIVE))
        stubHealthcheck(alphaUrl, true)
        gameServerManagementService.refresh()
        assertThat(serverHealthRegistry.isHealthy(1L)).isTrue()

        serverHealthRegistry.replaceServers(emptyList())

        assertThat(serverHealthRegistry.isHealthy(1L)).isFalse()
        assertThat(gameServerManagementService.getAvailableServers()).isEmpty()
    }
}
