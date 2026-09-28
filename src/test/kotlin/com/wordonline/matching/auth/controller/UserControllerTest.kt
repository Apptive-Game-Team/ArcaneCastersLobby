package com.wordonline.matching.auth.controller

import com.wordonline.matching.auth.dto.UserGameRecordResponseDto
import com.wordonline.matching.auth.dto.UserStatisticsGamesResponseDto
import com.wordonline.matching.auth.dto.UserStatisticsOverviewResponseDto
import com.wordonline.matching.auth.service.UserIdResolver
import com.wordonline.matching.auth.service.UserService
import com.wordonline.matching.auth.service.UserStatisticsService
import com.wordonline.matching.quest.service.QuestService
import com.wordonline.matching.session.service.LegacyGameMatchService
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest
import org.springframework.context.annotation.Import
import org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.reactive.server.WebTestClient
import java.time.Instant

/**
 * Response-shape coverage for issue #27's two statistics endpoints. `UserStatisticsService` is
 * mocked, so this pins the JSON shape and query parameter wiring only, not the SQL behind it.
 */
@WebFluxTest(UserController::class)
@Import(UserIdResolver::class)
class UserControllerTest {

    @Autowired
    private lateinit var webTestClient: WebTestClient

    @MockitoBean
    private lateinit var userService: UserService

    @MockitoBean
    private lateinit var userStatisticsService: UserStatisticsService

    @MockitoBean
    private lateinit var gameMatchService: LegacyGameMatchService

    @MockitoBean
    private lateinit var questService: QuestService

    @Test
    @DisplayName("전적 overview 조회는 승패무 합계를 totalGameNum 으로 되돌려준다")
    fun `getMyStatisticsOverview returns win-lose-draw totals`() = runTest {
        val userId = 1L
        val overview = UserStatisticsOverviewResponseDto(
            totalGameNum = 12,
            totalWinNum = 7,
            totalLoseNum = 4,
            totalDrawNum = 1,
        )
        whenever(userStatisticsService.getOverview(userId)).thenReturn(overview)

        webTestClient
            .mutateWith(SecurityMockServerConfigurers.mockJwt().jwt { it.claim("memberId", userId) })
            .get()
            .uri("/api/users/mine/statistics/overview")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.totalGameNum").isEqualTo(12)
            .jsonPath("$.totalWinNum").isEqualTo(7)
            .jsonPath("$.totalLoseNum").isEqualTo(4)
            .jsonPath("$.totalDrawNum").isEqualTo(1)
    }

    @Test
    @DisplayName("전적 games 조회는 opponentName 과 page 메타데이터를 함께 되돌려준다")
    fun `getMyStatisticsGames returns opponent name and page metadata`() = runTest {
        val userId = 1L
        val games = UserStatisticsGamesResponseDto(
            games = listOf(
                UserGameRecordResponseDto(
                    opponentId = -3L,
                    opponentName = "Bot Persona",
                    result = "win",
                    gameType = "PVE",
                    playedAt = Instant.parse("2026-09-21T10:00:00Z"),
                ),
            ),
            page = 0,
            size = 20,
            totalPages = 3,
            last = false,
        )
        whenever(userStatisticsService.getGames(userId, 0, 20)).thenReturn(games)

        webTestClient
            .mutateWith(SecurityMockServerConfigurers.mockJwt().jwt { it.claim("memberId", userId) })
            .get()
            .uri("/api/users/mine/statistics/games?page=0&size=20")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.games[0].opponentId").isEqualTo(-3)
            .jsonPath("$.games[0].opponentName").isEqualTo("Bot Persona")
            .jsonPath("$.games[0].result").isEqualTo("win")
            .jsonPath("$.games[0].gameType").isEqualTo("PVE")
            .jsonPath("$.games[0].playedAt").isEqualTo("2026-09-21T10:00:00Z")
            .jsonPath("$.page").isEqualTo(0)
            .jsonPath("$.size").isEqualTo(20)
            .jsonPath("$.totalPages").isEqualTo(3)
            .jsonPath("$.last").isEqualTo(false)
    }

    @Test
    @DisplayName("전적 games 조회는 page, size 쿼리 파라미터를 service 로 그대로 전달한다")
    fun `getMyStatisticsGames forwards page and size query parameters`() = runTest {
        val userId = 1L
        val emptyPage = UserStatisticsGamesResponseDto(games = emptyList(), page = 2, size = 15, totalPages = 0, last = true)
        whenever(userStatisticsService.getGames(userId, 2, 15)).thenReturn(emptyPage)

        webTestClient
            .mutateWith(SecurityMockServerConfigurers.mockJwt().jwt { it.claim("memberId", userId) })
            .get()
            .uri("/api/users/mine/statistics/games?page=2&size=15")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.games.length()").isEqualTo(0)
            .jsonPath("$.page").isEqualTo(2)
            .jsonPath("$.size").isEqualTo(15)
            .jsonPath("$.last").isEqualTo(true)
    }
}
