package com.wordonline.matching.matching.service

import com.wordonline.matching.auth.dto.UserDetailResponseDto
import com.wordonline.matching.auth.service.UserService
import com.wordonline.matching.matching.config.MatchTicketProperties
import com.wordonline.matching.matching.domain.MatchTicket
import com.wordonline.matching.matching.domain.MatchTicketState
import com.wordonline.matching.matching.dto.MatchedInfoDto
import com.wordonline.matching.matching.repository.MatchTicketRepository
import com.wordonline.matching.matching.repository.RedisScriptSandbox
import com.wordonline.matching.matching.repository.sandboxMatchTicketRepository
import com.wordonline.matching.server.config.GameServerProperties
import com.wordonline.matching.server.entity.Server
import com.wordonline.matching.server.entity.ServerState
import com.wordonline.matching.server.entity.ServerType
import com.wordonline.matching.server.exception.GameServerUnreachableException
import com.wordonline.matching.server.service.ServerHealthRegistry
import com.wordonline.matching.session.service.LegacyGameMatchService
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder
import reactor.core.publisher.Mono
import java.time.Duration
import java.time.Instant

/**
 * The reconciler is the only thing watching a `MATCHED` ticket when both clients are gone,
 * so its bias matters in both directions: it must eventually free a ticket whose host died,
 * and it must not free one just because a probe was missed.
 */
class MatchedTicketReconcilerTest {

    private val objectMapper = Jackson2ObjectMapperBuilder.json().build<com.fasterxml.jackson.databind.ObjectMapper>()
    private val sandbox = RedisScriptSandbox()
    private val properties = MatchTicketProperties(matchedScanGrace = Duration.ofSeconds(30))
    private val repository = sandboxMatchTicketRepository(sandbox, properties)
    private val legacyGameMatchService: LegacyGameMatchService = mock()
    private val userService: UserService = mock()
    private val serverProperties = GameServerProperties(failureThreshold = 3)
    private val serverHealthRegistry = ServerHealthRegistry(serverProperties)

    private val probe = SessionLivenessProbe(legacyGameMatchService, serverHealthRegistry)
    private val recovery = LostSessionRecovery(repository, userService)
    private val reconciler = MatchedTicketReconciler(repository, probe, recovery, serverHealthRegistry, properties)

    private val host = "http://127.0.0.1:7777"
    private val server = Server(
        id = 7L,
        protocol = "http",
        domain = "127.0.0.1",
        port = 7777,
        state = ServerState.ACTIVE,
        type = ServerType.GAME,
        instanceId = "boot-1",
    )

    init {
        whenever(userService.markOnline(any())).thenReturn(Mono.empty())
        serverHealthRegistry.replaceServers(listOf(server))
        serverHealthRegistry.recordProbe(7L, true)
    }

    private fun seedMatchedPair(sessionId: String = "session-1", matchedAt: Instant = Instant.now().minusSeconds(600)) {
        val info = MatchedInfoDto(
            message = "Successfully Matched",
            server = host,
            leftUser = UserDetailResponseDto(1L, "left", "left@team6515.com"),
            rightUser = UserDetailResponseDto(2L, "right", "right@team6515.com"),
            sessionId = sessionId,
            webSocketUrl = "ws://127.0.0.1:7777/ws",
        )
        listOf("ticket-1" to 1L, "ticket-2" to 2L).forEach { (ticketId, userId) ->
            val ticket = MatchTicket(
                ticketId = ticketId,
                userId = userId,
                mmr = 1000L,
                state = MatchTicketState.MATCHED,
                version = 2L,
                matchInfo = info,
                serverId = 7L,
                serverInstanceId = "boot-1",
                createdAt = matchedAt,
                updatedAt = matchedAt,
            )
            sandbox.set("matching:ticket:$ticketId", objectMapper.writeValueAsString(ticket))
            sandbox.set("matching:active:$userId", ticketId)
            sandbox.zadd(MatchTicketRepository.MATCHED_KEY, ticketId, matchedAt.toEpochMilli().toDouble())
        }
    }

    @Test
    fun `호스트가 세션 없음을 답하면 양쪽 티켓을 정리한다`() = runTest {
        seedMatchedPair()
        whenever(legacyGameMatchService.isSessionActive(host, "session-1")).thenReturn(false)

        reconciler.reconcile()

        assertThat(decoded("ticket-1").state).isEqualTo(MatchTicketState.FAILED)
        assertThat(decoded("ticket-2").state).isEqualTo(MatchTicketState.FAILED)
        assertThat(decoded("ticket-1").reason)
            .`as`("같은 프로세스가 세션 없음을 답했으면 소실이 아니라 정상 종료다")
            .isEqualTo("SESSION_ENDED")
        assertThat(sandbox.get("matching:active:1")).isNull()
        assertThat(sandbox.get("matching:active:2")).isNull()
    }

    @Test
    fun `부팅 세대값이 바뀐 호스트의 티켓은 소실로 기록한다`() = runTest {
        seedMatchedPair()
        serverHealthRegistry.replaceServers(listOf(server.copy(instanceId = "boot-2")))
        serverHealthRegistry.recordProbe(7L, true)

        reconciler.reconcile()

        assertThat(decoded("ticket-1").reason).isEqualTo("SESSION_LOST")
        assertThat(decoded("ticket-2").reason).isEqualTo("SESSION_LOST")
    }

    @Test
    fun `호스트가 사라져 확정된 경우도 소실로 기록한다`() = runTest {
        seedMatchedPair()
        whenever(legacyGameMatchService.isSessionActive(host, "session-1"))
            .thenThrow(GameServerUnreachableException("unreachable"))
        repeat(serverProperties.failureThreshold) { serverHealthRegistry.recordProbe(7L, false) }

        reconciler.reconcile()

        assertThat(decoded("ticket-1").reason)
            .`as`("게임이 끝까지 갔는지 알 수 없으면 정상 종료로 적으면 안 된다")
            .isEqualTo("SESSION_LOST")
    }

    @Test
    fun `한 번의 무응답으로는 세션을 끝내지 않는다`() = runTest {
        seedMatchedPair()
        whenever(legacyGameMatchService.isSessionActive(host, "session-1"))
            .thenThrow(GameServerUnreachableException("unreachable"))
        // one failed probe: below the registry's threshold, so the server is still healthy
        serverHealthRegistry.recordProbe(7L, false)

        reconciler.reconcile()

        assertThat(decoded("ticket-1").state)
            .`as`("일시적 무응답을 세션 종료로 오판하면 안 된다")
            .isEqualTo(MatchTicketState.MATCHED)
        assertThat(sandbox.get("matching:active:1")).isEqualTo("ticket-1")
    }

    @Test
    fun `무응답이 임계치를 넘어 서버가 로테이션에서 빠지면 정리한다`() = runTest {
        seedMatchedPair()
        whenever(legacyGameMatchService.isSessionActive(host, "session-1"))
            .thenThrow(GameServerUnreachableException("unreachable"))
        repeat(serverProperties.failureThreshold) { serverHealthRegistry.recordProbe(7L, false) }

        reconciler.reconcile()

        assertThat(decoded("ticket-1").state).isEqualTo(MatchTicketState.FAILED)
        assertThat(decoded("ticket-2").state).isEqualTo(MatchTicketState.FAILED)
    }

    @Test
    fun `세션이 살아있으면 아무것도 건드리지 않는다`() = runTest {
        seedMatchedPair()
        whenever(legacyGameMatchService.isSessionActive(host, "session-1")).thenReturn(true)

        reconciler.reconcile()

        assertThat(decoded("ticket-1").state).isEqualTo(MatchTicketState.MATCHED)
        assertThat(decoded("ticket-2").state).isEqualTo(MatchTicketState.MATCHED)
        assertThat(sandbox.zmembers(MatchTicketRepository.MATCHED_KEY))
            .containsExactlyInAnyOrder("ticket-1", "ticket-2")
    }

    @Test
    fun `유예 시간 안에 있는 티켓은 감사하지 않는다`() = runTest {
        seedMatchedPair(matchedAt = Instant.now())
        whenever(legacyGameMatchService.isSessionActive(host, "session-1")).thenReturn(false)

        reconciler.reconcile()

        assertThat(decoded("ticket-1").state)
            .`as`("게임 서버가 세션 등록을 마치기 전에 감사하면 살아있는 세션을 죽인다")
            .isEqualTo(MatchTicketState.MATCHED)
    }

    @Test
    fun `신고됐지만 판정 못 한 티켓은 유예 시간을 기다리지 않고 다시 확인한다`() = runTest {
        seedMatchedPair(matchedAt = Instant.now())
        sandbox.zadd(MatchTicketRepository.PENDING_VERIFICATION_KEY, "ticket-1", Instant.now().toEpochMilli().toDouble())
        whenever(legacyGameMatchService.isSessionActive(host, "session-1")).thenReturn(false)

        reconciler.reconcilePending()

        assertThat(decoded("ticket-1").state).isEqualTo(MatchTicketState.FAILED)
        assertThat(decoded("ticket-2").state).isEqualTo(MatchTicketState.FAILED)
        assertThat(sandbox.zmembers(MatchTicketRepository.PENDING_VERIFICATION_KEY)).isEmpty()
    }

    @Test
    fun `재확인에서도 호스트가 침묵하면 대기열에 남겨 다음 주기에 다시 본다`() = runTest {
        seedMatchedPair()
        sandbox.zadd(MatchTicketRepository.PENDING_VERIFICATION_KEY, "ticket-1", Instant.now().toEpochMilli().toDouble())
        whenever(legacyGameMatchService.isSessionActive(host, "session-1"))
            .thenThrow(GameServerUnreachableException("unreachable"))
        serverHealthRegistry.recordProbe(7L, false)

        reconciler.reconcilePending()

        assertThat(decoded("ticket-1").state).isEqualTo(MatchTicketState.MATCHED)
        assertThat(sandbox.zmembers(MatchTicketRepository.PENDING_VERIFICATION_KEY))
            .`as`("확정 기준을 낮추지 않고 재확인만 반복한다")
            .containsExactly("ticket-1")
    }

    private fun decoded(ticketId: String): MatchTicket =
        objectMapper.readValue(sandbox.get("matching:ticket:$ticketId"), MatchTicket::class.java)
}
