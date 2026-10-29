package com.wordonline.matching.matching.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.wordonline.matching.auth.dto.UserDetailResponseDto
import com.wordonline.matching.auth.service.UserService
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
import com.wordonline.matching.server.service.ServerHealthRegistry
import com.wordonline.matching.session.service.LegacyGameMatchService
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder
import reactor.core.publisher.Mono
import java.time.Instant

/**
 * The notification exists to close a ticket the moment the game ends instead of a sweep
 * interval later, so its whole value is in being trusted without a round trip back to the
 * sender. That makes the guards the interesting part: it must not be usable to end a session
 * the sender no longer owns, and repeating it must be free.
 */
class SessionEndedNotificationServiceTest {

    private val objectMapper = Jackson2ObjectMapperBuilder.json().build<ObjectMapper>()
    private val sandbox = RedisScriptSandbox()
    private val repository = sandboxMatchTicketRepository(sandbox)
    private val legacyGameMatchService: LegacyGameMatchService = mock()
    private val userService: UserService = mock()
    private val serverHealthRegistry = ServerHealthRegistry(GameServerProperties())

    private val probe = SessionLivenessProbe(legacyGameMatchService, serverHealthRegistry)
    private val recovery = LostSessionRecovery(repository, userService)
    private val service = SessionEndedNotificationService(repository, probe, recovery)

    private val matchedAt = Instant.parse("2026-08-10T00:00:00Z")
    private val host = "http://localhost:7777"
    private val server = Server(
        id = 7L,
        protocol = "http",
        domain = "localhost",
        port = 7777,
        state = ServerState.ACTIVE,
        type = ServerType.GAME,
        instanceId = "boot-1",
    )

    init {
        whenever(userService.markOnline(any())).thenReturn(Mono.empty())
        serverHealthRegistry.replaceServers(listOf(server))
    }

    private fun matchInfo(sessionId: String) = MatchedInfoDto(
        message = "Successfully Matched",
        server = host,
        leftUser = UserDetailResponseDto(1L, "left", "left@team6515.com"),
        rightUser = UserDetailResponseDto(2L, "right", "right@team6515.com"),
        sessionId = sessionId,
        webSocketUrl = "ws://localhost:7777/ws",
    )

    private fun matchedTicket(
        ticketId: String,
        userId: Long,
        sessionId: String = "session-1",
        instanceId: String? = "boot-1",
    ) = MatchTicket(
        ticketId = ticketId,
        userId = userId,
        mmr = 1000L,
        state = MatchTicketState.MATCHED,
        version = 2L,
        matchInfo = matchInfo(sessionId),
        serverId = 7L,
        serverInstanceId = instanceId,
        createdAt = matchedAt,
        updatedAt = matchedAt,
    )

    private fun seed(ticket: MatchTicket) {
        sandbox.set("matching:ticket:${ticket.ticketId}", objectMapper.writeValueAsString(ticket))
        sandbox.set("matching:active:${ticket.userId}", ticket.ticketId)
        if (ticket.state == MatchTicketState.MATCHED) {
            sandbox.zadd(MatchTicketRepository.MATCHED_KEY, ticket.ticketId, ticket.updatedAt.toEpochMilli().toDouble())
        }
    }

    @Test
    fun `통보를 받으면 양쪽 티켓을 SESSION_ENDED로 즉시 정리한다`() = runTest {
        seed(matchedTicket("ticket-1", 1L))
        seed(matchedTicket("ticket-2", 2L))

        service.sessionEnded("session-1", "boot-1")

        assertThat(decoded("ticket-1").state).isEqualTo(MatchTicketState.FAILED)
        assertThat(decoded("ticket-2").state).isEqualTo(MatchTicketState.FAILED)
        assertThat(decoded("ticket-1").reason).isEqualTo("SESSION_ENDED")
        assertThat(decoded("ticket-2").reason).isEqualTo("SESSION_ENDED")
        assertThat(sandbox.get("matching:active:1")).isNull()
        assertThat(sandbox.get("matching:active:2")).isNull()
        assertThat(sandbox.zmembers(MatchTicketRepository.MATCHED_KEY)).isEmpty()
        verify(userService).markOnline(1L)
        verify(userService).markOnline(2L)
    }

    @Test
    fun `호스트에 세션 상태를 되묻지 않는다`() = runTest {
        seed(matchedTicket("ticket-1", 1L))
        seed(matchedTicket("ticket-2", 2L))

        service.sessionEnded("session-1", "boot-1")

        verifyNoInteractions(legacyGameMatchService)
    }

    @Test
    fun `다른 세션의 티켓은 건드리지 않는다`() = runTest {
        seed(matchedTicket("ticket-1", 1L, sessionId = "session-1"))
        seed(matchedTicket("ticket-9", 9L, sessionId = "session-9"))

        service.sessionEnded("session-1", "boot-1")

        assertThat(decoded("ticket-1").state).isEqualTo(MatchTicketState.FAILED)
        assertThat(decoded("ticket-9").state).isEqualTo(MatchTicketState.MATCHED)
    }

    @Test
    fun `재시작 뒤 도착한 통보는 무시한다`() = runTest {
        seed(matchedTicket("ticket-1", 1L))
        seed(matchedTicket("ticket-2", 2L))
        serverHealthRegistry.replaceServers(listOf(server.copy(instanceId = "boot-2")))

        service.sessionEnded("session-1", "boot-1")

        assertThat(decoded("ticket-1").state)
            .`as`("이미 교체된 프로세스의 통보로 티켓을 닫으면 reconciler가 소실로 기록할 기회를 잃는다")
            .isEqualTo(MatchTicketState.MATCHED)
        assertThat(decoded("ticket-2").state).isEqualTo(MatchTicketState.MATCHED)
        verify(userService, never()).markOnline(any())
    }

    @Test
    fun `세션을 받은 적 없는 프로세스의 통보는 무시한다`() = runTest {
        seed(matchedTicket("ticket-1", 1L, instanceId = "boot-1"))

        service.sessionEnded("session-1", "boot-0")

        assertThat(decoded("ticket-1").state).isEqualTo(MatchTicketState.MATCHED)
    }

    @Test
    fun `같은 통보가 반복돼도 실패하지 않고 이미 끝난 티켓을 바꾸지 않는다`() = runTest {
        seed(matchedTicket("ticket-1", 1L))
        seed(matchedTicket("ticket-2", 2L))

        service.sessionEnded("session-1", "boot-1")
        val afterFirst = decoded("ticket-1")
        service.sessionEnded("session-1", "boot-1")

        assertThat(decoded("ticket-1")).isEqualTo(afterFirst)
        assertThat(decoded("ticket-2").reason).isEqualTo("SESSION_ENDED")
        verify(userService, times(1)).markOnline(1L)
    }

    @Test
    fun `모르는 세션의 통보도 성공으로 지나간다`() = runTest {
        service.sessionEnded("session-unknown", "boot-1")

        verify(userService, never()).markOnline(any())
    }

    @Test
    fun `상대가 이미 재큐했으면 상대의 새 티켓은 건드리지 않는다`() = runTest {
        seed(matchedTicket("ticket-1", 1L))
        val requeued = MatchTicket(
            ticketId = "ticket-new",
            userId = 2L,
            mmr = 1000L,
            state = MatchTicketState.QUEUED,
            version = 1L,
            createdAt = matchedAt,
            updatedAt = matchedAt,
        )
        seed(requeued)

        service.sessionEnded("session-1", "boot-1")

        assertThat(decoded("ticket-new").state)
            .`as`("종료 통보가 상대의 새 매칭을 깨면 안 된다")
            .isEqualTo(MatchTicketState.QUEUED)
        assertThat(sandbox.get("matching:active:2")).isEqualTo("ticket-new")
        assertThat(decoded("ticket-1").state).isEqualTo(MatchTicketState.FAILED)
        verify(userService).markOnline(1L)
        verify(userService, never()).markOnline(2L)
    }

    @Test
    fun `lockstep outbox는 호스트와 수신 서비스 재시작 뒤에도 최초 placement 통보를 한 번 반영한다`() = runTest {
        val first = matchedTicket("ticket-1", 1L)
        val second = matchedTicket("ticket-2", 2L)
        seed(first.copy(matchInfo = first.matchInfo!!.copy(lockstepSimulationVersion = "lockstep-v1")))
        seed(second.copy(matchInfo = second.matchInfo!!.copy(lockstepSimulationVersion = "lockstep-v1")))
        serverHealthRegistry.replaceServers(listOf(server.copy(instanceId = "boot-2")))
        service.sessionEnded("session-1", "boot-1")
        val after = decoded("ticket-1")
        val restarted = SessionEndedNotificationService(repository, probe, recovery)
        restarted.sessionEnded("session-1", "boot-1")
        assertThat(decoded("ticket-1")).isEqualTo(after)
        assertThat(decoded("ticket-2").reason).isEqualTo("SESSION_ENDED")
        verify(userService, times(1)).markOnline(1L)
        verify(userService, times(1)).markOnline(2L)
    }

    @Test
    fun `lockstep도 최초 placement와 다른 송신자의 종료 통보는 거부한다`() = runTest {
        val original = matchedTicket("ticket-1", 1L)
        seed(original.copy(matchInfo = original.matchInfo!!.copy(lockstepSimulationVersion = "lockstep-v1")))
        service.sessionEnded("session-1", "boot-0")
        assertThat(decoded("ticket-1").state).isEqualTo(MatchTicketState.MATCHED)
        verify(userService, never()).markOnline(any())
    }

    private fun decoded(ticketId: String): MatchTicket =
        objectMapper.readValue(sandbox.get("matching:ticket:$ticketId"), MatchTicket::class.java)
}
