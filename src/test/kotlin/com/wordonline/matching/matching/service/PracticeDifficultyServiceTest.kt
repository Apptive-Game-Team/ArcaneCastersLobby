package com.wordonline.matching.matching.service

import com.wordonline.matching.matching.domain.PracticeDifficulty
import com.wordonline.matching.matching.repository.BotPersonaRepository
import com.wordonline.matching.matching.repository.PracticeHistory
import com.wordonline.matching.matching.repository.PracticeHistoryRepository
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.springframework.web.server.ResponseStatusException
import reactor.core.publisher.Flux

class PracticeDifficultyServiceTest {
    private val bots: BotPersonaRepository = mock()
    private val learned: LearnedBotAvailability = mock()
    private val history: PracticeHistoryRepository = mock()
    private val service = PracticeDifficultyService(bots, learned, history)

    @Test
    fun `선택한 단계에서 준비된 상대만 고르고 후보가 없으면 거부한다`() = runTest {
        val roster = PracticeDifficulty.INTRO.botIds
        whenever(learned.unavailableBotIds()).thenReturn(listOf(roster.first()))
        whenever(bots.findMatchableIds(roster.drop(1))).thenReturn(Flux.just(roster.last()), Flux.empty())
        assertThat(service.select("INTRO")).isEqualTo(roster.last())
        val error = runCatching { service.select("INTRO") }.exceptionOrNull() as ResponseStatusException
        assertThat(error.statusCode.value()).isEqualTo(409)
    }

    @Test
    fun `서버 전적으로 추천하고 현재 상대가 없는 단계도 기록을 반환한다`() = runTest {
        val roster = PracticeDifficulty.entries.flatMap { it.botIds }
        assertThat(roster).hasSize(45).doesNotHaveDuplicates()
        whenever(learned.unavailableBotIds()).thenReturn(emptyList())
        whenever(bots.findMatchableIds(roster)).thenReturn(Flux.fromIterable(roster - PracticeDifficulty.EXPERT.botIds.toSet()))
        whenever(history.recent(9L)).thenReturn(listOf(
            PracticeHistory("BASIC", 4, 1, 0), PracticeHistory("SKILLED", 2, 2, 1),
            PracticeHistory("ADVANCED", 1, 4, 0), PracticeHistory("EXPERT", 0, 1, 0),
        ))
        val overview = service.overview(9L)
        assertThat(overview.recommendedId).isEqualTo("SKILLED")
        assertThat(overview.tiers.last().available).isFalse()
        assertThat(overview.tiers.last().losses).isEqualTo(1)
    }
}
