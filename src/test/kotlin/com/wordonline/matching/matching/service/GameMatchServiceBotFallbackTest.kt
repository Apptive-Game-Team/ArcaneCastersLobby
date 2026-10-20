package com.wordonline.matching.matching.service

import com.wordonline.matching.auth.dto.UserDetailResponseDto
import com.wordonline.matching.auth.service.UserService
import com.wordonline.matching.deck.service.DeckService
import com.wordonline.matching.matching.config.MatchTicketProperties
import com.wordonline.matching.matching.domain.MatchTicket
import com.wordonline.matching.matching.domain.MatchTicketState
import com.wordonline.matching.matching.dto.MatchedInfoDto
import com.wordonline.matching.matching.dto.SessionDto
import com.wordonline.matching.matching.dto.SessionType
import com.wordonline.matching.matching.repository.MatchTicketRepository
import com.wordonline.matching.matching.repository.MatchingQueueRepository
import com.wordonline.matching.server.service.GameServerManagementService
import com.wordonline.matching.session.domain.SessionPlacement
import com.wordonline.matching.session.service.LegacyGameMatchService
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import reactor.core.publisher.Mono
import java.time.Instant

class GameMatchServiceBotFallbackTest {

    private val botMemberMaker: BotMemberMaker = mock()
    private val legacyGameMatchService: LegacyGameMatchService = mock()
    private val userService: UserService = mock()
    private val matchingQueueRepository: MatchingQueueRepository = mock()
    private val matchTicketRepository: MatchTicketRepository = mock()

    private val service = GameMatchService(
        botMemberMaker,
        legacyGameMatchService,
        userService,
        mock<DeckService>(),
        mock<RandomDeckService>(),
        matchingQueueRepository,
        matchTicketRepository,
        MatchTicketProperties(),
        mock<GameServerManagementService>(),
    )

    private fun ticket(userId: Long, waitedSeconds: Long, mmr: Long = 1000L): MatchTicket {
        val createdAt = Instant.now().minusSeconds(waitedSeconds)
        return MatchTicket(
            ticketId = "ticket-$userId",
            userId = userId,
            mmr = mmr,
            state = MatchTicketState.QUEUED,
            version = 1L,
            deckCardIds = listOf(1L, 2L),
            createdAt = createdAt,
            updatedAt = createdAt,
        )
    }

    private suspend fun stubRound(candidates: List<MatchTicket>) {
        whenever(matchTicketRepository.expiredAllocations()).thenReturn(emptyList())
        whenever(matchTicketRepository.expiredQueued()).thenReturn(emptyList())
        whenever(matchTicketRepository.findCandidates()).thenReturn(candidates)
        whenever(matchTicketRepository.claim(any<MatchTicket>())).thenReturn(true)
        whenever(matchTicketRepository.transition(any(), eq(MatchTicketState.ALLOCATING)))
            .thenAnswer { it.getArgument<MatchTicket>(0) }
        whenever(matchingQueueRepository.nextSessionId()).thenReturn(Mono.just(42L))
        whenever(botMemberMaker.getPracticeBotIdNear(any())).thenReturn(-7L)
        whenever(userService.markPlaying(any())).thenReturn(Mono.empty())
        whenever(userService.markOnline(any())).thenReturn(Mono.empty())
        whenever(legacyGameMatchService.createSession(any(), any(), any())).thenReturn(
            SessionPlacement(
                MatchedInfoDto(
                    "Successfully Matched",
                    "http://localhost:7777",
                    UserDetailResponseDto(1L, "left", "left@team6515.com"),
                    UserDetailResponseDto(-7L, "bot", "bot@team6515.com"),
                    "session-42",
                    "ws://localhost:7777/ws",
                ),
                7L,
                "boot-1",
            ),
        )
    }

    @Test
    fun `15초 넘게 혼자 기다린 티켓은 음수 uid2 의 PVP 세션으로 bot 과 매칭된다`() = runTest {
        stubRound(listOf(ticket(1L, waitedSeconds = 16)))

        service.runMatchingRound()

        val session = argumentCaptor<SessionDto>()
        verify(legacyGameMatchService).createSession(session.capture(), any(), any())
        assertThat(session.firstValue.sessionType).isEqualTo(SessionType.PVP)
        assertThat(session.firstValue.uid1).isEqualTo(1L)
        assertThat(session.firstValue.uid2).isEqualTo(-7L)
        assertThat(session.firstValue.sessionId).isEqualTo("session-42")
        assertThat(session.firstValue.leftDeckCardIds).containsExactly(1L, 2L)
        assertThat(session.firstValue.rightDeckCardIds).isNull()

        val matched = argumentCaptor<MatchTicket>()
        verify(matchTicketRepository).transition(matched.capture(), eq(MatchTicketState.ALLOCATING))
        assertThat(matched.firstValue.state).isEqualTo(MatchTicketState.MATCHED)
        verify(userService).markPlaying(1L)
    }

    @Test
    fun `15초가 안 된 티켓은 bot 과 매칭되지 않는다`() = runTest {
        stubRound(listOf(ticket(1L, waitedSeconds = 5)))

        service.runMatchingRound()

        verify(matchTicketRepository, never()).claim(any<MatchTicket>())
        verify(legacyGameMatchService, never()).createSession(any(), any(), any())
    }

    @Test
    fun `사용할 bot 이 없으면 티켓을 claim 하지 않고 대기열에 둔다`() = runTest {
        stubRound(listOf(ticket(1L, waitedSeconds = 30)))
        whenever(botMemberMaker.getPracticeBotIdNear(any())).thenThrow(IllegalStateException("No enabled bot persona is available."))

        service.runMatchingRound()

        verify(matchTicketRepository, never()).claim(any<MatchTicket>())
    }
}
