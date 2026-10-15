package com.wordonline.matching.matching.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.wordonline.matching.matching.config.BotQueueProperties
import com.wordonline.matching.matching.domain.MatchTicket
import com.wordonline.matching.matching.domain.MatchTicketState
import com.wordonline.matching.matching.repository.BotQueueRepository
import com.wordonline.matching.matching.repository.GameServerBotTarget
import com.wordonline.matching.matching.repository.MatchableBot
import com.wordonline.matching.matching.repository.RedisScriptSandbox
import com.wordonline.matching.matching.repository.sandboxMatchTicketRepository
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder
import java.time.Instant

class BotQueueFeederTest {

    private val objectMapper = Jackson2ObjectMapperBuilder.json().build<ObjectMapper>()
    private val sandbox = RedisScriptSandbox()
    private val ticketRepository = sandboxMatchTicketRepository(sandbox)
    private val botQueueRepository: BotQueueRepository = mock()
    private val properties = BotQueueProperties(defaultTargetPerServer = 1, maxEnqueuePerTick = 4)
    private val feeder = BotQueueFeeder(
        properties,
        botQueueRepository,
        BotTicketCensus(botQueueRepository, ticketRepository),
        ticketRepository,
    )
    private val now = Instant.parse("2026-10-15T00:00:00Z")

    private suspend fun givenServers(vararg targets: Int) {
        whenever(botQueueRepository.findActiveGameServerTargets(any()))
            .thenReturn(targets.mapIndexed { index, target -> GameServerBotTarget(index + 1L, target) })
    }

    private suspend fun givenBots(vararg bots: MatchableBot) {
        whenever(botQueueRepository.findAllBotUserIds()).thenReturn((-1L downTo -10L).toList())
        whenever(botQueueRepository.findMatchableBots()).thenReturn(bots.toList())
    }

    private fun seed(userId: Long, state: MatchTicketState) {
        val ticket = MatchTicket("ticket$userId", userId, 1000L, state, 2L, createdAt = now, updatedAt = now)
        sandbox.set("matching:ticket:${ticket.ticketId}", objectMapper.writeValueAsString(ticket))
        sandbox.set("matching:active:$userId", ticket.ticketId)
    }

    @Test
    fun `원하는 경기 수의 두 배까지 채우되 한 번에 최대치를 넘지 않는다`() {
        assertThat(BotQueueFeeder.botsToEnqueue(wantedGames = 1, queuedTickets = 0, inProgressTickets = 0, maxPerTick = 4)).isEqualTo(2)
        assertThat(BotQueueFeeder.botsToEnqueue(wantedGames = 5, queuedTickets = 0, inProgressTickets = 0, maxPerTick = 4)).isEqualTo(4)
        assertThat(BotQueueFeeder.botsToEnqueue(wantedGames = 3, queuedTickets = 1, inProgressTickets = 2, maxPerTick = 4)).isEqualTo(3)
        assertThat(BotQueueFeeder.botsToEnqueue(wantedGames = 1, queuedTickets = 0, inProgressTickets = 2, maxPerTick = 4)).isEqualTo(0)
        assertThat(BotQueueFeeder.botsToEnqueue(wantedGames = 1, queuedTickets = 3, inProgressTickets = 2, maxPerTick = 4)).isEqualTo(0)
    }

    @Test
    fun `봇 티켓을 실제 MMR 로 큐에 넣는다`() = runTest {
        givenServers(1)
        givenBots(MatchableBot(-1L, 1200L), MatchableBot(-2L, 900L), MatchableBot(-3L, 1000L))

        val enqueued = feeder.feed()

        assertThat(enqueued.map { it.userId to it.mmr }).containsExactly(-1L to 1200L, -2L to 900L)
        assertThat(enqueued).allMatch { it.state == MatchTicketState.QUEUED && it.deckCardIds == null }
        assertThat(sandbox.zmembers("matching:queue")).containsExactlyInAnyOrder("-1", "-2")
    }

    @Test
    fun `이미 티켓을 가진 봇은 고르지 않고 진행 중인 경기를 목표에서 뺀다`() = runTest {
        givenServers(2, 1)
        seed(-1L, MatchTicketState.MATCHED)
        seed(-2L, MatchTicketState.MATCHED)
        seed(-3L, MatchTicketState.QUEUED)
        givenBots(
            MatchableBot(-1L, 1000L),
            MatchableBot(-2L, 1000L),
            MatchableBot(-3L, 1000L),
            MatchableBot(-4L, 1000L),
            MatchableBot(-5L, 1000L),
            MatchableBot(-6L, 1000L),
        )

        val enqueued = feeder.feed()

        // wanted 3 games = 6 tickets; 2 matched + 1 queued already live.
        assertThat(enqueued.map { it.userId }).containsExactly(-4L, -5L, -6L)
    }

    @Test
    fun `끝난 티켓의 봇은 다시 넣을 수 있다`() = runTest {
        givenServers(1)
        seed(-1L, MatchTicketState.FAILED)
        sandbox.set("matching:active:-1", "gone")
        givenBots(MatchableBot(-1L, 1000L), MatchableBot(-2L, 1000L))

        assertThat(feeder.feed().map { it.userId }).containsExactly(-1L, -2L)
    }

    @Test
    fun `ACTIVE 게임 서버가 없으면 아무것도 하지 않는다`() = runTest {
        givenServers()
        givenBots(MatchableBot(-1L, 1000L), MatchableBot(-2L, 1000L))

        assertThat(feeder.feed()).isEmpty()
        verify(botQueueRepository, never()).findMatchableBots()
    }

    @Test
    fun `매칭 가능한 봇이 둘보다 적으면 아무것도 하지 않는다`() = runTest {
        givenServers(1)
        givenBots(MatchableBot(-1L, 1000L))

        assertThat(feeder.feed()).isEmpty()
        assertThat(sandbox.zmembers("matching:queue")).isEmpty()
    }
}
