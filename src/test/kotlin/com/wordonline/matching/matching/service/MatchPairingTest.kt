package com.wordonline.matching.matching.service

import com.wordonline.matching.matching.domain.MatchTicket
import com.wordonline.matching.matching.domain.MatchTicketState
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant

class MatchPairingTest {

    private val now = Instant.parse("2026-10-15T00:00:00Z")

    private fun ticket(userId: Long, mmr: Long) = MatchTicket(
        ticketId = "ticket-$userId",
        userId = userId,
        mmr = mmr,
        state = MatchTicketState.QUEUED,
        version = 1L,
        createdAt = now,
        updatedAt = now,
    )

    private fun ids(pairs: List<Pair<MatchTicket, MatchTicket>>) = pairs.map { it.first.userId to it.second.userId }

    @Test
    fun `사람끼리는 예전처럼 MMR 차이가 가장 작은 이웃 한 쌍을 고른다`() {
        val candidates = listOf(ticket(1, 1000), ticket(2, 1300), ticket(3, 1290), ticket(4, 900))

        assertThat(ids(MatchPairing.pairsToMatch(candidates))).containsExactly(3L to 2L)
    }

    @Test
    fun `봇은 사람과 짝지어지지 않는다`() {
        val candidates = listOf(ticket(1, 1000), ticket(-5, 1000), ticket(2, 1500), ticket(-6, 1600))

        val pairs = ids(MatchPairing.pairsToMatch(candidates))

        assertThat(pairs).containsExactly(1L to 2L, -5L to -6L)
    }

    @Test
    fun `봇 그룹 안에서는 MMR 차이가 가장 작은 쌍을 고른다`() {
        val candidates = listOf(ticket(-1, 800), ticket(-2, 1500), ticket(-3, 1480), ticket(-4, 1200))

        assertThat(ids(MatchPairing.pairsToMatch(candidates))).containsExactly(-3L to -2L)
    }

    @Test
    fun `사람 한 쌍이 봇 한 쌍보다 먼저 나온다`() {
        val candidates = listOf(ticket(-1, 1000), ticket(-2, 1000), ticket(1, 1000), ticket(2, 1900))

        val pairs = MatchPairing.pairsToMatch(candidates)

        assertThat(pairs.first().first.userId).isPositive()
        assertThat(pairs).hasSize(2)
    }

    @Test
    fun `짝이 없는 그룹은 건너뛴다`() {
        assertThat(MatchPairing.pairsToMatch(listOf(ticket(1, 1000), ticket(-1, 1000)))).isEmpty()
        assertThat(ids(MatchPairing.pairsToMatch(listOf(ticket(1, 1000), ticket(-1, 1000), ticket(-2, 900)))))
            .containsExactly(-2L to -1L)
    }
}
