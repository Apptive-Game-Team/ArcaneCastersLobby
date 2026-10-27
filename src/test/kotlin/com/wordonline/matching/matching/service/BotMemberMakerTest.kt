package com.wordonline.matching.matching.service

import com.wordonline.matching.matching.config.PracticeMatchProperties
import com.wordonline.matching.matching.domain.BotPersona
import com.wordonline.matching.matching.repository.BotPersonaRepository
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

class BotMemberMakerTest {
    private val learnedBots = LearnedBotAvailability(
        org.springframework.web.reactive.function.client.WebClient.builder(), mock(),
        com.wordonline.matching.matching.config.LearnedBotProperties(),
        com.fasterxml.jackson.databind.ObjectMapper(),
    )

    private val botPersonaRepository: BotPersonaRepository = mock()
    private val botMemberMaker = BotMemberMaker(botPersonaRepository, PracticeMatchProperties(nearestBotCount = 3), learnedBots)

    @Test
    fun `활성 persona 의 이름을 돌려준다`() = runTest {
        whenever(botPersonaRepository.findByUserId(-7L))
            .thenReturn(Mono.just(BotPersona(-7L, "database bot", true, false)))

        assertThat(botMemberMaker.getBot(-7L).awaitSingle().name).isEqualTo("database bot")
    }

    @Test
    fun `persona 가 없으면 기본 이름 없이 거부한다`() = runTest {
        whenever(botPersonaRepository.findByUserId(-7L)).thenReturn(Mono.empty())

        assertThat(failureOf { botMemberMaker.getBot(-7L).awaitSingle() })
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("userId=-7")
    }

    @Test
    fun `비활성 persona 는 거부한다`() = runTest {
        whenever(botPersonaRepository.findByUserId(-7L))
            .thenReturn(Mono.just(BotPersona(-7L, "disabled bot", false, false)))

        assertThat(failureOf { botMemberMaker.getBot(-7L).awaitSingle() })
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("disabled")
    }

    @Test
    fun `연습 상대는 플레이어 MMR 에 가장 가까운 봇 중에서 고른다`() = runTest {
        whenever(botPersonaRepository.findNearestMatchable(1250L, 3)).thenReturn(
            Flux.just(
                BotPersona(-12L, "near", true, false),
                BotPersona(-13L, "nearer", true, false),
                BotPersona(-14L, "nearest", true, false),
            ),
        )

        val picked = (1..30).map { botMemberMaker.getPracticeBotIdNear(1250L) }.toSet()

        assertThat(picked).isSubsetOf(-12L, -13L, -14L).hasSizeGreaterThan(1)
        verify(botPersonaRepository, times(30)).findNearestMatchable(1250L, 3)
    }

    @Test
    fun `nearestBotCount 가 0 이하여도 한 봇은 고른다`() = runTest {
        val maker = BotMemberMaker(botPersonaRepository, PracticeMatchProperties(nearestBotCount = 0), learnedBots)
        whenever(botPersonaRepository.findNearestMatchable(1000L, 1))
            .thenReturn(Flux.just(BotPersona(-12L, "only", true, false)))

        assertThat(maker.getPracticeBotIdNear(1000L)).isEqualTo(-12L)
    }

    @Test
    fun `준비되지 않은 모델은 가까운 봇 개수를 제한하기 전에 제외한다`() = runTest {
        val availability: LearnedBotAvailability = mock()
        whenever(availability.unavailableBotIds()).thenReturn(listOf(-12L))
        whenever(botPersonaRepository.findNearestMatchable(1000L, 3, listOf(-12L)))
            .thenReturn(Flux.just(BotPersona(-13L, "rule", true, false)))
        val maker = BotMemberMaker(botPersonaRepository, PracticeMatchProperties(nearestBotCount = 3), availability)
        assertThat(maker.getPracticeBotIdNear(1000L)).isEqualTo(-13L)
        verify(botPersonaRepository).findNearestMatchable(1000L, 3, listOf(-12L))
    }

    @Test
    fun `매칭 가능한 봇이 없으면 연습 상대 선택을 거부한다`() = runTest {
        whenever(botPersonaRepository.findNearestMatchable(1000L, 3)).thenReturn(Flux.empty())

        assertThat(failureOf { botMemberMaker.getPracticeBotIdNear(1000L) })
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("No enabled bot persona")
    }

    @Test
    fun `초보자 경로에는 hospitality persona 를 고른다`() = runTest {
        whenever(botPersonaRepository.findHospitality())
            .thenReturn(Mono.just(BotPersona(-3L, "Warm Welcome", true, true)))

        assertThat(botMemberMaker.getHospitalityBotId().awaitSingle()).isEqualTo(-3L)
    }

    // The caller falls back to the ordinary pool on this error. A missing tutorial opponent must not
    // be the reason a new player cannot start a match.
    @Test
    fun `hospitality persona 가 없으면 알린다`() = runTest {
        whenever(botPersonaRepository.findHospitality()).thenReturn(Mono.empty())

        assertThat(failureOf { botMemberMaker.getHospitalityBotId().awaitSingle() })
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("No hospitality bot persona")
    }

    private suspend fun failureOf(block: suspend () -> Any): Throwable? =
        runCatching { block() }.exceptionOrNull()
}
