package com.wordonline.matching.matching.service

import com.wordonline.matching.auth.dto.UserDetailResponseDto
import com.wordonline.matching.auth.service.UserService
import com.wordonline.matching.global.service.LocalizationService
import com.wordonline.matching.matching.domain.MatchTicket
import com.wordonline.matching.matching.domain.MatchTicketState
import com.wordonline.matching.matching.domain.SessionLostReport
import com.wordonline.matching.matching.dto.MatchedInfoDto
import com.wordonline.matching.matching.repository.MatchTicketRepository
import com.wordonline.matching.matching.repository.RedisScriptSandbox
import com.wordonline.matching.matching.repository.sandboxMatchTicketRepository
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
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder
import reactor.core.publisher.Mono
import java.time.Instant

/**
 * The endpoint's contract: a client report only points the lobby at a session, and the
 * lobby's own verification is the only thing that can end a ticket.
 */
class SessionLostReportServiceTest {

    private val objectMapper = Jackson2ObjectMapperBuilder.json().build<com.fasterxml.jackson.databind.ObjectMapper>()
    private val sandbox = RedisScriptSandbox()
    private val repository = sandboxMatchTicketRepository(sandbox)
    private val legacyGameMatchService: LegacyGameMatchService = mock()
    private val userService: UserService = mock()
    private val localizationService: LocalizationService = mock()
    private val serverHealthRegistry = ServerHealthRegistry(com.wordonline.matching.server.config.GameServerProperties())

    private val probe = SessionLivenessProbe(legacyGameMatchService, serverHealthRegistry)
    private val recovery = LostSessionRecovery(repository, userService)
    private val service = SessionLostReportService(repository, probe, recovery, localizationService)

    private val matchedAt = Instant.parse("2026-08-10T00:00:00Z")
    private val host = "http://127.0.0.1:7777"

    init {
        whenever(userService.markOnline(any())).thenReturn(Mono.empty())
        whenever(localizationService.getMessage(any(), any())).thenAnswer { it.getArgument(1) }
        serverHealthRegistry.replaceServers(
            listOf(
                Server(
                    id = 7L,
                    protocol = "http",
                    domain = "127.0.0.1",
                    port = 7777,
                    state = ServerState.ACTIVE,
                    type = ServerType.GAME,
                    instanceId = "boot-1",
                ),
            ),
        )
    }

    private fun matchInfo(sessionId: String) = MatchedInfoDto(
        message = "Successfully Matched",
        server = host,
        leftUser = UserDetailResponseDto(1L, "left", "left@team6515.com"),
        rightUser = UserDetailResponseDto(2L, "right", "right@team6515.com"),
        sessionId = sessionId,
        webSocketUrl = "ws://127.0.0.1:7777/ws",
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
    fun `세션이 살아있으면 신고해도 티켓을 끝내지 않는다`() = runTest {
        seed(matchedTicket("ticket-1", 1L))
        seed(matchedTicket("ticket-2", 2L))
        whenever(legacyGameMatchService.isSessionActive(host, "session-1")).thenReturn(true)

        val report = service.report(1L, "session-1")

        assertThat(report).isInstanceOf(SessionLostReport.SessionAlive::class.java)
        assertThat(repository.getActive(1L)?.state).isEqualTo(MatchTicketState.MATCHED)
        assertThat(repository.getActive(2L)?.state).isEqualTo(MatchTicketState.MATCHED)
        verify(userService, never()).markOnline(any())
    }

    @Test
    fun `세션이 사라졌으면 양쪽 티켓을 FAILED로 정리한다`() = runTest {
        seed(matchedTicket("ticket-1", 1L))
        seed(matchedTicket("ticket-2", 2L))
        whenever(legacyGameMatchService.isSessionActive(host, "session-1")).thenReturn(false)

        val report = service.report(1L, "session-1")

        assertThat(report).isInstanceOf(SessionLostReport.Released::class.java)
        assertThat((report as SessionLostReport.Released).ticket.reason)
            .`as`("같은 프로세스가 세션 없음을 답했으면 정상 종료로 기록한다")
            .isEqualTo("SESSION_ENDED")
        assertThat(sandbox.get("matching:active:1")).isNull()
        assertThat(sandbox.get("matching:active:2")).isNull()
        assertThat(decoded("ticket-2").state).isEqualTo(MatchTicketState.FAILED)
        verify(userService).markOnline(1L)
        verify(userService).markOnline(2L)
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
        whenever(legacyGameMatchService.isSessionActive(host, "session-1")).thenReturn(false)

        service.report(1L, "session-1")

        assertThat(decoded("ticket-new").state)
            .`as`("뒤늦은 신고가 상대의 새 매칭을 깨면 안 된다")
            .isEqualTo(MatchTicketState.QUEUED)
        assertThat(sandbox.get("matching:active:2")).isEqualTo("ticket-new")
        assertThat(decoded("ticket-1").state).isEqualTo(MatchTicketState.FAILED)
        verify(userService).markOnline(1L)
        verify(userService, never()).markOnline(2L)
    }

    @Test
    fun `호스트가 응답하지 않으면 티켓을 유지한 채 도달 불가로 답한다`() = runTest {
        seed(matchedTicket("ticket-1", 1L))
        seed(matchedTicket("ticket-2", 2L))
        whenever(legacyGameMatchService.isSessionActive(host, "session-1"))
            .thenThrow(GameServerUnreachableException("unreachable"))

        val error = runCatching { service.report(1L, "session-1") }.exceptionOrNull()

        assertThat(error).isInstanceOf(GameServerUnreachableException::class.java)
        assertThat(decoded("ticket-1").state)
            .`as`("일시적 통신 실패로 살아있는 세션을 끝내면 안 된다")
            .isEqualTo(MatchTicketState.MATCHED)
        assertThat(decoded("ticket-2").state).isEqualTo(MatchTicketState.MATCHED)
        assertThat(sandbox.zmembers(MatchTicketRepository.PENDING_VERIFICATION_KEY))
            .`as`("판정을 미룬 티켓은 다음 전체 스캔까지 방치되면 안 된다")
            .containsExactly("ticket-1")
    }

    @Test
    fun `세션이 살아있다고 확인되면 재확인 대기열에서 뺀다`() = runTest {
        seed(matchedTicket("ticket-1", 1L))
        sandbox.zadd(MatchTicketRepository.PENDING_VERIFICATION_KEY, "ticket-1", matchedAt.toEpochMilli().toDouble())
        whenever(legacyGameMatchService.isSessionActive(host, "session-1")).thenReturn(true)

        service.report(1L, "session-1")

        assertThat(sandbox.zmembers(MatchTicketRepository.PENDING_VERIFICATION_KEY)).isEmpty()
    }

    @Test
    fun `부팅 세대값이 다르면 호스트에 묻지 않고 세션 소실로 판정한다`() = runTest {
        seed(matchedTicket("ticket-1", 1L, instanceId = "boot-0"))
        seed(matchedTicket("ticket-2", 2L, instanceId = "boot-0"))

        val report = service.report(1L, "session-1")

        assertThat(report).isInstanceOf(SessionLostReport.Released::class.java)
        assertThat((report as SessionLostReport.Released).ticket.reason).isEqualTo("SESSION_LOST")
        verify(legacyGameMatchService, never()).isSessionActive(any(), any())
    }

    @Test
    fun `세대값이 보고되지 않았으면 재시작으로 보지 않는다`() = runTest {
        seed(matchedTicket("ticket-1", 1L, instanceId = null))
        whenever(legacyGameMatchService.isSessionActive(host, "session-1")).thenReturn(true)

        val report = service.report(1L, "session-1")

        assertThat(report)
            .`as`("구버전 게임 서버의 NULL을 재시작 증거로 쓰면 안 된다")
            .isInstanceOf(SessionLostReport.SessionAlive::class.java)
    }

    @Test
    fun `활성 티켓이 없으면 알 수 없는 세션이다`() = runTest {
        val report = service.report(1L, "session-1")

        assertThat(report).isEqualTo(SessionLostReport.UnknownSession)
    }

    @Test
    fun `다른 세션을 신고하면 알 수 없는 세션이다`() = runTest {
        seed(matchedTicket("ticket-1", 1L, sessionId = "session-1"))

        val report = service.report(1L, "session-9")

        assertThat(report).isEqualTo(SessionLostReport.UnknownSession)
    }

    @Test
    fun `MATCHED가 아닌 티켓은 그대로 스냅샷을 돌려준다`() = runTest {
        val queued = MatchTicket(
            ticketId = "ticket-1",
            userId = 1L,
            mmr = 1000L,
            state = MatchTicketState.QUEUED,
            version = 1L,
            matchInfo = matchInfo("session-1"),
            createdAt = matchedAt,
            updatedAt = matchedAt,
        )
        seed(queued)

        val report = service.report(1L, "session-1")

        assertThat(report).isInstanceOf(SessionLostReport.NothingToRelease::class.java)
        assertThat((report as SessionLostReport.NothingToRelease).ticket.state).isEqualTo(MatchTicketState.QUEUED)
    }

    private fun decoded(ticketId: String): MatchTicket =
        objectMapper.readValue(sandbox.get("matching:ticket:$ticketId"), MatchTicket::class.java)
}
