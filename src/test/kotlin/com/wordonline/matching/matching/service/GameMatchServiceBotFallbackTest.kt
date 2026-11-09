package com.wordonline.matching.matching.service

import com.wordonline.matching.auth.dto.UserDetailResponseDto
import com.wordonline.matching.auth.service.UserService
import com.wordonline.matching.deck.service.DeckService
import com.wordonline.matching.matching.config.MatchTicketProperties
import com.wordonline.matching.matching.domain.MatchTicket
import com.wordonline.matching.matching.domain.MatchTicketState
import com.wordonline.matching.matching.dto.MatchDeckMode
import com.wordonline.matching.matching.dto.MatchedInfoDto
import com.wordonline.matching.matching.dto.SessionDto
import com.wordonline.matching.matching.dto.SessionType
import com.wordonline.matching.matching.repository.MatchTicketRepository
import com.wordonline.matching.matching.repository.MatchingQueueRepository
import com.wordonline.matching.server.service.GameServerManagementService
import com.wordonline.matching.session.domain.SessionPlacement
import com.wordonline.matching.session.service.LegacyGameMatchService
import com.wordonline.matching.progression.ProgressionRepository
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
import org.mockito.kotlin.inOrder
import org.mockito.kotlin.verifyNoInteractions
import reactor.core.publisher.Mono
import java.time.Instant

class GameMatchServiceBotFallbackTest {

    private val botMemberMaker: BotMemberMaker = mock()
    private val legacyGameMatchService: LegacyGameMatchService = mock()
    private val userService: UserService = mock()
    private val matchingQueueRepository: MatchingQueueRepository = mock()
    private val matchTicketRepository: MatchTicketRepository = mock()

    private val decks: DeckService = mock()
    private val random: RandomDeckService = mock()
    private val progression: ProgressionRepository = mock()
    private val opponents: PracticeOpponentSelector = mock()
    private val service = GameMatchService(
        opponents,
        botMemberMaker,
        legacyGameMatchService,
        userService,
        decks,
        random,
        matchingQueueRepository,
        matchTicketRepository,
        MatchTicketProperties(),
        mock<GameServerManagementService>(),
        progression,
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
    fun `빈 선택 덱은 세션 번호를 발급하지 않는다`() = runTest {
        whenever(decks.validSelectedDeckCards(1L)).thenReturn(Mono.empty())
        assertThat(service.matchSelectedPractice(1L, -7L, null, MatchDeckMode.SELECTED)).isNull()
        verify(matchingQueueRepository, never()).nextSessionId()
        verifyNoInteractions(progression)
    }

    @Test
    fun `랜덤 덱 카드를 연습 세션으로 전달한다`() = runTest {
        stubRound(emptyList())
        val cards = (1L..15L).toList()
        whenever(random.create(1L)).thenReturn(cards)
        service.matchSelectedPractice(1L, -7L, null, MatchDeckMode.RANDOM)
        val session = argumentCaptor<SessionDto>()
        verify(legacyGameMatchService).createSession(session.capture(), any(), any())
        assertThat(session.firstValue.sessionType).isEqualTo(SessionType.Practice)
        assertThat(session.firstValue.leftDeckCardIds).isEqualTo(cards)
        assertThat(session.firstValue.rightDeckCardIds).isNull()
        verify(progression).registerMatch(eq("bot-42"), eq(1L), any())
    }

    @Test
    fun `자동 상대 연습은 세션 생성 후 사용자만 성장 경기에 등록한다`() = runTest {
        stubRound(emptyList())
        whenever(opponents.select(1L)).thenReturn(PracticeOpponent(-7L, false))

        service.matchPractice(1L)

        val order = inOrder(legacyGameMatchService, progression)
        order.verify(legacyGameMatchService).createSession(any(), any(), any())
        order.verify(progression).registerMatch(eq("bot-42"), eq(1L), any())
        order.verifyNoMoreInteractions()
    }

    @Test
    fun `선택 덱 연습도 성장 경기에 등록한다`() = runTest {
        stubRound(emptyList())
        whenever(decks.validSelectedDeckCards(1L)).thenReturn(Mono.just((1L..15L).toList()))

        service.matchSelectedPractice(1L, -7L, null, MatchDeckMode.SELECTED)

        verify(progression).registerMatch(eq("bot-42"), eq(1L), any())
    }

    @Test
    fun `연습 세션 생성 실패는 성장 경기에 등록하지 않는다`() = runTest {
        stubRound(emptyList())
        whenever(random.create(1L)).thenReturn((1L..15L).toList())
        whenever(legacyGameMatchService.createSession(any(), any(), any()))
            .thenThrow(IllegalStateException("session unavailable"))

        var failure: Throwable? = null
        try {
            service.matchSelectedPractice(1L, -7L, null, MatchDeckMode.RANDOM)
        } catch (e: IllegalStateException) {
            failure = e
        }
        assertThat(failure).isInstanceOf(IllegalStateException::class.java)
        verifyNoInteractions(progression)
    }

    @Test
    fun `관리자 봇 대전과 PvE는 성장 경기에 등록하지 않는다`() = runTest {
        stubRound(emptyList())

        service.matchBots(-7L, -8L)
        service.matchPVE(1L, 1L)

        verifyNoInteractions(progression)
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
        verify(botMemberMaker).getPracticeBotIdNear(1000L)
        verify(botMemberMaker, never()).getHospitalityBotId()
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
