package com.wordonline.matching.publicstats.service

import com.wordonline.matching.matching.client.AccountClient
import com.wordonline.matching.matching.dto.AccountMemberResponseDto
import com.wordonline.matching.publicstats.PublicStatsProperties
import com.wordonline.matching.publicstats.repository.MagicDeckFigures
import com.wordonline.matching.publicstats.repository.PublicStatsRepository
import com.wordonline.matching.publicstats.repository.RankingCandidate
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

class PublicStatsServiceTest {

    private val repository: PublicStatsRepository = mock()
    private val accountClient: AccountClient = mock()
    private val clock = MutableClock(Instant.parse("2026-10-11T03:00:00Z"))

    private fun service(properties: PublicStatsProperties = PublicStatsProperties()) =
        PublicStatsService(repository, accountClient, properties, clock)

    private fun candidate(id: Long, mmr: Int) = RankingCandidate(id, mmr, wins = id, losses = 1, draws = 0)

    private fun member(name: String) = AccountMemberResponseDto(email = "$name@secret.example", name = name, id = 1)

    @Test
    fun `이름을 못 찾은 플레이어는 건너뛰고 순위를 빈틈없이 다시 매긴다`() = runTest {
        whenever(repository.findRankingCandidates(any(), any()))
            .thenReturn(listOf(candidate(1, 1500), candidate(2, 1400), candidate(3, 1300)))
        whenever(accountClient.getMemberSuspend(1)).thenReturn(member("first"))
        whenever(accountClient.getMemberSuspend(2)).thenThrow(IllegalArgumentException("not found"))
        whenever(accountClient.getMemberSuspend(3)).thenReturn(member("third"))

        val ranking = service().getRanking()

        assertThat(ranking.players.map { Triple(it.rank, it.name, it.mmr) })
            .containsExactly(Triple(1, "first", 1500), Triple(2, "third", 1300))
        assertThat(ranking.updatedAt).isEqualTo(Instant.parse("2026-10-11T03:00:00Z"))
    }

    @Test
    fun `게스트 계정은 순위에서 빼고 순위 번호를 이어 붙인다`() = runTest {
        whenever(repository.findRankingCandidates(any(), any()))
            .thenReturn(listOf(candidate(1, 1500), candidate(2, 1400), candidate(3, 1300)))
        whenever(accountClient.getMemberSuspend(1)).thenReturn(AccountMemberResponseDto("g@x.example", "guest", 1, guest = true))
        whenever(accountClient.getMemberSuspend(2)).thenReturn(member("second"))
        whenever(accountClient.getMemberSuspend(3)).thenReturn(member("third"))

        val ranking = service().getRanking()

        assertThat(ranking.players.map { it.rank to it.name }).containsExactly(1 to "second", 2 to "third")
    }

    @Test
    fun `순위는 설정한 인원까지만 자르고 후보는 인원의 배수만큼 읽는다`() = runTest {
        val properties = PublicStatsProperties(rankingSize = 2, rankingCandidateFactor = 3)
        whenever(repository.findRankingCandidates(5, 6))
            .thenReturn(listOf(candidate(1, 1500), candidate(2, 1400), candidate(3, 1300)))
        whenever(accountClient.getMemberSuspend(any())).thenReturn(member("anyone"))

        val ranking = service(properties).getRanking()

        assertThat(ranking.players.map { it.rank }).containsExactly(1, 2)
    }

    @Test
    fun `캐시 시간 안에서는 데이터베이스를 다시 읽지 않고 시간이 지나면 다시 읽는다`() = runTest {
        whenever(repository.findRankingCandidates(any(), any())).thenReturn(listOf(candidate(1, 1500)))
        whenever(accountClient.getMemberSuspend(1)).thenReturn(member("first"))
        val service = service()

        service.getRanking()
        clock.advance(Duration.ofMinutes(4))
        service.getRanking()
        verify(repository, times(1)).findRankingCandidates(any(), any())

        clock.advance(Duration.ofMinutes(2))
        service.getRanking()
        verify(repository, times(2)).findRankingCandidates(any(), any())
    }

    @Test
    fun `마법 통계는 pickRate 와 winRate 와 casts 를 계산하고 표본이 모자라면 winRate 가 null 이다`() = runTest {
        whenever(repository.countGames(any(), any())).thenReturn(100)
        whenever(repository.countDeckEntries(any(), any())).thenReturn(200)
        whenever(repository.findMagicDeckFigures(any(), any())).thenReturn(
            listOf(
                MagicDeckFigures(1, "Fireball", "Fireball", decks = 100, winOutcomeDecks = 40, winOutcomeDecksWon = 20),
                MagicDeckFigures(2, "Rare", null, decks = 10, winOutcomeDecks = 19, winOutcomeDecksWon = 19),
            ),
        )
        whenever(repository.sumCastsByMagic(any(), any())).thenReturn(mapOf(1L to 4100L))

        val statistics = service().getMagicStatistics()

        assertThat(statistics.totalGames).isEqualTo(100)
        assertThat(statistics.to).isEqualTo(Instant.parse("2026-10-11T03:00:00Z"))
        assertThat(statistics.from).isEqualTo(Instant.parse("2026-09-11T03:00:00Z"))
        val (fireball, rare) = statistics.magics
        assertThat(fireball.pickRate).isEqualTo(0.5)
        assertThat(fireball.winRate).isEqualTo(0.5)
        assertThat(fireball.casts).isEqualTo(4100)
        assertThat(rare.winRate).isNull()
        assertThat(rare.casts).isEqualTo(0)
        assertThat(rare.prefab).isNull()
    }

    @Test
    fun `덱 기록이 없으면 pickRate 는 0 이고 마법 목록은 비어 있다`() = runTest {
        whenever(repository.countGames(any(), any())).thenReturn(0)
        whenever(repository.countDeckEntries(any(), any())).thenReturn(0)
        whenever(repository.findMagicDeckFigures(any(), any())).thenReturn(emptyList())
        whenever(repository.sumCastsByMagic(any(), any())).thenReturn(emptyMap())

        val statistics = service().getMagicStatistics()

        assertThat(statistics.magics).isEmpty()
        assertThat(statistics.totalGames).isEqualTo(0)
    }

    private class MutableClock(private var now: Instant) : Clock() {
        fun advance(duration: Duration) {
            now = now.plus(duration)
        }

        override fun getZone() = ZoneOffset.UTC
        override fun withZone(zone: java.time.ZoneId?) = this
        override fun instant(): Instant = now
    }
}
