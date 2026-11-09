package com.wordonline.matching.matching.service

import com.wordonline.matching.auth.dto.UserDetailResponseDto
import com.wordonline.matching.auth.service.UserService
import com.wordonline.matching.deck.service.DeckService
import com.wordonline.matching.matching.config.MatchTicketProperties
import com.wordonline.matching.matching.domain.CancelMatchResult
import com.wordonline.matching.matching.domain.MatchTicket
import com.wordonline.matching.matching.domain.MatchTicketState
import com.wordonline.matching.matching.dto.MatchedInfoDto
import com.wordonline.matching.matching.repository.MatchingQueueRepository
import com.wordonline.matching.matching.repository.RedisScriptSandbox
import com.wordonline.matching.matching.repository.sandboxMatchTicketRepository
import com.wordonline.matching.server.service.GameServerManagementService
import com.wordonline.matching.session.domain.SessionPlacement
import com.wordonline.matching.session.service.LegacyGameMatchService
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.stub
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder
import reactor.core.publisher.Mono
import java.time.Instant

/**
 * A cancel that arrives while a match is being allocated must survive both ways the
 * allocation can end without a match: a lease that runs out, and a failed session creation.
 */
class GameMatchServiceCancelTest {

    private val objectMapper = Jackson2ObjectMapperBuilder.json().build<com.fasterxml.jackson.databind.ObjectMapper>()
    private val sandbox = RedisScriptSandbox()
    private val repository = sandboxMatchTicketRepository(sandbox)
    private val userService: UserService = mock()
    private val legacyGameMatchService: LegacyGameMatchService = mock()
    private val matchingQueueRepository: MatchingQueueRepository = mock()
    private val now = Instant.now()

    private val service = GameMatchService(
        practiceOpponentSelector = mock(),
        botMemberMaker = mock(),
        legacyGameMatchService = legacyGameMatchService,
        userService = userService,
        deckService = mock<DeckService>(),
        randomDeckService = mock(),
        matchingQueueRepository = matchingQueueRepository,
        matchTicketRepository = repository,
        matchTicketProperties = MatchTicketProperties(),
        gameServerManagementService = mock<GameServerManagementService>(),
        progressionRepository = mock(),
    )

    init {
        whenever(userService.markOnline(any())).thenReturn(Mono.empty())
        whenever(userService.markPlaying(any())).thenReturn(Mono.empty())
        whenever(matchingQueueRepository.nextSessionId()).thenReturn(Mono.just(1L))
    }

    private fun ticket(id: String, userId: Long, state: MatchTicketState, mmr: Long = 1000L) = MatchTicket(
        ticketId = id, userId = userId, mmr = mmr, state = state, version = 1L,
        createdAt = now, updatedAt = now,
    )

    private fun seed(ticket: MatchTicket, leaseExpired: Boolean = false) {
        sandbox.set("matching:ticket:${ticket.ticketId}", objectMapper.writeValueAsString(ticket))
        sandbox.set("matching:active:${ticket.userId}", ticket.ticketId)
        if (ticket.state == MatchTicketState.QUEUED) sandbox.zadd("matching:queue", ticket.userId.toString(), now.toEpochMilli().toDouble())
        if (leaseExpired) sandbox.zadd("matching:allocation-leases", ticket.ticketId, 0.0)
    }

    private fun stored(ticketId: String): MatchTicket =
        objectMapper.readValue(sandbox.get("matching:ticket:$ticketId"), MatchTicket::class.java)

    private fun awaitTerminalOrMatched(vararg ids: String) {
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            if (ids.all { stored(it).state != MatchTicketState.ALLOCATING && stored(it).state != MatchTicketState.QUEUED }) return
            Thread.sleep(20)
        }
    }

    @Test
    fun `ALLOCATING 중 취소는 TOO_LATE 를 돌려주고 cancelRequested 를 남긴다`() = runTest {
        seed(ticket("ticket-1", 1L, MatchTicketState.ALLOCATING))

        val response = service.cancelTicket(1L, "ticket-1")

        assertThat(response.result).isEqualTo(CancelMatchResult.TOO_LATE)
        assertThat(response.ticket?.state).isEqualTo(MatchTicketState.ALLOCATING)
        assertThat(stored("ticket-1").cancelRequested).isTrue()
    }

    @Test
    fun `취소가 기록된 채 lease 가 만료되면 QUEUED 로 되돌리지 않고 CANCELED 로 끝낸다`() {
        seed(ticket("ticket-1", 1L, MatchTicketState.ALLOCATING), leaseExpired = true)
        runBlocking { service.cancelTicket(1L, "ticket-1") }

        service.tryMatching()
        awaitTerminalOrMatched("ticket-1")

        assertThat(stored("ticket-1").state).isEqualTo(MatchTicketState.CANCELED)
        assertThat(stored("ticket-1").reason).isEqualTo("USER_REQUESTED")
        assertThat(sandbox.get("matching:active:1")).isNull()
        org.mockito.kotlin.verify(userService).markOnline(1L)
    }

    @Test
    fun `취소가 없으면 lease 가 만료된 티켓은 QUEUED 로 돌아간다`() {
        seed(ticket("ticket-1", 1L, MatchTicketState.ALLOCATING), leaseExpired = true)

        service.tryMatching()
        val deadline = System.currentTimeMillis() + 10_000
        while (stored("ticket-1").state == MatchTicketState.ALLOCATING && System.currentTimeMillis() < deadline) Thread.sleep(20)

        assertThat(stored("ticket-1").state).isEqualTo(MatchTicketState.QUEUED)
    }

    private fun matchInfo() = MatchedInfoDto(
        message = "Successfully Matched",
        server = "http://localhost:7777",
        leftUser = UserDetailResponseDto(1L, "left", "left@team6515.com"),
        rightUser = UserDetailResponseDto(2L, "right", "right@team6515.com"),
        sessionId = "session-1",
        webSocketUrl = "ws://localhost:7777/ws",
    )

    private fun seedQueuedPair() {
        seed(ticket("ticket-1", 1L, MatchTicketState.QUEUED, mmr = 1000L))
        seed(ticket("ticket-2", 2L, MatchTicketState.QUEUED, mmr = 1001L))
    }

    @Test
    fun `세션 생성이 실패하면 취소한 쪽은 CANCELED 상대는 FAILED 로 끝난다`() {
        seedQueuedPair()
        legacyGameMatchService.stub {
            onBlocking { createSession(any(), any(), any()) } doSuspendableAnswer {
                service.cancelTicket(1L, "ticket-1")
                throw RuntimeException("no game server")
            }
        }

        service.tryMatching()
        awaitTerminalOrMatched("ticket-1", "ticket-2")

        assertThat(stored("ticket-1").state).isEqualTo(MatchTicketState.CANCELED)
        assertThat(stored("ticket-1").reason).isEqualTo("USER_REQUESTED")
        assertThat(stored("ticket-2").state).isEqualTo(MatchTicketState.FAILED)
        assertThat(stored("ticket-2").reason).isEqualTo("SESSION_CREATION_FAILED")
    }

    @Test
    fun `취소가 기록돼도 세션이 만들어지면 양쪽 모두 MATCHED 로 끝난다`() {
        seedQueuedPair()
        legacyGameMatchService.stub {
            onBlocking { createSession(any(), any(), any()) } doSuspendableAnswer {
                service.cancelTicket(1L, "ticket-1")
                SessionPlacement(matchInfo(), 7L, "instance-a")
            }
        }

        service.tryMatching()
        awaitTerminalOrMatched("ticket-1", "ticket-2")

        assertThat(stored("ticket-1").state).isEqualTo(MatchTicketState.MATCHED)
        assertThat(stored("ticket-2").state).isEqualTo(MatchTicketState.MATCHED)
        assertThat(stored("ticket-1").version).isGreaterThan(stored("ticket-2").version)
    }
}
