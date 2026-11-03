package com.wordonline.matching.matching.controller

import com.wordonline.matching.auth.service.UserIdResolver
import com.wordonline.matching.auth.dto.UserDetailResponseDto
import com.wordonline.matching.matching.domain.PracticeDifficulty
import com.wordonline.matching.matching.dto.MatchedInfoDto
import com.wordonline.matching.matching.repository.BotPersonaRepository
import com.wordonline.matching.matching.repository.PracticeHistoryRepository
import com.wordonline.matching.matching.service.GameMatchService
import com.wordonline.matching.matching.service.LearnedBotAvailability
import com.wordonline.matching.matching.service.PracticeDifficultyService
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest
import org.springframework.context.annotation.Import
import org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.csrf
import org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.mockJwt
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.reactive.server.WebTestClient
import reactor.core.publisher.Flux

@WebFluxTest(PracticeDifficultyController::class)
@Import(UserIdResolver::class, PracticeDifficultyService::class)
class PracticeDifficultyControllerTest {
    @Autowired private lateinit var web: WebTestClient
    @MockitoBean private lateinit var matches: GameMatchService
    @MockitoBean private lateinit var bots: BotPersonaRepository
    @MockitoBean private lateinit var history: PracticeHistoryRepository
    @MockitoBean private lateinit var learned: LearnedBotAvailability
    private val path = "/api/match/practice/difficulties"
    private fun client() = web.mutateWith(mockJwt().jwt { it.claim("memberId", 7L) }).mutateWith(csrf())

    @Test
    fun `전적이 없는 로그인 사용자는 입문부터 시작한다`() = runTest {
        whenever(learned.unavailableBotIds()).thenReturn(emptyList())
        whenever(bots.findMatchableIds(any())).thenReturn(Flux.just(-37))
        whenever(history.recent(7L)).thenReturn(emptyList())
        client().get().uri(path).exchange().expectStatus().isOk.expectBody()
            .jsonPath("$.recommendedId").isEqualTo("INTRO")
            .jsonPath("$.tiers[0].wins").isEqualTo(0)
            .jsonPath("$.tiers[0].available").isEqualTo(true)
            .jsonPath("$.tiers[1].available").isEqualTo(false)
        verify(history).recent(7L)
    }

    @Test
    fun `선택한 난이도의 봇과 핑을 이용해 로그인 사용자 세션을 배정한다`() = runTest {
        whenever(learned.unavailableBotIds()).thenReturn(emptyList())
        whenever(bots.findMatchableIds(PracticeDifficulty.SKILLED.botIds)).thenReturn(Flux.just(-60L))
        val player = UserDetailResponseDto(7L, "player", "")
        val opponent = UserDetailResponseDto(-60L, "Golem Architect", "")
        whenever(matches.matchSelectedPractice(7L, -60L, mapOf(2L to 42L))).thenReturn(
            MatchedInfoDto("matched", "test", player, opponent, "bot-123"),
        )
        client().post().uri("$path/SKILLED?pings=2:42").exchange().expectStatus().isOk.expectBody()
            .jsonPath("$.sessionId").isEqualTo("bot-123")
            .jsonPath("$.rightUser.id").isEqualTo(-60)
    }

    @Test
    fun `없는 난이도 요청은 대전 세션을 만들지 않는다`() {
        client().post().uri("$path/UNKNOWN").exchange().expectStatus().isBadRequest
        verifyNoInteractions(matches)
    }
}
